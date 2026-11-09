package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockInterner;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.item.ItemVariant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * Compiles palettes: vanilla data first, then one layer at a time.
 */
final class PaletteCompiler {

    private PaletteCompiler() {
    }

    /** The creative ordering of the Paletter a palette was compiled by; extensions compiled later use it too. */
    private static final Map<Palette, CreativeOrdering> ORDERINGS = Collections.synchronizedMap(new WeakHashMap<>());

    static void orderingOf(Palette palette, CreativeOrdering ordering) {
        ORDERINGS.put(palette, ordering);
    }

    static Palette vanilla(VanillaData loaded, BlockNetworkIds mode, CanonicalBlocks canonicalBlocks, CanonicalItems canonicalItems,
                           BlockInterner interner, Fallbacks fallbacks, VanillaContent content, CreativeOrdering ordering) {
        VanillaData data = content.apply(loaded);
        PaletteReport report = new PaletteReport();
        BlockRegistry blocks = BlockRegistry.build(data, mode, canonicalBlocks, interner)
            .withFallbacks(fallbacks.blocks(data.version()))
            .withDefaultFallback(fallbacks.defaultBlock());
        ItemRegistry.Builder builder = ItemRegistry.vanilla(data, blocks, canonicalItems);
        fallbacks.items(data.version()).forEach(builder::fallback);
        builder.defaultFallback(fallbacks.defaultItem());
        ItemRegistry items = builder.build(data.creative(), report);
        CreativeContent creative = CreativeContent.compile(data.creative(), items, report);
        prepareCanonical(blocks, null, items, null, report);
        Palette palette = new Palette(data, List.of(), blocks, items, data.creative(), creative, report);
        orderingOf(palette, ordering);
        return palette;
    }

    /**
     * @param session true if the layer is compiled for one session: custom blocks then need hashed block network ids
     */
    static Palette extend(Palette parent, PaletteLayer layer, boolean session) {
        if (session && !layer.blocks().isEmpty() && parent.blockNetworkIds() != BlockNetworkIds.HASHED) {
            throw new PaletteException("Custom blocks in a session layer need BlockNetworkIds.HASHED: layer " + layer.id()
                + " adds " + layer.blocks().size() + " custom blocks to a " + parent.blockNetworkIds() + " palette");
        }
        Palette extended = extendUnchecked(parent, layer);
        orderingOf(extended, ordering(parent));
        return extended;
    }

    private static CreativeOrdering ordering(Palette palette) {
        CreativeOrdering ordering = ORDERINGS.get(palette);
        return ordering == null ? CreativeOrdering.BDS : ordering;
    }

