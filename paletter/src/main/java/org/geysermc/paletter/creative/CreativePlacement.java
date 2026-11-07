package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Declares where a custom item or block item goes in the creative inventory. It is tried in this order:
 * <ol>
 *     <li>right after {@code after}, if set and present;</li>
 *     <li>at the end of {@code group}, if set. The group is created at the end of the category if it does not exist;</li>
 *     <li>as a loose stack at the end of the category.</li>
 * </ol>
 * It never ends up inside some unrelated vanilla group just because that group happens to be last in the category.
 * <p>
 * The category and group are also written into the item's data driven properties ({@code creative_category} and
 * {@code creative_group}), and into a custom block's {@code menu_category}, so the client and the creative packet agree.
 *
 * @param category the category
 * @param group the group to add to, or null
 * @param after a stack to insert after, or null
 */
public record CreativePlacement(@NonNull CreativeCategory category, @Nullable String group, @Nullable ItemMatcher after) {

    public static @NonNull CreativePlacement loose(@NonNull CreativeCategory category) {
        return new CreativePlacement(category, null, null);
    }

    public static @NonNull CreativePlacement group(@NonNull CreativeCategory category, @NonNull String group) {
        return new CreativePlacement(category, group, null);
    }

    public @NonNull CreativePlacement after(@NonNull ItemMatcher anchor) {
        return new CreativePlacement(category, group, anchor);
    }

    /**
     * Applies this placement to an editor.
     */
    public void apply(@NonNull CreativeEditor editor, @NonNull CreativeStack stack) {
        CreativeEditor.Insertion insertion = editor.insert(stack);
        if (after != null && insertion.after(after)) {
            return;
        }
        if (group != null) {
            insertion.intoGroup(category, group);
        } else {
            insertion.loose(category);
        }
    }
}
