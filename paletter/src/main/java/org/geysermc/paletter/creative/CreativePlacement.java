package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import java.util.Objects;
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
public sealed interface CreativePlacement permits DefaultPlacement, CreativePlacement.After {

    /**
     * The position the layer's {@link CreativeOrdering} gives: what an item or block has by default.
     *
     * @since 1.0.0
     */
    CreativePlacement DEFAULT = new DefaultPlacement();

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
     * A placement right after an anchor. If nothing matches the anchor, the default position is used and the palette
     * report gets a warning.
     *
     * @param anchor the stack to insert after
     * @since 1.0.0
     */
    record After(@NonNull ItemMatcher anchor) implements CreativePlacement {
        public After {
            Objects.requireNonNull(anchor, "anchor");
        }
    }
}
