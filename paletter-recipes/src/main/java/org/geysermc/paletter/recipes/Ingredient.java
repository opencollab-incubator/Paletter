package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.vanilla.ItemKey;

/**
 * What a recipe slot takes, as BDS sends it. Every ingredient has a {@link #count()}: how many items the slot uses.
 *
 * @since 1.0.0
 */
public sealed interface Ingredient {
    /**
     * @return how many items the slot uses; 0 for {@link Empty}
     */
    int count();

    /**
     * One item.
     *
     * @param item the item type when {@code anyVariant} (any aux value is accepted), else the exact variant
     * @param anyVariant whether every aux value of the item's type is accepted
     * @param count how many items the slot uses
     * @since 1.0.0
     */
    record Item(@NonNull ItemKey item, boolean anyVariant, int count) implements Ingredient {
    }

    /**
     * Any item of an item tag (a key of {@code Palette#itemTags()}).
     *
     * @param tag the tag, e.g. {@code minecraft:planks}
     * @param count how many items the slot uses
     * @since 1.0.0
     */
    record Tag(@NonNull String tag, int count) implements Ingredient {
    }

    /**
     * A name of the old, pre-flattening item groups that the data versions up to 26.30 still use in some recipes, e.g.
     * {@code minecraft:log}. Which variants it stands for is not part of the data.
     *
     * @param name the alias
     * @param count how many items the slot uses
     * @since 1.0.0
     */
    record Alias(@NonNull String name, int count) implements Ingredient {
    }

    /**
     * An empty slot.
     *
     * @since 1.0.0
     */
    record Empty() implements Ingredient {
        @Override
        public int count() {
            return 0;
        }
    }
}
