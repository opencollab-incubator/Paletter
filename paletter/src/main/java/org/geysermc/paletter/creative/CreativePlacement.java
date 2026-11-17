package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.ItemKey;

/**
 * Where a custom item or block item goes in the creative inventory <i>within</i> its category and group: the position
 * adjustment of the layer. Whether it is in the creative inventory at all, and its category and group, are decided by the
 * {@code menu_category} of the item or block alone, as in BDS (absent or {@code none}: not in it).
 * <ul>
 *     <li>{@link #DEFAULT}: where the layer's {@link CreativeOrdering} puts it (by default exactly like BDS, see
 *     {@link CreativeOrdering#BDS});</li>
 *     <li>{@link #after(ItemMatcher)}: right after the first stack that matches, under every ordering. An anchor that
 *     matches nothing falls back to the default position, and the compile reports it.</li>
 * </ul>
 *
 * @since 1.0.0
 */
public sealed interface CreativePlacement permits CreativePlacement.Default, CreativePlacement.After {

    /**
     * The position the layer's {@link CreativeOrdering} gives: what an item or block has by default.
     *
     * @since 1.0.0
     */
    CreativePlacement DEFAULT = new Default();

    /**
     * @return a placement right after the stacks an anchor matches
     * @since 1.0.0
     */
    static @NonNull CreativePlacement after(@NonNull ItemMatcher anchor) {
        return new After(anchor);
    }

    /**
     * @return a placement right after exactly that variant, if present (a type is its base variant)
     * @since 1.0.0
     */
    static @NonNull CreativePlacement after(@NonNull ItemKey anchor) {
        return after(ItemMatcher.of(anchor));
    }

    /**
     * @return a placement right after the stacks of any variant of an item type, if present
     * @since 1.0.0
     */
    static @NonNull CreativePlacement afterAnyVariantOf(@NonNull BedrockItemType anchor) {
        return after(ItemMatcher.anyVariantOf(anchor));
    }

    /**
     * Puts a stack in the creative inventory: after the anchor if there is one and it matches, else at the end of the
     * group (created at the end of the category if missing), else loose at the end of the category.
     *
     * @param category the category of the item's {@code menu_category}
     * @param group the group of the item's {@code menu_category}, or null
     * @return false if an anchor was set but matched nothing, so the default position was used
     * @since 1.0.0
     */
    @Internal
    boolean apply(@NonNull CreativeEditor editor, @NonNull BedrockItemStack stack, @NonNull CreativeCategory category,
               @Nullable String group, @NonNull CreativeOrdering ordering);

    /**
     * The type of {@link #DEFAULT}: there is only one.
     *
     * @since 1.0.0
     */
    final class Default implements CreativePlacement {
        private Default() {
        }

        @java.lang.Override
        public boolean apply(@NonNull CreativeEditor editor, @NonNull BedrockItemStack stack, @NonNull CreativeCategory category,
                             @Nullable String group, @NonNull CreativeOrdering ordering) {
            return place(editor.insert(stack), null, category, group, ordering);
        }

        @java.lang.Override
        public String toString() {
            return "CreativePlacement.DEFAULT";
        }
    }

    /**
     * A placement right after an anchor.
     *
     * @param anchor the stack to insert after
     * @since 1.0.0
     */
    record After(@NonNull ItemMatcher anchor) implements CreativePlacement {
        public After {
            Objects.requireNonNull(anchor, "anchor");
        }

        @java.lang.Override
        public boolean apply(@NonNull CreativeEditor editor, @NonNull BedrockItemStack stack, @NonNull CreativeCategory category,
                             @Nullable String group, @NonNull CreativeOrdering ordering) {
            return place(editor.insert(stack), anchor, category, group, ordering);
        }
    }

    private static boolean place(CreativeEditor.Insertion insertion, @Nullable ItemMatcher anchor, CreativeCategory category,
                                 @Nullable String group, CreativeOrdering ordering) {
        if (anchor != null && insertion.after(anchor)) {
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
        return anchor == null;
    }

    /**
     * The creative packet name BDS uses for a {@code menu_category} group: the {@code minecraft:} namespace is
     * dropped ({@code minecraft:itemGroup.name.sword} is {@code itemGroup.name.sword}), other namespaces are kept.
     */
    private static String bdsGroupName(String group) {
        return group.startsWith("minecraft:") ? group.substring("minecraft:".length()) : group;
    }
}
