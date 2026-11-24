package dev.opencollab.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.internal.item.ItemVariant;

import java.util.List;
import java.util.Objects;

/**
 * A Bedrock item type. Vanilla types are the constants of {@link BedrockItems}; a {@link CustomItem} (and the item of a
 * {@link dev.opencollab.paletter.block.CustomBlock}) has one too, so both are used alike:
 * <pre>{@code
 * ItemData apple = palette.item(BedrockItems.APPLE).toItemData().count(3).build();
 * ItemData potion = palette.item(BedrockItems.POTION.variant(5)).toItemData().build();
 * }</pre>
 * A type is an {@link ItemKey} for its base variant, so it can be passed to the methods that take a variant. Items whose
 * variants differ by aux value (potions, banners, goat horns, ...) expose them with {@link #variant(int)}.
 * The potions, tipped arrows, beds and banners have constants for them in {@link BedrockItemVariants}.
 * <p>
 * Equality: {@code equals} always works, and types are map keys. {@code ==} works too for vanilla types, which are
 * singletons (the constants), for their {@link #variant() base variant} and for registered custom content: a {@link CustomItem} owns its {@link CustomItem#type()
 * type} (a {@link dev.opencollab.paletter.block.CustomBlock} its {@link dev.opencollab.paletter.block.CustomBlock#itemType()
 * item type}), and a palette it is registered in hands back exactly that type. Custom types made for an
 * identifier that is not vanilla are not interned: equal to each other and to the custom item's type, not identical.
 * Nothing is kept for identifiers Paletter does not know, so untrusted identifiers cannot grow memory: what is kept is
 * bounded by the registered content. Equal custom items registered by different layers each own their type; those
 * types are equal, not identical.
 * <p>
 * Referencing a type loads nothing. A vanilla constant the newest bundled release does not have (deprecated, or a
 * {@link dev.opencollab.paletter.annotation.SincePreview @SincePreview} item) is not {@link #isAvailable() available}. A
 * palette whose newest version has the item (a preview item in a Paletter with previews) sends the item itself; any
 * other palette resolves it through its {@link dev.opencollab.paletter.Fallbacks}, {@code minecraft:unknown} by default. The constant is the one type of its identifier
 * either way: {@link dev.opencollab.paletter.Palette#decode} and the palette's item definitions give back the constant.
 *
 * @since 1.0.0
 */
public final class BedrockItemType implements ItemKey {
    private final @Nullable String constantName;
    private final String identifier;
    private final @Nullable String fallback;
    private final boolean custom;
    private final boolean education;
    private volatile @Nullable Linked linked;
    /** The base variant: made with the type, so it is the same object whenever it is asked for. */
    private final BedrockItemVariant base = new BedrockItemVariant(this, 0, -1);
    /** The named variants with a nonzero aux, registered by the generated constants of {@link BedrockItemVariants}. */
    private volatile BedrockItemVariant[] named = new BedrockItemVariant[0];

    /**
     * A vanilla type, linked by {@link VanillaIndex}.
     *
     * @param constantName the name of the generated constant that made it, or null for a type the data alone has
     */
    BedrockItemType(@Nullable String constantName, String identifier, String fallback, boolean education) {
        this.constantName = constantName;
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = fallback;
        this.custom = false;
        this.education = education;
    }

    private BedrockItemType(String identifier) {
        this.constantName = null;
        this.identifier = Objects.requireNonNull(identifier);
        this.fallback = null;
        this.custom = true;
        this.education = false;
        this.linked = new Linked(this, true, List.of(base), new int[] {-1});
    }

    /**
     * Internal: the type of an identifier: the vanilla constant if the identifier (after renames) is vanilla in the
     * newest bundled data or has a generated constant, else a custom type known by its identifier only (not interned,
     * so unknown identifiers never accumulate; equal, not identical, for equal identifiers). Loads the data. Use
     * {@link #byIdentifier(String)}, {@link dev.opencollab.paletter.Palette#itemType} or the constants of
     * {@link BedrockItems}.
     *
     * @param identifier an item identifier
     * @return the type of the identifier
     */
    @Internal
    public static @NonNull BedrockItemType fromIdentifier(@NonNull String identifier) {
        BedrockItemType known = byIdentifier(identifier);
        return known != null ? known : new BedrockItemType(identifier);
    }

