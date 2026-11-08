package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.item.CanonicalItems;
import org.geysermc.paletter.item.ItemVariant;

import java.util.Objects;

/**
 * A variant of a {@link BedrockItemType}: its damage value and, for the few block items that differ only in the block
 * they place, a block state. Obtained from {@link BedrockItemType#variant(int)} and {@link BedrockItemType#variants()}.
 *
 * @since 1.0.0
 */
public final class BedrockItemVariant {
    private final BedrockItemType type;
    private final int damage;
    private final @Nullable BedrockBlockState blockState;
    private final int id;
    private volatile @Nullable ItemVariant variant;

    BedrockItemVariant(BedrockItemType type, int damage, @Nullable BedrockBlockState blockState, int id) {
        this.type = type;
        this.damage = damage;
        this.blockState = blockState;
        this.id = id;
    }

    public @NonNull BedrockItemType type() {
        return type;
    }

    public int damage() {
        return damage;
    }

    /**
     * @return the block state that tells this variant apart, or null
     */
    public @Nullable BedrockBlockState blockState() {
        return blockState;
    }

    public @NonNull ItemVariant toItemVariant() {
        ItemVariant variant = this.variant;
        if (variant == null) {
            variant = new ItemVariant(type.identifier(), damage, blockState == null ? null : blockState.toNbt());
            this.variant = variant;
        }
        return variant;
    }

    /**
     * @return the canonical id of this variant in a palette's canonical items, or -1
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalItems canonical) {
        if (id >= 0 && canonical.extendsOrIs(VanillaIndex.get().items)) {
            return id;
        }
        return canonical.index(toItemVariant());
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockItemVariant other && damage == other.damage && type.equals(other.type)
            && Objects.equals(blockState, other.blockState);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + damage;
    }

    @Override
    public String toString() {
        return toItemVariant().toString();
    }
}
