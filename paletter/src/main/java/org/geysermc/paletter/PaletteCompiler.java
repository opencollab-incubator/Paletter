package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockNetworkIds;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Compiles palettes: vanilla data first, then one layer at a time.
 */
final class PaletteCompiler {

    private PaletteCompiler() {
    }

    static Palette vanilla(VanillaData data, BlockNetworkIds mode, CanonicalBlocks canonicalBlocks, CanonicalItems canonicalItems) {
        PaletteReport report = new PaletteReport();
        BlockPalette blocks = BlockPalette.build(data, mode, canonicalBlocks);
        ItemPalette items = ItemPalette.vanilla(data, blocks, canonicalItems).build(data.creative(), report);
        CreativeContent creative = CreativeContent.compile(data.creative(), items, report);
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

            List<String> customItems = new ArrayList<>();
            layer.items().forEach(item -> customItems.add(item.identifier()));
            for (CustomBlock block : layer.blocks()) {
                if (!(block.item() instanceof CustomBlock.BlockItem.None)) {
                    customItems.add(block.identifier());
                }
            }
            CanonicalItems canonicalItems = parent.items().canonical().extend(layer, customItems);

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

            List<PaletteLayer> layers = new ArrayList<>(parent.layers());
            layers.add(layer);
            return new Palette(data, layers, blocks, itemPalette, layout, content, report);
        } catch (PaletteException e) {
            throw new PaletteException("Unable to compile layer " + source + " for " + data.version() + ": " + e.getMessage(), e);
        }
    }
}
