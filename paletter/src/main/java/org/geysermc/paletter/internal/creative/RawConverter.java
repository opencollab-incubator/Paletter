package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeGroup;
import org.geysermc.paletter.creative.CreativeItem;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.creative.CreativeNode;
import org.geysermc.paletter.internal.CanonicalBlocks;
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
 * renames and splits of {@link BedrockItemVariant#of(String, int)}. Block states are mapped forward to the newest
 * version like {@link org.geysermc.paletter.Palette#block}; a state without an equivalent is dropped from the stack
 * (it then places the item's default block) and reported.
 */
public final class RawConverter {
    private final CanonicalBlocks blocks;
    private final PaletteReport report;

    private RawConverter(CanonicalBlocks blocks, PaletteReport report) {
        this.blocks = blocks;
        this.report = report;
    }

    public static @NonNull CreativeLayout convert(@NonNull RawLayout raw, @NonNull BlockRegistry blocks, @NonNull PaletteReport report) {
        RawConverter converter = new RawConverter(blocks.canonical(), report);
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
        return new BedrockItemStack(BedrockItemVariant.of(stack.identifier(), stack.damage()), stack.count(), stack.tag(), state);
    }
}
