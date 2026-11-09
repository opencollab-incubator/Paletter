package org.geysermc.paletter.internal.data;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * What one experiment toggle adds to a version's vanilla content, as BDS showed it: the bundled data of a version is
 * captured without experiments, so this content is either missing from it (new items) or present but not usable
 * without the toggle (blocks in the palette, items in the registry that only the toggle turns on). A Paletter treats it
 * as missing unless it enables the toggle ({@code VanillaContent#experiments}), so ordinary fallbacks apply to it; a block
 * is gated with its item (the block to item relation).
 * <p>
 * Recorded by the generator's {@code update} (one capture per toggle against one without any, see README.md
 * "Experiments").
 *
 * @param items item identifiers the toggle adds to the item registry or the Script API item types, changes
 *              (version, component based flag or components) or first puts into the creative inventory
 * @param blocks block identifiers the toggle adds to the Script API block types or to the start game block properties
 * @param creative creative stacks the toggle adds, as {@code identifier} or {@code identifier:damage}
 *
 * @since 1.0.0
 */
public record ExperimentContent(@NonNull List<String> items, @NonNull List<String> blocks, @NonNull List<String> creative) {
    public static final ExperimentContent EMPTY = new ExperimentContent(List.of(), List.of(), List.of());

    public ExperimentContent {
        items = List.copyOf(items);
        blocks = List.copyOf(blocks);
        creative = List.copyOf(creative);
    }

    public boolean isEmpty() {
        return items.isEmpty() && blocks.isEmpty() && creative.isEmpty();
    }
}
