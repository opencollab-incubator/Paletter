package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.shape.VoxelShapeCompiler;
import org.geysermc.paletter.internal.shape.VoxelShapeStore;
import org.geysermc.paletter.internal.block.BlockInterner;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.internal.creative.RawConverter;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.internal.item.ItemVariant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Compiles palettes: vanilla data first, then one layer at a time.
 */
final class PaletteCompiler {

    private PaletteCompiler() {
    }

    static Palette vanilla(VanillaData loaded, CanonicalBlocks canonicalBlocks, CanonicalItems canonicalItems,
                           BlockInterner interner, Fallbacks fallbacks, VanillaContent content, CreativeOrdering ordering,
                           Supplier<BehaviourStore> behaviour, VoxelShapeStore shapeStore) {
        VanillaContent.Applied applied = content.apply(loaded);
        VanillaData data = applied.data();
        PaletteReport report = new PaletteReport();
        applied.gating().notes().forEach(note -> report.info("vanilla", note));
        BlockRegistry blocks = BlockRegistry.build(data, canonicalBlocks, interner)
            .withMissing(applied.gating().missingBlocks())
            .withFallbacks(fallbacks.blocks(data.version()))
            .withDefaultFallback(fallbacks.defaultBlock());
        ItemRegistry.Builder builder = ItemRegistry.vanilla(data, blocks, canonicalItems);
        fallbacks.items(data.version()).forEach(builder::fallback);
        builder.defaultFallback(fallbacks.defaultItem());
        CreativeLayout layout = RawConverter.convert(data.creative(), blocks, report);
        ItemRegistry items = builder.build(layout, report);
        CreativeContent creative = CreativeContent.compile(layout, items, report);
        prepareCanonical(blocks, null, items, null, report);
        // The experiment changes the vanilla voxel shapes of some versions: those palettes need the toggle
        boolean shapeExperiment = content.requests(VoxelShapeStore.EXPERIMENT) && shapeStore.experimentMatters(data.version());
        List<VoxelShapeStore.Entry> vanillaShapes = shapeStore.shapes(data.version(), shapeExperiment);
        if (vanillaShapes == null) {
            // BDS sends the voxel shapes packet to every client (protocol 924 and later), so a palette needs them
            throw new PaletteException("No vanilla voxel shapes for " + data.version() + " in the data source (no registry"
                + " of the version in voxel_shapes.nbt): the palette cannot build the voxel shapes packet, which BDS sends"
                + " to every client before start game");
        }
        Palette.Shapes shapes = new Palette.Shapes(vanillaShapes, VoxelShapeCompiler.compile(vanillaShapes, Map.of(), blocks, report));
        VanillaContent.ExperimentGating gating = shapeExperiment ? applied.gating().withEnabled(VoxelShapeStore.EXPERIMENT) : applied.gating();
        return new Palette(data, List.of(), blocks, items, layout, creative, report, ordering, gating, behaviour, shapes, null);
    }

