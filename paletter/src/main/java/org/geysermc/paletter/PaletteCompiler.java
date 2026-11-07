package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.attach.AttachmentStore;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeContent;
import org.geysermc.paletter.creative.CreativeEditor;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeStack;
import org.geysermc.paletter.data.VanillaData;
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

    static Palette vanilla(VanillaData data) {
        PaletteReport report = new PaletteReport();
        BlockPalette blocks = BlockPalette.build(data, List.of());
        ItemPalette items = ItemPalette.vanilla(data, blocks).build(data.creative(), report);
        CreativeContent creative = CreativeContent.compile(data.creative(), items, report);
        return new Palette(data, List.of(), blocks, items, data.creative(), creative, report);
    }

    static Palette extend(Palette parent, PaletteLayer layer) {
        VanillaData data = parent.vanillaData();
        String source = layer.id();
        PaletteReport report = new PaletteReport();
        report.addAll(parent.report());

        try {
            // Blocks first: items need to know which blocks exist, and custom blocks re-number all states
            BlockPalette blocks = parent.blocks().withCustomBlocks(data, layer.blocks());
            AttachmentStore<NbtMap> blockAttachments = blocks == parent.blocks() ? blocks.attachmentStore().copy() : blocks.attachmentStore();
            blocks = blocks.derive(layer.blockFallbacks(), blockAttachments);
            for (PaletteLayer.BlockAttachment attachment : layer.blockAttachments()) {
                BlockResolution resolution = blocks.resolve(attachment.state());
                if (resolution.block() == null) {
                    report.info(source, "Not attaching " + attachment.key() + " to " + attachment.state() + ": the block does not exist in " + data.version());
                    continue;
                }
                blockAttachments.put(resolution.block().getState(), attachment.key(), attachment.value(),
                    resolution.kind() == BlockResolution.Kind.FALLBACK, source);
            }

            ItemPalette.Builder items = parent.items().extend(blocks);
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
                for (CustomItem.Attached<?> attached : custom.attachments()) {
                    items.attach(custom.variant(), attached.key(), attached.value(), source);
                }
            }
            for (CustomBlock block : layer.blocks()) {
                if (items.addBlockItem(block) != null && block.creative() != null) {
                    block.creative().apply(creative, CreativeStack.of(block.identifier()).withBlockState(block.defaultState()));
                }
            }
            for (BiConsumer<CreativeEditor, org.geysermc.paletter.data.DataVersion> edit : layer.creativeEdits()) {
                edit.accept(creative, data.version());
            }
            layer.tags().forEach(items::tag);

            for (PaletteLayer.ItemAttachment attachment : layer.itemAttachments()) {
                if (!items.attach(attachment.variant(), attachment.key(), attachment.value(), source)) {
                    report.info(source, "Not attaching " + attachment.key() + " to " + attachment.variant() + ": the item does not exist in "
                        + data.version() + " and has no fallback");
                }
            }

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