    private static Palette extendUnchecked(Palette parent, PaletteLayer layer) {
        VanillaData data = parent.vanillaData();
        String source = layer.id();
        PaletteReport report = new PaletteReport();
        report.addAll(parent.report());

        try {
            // Blocks first: items need to know which blocks exist, and custom blocks re-number all states.
            // Without custom blocks or block fallbacks the parent's block palette (and its canonical table) is shared
            BlockRegistry blocks = parent.blocks()
                .withCustomBlocks(data, layer, layer.blocks(), report, source);

            List<ItemVariant> newVariants = new ArrayList<>();
            // blockItem(...) for a block of this layer takes over the item registration the block would do itself
            Set<String> explicit = new HashSet<>();
            layer.itemEntries().forEach(entry -> {
                if (entry instanceof PaletteLayer.ItemEntry.OfBlock ofBlock) {
                    explicit.add(ofBlock.block());
                }
            });
            for (PaletteLayer.ItemEntry entry : layer.itemEntries()) {
                switch (entry) {
                    case PaletteLayer.ItemEntry.Custom custom -> newVariants.add(custom.item().variant());
                    case PaletteLayer.ItemEntry.OfBlock ofBlock -> {
                        if (!(ofBlock.item() instanceof CustomBlock.BlockItem.None)) {
                            newVariants.add(ItemVariant.of(ofBlock.block()));
                        }
                    }
                }
            }
            for (CustomBlock block : layer.blocks()) {
                if (!explicit.contains(block.identifier()) && !(block.item() instanceof CustomBlock.BlockItem.None)) {
                    newVariants.add(ItemVariant.of(block.identifier()));
                }
            }
            List<String> registered = newVariants.stream().map(ItemVariant::identifier).toList();
            newVariants.addAll(layer.variants());
            CanonicalItems canonicalItems = parent.items().canonical().extend(layer, newVariants);

            ItemRegistry.Builder items = parent.items().extend(blocks, canonicalItems);
            // Components refer to items of this layer registered later: declare them all first
            registered.forEach(items::declare);
            for (PaletteLayer.Override override : layer.overrides()) {
                if (!items.override(override.identifier(), override.modifier(), report, source)) {
                    report.info(source, "Not overriding " + override.identifier() + ": the item does not exist in " + data.version());
                }
            }

            CreativeEditor creative = parent.creativeLayout().edit();
            CreativeOrdering ordering = ordering(parent);
            // Placements in registration order (custom items and block items interleaved, then items of blocks)
            List<Placed> placed = new ArrayList<>();
            for (PaletteLayer.ItemEntry entry : layer.itemEntries()) {
                switch (entry) {
                    case PaletteLayer.ItemEntry.Custom(CustomItem custom) -> {
                        items.addCustom(custom, report, source);
                        if (custom.creative() != null) {
                            placed.add(new Placed(null, CreativeStack.of(custom.identifier()), custom.creative()));
                        }
                    }
                    case PaletteLayer.ItemEntry.OfBlock(String identifier, CustomBlock.BlockItem blockItem, var placement) -> {
                        CustomBlock block = blocks.customBlocks().stream().filter(b -> b.identifier().equals(identifier)).findFirst()
                            .orElseThrow(() -> new PaletteException("Item of unknown custom block " + identifier));
                        // The block of this layer keeps its own placement unless blockItem gives another
                        CreativePlacement effective = placement != null || layer.blocks().stream().noneMatch(b -> b.identifier().equals(identifier))
                            ? placement : creativePlacement(block, ordering);
                        if (items.addBlockItem(block, blockItem, report, source) != null && effective != null) {
                            placed.add(new Placed(identifier, CreativeStack.of(block.identifier()).withBlockState(block.defaultState()), effective));
                        }
                    }
                }
            }
            // BDS registers block items in the order of the block files, identifier descending without files, whatever
            // order the layer lists them in
            List<CustomBlock> blocksByItemOrder = new ArrayList<>(layer.blocks());
            blocksByItemOrder.sort(Comparator.comparing(CustomBlock::identifier).reversed());
            for (CustomBlock block : blocksByItemOrder) {
                if (explicit.contains(block.identifier())) {
                    continue;
                }
                CreativePlacement placement = creativePlacement(block, ordering);
                if (items.addBlockItem(block, report, source) != null && placement != null) {
                    placed.add(new Placed(block.identifier(), CreativeStack.of(block.identifier()).withBlockState(block.defaultState()), placement));
                }
            }
            if (ordering == CreativeOrdering.APPEND) {
                placed.forEach(entry -> entry.placement().apply(creative, entry.stack(), ordering));
            } else {
                // BDS adds block items while iterating its data driven block map, then items in registration order.
                // Custom blocks enter the map by block id, which Paletter assigns by identifier, descending
                Map<String, Placed> blockItems = new HashMap<>();
                placed.stream().filter(entry -> entry.block() != null).forEach(entry -> blockItems.put(entry.block(), entry));
                List<String> byBlockId = new ArrayList<>();
                blocks.customBlocks().forEach(block -> byBlockId.add(block.identifier()));
                byBlockId.sort(Comparator.reverseOrder());
                for (String identifier : ordering.blockOrder(byBlockId,
                    data.dataDrivenBlocks().stream().map(BlockPropertyData::getName).toList())) {
                    Placed entry = blockItems.get(identifier);
                    if (entry != null) {
                        entry.placement().apply(creative, entry.stack(), ordering);
                    }
                }
                placed.stream().filter(entry -> entry.block() == null)
                    .forEach(entry -> entry.placement().apply(creative, entry.stack(), ordering));
            }
            for (BiConsumer<CreativeEditor, DataVersion> edit : layer.creativeEdits()) {
                edit.accept(creative, data.version());
            }
            layer.tags().forEach(items::tag);

            CreativeLayout layout = creative.build();
            ItemRegistry itemPalette = items.build(layout, report);
            CreativeContent content = CreativeContent.compile(layout, itemPalette, report);
            prepareCanonical(blocks, parent.blocks(), itemPalette, parent.items(), report);

            List<PaletteLayer> layers = new ArrayList<>(parent.layers());
            layers.add(layer);
            return new Palette(data, layers, blocks, itemPalette, layout, content, report);
        } catch (PaletteException e) {
            throw new PaletteException("Unable to compile layer " + source + " for " + data.version() + ": " + e.getMessage(), e);
        }
    }

    /** A creative placement to apply; {@code block} is the custom block if the stack is a block item. */
    private record Placed(@Nullable String block, CreativeStack stack, CreativePlacement placement) {
    }

    /**
     * The creative placement of a custom block: its own, or with {@link CreativeOrdering#BDS} the one its menu
     * category implies (BDS puts every block with a menu category in the creative inventory).
     */
    private static @Nullable CreativePlacement creativePlacement(CustomBlock block, CreativeOrdering ordering) {
        org.geysermc.paletter.block.component.MenuCategory menu = block.menuCategory();
        if (block.creative() != null || ordering == CreativeOrdering.APPEND || menu == null
            || menu.category() == org.geysermc.paletter.block.component.MenuCategory.Category.NONE) {
            return block.creative();
        }
        CreativeCategory category = CreativeCategory.fromBedrockName(menu.category().value());
        return menu.group() == null || menu.group().isEmpty() ? CreativePlacement.loose(category) : CreativePlacement.group(category, menu.group());
    }

    /**
     * Builds the canonical tables eagerly, and reports what they lack (only what is new compared to the parent, whose
     * report entries are already included).
     */
    private static void prepareCanonical(BlockRegistry blocks, BlockRegistry parentBlocks, ItemRegistry items, ItemRegistry parentItems,
                                         PaletteReport report) {
        blocks.prepareCanonical();
        items.prepareCanonical();
        if (blocks != parentBlocks) {
            List<NbtMap> missing = blocks.missingCanonical();
            if (!missing.isEmpty() && (parentBlocks == null || !missing.equals(parentBlocks.missingCanonical()))) {
                Set<String> names = new LinkedHashSet<>();
                missing.forEach(state -> names.add(BlockStates.name(state)));
                report.info("canonical", missing.size() + " canonical block states have no " + blocks.version()
                    + " block and no fallback, and are sent as " + (blocks.defaultFallback() == null ? "air"
                    : blocks.defaultFallback().target()) + ": " + names);
            }
        }
        List<ItemVariant> missingItems = items.missingCanonical();
        if (!missingItems.isEmpty() && (parentItems == null || !missingItems.equals(parentItems.missingCanonical()))) {
            report.info("canonical", missingItems.size() + " canonical item variants do not exist in " + items.version()
                + " and have no fallback" + (items.defaultFallback() == null ? "" : ", and are sent as "
                + items.defaultFallback().target()) + ": " + missingItems);
        }
    }
}
