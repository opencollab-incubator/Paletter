package dev.opencollab.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.item.BedrockItemStack;

/**
 * Puts the stack of an item or block item in the creative inventory, where its {@link CreativePlacement} says within the
 * category and group of its menu category.
 */
final class Placements {
    private Placements() {
    }

    /**
     * Inserts a stack: after or before the anchor if there is one and it matches, else at the end of the group (the start for
     * {@link CreativePlacement#FIRST}; the group is created at the end of the category if missing), else loose at the end
     * (or the start) of the category.
     *
     * @param placement where the stack goes
     * @param editor the creative editor of the palette being compiled
     * @param stack the stack to insert
     * @param category the creative category of the menu category
     * @param group the group of the {@code menu_category}, or null
     * @param ordering the creative ordering of the palette
     * @return false if an anchor was set but matched nothing, so the default position was used
     */
    static boolean apply(@NonNull CreativePlacement placement, @NonNull CreativeEditor editor, @NonNull BedrockItemStack stack,
                         @NonNull CreativeCategory category, @Nullable String group, @NonNull CreativeOrdering ordering) {
        CreativeEditor.Insertion insertion = editor.insert(stack);
        boolean anchored = placement instanceof CreativePlacement.After || placement instanceof CreativePlacement.Before;
        if (placement instanceof CreativePlacement.After after && insertion.after(after.anchor())) {
            return true;
        }
        if (placement instanceof CreativePlacement.Before before && insertion.before(before.anchor())) {
            return true;
        }
        boolean first = placement == CreativePlacement.FIRST;
        if (group != null) {
            boolean bds = ordering == CreativeOrdering.BDS;
            insertion.intoCategoryGroup(category, bds ? bdsGroupName(group) : group, bds, first);
        } else if (first) {
            insertion.looseFirst(category);
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
