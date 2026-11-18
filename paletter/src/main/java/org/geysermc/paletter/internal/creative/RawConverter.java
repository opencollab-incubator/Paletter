package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.internal.PaletteReportBuilder;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeItem;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the identifier based creative data of a version to a layout of stacks. Item identifiers go through the
 * renames and splits of {@link BedrockItemVariant#fromIdentifier}. Block states are mapped forward to the newest
 * version like {@link org.geysermc.paletter.Palette#blockDefinition}; a state without an equivalent is dropped from the stack
 * (it then places the item's default block) and reported. An item whose constant is not available (one only a preview
 * has) is the handle of the palette's canonical items, so it is sent as itself, not as the constant's fallback.
 */
public final class RawConverter {
    private final CanonicalBlocks blocks;
    private final CanonicalItems items;
    private final PaletteReportBuilder report;

    private RawConverter(CanonicalBlocks blocks, CanonicalItems items, PaletteReportBuilder report) {
        this.blocks = blocks;
        this.items = items;
        this.report = report;
    }

    public static @NonNull CreativeLayout convert(@NonNull RawLayout raw, @NonNull BlockRegistry blocks, @NonNull CanonicalItems items,
                                                  @NonNull PaletteReportBuilder report) {
        RawConverter converter = new RawConverter(blocks.canonical(), items, report);
        Map<CreativeCategory, List<CreativeNode>> categories = new EnumMap<>(CreativeCategory.class);
        for (CreativeCategory category : CreativeCategory.values()) {
            List<CreativeNode> nodes = new ArrayList<>();
            for (RawNode node : raw.nodes(category)) {
                switch (node) {
                    case RawStack stack -> nodes.add(new CreativeItem(converter.convert(stack)));
                    case RawGroup group -> nodes.add(new CreativeGroup(group.name(),
                        group.icon() == null ? null : converter.convert(group.icon()),
                        group.stacks().stream().map(converter::convert).toList()));
                }
            }
            categories.put(category, nodes);
        }
        return CreativeLayout.of(categories);
    }

    private BedrockItemStack convert(RawStack stack) {
        BedrockBlockState state = null;
        if (stack.blockState() != null) {
            state = blocks.handle(Canonicals.block(blocks, stack.blockState()));
            if (state == null) {
                report.info("creative", "Dropped the block state of " + stack.identifier() + ": "
                    + Canonicals.forwardBlock(blocks, stack.blockState()).detail());
            }
        }
        BedrockItemVariant variant = BedrockItemVariant.fromIdentifier(stack.identifier(), stack.damage());
        if (!variant.type().isAvailable()) {
            BedrockItemVariant own = items.handle(items.index(variant.toItemVariant()));
            if (own != null) {
                variant = own;
            }
        }
        return new BedrockItemStack(variant, stack.count(), stack.tag(), state);
    }
}
