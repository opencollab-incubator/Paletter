package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.ItemKey;

import java.util.Objects;

/**
 * A stack of items, independent of any protocol version: what a {@link org.geysermc.paletter.Palette} encodes to and
 * decodes from the client's {@code ItemData}, and what the creative inventory is made of.
 *
 * @param variant the item variant (type and aux value)
 * @param count the stack size, at least 1
 * @param nbt the item NBT, or null for none (an empty compound is normalized to null)
 * @param blockState the block the stack places, or null for the item's default block (or none)
 * @since 1.0.0
 */
public record BedrockItemStack(@NonNull BedrockItemVariant variant, int count, @Nullable NbtMap nbt,
                               @Nullable BedrockBlockState blockState) {

    /**
     * Creates a stack.
     *
     * @throws NullPointerException if the variant is null
     * @throws IllegalArgumentException if the count is below 1
     */
    public BedrockItemStack {
        Objects.requireNonNull(variant, "variant");
        if (count < 1) {
            throw new IllegalArgumentException("Count must be at least 1, was " + count);
        }
        if (nbt != null && nbt.isEmpty()) {
            nbt = null;
        }
    }

    /**
     * Creates a stack of one item.
     *
     * @param key a variant, or a type for its base variant
     * @return one item of a variant, or of a type's base variant, without NBT or block state
     */
    public static @NonNull BedrockItemStack of(@NonNull ItemKey key) {
        return new BedrockItemStack(key.variant(), 1, null, null);
    }

    /**
     * Creates a stack of several items.
     *
     * @param key a variant, or a type for its base variant
     * @param count the stack size, at least 1
     * @return a stack of a variant, or of a type's base variant, without NBT or block state
     * @throws IllegalArgumentException if the count is below 1
     */
    public static @NonNull BedrockItemStack of(@NonNull ItemKey key, int count) {
        return new BedrockItemStack(key.variant(), count, null, null);
    }

    /**
     * Returns the item type of the variant.
     *
     * @return the item type
     */
    public @NonNull BedrockItemType type() {
        return variant.type();
    }

    /**
     * Returns a copy with another variant.
     *
     * @param key a variant, or a type for its base variant
     * @return the new stack
     */
    public @NonNull BedrockItemStack withVariant(@NonNull ItemKey key) {
        return new BedrockItemStack(key.variant(), count, nbt, blockState);
    }

    /**
     * Returns a copy with another count.
     *
     * @param count the stack size, at least 1
     * @return the new stack
     * @throws IllegalArgumentException if the count is below 1
     */
    public @NonNull BedrockItemStack withCount(int count) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

    /**
     * Returns a copy with other NBT.
     *
     * @param nbt the item NBT, or null for none
     * @return the new stack
     */
    public @NonNull BedrockItemStack withNbt(@Nullable NbtMap nbt) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

    /**
     * Returns a copy with another block state.
     *
     * @param blockState the block the stack places, or null for the item's default block (or none)
     * @return the new stack
     */
    public @NonNull BedrockItemStack withBlockState(@Nullable BedrockBlockState blockState) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (count != 1) {
            builder.append(count).append("x ");
        }
        builder.append(variant);
        if (blockState != null) {
            builder.append(" placing ").append(blockState);
        }
        if (nbt != null) {
            builder.append(' ').append(nbt);
        }
        return builder.toString();
    }
}
