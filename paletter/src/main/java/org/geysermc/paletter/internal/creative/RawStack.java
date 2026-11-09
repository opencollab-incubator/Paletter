package org.geysermc.paletter.internal.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.item.ItemVariant;

/**
 * A stack of the bundled creative data, described by identifiers only. It is converted to a
 * {@link org.geysermc.paletter.item.BedrockItemStack} when a palette is compiled.
 *
 * @param identifier the item identifier
 * @param damage the item damage / aux value
 * @param count the stack size
 * @param blockState the block state as {@code {name, states}}, or null to use the item's default block (if any)
 * @param tag the item NBT, if any
 */
public record RawStack(@NonNull String identifier, int damage, int count, @Nullable NbtMap blockState, @Nullable NbtMap tag) implements RawNode {

    public RawStack {
        if (count < 1) {
            throw new IllegalArgumentException("Count must be at least 1");
        }
        if (blockState != null) {
            blockState = BlockStates.normalize(blockState);
        }
        if (tag != null && tag.isEmpty()) {
            tag = null;
        }
    }

    public static @NonNull RawStack of(@NonNull String identifier) {
        return new RawStack(identifier, 0, 1, null, null);
    }

    public @NonNull RawStack withDamage(int damage) {
        return new RawStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull RawStack withCount(int count) {
        return new RawStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull RawStack withBlockState(@Nullable NbtMap blockState) {
        return new RawStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull RawStack withTag(@Nullable NbtMap tag) {
        return new RawStack(identifier, damage, count, blockState, tag);
    }

    /**
     * @return the item variant this stack represents (its NBT and count are not part of a variant)
     */
    public @NonNull ItemVariant variant() {
        return new ItemVariant(identifier, damage, blockState);
    }
}
