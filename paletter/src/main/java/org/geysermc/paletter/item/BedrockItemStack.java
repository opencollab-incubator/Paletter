package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

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
     * @return one item of a variant, without NBT or block state
     */
    public static @NonNull BedrockItemStack of(@NonNull BedrockItemVariant variant) {
        return new BedrockItemStack(variant, 1, null, null);
    }

    /**
     * @return a stack of a variant, without NBT or block state
     */
    public static @NonNull BedrockItemStack of(@NonNull BedrockItemVariant variant, int count) {
        return new BedrockItemStack(variant, count, null, null);
    }

    /**
     * @return one item of a type's base variant
     */
    public static @NonNull BedrockItemStack of(@NonNull BedrockItemType type) {
        return of(type.base());
    }

    public @NonNull BedrockItemType type() {
        return variant.type();
    }

    public @NonNull BedrockItemStack withVariant(@NonNull BedrockItemVariant variant) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

    public @NonNull BedrockItemStack withCount(int count) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

    public @NonNull BedrockItemStack withNbt(@Nullable NbtMap nbt) {
        return new BedrockItemStack(variant, count, nbt, blockState);
    }

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
