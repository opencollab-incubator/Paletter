package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeContent;
import dev.opencollab.paletter.creative.CreativeEditor;
import dev.opencollab.paletter.creative.CreativeLayout;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.creative.CreativePlacement;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.internal.item.ItemRegistry;
import dev.opencollab.paletter.item.BedrockItemStack;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;

import java.util.Collection;
import java.util.List;

/** The package-private members of {@code dev.opencollab.paletter.creative} that other packages use. Not API. */
public interface CreativeAccess {
    /** {@code CreativeContent.compile}: compiles a layout for one palette. */
    @NonNull CreativeContent compile(@NonNull CreativeLayout layout, @NonNull ItemRegistry items, @NonNull PaletteReportBuilder report);

    /** {@code CreativeContent#toPacket()}. */
    @NonNull CreativeContentPacket toPacket(@NonNull CreativeContent content);

    /** {@code CreativeOrdering#blockOrder}: the order the custom blocks are registered in. */
    @NonNull List<String> blockOrder(@NonNull CreativeOrdering ordering, @NonNull List<String> customBlocks,
                                     @NonNull Collection<String> vanillaDataDrivenBlocks);

    /**
     * {@code Placements.apply}: inserts a stack where its placement says.
     *
     * @return false if an anchor was set but matched nothing, so the default position was used
     */
    boolean place(@NonNull CreativePlacement placement, @NonNull CreativeEditor editor, @NonNull BedrockItemStack stack,
                  @NonNull CreativeCategory category, @Nullable String group, @NonNull CreativeOrdering ordering);
}
