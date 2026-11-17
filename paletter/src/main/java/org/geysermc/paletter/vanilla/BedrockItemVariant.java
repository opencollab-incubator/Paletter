package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.data.Renames;
import org.geysermc.paletter.internal.item.ItemVariant;

/**
 * A variant of a {@link BedrockItemType}: the type and its aux (damage) value. The block a stack places is not part of
 * the variant, it belongs to the stack ({@link org.geysermc.paletter.item.BedrockItemStack#blockState()}). Obtained from
 * {@link BedrockItemType#variant(int)} and {@link BedrockItemType#variants()}, or {@link BedrockItemType#variant()} for the base variant. It is an {@link ItemKey}, as is a type (for its base variant).
 * <p>
 * Equality: as for {@link BedrockItemType}, {@code equals} always works. {@code ==} works too for the variants of the
 * vanilla constants (the types of {@link BedrockItems}, the named variants of {@link BedrockItemVariants}) and for the base variant of registered custom content: {@code customItem.type().variant()} is the very
 * variant {@link org.geysermc.paletter.Palette#decode} gives back. Variants made by
 * {@link BedrockItemType#variant(int)} for an aux value that is not one of {@link BedrockItemType#variants()}, and
 * variants of types made for unknown identifiers, are new objects: compare those
 * with {@code equals}.
 *
 * @since 0.1.0
 */
public final class BedrockItemVariant implements ItemKey {
    private final BedrockItemType type;
    private final int aux;
    /** The canonical id in the newest bundled data, or -1: a variant without one, and the named constants, which look it up. */
    private final int id;
    private volatile ItemVariant variant;

    BedrockItemVariant(BedrockItemType type, int aux, int id) {
        this.type = type;
        this.aux = aux;
        this.id = id;
    }

    /**
     * Plumbing of {@link org.geysermc.paletter.Palette}: the handle of a canonical id.
     *
     * @return the variant of a canonical id (not null for an id in range)
     */
    @Internal
    public static @NonNull BedrockItemVariant fromCanonical(@NonNull CanonicalItems canonical, int id) {
        VanillaIndex index = VanillaIndex.get();
        if (id < canonical.vanillaSize()) {
            // The ids belong to the palette's own newest version, which is not necessarily the bundled one
            int bundled = canonical.root() == index.items ? id : canonical.vanillaIdsIn(index.items)[id];
            if (bundled != -1) {
                return index.itemsByCanonical[bundled];
            }
        }
        ItemVariant variant = canonical.variant(id);
        return BedrockItemType.fromIdentifier(variant.identifier()).variant(variant.damage());
    }

    /**
     * Plumbing: the variant of an identifier and aux value, applying renames and splits (a legacy variant that became its
     * own type). Loads the data. Use {@link org.geysermc.paletter.Palette#itemVariant} or {@code type.variant(aux)}.
     */
    @Internal
    public static @NonNull BedrockItemVariant fromIdentifier(@NonNull String identifier, int aux) {
        Renames.Split split = VanillaIndex.get().renames.split(identifier, aux);
        if (split != null) {
            return BedrockItemType.fromIdentifier(split.identifier()).variant(split.aux());
        }
        return BedrockItemType.fromIdentifier(identifier).variant(aux);
    }

    /**
     * @return this variant
     */
    @Override
    public @NonNull BedrockItemVariant variant() {
        return this;
    }

    public @NonNull BedrockItemType type() {
        return type;
    }

    /**
     * @return the aux (damage) value
     */
    public int aux() {
        return aux;
    }

    @Internal
    public @NonNull ItemVariant toItemVariant() {
        ItemVariant variant = this.variant;
        if (variant == null) {
            variant = ItemVariant.of(type.identifier(), aux);
            this.variant = variant;
        }
        return variant;
    }

    /**
     * @return the canonical id of this variant in a palette's canonical items, or -1
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalItems canonical) {
        int bundledId = id >= 0 ? id : type.bundledId(aux);
        if (bundledId >= 0) {
            CanonicalItems bundled = VanillaIndex.get().items;
            return canonical.root() == bundled ? bundledId : canonical.vanillaIdsFrom(bundled)[bundledId];
        }
        return canonical.index(toItemVariant());
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockItemVariant other && aux == other.aux && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + aux;
    }

    @Override
    public String toString() {
        return toItemVariant().toString();
    }
}
