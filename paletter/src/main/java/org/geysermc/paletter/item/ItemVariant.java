package org.geysermc.paletter.item;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;

/**
 * A Bedrock item variant, independent of any protocol version: what a stack "is", without count or NBT.
 * <p>
 * Most items are fully identified by their identifier. Some still use the damage (aux) value to tell variants apart,
 * e.g. potions, banners and goat horns, and a few block items differ only in the block they place.
 * The variants of the newest version have dense canonical ids, see {@link CanonicalItems}.
 *
 * @param identifier the item identifier
 * @param damage the damage / aux value
 * @param blockState the block state as {@code {name, states}}, or null for "any" / not a block item
 */
public record ItemVariant(@NonNull String identifier, int damage, @Nullable NbtMap blockState) {

    public ItemVariant {
        if (blockState != null) {
            blockState = BlockStates.normalize(blockState);
        }
    }

    public static @NonNull ItemVariant of(@NonNull String identifier) {
        return new ItemVariant(identifier, 0, null);
    }

    public static @NonNull ItemVariant of(@NonNull String identifier, int damage) {
        return new ItemVariant(identifier, damage, null);
    }

    public @NonNull ItemVariant withDamage(int damage) {
        return new ItemVariant(identifier, damage, blockState);
    }

    public @NonNull ItemVariant withBlockState(@Nullable NbtMap blockState) {
        return new ItemVariant(identifier, damage, blockState);
    }

    public @NonNull ItemVariant withIdentifier(@NonNull String identifier) {
        return new ItemVariant(identifier, damage, blockState);
    }

    /**
     * @return true if this is the plain variant: damage 0 and no block state
     */
    public boolean isBase() {
        return damage == 0 && blockState == null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(identifier);
        if (damage != 0) {
            builder.append('@').append(damage);
        }
        if (blockState != null) {
            builder.append(blockState.getCompound("states"));
        }
        return builder.toString();
    }
}
