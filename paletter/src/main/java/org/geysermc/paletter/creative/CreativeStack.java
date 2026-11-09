package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.ItemVariant;

/**
 * A stack in the creative inventory, described by identifiers only. It is resolved against the item and block
 * palette of each client version when the palette is compiled, so one layout serves every version;
 * stacks whose item does not exist in a version are left out for that version.
 *
 * @param identifier the item identifier
 * @param damage the item damage / aux value
 * @param count the stack size
 * @param blockState the block state as {@code {name, states}}, or null to use the item's default block (if any)
 * @param tag the item NBT, if any
 * @since 1.0.0
 */
public record CreativeStack(@NonNull String identifier, int damage, int count, @Nullable NbtMap blockState, @Nullable NbtMap tag) implements CreativeNode {

    public CreativeStack {
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

    public static @NonNull CreativeStack of(@NonNull String identifier) {
        return new CreativeStack(identifier, 0, 1, null, null);
    }

    public static @NonNull CreativeStack of(@NonNull ItemVariant variant) {
        return new CreativeStack(variant.identifier(), variant.damage(), 1, variant.blockState(), null);
    }

    public @NonNull CreativeStack withDamage(int damage) {
        return new CreativeStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull CreativeStack withCount(int count) {
        return new CreativeStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull CreativeStack withBlockState(@Nullable NbtMap blockState) {
        return new CreativeStack(identifier, damage, count, blockState, tag);
    }

    public @NonNull CreativeStack withTag(@Nullable NbtMap tag) {
        return new CreativeStack(identifier, damage, count, blockState, tag);
    }

    /**
     * @return the item variant this stack represents (its NBT and count are not part of a variant)
     */
    public @NonNull ItemVariant variant() {
        return new ItemVariant(identifier, damage, blockState);
    }
}
