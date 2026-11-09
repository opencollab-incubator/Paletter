package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemVariant;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Bedrock item type. Vanilla types are the constants of {@link BedrockItems}; a {@link CustomItem} (and the item of a
 * {@link org.geysermc.paletter.block.CustomBlock}) has one too, so both are used alike:
 * <pre>{@code
 * ItemData apple = palette.item(BedrockItems.APPLE).toItemData().count(3).build();
 * ItemData potion = palette.item(BedrockItems.POTION.variant(5)).toItemData().build();
 * }</pre>
 * Items whose variants differ by damage (potions, banners, goat horns, ...) expose them with {@link #variant(int)} (null if unknown, {@link #variantOf(int)} makes any): the
 * bundled data has no names for them, so they have no constants of their own.
 * <p>
 * Referencing a type loads nothing. A vanilla constant the bundled data does not have (deprecated, or a preview item
 * without the preview loaded) is not {@link #isAvailable() available} and resolves to its fallback, normally
 * {@code minecraft:unknown}.
 *
 * @since 1.0.0
 */
public final class BedrockItemType {
    private static final Map<String, BedrockItemType> CUSTOM = new ConcurrentHashMap<>();

    private final String identifier;
    private final @Nullable String fallback;
    private final boolean custom;
    private volatile @Nullable Linked linked;

    BedrockItemType(String identifier, String fallback) {
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = fallback;
        this.custom = false;
    }

    private BedrockItemType(String identifier) {
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = null;
        this.custom = true;
        BedrockItemVariant base = new BedrockItemVariant(this, 0, null, -1);
        this.linked = new Linked(this, true, base, List.of(base));
    }

    /**
     * The key of an identifier: the vanilla constant if the identifier (after renames) is vanilla in the newest bundled
     * data or has a generated constant, else a custom type, the same instance for every call with an identifier.
     * Loads the data.
     *
     * @return the type of an identifier
     */
    public static @NonNull BedrockItemType of(@NonNull String identifier) {
        Objects.requireNonNull(identifier);
        VanillaIndex index = VanillaIndex.get();
        String renamed = index.renames.item(identifier);
        if (renamed != null && index.itemOrConstant(renamed) != null) {
            identifier = renamed;
        }
        BedrockItemType known = index.itemOrConstant(identifier);
        if (known != null) {
            return known;
        }
        return CUSTOM.computeIfAbsent(identifier, BedrockItemType::new);
    }

    /**
     * @return the type of a custom item, or of the item of a custom block; same as {@link #of(String)}
     */
    public static @NonNull BedrockItemType custom(@NonNull String identifier) {
        return of(identifier);
    }

    /**
     * @return the type of a custom item; same as {@link #of(String)}
     */
    public static @NonNull BedrockItemType of(@NonNull CustomItem item) {
        return of(item.identifier());
    }

    /**
     * @return the vanilla type of an identifier in the newest bundled data, or null if there is none. Loads the data
     */
    public static @Nullable BedrockItemType vanilla(@NonNull String identifier) {
        return VanillaIndex.get().item(identifier);
    }

    /**
     * @return every vanilla item type of the newest bundled data, in runtime id order. Loads the data
     */
    public static @NonNull List<BedrockItemType> vanillaTypes() {
        return VanillaIndex.get().itemTypes;
    }

    void link(List<BedrockItemVariant> variants) {
        this.linked = new Linked(this, true, variants.getFirst(), List.copyOf(variants));
    }

    void linkAbsent(@Nullable BedrockItemType fallbackType) {
        if (fallbackType == null) {
            BedrockItemVariant base = new BedrockItemVariant(this, 0, null, -1);
            this.linked = new Linked(this, false, base, List.of(base));
        } else {
            Linked target = fallbackType.linked();
            this.linked = new Linked(target.owner, false, target.base, target.variants);
        }
    }

    private Linked linked() {
        Linked linked = this.linked;
        if (linked == null) {
            VanillaIndex index = VanillaIndex.get();
            synchronized (this) {
                linked = this.linked;
                if (linked == null) {
                    // Registered after the index was built: not in the data
                    linkAbsent(index.item(fallback));
                    linked = this.linked;
                }
            }
        }
        return linked;
    }

    @Nullable String fallback() {
        return fallback;
    }

    public @NonNull String identifier() {
        return identifier;
    }

    public boolean isCustom() {
        return custom;
    }

    /**
     * @return false for a vanilla constant the bundled data does not have: it resolves to its fallback type
     */
    public boolean isAvailable() {
        return linked().available;
    }

    /**
     * @return this type, or for a type that is not {@link #isAvailable() available} the type it resolves to
     */
    public @NonNull BedrockItemType resolved() {
        return linked().owner;
    }

    /**
     * @return the base variant: damage 0, no block state
     */
    public @NonNull BedrockItemVariant base() {
        return linked().base;
    }

    /**
     * @return the variant with a damage value if it is one of {@link #variants()}, i.e. a known variant of the newest
     *         data, else null
     */
    public @Nullable BedrockItemVariant variant(int damage) {
        Linked linked = linked();
        for (BedrockItemVariant variant : linked.variants) {
            if (variant.damage() == damage && variant.blockState() == null) {
                return variant;
            }
        }
        return null;
    }

    /**
     * @return whether {@link #variant(int)} would return a variant for this damage value
     * @since 1.0.0
     */
    public boolean hasVariant(int damage) {
        return variant(damage) != null;
    }

    /**
     * @return the variant with a damage value: one of {@link #variants()} if it has a canonical id, else a new variant
     *         without one (no canonical id; a layer can give it one with
     *         {@link org.geysermc.paletter.PaletteLayer.Builder#variant(BedrockItemVariant)})
     * @since 1.0.0
     */
    public @NonNull BedrockItemVariant variantOf(int damage) {
        BedrockItemVariant known = variant(damage);
        return known != null ? known : new BedrockItemVariant(linked().owner, damage, null, -1);
    }

    /**
     * @return the variants with a canonical id: the base variant first, then the creative inventory's other variants
     */
    public @NonNull List<BedrockItemVariant> variants() {
        return linked().variants;
    }

    /**
     * @return the base variant as an {@link ItemVariant} (of the {@link #resolved()} type)
     */
    public @NonNull ItemVariant toItemVariant() {
        return base().toItemVariant();
    }

    /**
     * @return the canonical id of the base variant in a palette's canonical items, or -1
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalItems canonical) {
        return base().canonicalIn(canonical);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BedrockItemType other && identifier.equals(other.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public String toString() {
        return identifier;
    }

    private record Linked(BedrockItemType owner, boolean available, BedrockItemVariant base, List<BedrockItemVariant> variants) {
    }
}