    /**
     * Finds the vanilla type of an identifier, without making a type for an unknown identifier. The identifier is first
     * mapped through the renames of the bundled data, so the name an older version gave an item finds its type. Loads
     * the data.
     *
     * @param identifier an item identifier
     * @return the vanilla constant, or null if the identifier (after renames) is not an item of the newest bundled data and
     *         has no generated constant
     * @throws NullPointerException if the identifier is null
     * @since 1.0.0
     */
    public static @Nullable BedrockItemType byIdentifier(@NonNull String identifier) {
        return byIdentifier(identifier, VanillaIndex.get().renames);
    }

    /** {@link #byIdentifier(String)} with the renames given, for a test of them. */
    static @Nullable BedrockItemType byIdentifier(String identifier, Renames renames) {
        Objects.requireNonNull(identifier);
        VanillaIndex index = VanillaIndex.get();
        String renamed = renames.item(identifier);
        if (renamed != null && index.itemOrConstant(renamed) != null) {
            identifier = renamed;
        }
        return index.itemOrConstant(identifier);
    }

    /**
     * Internal: plumbing of {@link CustomItem} and {@link dev.opencollab.paletter.block.CustomBlock}, which create their
     * type once and own it: use {@link CustomItem#type()} and {@link dev.opencollab.paletter.block.CustomBlock#itemType()}.
     *
     * @param identifier the item identifier
     * @return a new custom type of an identifier, with its base variant. Loads no data
     */
    @Internal
    public static @NonNull BedrockItemType custom(@NonNull String identifier) {
        return new BedrockItemType(identifier);
    }

    /**
     * The vanilla item types.
     *
     * @return every vanilla item type of the newest bundled data, in runtime id order. Loads the data
     */
    public static @NonNull List<BedrockItemType> vanillaTypes() {
        return VanillaIndex.get().itemTypes;
    }

    /** The data's variants of this type, the base first, with their canonical ids in the bundled data. */
    void link(List<BedrockItemVariant> variants, int[] ids) {
        this.linked = new Linked(this, true, List.copyOf(variants), ids);
    }

    void linkAbsent(@Nullable BedrockItemType fallbackType) {
        if (fallbackType == null) {
            this.linked = new Linked(this, false, List.of(base), new int[] {-1});
        } else {
            // Keys of this type stay of this type; they find the fallback's canonical ids through this
            Linked target = fallbackType.linked();
            this.linked = new Linked(target.owner, false, target.variants, target.ids);
        }
    }

    /**
     * Registers a named variant of the generated constants: the one instance of its aux for this type. Only named
     * variants are kept, never arbitrary ones.
     *
     * @return the registered variant of this aux, which is the given one unless the aux was already named
     */
    synchronized BedrockItemVariant name(BedrockItemVariant variant) {
        for (BedrockItemVariant known : named) {
            if (known.aux() == variant.aux()) {
                return known;
            }
        }
        BedrockItemVariant[] grown = java.util.Arrays.copyOf(named, named.length + 1);
        grown[named.length] = variant;
        named = grown;
        return variant;
    }

    @Nullable BedrockItemVariant named(int aux) {
        for (BedrockItemVariant variant : named) {
            if (variant.aux() == aux) {
                return variant;
            }
        }
        return null;
    }

