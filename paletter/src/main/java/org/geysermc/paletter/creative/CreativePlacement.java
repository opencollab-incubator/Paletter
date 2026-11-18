package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.ItemKey;

/**
 * Declares where a custom item or block item goes in the creative inventory. It is tried in this order:
 * <ol>
 *     <li>right after {@code after}, if set and present;</li>
 *     <li>at the end of {@code group}, if set. The group is created at the end of the category if it does not exist;</li>
 *     <li>as a loose stack at the end of the category.</li>
 * </ol>
 * It never ends up inside some unrelated vanilla group just because that group happens to be last in the category.
 * Without an anchor, the layer's {@link CreativeOrdering} decides the order between stacks and how groups are found
 * (by default exactly like BDS, see {@link CreativeOrdering#BDS}).
 * <p>
 * The category and group are also written into the item's data driven properties ({@code creative_category} and
 * {@code creative_group}), and into a custom block's {@code menu_category}, so the client and the creative packet agree.
 *
 * @param category the category
 * @param group the group to add to, or null
 * @param after a stack to insert after, or null
 * @since 1.0.0
 */
public record CreativePlacement(@NonNull CreativeCategory category, @Nullable String group, @Nullable ItemMatcher after) {

    /**
     * Places at the end of a category, outside any group.
     *
     * @param category the category
     * @return the placement
     */
    public static @NonNull CreativePlacement loose(@NonNull CreativeCategory category) {
        return new CreativePlacement(category, null, null);
    }

    /**
     * Places at the end of a group of a category.
     *
     * @param category the category
     * @param group the group name, a translation key like {@code itemGroup.name.minecart}
     * @return the placement
     */
    public static @NonNull CreativePlacement group(@NonNull CreativeCategory category, @NonNull String group) {
        return new CreativePlacement(category, group, null);
    }

    /**
     * Returns this placement with an anchor.
     *
     * @param anchor the stacks to insert after
     * @return this placement, right after the first stack the matcher selects, if present
     */
    public @NonNull CreativePlacement after(@NonNull ItemMatcher anchor) {
        return new CreativePlacement(category, group, anchor);
    }

    /**
     * Returns this placement with an anchor on every variant of an item type.
     *
     * @param anchor the item type to insert after
     * @return this placement, right after the stacks of any variant of an item type if present
     * @since 1.0.0
     */
    public @NonNull CreativePlacement afterAnyVariantOf(@NonNull BedrockItemType anchor) {
        return after(ItemMatcher.anyVariantOf(anchor));
    }

    /**
     * Returns this placement with an anchor on one variant.
     *
     * @param anchor the variant to insert after, or a type for its base variant
     * @return this placement, right after exactly that variant if present (a type is its base variant)
     * @since 1.0.0
     */
    public @NonNull CreativePlacement after(@NonNull ItemKey anchor) {
        return after(ItemMatcher.of(anchor));
    }

    /**
     * Applies this placement to an editor. An anchor ({@link #after}) always wins if it exists; otherwise the
     * stack goes where the ordering policy puts it.
     * <p>
     * Internal: called when a layer's content is added to the layout.
     *
     * @param editor the editor of the layout
     * @param stack the stack to place
     * @param ordering the ordering policy of the palette
     * @since 1.0.0
     */
    @Internal
    public void apply(@NonNull CreativeEditor editor, @NonNull BedrockItemStack stack, @NonNull CreativeOrdering ordering) {
        if (ordering == CreativeOrdering.BDS) {
            CreativeEditor.Insertion insertion = editor.insert(stack);
            if (after != null && insertion.after(after)) {
                return;
            }
            if (group != null) {
                insertion.intoCategoryGroup(category, bdsGroupName(group));
            } else {
                insertion.loose(category);
            }
            return;
        }
        applyAppend(editor, stack);
    }

    /**
     * The creative packet name BDS uses for a {@code menu_category} group: the {@code minecraft:} namespace is
     * dropped ({@code minecraft:itemGroup.name.sword} is {@code itemGroup.name.sword}), other namespaces are kept.
     */
    static @NonNull String bdsGroupName(@NonNull String group) {
        return group.startsWith("minecraft:") ? group.substring("minecraft:".length()) : group;
    }

    private void applyAppend(@NonNull CreativeEditor editor, @NonNull BedrockItemStack stack) {
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