    /**
     * @param session true if the layer is compiled for one session: a layer that cannot be applied then leaves the parent
     *                unchanged, with an error in its report, instead of throwing (global layers are startup configuration
     *                and fail loudly)
     */
    static Palette extend(Palette parent, PaletteLayer layer, boolean session) {
        try {
            return extendUnchecked(parent, layer);
        } catch (PaletteException e) {
            if (!session) {
                throw e;
            }
            PaletteReport report = new PaletteReport();
            report.addAll(parent.report());
            report.error(layer.id(), e.getMessage() + " (the layer is not applied to the " + parent.version() + " palette)");
            return parent.withReport(report);
        }
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
            // The handles of the new variants are the types the custom items and blocks own
            List<BedrockItemType> customTypes = new ArrayList<>();
            blocks.customBlocks().forEach(block -> customTypes.add(block.itemType()));
            // blockItem(...) for a block of this layer takes over the item registration the block would do itself
            Set<String> explicit = new HashSet<>();
            layer.itemEntries().forEach(entry -> {
                if (entry instanceof PaletteLayer.ItemEntry.OfBlock ofBlock) {
                    explicit.add(ofBlock.block());
                }
            });
            for (PaletteLayer.ItemEntry entry : layer.itemEntries()) {
                switch (entry) {
                    case PaletteLayer.ItemEntry.Custom custom -> {
                        newVariants.add(custom.item().variant());
                        customTypes.add(custom.item().type());
                    }
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
            CanonicalItems canonicalItems = parent.items().canonical().extend(layer, newVariants, customTypes);

            ItemRegistry.Builder items = parent.items().extend(blocks, canonicalItems);
            // Components refer to items of this layer registered later: declare them all first
            registered.forEach(items::declare);
            for (PaletteLayer.Override override : layer.overrides()) {
                if (!items.override(override.identifier(), override.modifier(), report, source)) {
                    report.info(source, "Not overriding " + override.identifier() + ": the item does not exist in " + data.version());
                }
            }

            CreativeOrdering ordering = parent.ordering();
            // Placements in registration order (custom items and block items interleaved, then items of blocks)
            List<Placed> placed = new ArrayList<>();
            for (PaletteLayer.ItemEntry entry : layer.itemEntries()) {
                switch (entry) {
                    case PaletteLayer.ItemEntry.Custom(CustomItem custom) -> {
                        items.addCustom(custom, report, source);
                        if (custom.creative() != null) {
                            placed.add(new Placed(null, BedrockItemStack.of(custom.type()), custom.creative()));
                        }
                    }
                    case PaletteLayer.ItemEntry.OfBlock(String identifier, CustomBlock.BlockItem blockItem, var placement) -> {
                        CustomBlock block = blocks.customBlocks().stream().filter(b -> b.identifier().equals(identifier)).findFirst()
                            .orElseThrow(() -> new PaletteException("Item of unknown custom block " + identifier));
                        // The block of this layer keeps its own placement unless blockItem gives another
                        CreativePlacement effective = placement != null || layer.blocks().stream().noneMatch(b -> b.identifier().equals(identifier))
                            ? placement : creativePlacement(block, ordering);
                        if (items.addBlockItem(block, blockItem, report, source) != null && effective != null) {
                            placed.add(new Placed(identifier, blockItemStack(block, blocks), effective));
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
                    placed.add(new Placed(block.identifier(), blockItemStack(block, blocks), placement));
                }
            }
            layer.tags().forEach(items::tag);

            // Without placements and edits the layout is the parent's, and so is what is compiled from it unless the layer
            // registers an item or block the layout mentions
            boolean sameLayout = placed.isEmpty() && layer.creativeEdits().isEmpty();
            CreativeLayout layout = sameLayout ? parent.creativeLayout() : creativeLayout(parent, layer, blocks, placed);
            ItemRegistry itemPalette = items.build(layout, report);
            Set<String> registers = new HashSet<>(registered);
            layer.overrides().forEach(override -> registers.add(override.identifier()));
            layer.blocks().forEach(block -> registers.add(block.identifier()));
            registers.addAll(items.renumberedBlockItems());
            CreativeContent content = sameLayout && !mentions(layout, registers)
                ? parent.creative() : CreativeContent.compile(layout, itemPalette, report);
            prepareCanonical(blocks, parent.blocks(), itemPalette, parent.items(), report);

            List<PaletteLayer> layers = new ArrayList<>(parent.layers());
            layers.add(layer);
            // The voxel shapes are the parent's unless the layer defines shapes or blocks (which may name shapes)
            Palette.Shapes shapes = parent.shapes();
            if (!layer.voxelShapes().isEmpty() || !layer.blocks().isEmpty()) {
                Map<String, VoxelShape> custom = new LinkedHashMap<>();
                for (PaletteLayer each : layers) {
                    each.voxelShapes().forEach((identifier, shape) -> {
                        if (custom.putIfAbsent(identifier, shape) != null) {
                            report.error(source, "The voxel shape " + identifier + " is defined by an earlier layer; that definition is used");
                        }
                    });
                }
                shapes = new Palette.Shapes(shapes.vanilla(), VoxelShapeCompiler.compile(shapes.vanilla(), custom, blocks, report));
            }
            return new Palette(data, layers, blocks, itemPalette, layout, content, report, parent.ordering(), parent.gating(),
                parent.behaviourStore(), shapes, parent);
        } catch (PaletteException e) {
            throw new PaletteException("Unable to compile layer " + source + " for " + data.version() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // A component encoder or a consumer callback (override modifier, creative edit) failed
            throw new PaletteException("Unable to compile layer " + source + " for " + data.version() + ": "
                + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
    }

    /** The layout of the parent with the creative placements and edits of the layer applied. */
    private static CreativeLayout creativeLayout(Palette parent, PaletteLayer layer, BlockRegistry blocks, List<Placed> placed) {
        CreativeEditor creative = parent.creativeLayout().edit();
        CreativeOrdering ordering = parent.ordering();
        VanillaData data = parent.vanillaData();
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
        return creative.build();
    }

    /** @return true if a stack of the layout, or the icon of a group, is an item or a block state with one of these identifiers */
    private static boolean mentions(CreativeLayout layout, Set<String> identifiers) {
        Stream<BedrockItemStack> icons = Arrays.stream(CreativeCategory.values()).flatMap(category -> layout.nodes(category).stream())
            .filter(CreativeGroup.class::isInstance).map(node -> ((CreativeGroup) node).icon()).filter(Objects::nonNull);
        return Stream.concat(layout.stacks(), icons).anyMatch(stack -> identifiers.contains(stack.type().identifier())
            || stack.blockState() != null && identifiers.contains(stack.blockState().type().identifier()));
    }

    private static BedrockItemStack blockItemStack(CustomBlock block, BlockRegistry blocks) {
        BedrockBlockState state = blocks.canonical().handle(Canonicals.block(blocks.canonical(), block.defaultState()));
        return BedrockItemStack.of(block.itemType()).withBlockState(state);
    }

    /** A creative placement to apply; {@code block} is the custom block if the stack is a block item. */
    private record Placed(@Nullable String block, BedrockItemStack stack, CreativePlacement placement) {
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
                    : blocks.defaultFallback().target().identifier()) + ": " + names);
            }
        }
        List<ItemVariant> missingItems = items.missingCanonical();
        if (!missingItems.isEmpty() && (parentItems == null || !missingItems.equals(parentItems.missingCanonical()))) {
            report.info("canonical", missingItems.size() + " canonical item variants do not exist in " + items.version()
                + " and have no fallback" + (items.defaultFallback() == null ? "" : ", and are sent as "
                + items.defaultFallback().target().identifier()) + ": " + missingItems);
        }
    }
}