    /** The bundled data's canonical id of the variant of this aux if the data has one, else -1. */
    int bundledId(int aux) {
        Linked linked = this.linked;
        if (linked == null) {
            return -1;
        }
        // Most types number their variants 0, 1, 2, ... in data order (potions, dyes, ...): then the aux is the position
        if (aux >= 0 && aux < linked.ids.length && linked.variants.get(aux).aux() == aux) {
            return linked.ids[aux];
        }
        for (int i = 0; i < linked.ids.length; i++) {
            if (linked.variants.get(i).aux() == aux) {
                return linked.ids[i];
            }
        }
        return -1;
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

    /**
     * The item identifier.
     *
     * @return the item identifier, e.g. {@code minecraft:apple}
     */
    public @NonNull String identifier() {
        return identifier;
    }

    /**
     * The name of the generated constant of {@link BedrockItems} (or the preview items) that holds this type, preferring
     * one that is not deprecated. Loads no data.
     *
     * @return the constant's name, e.g. {@code APPLE}, or null for a custom type or one without a constant
     * @since 1.0.0
     */
    public @Nullable String constantName() {
        return constantName;
    }

    /**
     * Whether this is a custom type.
     *
     * @return true for the type of a {@link CustomItem} or of a custom block's item, and for a type made for an
     *         identifier the bundled data does not know
     */
    public boolean isCustom() {
        return custom;
    }

    /**
     * Whether BDS only offers this item to worlds with Education Edition features (the same content the generated
     * constant carries {@link dev.opencollab.paletter.annotation.Education @Education} for). Decided from BDS captures when
     * the constants were generated; always false for custom items and for types made for an unknown identifier.
     *
     * @return true if BDS only offers the item with Education Edition features
     * @since 1.0.0
     */
    public boolean isEducation() {
        return education;
    }

    /**
     * Whether the newest bundled release has this type. Says nothing about one palette: a palette whose newest version
     * has the item (a preview item in a Paletter with previews) sends it although it is not available.
     *
     * @return false for a vanilla constant the newest bundled release does not have: palettes without the item resolve
     *         it through their {@link dev.opencollab.paletter.Fallbacks}
     */
    public boolean isAvailable() {
        return linked().available;
    }

    /**
     * The type the newest bundled release links this type to. Palettes resolve a type that is not available through
     * their {@link dev.opencollab.paletter.Fallbacks}, not through this.
     *
     * @return this type, or for a type that is not {@link #isAvailable() available} its fallback type
     */
    public @NonNull BedrockItemType resolved() {
        return linked().owner;
    }

    /**
     * The base variant of the type.
     *
     * @return the base variant: aux 0, no block state. Loads no data
     */
    @Override
    public @NonNull BedrockItemVariant variant() {
        return base;
    }

    @Override
    public @NonNull BedrockItemType type() {
        return this;
    }

    /**
     * The variant of this type with an aux value, never null, and always of this type (also for a type that is not
     * {@link #isAvailable() available}: it resolves like any variant of it). The named constants of
     * {@link BedrockItemVariants} and the variants the bundled data lists are the same instance every time, so {@code ==}
     * holds for them whenever the key was made. An aux nobody named gives a new variant without a canonical id (a layer can
     * give it one with {@link dev.opencollab.paletter.PaletteLayer.Builder#variant(ItemKey)}); those are not kept, so
     * arbitrary auxes cannot grow memory. Loads no bundled data: only the constants of {@link BedrockItemVariants}.
     *
     * @param aux the aux value
     * @return the variant of this type with this aux value
     */
    public @NonNull BedrockItemVariant variant(int aux) {
        if (aux == 0) {
            return base;
        }
        if (!custom) {
            VanillaTypes.loadNamedVariants();
        }
        BedrockItemVariant known = named(aux);
        if (known != null) {
            return known;
        }
        Linked linked = this.linked;
        if (linked != null) {
            for (BedrockItemVariant variant : linked.variants) {
                if (variant.aux() == aux && variant.type() == this) {
                    return variant;
                }
            }
        }
        return new BedrockItemVariant(this, aux, -1);
    }

    /**
     * Whether the bundled data names a variant with an aux value.
     *
     * @param aux the aux value
     * @return whether the newest bundled data names a variant with this aux value, i.e. it is one of
     *         {@link #variants()}. Loads the data
     * @since 1.0.0
     */
    public boolean hasVariant(int aux) {
        for (BedrockItemVariant variant : linked().variants) {
            if (variant.aux() == aux) {
                return true;
            }
        }
        return false;
    }

    /**
     * The variants of the type that have a canonical id.
     *
     * @return the variants with a canonical id: the base variant first, then the creative inventory's other variants
     */
    public @NonNull List<BedrockItemVariant> variants() {
        return linked().variants;
    }

    /**
     * Internal: the base variant as an {@link ItemVariant}.
     *
     * @return the base variant as an {@link ItemVariant} (of the {@link #resolved()} type)
     */
    @Internal
    public @NonNull ItemVariant toItemVariant() {
        return variant().toItemVariant();
    }

    /**
     * Internal: the canonical id of the base variant in a palette's canonical items.
     *
     * @param canonical the canonical items of a palette
     * @return the canonical id of the base variant in a palette's canonical items, or -1
     */
    @Internal
    public int canonicalIn(@NonNull CanonicalItems canonical) {
        return variant().canonicalIn(canonical);
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

    /** What linking the data gave: {@code ids} are the canonical ids of {@code variants}, the base first, or -1. */
    private record Linked(BedrockItemType owner, boolean available, List<BedrockItemVariant> variants, int[] ids) {
    }
}
