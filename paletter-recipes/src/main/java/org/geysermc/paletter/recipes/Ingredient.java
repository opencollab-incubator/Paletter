package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

/**
 * What a recipe slot takes, as BDS sends it. Every ingredient has a {@link #count()}: how many items the slot uses.
 *
 * @since 1.0.0
 */
public sealed interface Ingredient {
    /**
     * @return how many items the slot uses; 0 for {@link Empty}, else at least 1
     */
    int count();

    /**
     * One exact item variant.
     *
     * @param item the variant
     * @param count how many items the slot uses, at least 1
     * @since 1.0.0
     */
    record Item(@NonNull BedrockItemVariant item, int count) implements Ingredient {
        public Item {
            requireCount(count);
        }
    }

    /**
     * Any variant of an item type (BDS's aux value 32767).
     *
     * @param type the item type
     * @param count how many items the slot uses, at least 1
     * @since 1.0.0
     */
    record AnyVariant(@NonNull BedrockItemType type, int count) implements Ingredient {
        public AnyVariant {
            requireCount(count);
        }
    }

    /**
     * Any item of an item tag (a key of {@code Palette#itemTags()}).
     *
     * @param tag the tag, e.g. {@code minecraft:planks}
     * @param count how many items the slot uses, at least 1
     * @since 1.0.0
     */
    record Tag(@NonNull String tag, int count) implements Ingredient {
        public Tag {
            requireCount(count);
        }
    }

    /**
     * A name of the old, pre-flattening item groups, e.g. {@code minecraft:log}. Only the data version 26.30 has them (in
     * some of its recipes); newer data versions use items and tags instead. Which variants an alias stands for is not part of
     * the data.
     *
     * @param name the alias
     * @param count how many items the slot uses, at least 1
     * @since 1.0.0
     */
    record Alias(@NonNull String name, int count) implements Ingredient {
        public Alias {
            requireCount(count);
        }
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

    private static void requireCount(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("An ingredient needs a count of at least 1: " + count);
        }
    }
}
