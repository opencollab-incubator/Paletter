package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.item.BedrockItemStack;

/**
 * Puts the stack of an item or block item in the creative inventory, where its {@link CreativePlacement} says within the
 * category and group of its menu category.
 */
@Internal
public final class Placements {
    private Placements() {
    }

    /**
     * Inserts a stack: after the anchor if there is one and it matches, else at the end of the group (created at the end of
     * the category if missing), else loose at the end of the category.
     *
     * @param group the group of the {@code menu_category}, or null
     * @return false if an anchor was set but matched nothing, so the default position was used
     */
    public static boolean apply(@NonNull CreativePlacement placement, @NonNull CreativeEditor editor, @NonNull BedrockItemStack stack,
                                @NonNull CreativeCategory category, @Nullable String group, @NonNull CreativeOrdering ordering) {
        CreativeEditor.Insertion insertion = editor.insert(stack);
        boolean anchored = placement instanceof CreativePlacement.After;
        if (placement instanceof CreativePlacement.After after && insertion.after(after.anchor())) {
            return true;
        }
        if (ordering == CreativeOrdering.BDS) {
            if (group != null) {
                insertion.intoCategoryGroup(category, bdsGroupName(group));
            } else {
                insertion.loose(category);
            }
        } else if (group != null) {
            insertion.intoGroup(category, group);
        } else {
            insertion.loose(category);
        }
        return !anchored;
    }

    /**
     * The creative packet name BDS uses for a {@code menu_category} group: the {@code minecraft:} namespace is
     * dropped ({@code minecraft:itemGroup.name.sword} is {@code itemGroup.name.sword}), other namespaces are kept.
     */
    private static String bdsGroupName(String group) {
        return group.startsWith("minecraft:") ? group.substring("minecraft:".length()) : group;
    }
}
