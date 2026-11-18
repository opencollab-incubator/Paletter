package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
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
 * @since 1.0.0
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
     * Internal: the handle of a canonical id, for {@link org.geysermc.paletter.Palette}.
     *
     * @param canonical the canonical items of a palette lineage
     * @param id a canonical id
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
        // An item the bundled release lacks (one only a preview has: its @SincePreview constant, which canonicalIn finds
        // in this palette by identifier), or a custom item
        ItemVariant variant = canonical.variant(id);
        return BedrockItemType.fromIdentifier(variant.identifier()).variant(variant.damage());
    }

    /**
     * Finds the vanilla variant of an identifier and aux value, without making a type for an unknown identifier. Splits
     * (a legacy variant that became its own type) and renames of the bundled data are applied first. Loads the data.
     *
     * @param identifier an item identifier
     * @param aux the aux (damage) value
     * @return the variant of a vanilla constant, or null if the identifier (after splits and renames) is not an item of
     *         the newest bundled data and has no generated constant
     * @throws NullPointerException if the identifier is null
     * @since 1.0.0
     */
    public static @Nullable BedrockItemVariant byIdentifier(@NonNull String identifier, int aux) {
        Renames.Split split = VanillaIndex.get().renames.split(identifier, aux);
        BedrockItemType type = BedrockItemType.byIdentifier(split != null ? split.identifier() : identifier);
        return type == null ? null : type.variant(split != null ? split.aux() : aux);
    }

    /**
     * Internal: the variant of an identifier and aux value, applying renames and splits (a legacy variant that
     * became its own type). Loads the data. Use {@link org.geysermc.paletter.Palette#itemVariant} or {@code type.variant(aux)}.
     *
     * @param identifier an item identifier
     * @param aux the aux (damage) value
     * @return the variant, a plain value if Paletter does not know the identifier
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

    /**
     * The type of the variant.
     *
     * @return the type this is a variant of
     */
    public @NonNull BedrockItemType type() {
        return type;
    }

    /**
     * The aux value of the variant.
     *
     * @return the aux (damage) value
     */
    public int aux() {
        return aux;
    }

    /**
     * Internal: the variant as an {@link ItemVariant}.
     *
     * @return the identifier and aux value of this variant
     */
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
     * Internal: the canonical id of this variant in a palette's canonical items. A variant of a type that is not
     * {@link BedrockItemType#isAvailable() available} is the palette's own item if the palette's newest version has it
     * (an {@link org.geysermc.paletter.annotation.SincePreview @SincePreview} item in a palette with previews), else it
     * has no id: the palette resolves it by identifier, through the {@link org.geysermc.paletter.Fallbacks}.
     *
     * @param canonical the canonical items of a palette
     * @return the canonical id of this variant in a palette's canonical items, or -1
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalItems canonical) {
        if (id < 0 && !type.isCustom() && !type.isAvailable()) {
            int own = canonical.index(toItemVariant());
            return own < canonical.vanillaSize() ? own : -1;
        }
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
