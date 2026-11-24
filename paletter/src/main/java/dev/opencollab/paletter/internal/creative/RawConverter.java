package dev.opencollab.paletter.internal.creative;

import dev.opencollab.paletter.PaletteReport;
import org.checkerframework.checker.nullness.qual.NonNull;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.block.BlockRegistry;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeGroup;
import dev.opencollab.paletter.creative.CreativeItem;
import dev.opencollab.paletter.creative.CreativeLayout;
import dev.opencollab.paletter.creative.CreativeNode;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.Canonicals;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the identifier based creative data of a version to a layout of stacks. Item identifiers go through the
 * renames and splits of {@link BedrockItemVariant#fromIdentifier}. Block states are mapped forward to the newest
 * version like {@link dev.opencollab.paletter.Palette#blockDefinition}; a state without an equivalent is dropped from the stack
 * (it then places the item's default block) and reported.
 */
public final class RawConverter {
    private final CanonicalBlocks blocks;
    private final PaletteReportBuilder report;

    private RawConverter(CanonicalBlocks blocks, PaletteReportBuilder report) {
        this.blocks = blocks;
        this.report = report;
    }

    public static @NonNull CreativeLayout convert(@NonNull RawLayout raw, @NonNull BlockRegistry blocks, @NonNull PaletteReportBuilder report) {
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
                report.info("creative", PaletteReport.CREATIVE_BLOCK_STATE_DROPPED, stack.identifier(), "Dropped the block state of " + stack.identifier() + ": "
                    + Canonicals.forwardBlock(blocks, stack.blockState()).detail());
            }
        }
        return BedrockItemStack.builder(BedrockItemVariant.fromIdentifier(stack.identifier(), stack.damage())).count(stack.count())
            .nbt(stack.tag()).blockState(state).build();
    }
}
