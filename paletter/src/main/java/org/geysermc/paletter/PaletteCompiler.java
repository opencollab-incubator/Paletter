package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockInterner;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.CanonicalBlocks;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.item.CanonicalItems;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemPalette;
import org.geysermc.paletter.item.ItemVariant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Compiles palettes: vanilla data first, then one layer at a time.
 */
final class PaletteCompiler {

    private PaletteCompiler() {
    }

    static Palette vanilla(VanillaData data, BlockNetworkIds mode, CanonicalBlocks canonicalBlocks, CanonicalItems canonicalItems,
                           BlockInterner interner) {
        PaletteReport report = new PaletteReport();
        BlockPalette blocks = BlockPalette.build(data, mode, canonicalBlocks, interner);
        ItemPalette items = ItemPalette.vanilla(data, blocks, canonicalItems).build(data.creative(), report);
        CreativeContent creative = CreativeContent.compile(data.creative(), items, report);
        prepareCanonical(blocks, null, items, null, report);
        return new Palette(data, List.of(), blocks, items, data.creative(), creative, report);
    }

    static Palette extend(Palette parent, PaletteLayer layer) {
        VanillaData data = parent.vanillaData();
        String source = layer.id();
        PaletteReport report = new PaletteReport();
        report.addAll(parent.report());

        try {
            // Blocks first: items need to know which blocks exist, and custom blocks re-number all states.
            // Without custom blocks or block fallbacks the parent's block palette (and its canonical table) is shared
            BlockPalette blocks = parent.blocks()
                .withCustomBlocks(data, layer, layer.blocks())
                .withFallbacks(layer.blockFallbacks());

            List<ItemVariant> newVariants = new ArrayList<>();
            layer.items().forEach(item -> newVariants.add(item.variant()));
            for (CustomBlock block : layer.blocks()) {
                if (!(block.item() instanceof CustomBlock.BlockItem.None)) {
                    newVariants.add(ItemVariant.of(block.identifier()));
                }
            }
            newVariants.addAll(layer.variants());
            CanonicalItems canonicalItems = parent.items().canonical().extend(layer, newVariants);

            ItemPalette.Builder items = parent.items().extend(blocks, canonicalItems);
            layer.itemFallbacks().forEach(items::fallback);
            for (PaletteLayer.Override override : layer.overrides()) {
                if (!items.override(override.identifier(), override.modifier())) {
                    report.info(source, "Not overriding " + override.identifier() + ": the item does not exist in " + data.version());
                }
            }

            CreativeEditor creative = parent.creativeLayout().edit();
            for (CustomItem custom : layer.items()) {
                items.addCustom(custom);
                if (custom.creative() != null) {
                    custom.creative().apply(creative, CreativeStack.of(custom.identifier()));
                }
            }
            for (CustomBlock block : layer.blocks()) {
                if (items.addBlockItem(block) != null && block.creative() != null) {
                    block.creative().apply(creative, CreativeStack.of(block.identifier()).withBlockState(block.defaultState()));
                }
            }
            for (BiConsumer<CreativeEditor, DataVersion> edit : layer.creativeEdits()) {
                edit.accept(creative, data.version());
            }
            layer.tags().forEach(items::tag);

            CreativeLayout layout = creative.build();
            ItemPalette itemPalette = items.build(layout, report);
            CreativeContent content = CreativeContent.compile(layout, itemPalette, report);
            prepareCanonical(blocks, parent.blocks(), itemPalette, parent.items(), report);

            List<PaletteLayer> layers = new ArrayList<>(parent.layers());
            layers.add(layer);
            return new Palette(data, layers, blocks, itemPalette, layout, content, report);
        } catch (PaletteException e) {
            throw new PaletteException("Unable to compile layer " + source + " for " + data.version() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the canonical tables eagerly, and reports what they lack (only what is new compared to the parent, whose
     * report entries are already included).
     */
    private static void prepareCanonical(BlockPalette blocks, BlockPalette parentBlocks, ItemPalette items, ItemPalette parentItems,
                                         PaletteReport report) {
        blocks.prepareCanonical();
        items.prepareCanonical();
        if (blocks != parentBlocks) {
            List<NbtMap> missing = blocks.missingCanonical();
            if (!missing.isEmpty() && (parentBlocks == null || !missing.equals(parentBlocks.missingCanonical()))) {
                Set<String> names = new LinkedHashSet<>();
                missing.forEach(state -> names.add(BlockStates.name(state)));
                report.info("canonical", missing.size() + " canonical block states have no " + blocks.version()
                    + " block and no fallback, and are sent as air: " + names);
            }
        }
        List<ItemVariant> missingItems = items.missingCanonical();
        if (!missingItems.isEmpty() && (parentItems == null || !missingItems.equals(parentItems.missingCanonical()))) {
            report.info("canonical", missingItems.size() + " canonical item variants do not exist in " + items.version()
                + " and have no fallback: " + missingItems);
        }
    }
}
