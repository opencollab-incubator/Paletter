package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.vanilla.ItemKey;

/**
 * An item a recipe produces.
 *
 * @param item the item variant
 * @param count how many items
 * @param blockState the block state of the item (compound with {@code name} and {@code states}), or an empty map if the item places no particular state
 * @param tag the item's NBT, or an empty map if it has none
 * @since 1.0.0
 */
public record Result(@NonNull ItemKey item, int count, @NonNull NbtMap blockState, @NonNull NbtMap tag) {
}
