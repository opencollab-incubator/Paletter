package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.data.Renames;
import org.geysermc.paletter.internal.item.ItemVariant;

/**
 * A variant of a {@link BedrockItemType}: the type and its aux (damage) value. The block a stack places is not part of
 * the variant, it belongs to the stack ({@link org.geysermc.paletter.item.BedrockItemStack#blockState()}). Obtained from
 * {@link BedrockItemType#variant(int)} and {@link BedrockItemType#variants()}.
 * <p>
 * Equality: as for {@link BedrockItemType}, compare with {@code equals}, never with {@code ==}. Variants of custom
 * types (and variants made by {@link BedrockItemType#variantOf(int)}) are not interned: {@code customItem.type().base()}
 * and the variant {@link org.geysermc.paletter.Palette#decode} gives back are equal, not identical.
 *
 * @since 0.1.0
 */
public final class BedrockItemVariant {
    private final BedrockItemType type;
    private final int aux;
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
        BedrockItemType type = BedrockItemType.of(variant.identifier());
        for (BedrockItemVariant known : type.variants()) {
            if (known.aux() == variant.damage()) {
                return known;
            }
        }
        return new BedrockItemVariant(type, variant.damage(), -1);
    }

    /**
     * The variant of an identifier and aux value, applying renames and splits (a legacy variant that became its own
     * type). Loads the data.
     *
     * @return the variant
     */
    public static @NonNull BedrockItemVariant of(@NonNull String identifier, int aux) {
        Renames.Split split = VanillaIndex.get().renames.split(identifier, aux);
        if (split != null) {
            return of(BedrockItemType.of(split.identifier()), split.aux());
        }
        return of(BedrockItemType.of(identifier), aux);
    }

    /**
     * @return the known variant of the type with this aux value (the same instance as {@code type.variant(aux)}), else
     *         a new variant
     */
    public static @NonNull BedrockItemVariant of(@NonNull BedrockItemType type, int aux) {
        return type.variantOf(aux);
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
        if (id >= 0) {
            CanonicalItems bundled = VanillaIndex.get().items;
            return canonical.root() == bundled ? id : canonical.vanillaIdsFrom(bundled)[id];
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
