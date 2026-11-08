package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.internal.CanonicalBlocks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.CanonicalItems;
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

    /**
     * Plumbing of {@link org.geysermc.paletter.Palette}: the handle of a canonical id.
     *
     * @return the variant of a canonical id (not null for an id in range)
     */
    @Internal
    public static @NonNull BedrockItemVariant fromCanonical(@NonNull CanonicalItems canonical, @NonNull CanonicalBlocks blocks, int id) {
        VanillaIndex index = VanillaIndex.get();
        if (id < canonical.vanillaSize() && canonical.root() == index.items) {
            return index.itemsByCanonical[id];
        }
        ItemVariant variant = canonical.variant(id);
        BedrockItemType type = BedrockItemType.vanilla(variant.identifier());
        BedrockBlockState state = null;
        if (variant.blockState() != null) {
            state = BedrockBlockState.fromCanonical(blocks, blocks.index(variant.blockState()));
        }
        if (type != null) {
            for (BedrockItemVariant known : type.variants()) {
                if (known.damage() == variant.damage() && Objects.equals(known.blockState(), state)) {
                    return known;
                }
            }
        } else {
            type = BedrockItemType.custom(variant.identifier());
        }
        return new BedrockItemVariant(type, variant.damage(), state, -1);
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
        if (id >= 0 && canonical.root() == VanillaIndex.get().items) {
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
