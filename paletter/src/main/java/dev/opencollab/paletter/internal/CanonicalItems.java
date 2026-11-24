package dev.opencollab.paletter.internal;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.PaletteException;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dense, version independent item variant indices ("canonical" ids), the item counterpart of
 * {@link CanonicalBlocks}. The newest bundled version defines them, stable for a Paletter
 * release:
 * <ol>
 *     <li>the base variant ({@code identifier}, damage 0, no block state) of every item, in runtime id order;</li>
 *     <li>every other variant of its creative inventory (damage values, block states), in creative order.</li>
 * </ol>
 * Custom items and custom block items of layers are appended per lineage of layers, like custom block states.
 * <p>
 * A variant is an identifier and an aux value; the block state of a stack is not part of it, so creative stacks that
 * differ only by block state are one variant. [@link #index(ItemVariant)] ignores the block state of a variant.
 *
 * @since 1.0.0
 */
public final class CanonicalItems {
    private final @Nullable CanonicalItems parent;
    private final CanonicalItems root;
    private final int offset;
    private final ItemVariant[] variants;
    private final BedrockItemVariant[] handles;
    private final Object2IntMap<ItemVariant> index;
    private final Map<Object, CanonicalItems> extensions = new WeakHashMap<>();
    private final Renames renames;
    private final String version;
    /** Of a root only: its vanilla ids and those of the one other root asked about last. */
    private volatile @Nullable Translation translation;

    private CanonicalItems(@Nullable CanonicalItems parent, List<ItemVariant> variants, BedrockItemVariant[] handles, Renames renames,
                           String version) {
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        this.renames = renames;
        this.version = version;
        this.offset = parent == null ? 0 : parent.size();
        this.variants = variants.toArray(ItemVariant[]::new);
        this.handles = handles;
        Object2IntOpenHashMap<ItemVariant> index = new Object2IntOpenHashMap<>(variants.size());
        index.defaultReturnValue(-1);
        for (int i = 0; i < this.variants.length; i++) {
            if (index.put(this.variants[i], offset + i) != -1 || (parent != null && parent.index(this.variants[i]) != -1)) {
                throw new PaletteException("Duplicate canonical item variant " + this.variants[i], null, this.variants[i].identifier(), null);
            }
        }
        index.trim();
        this.index = index;
    }

    /**
     * @param newest the data of the newest version
     */
    public static @NonNull CanonicalItems of(@NonNull VanillaData newest) {
        return of(newest, Renames.NONE);
    }

    /**
     * @param newest the data of the newest version
     * @param renames items Bedrock renamed, for {@link #forward}
     */
    @Internal
    public static @NonNull CanonicalItems of(@NonNull VanillaData newest, @NonNull Renames renames) {
        Set<ItemVariant> variants = new LinkedHashSet<>();
        List<VanillaItem> items = new ArrayList<>(newest.items());
        items.sort(Comparator.comparingInt(VanillaItem::runtimeId));
        for (VanillaItem item : items) {
            variants.add(ItemVariant.of(item.identifier()));
        }
        newest.creative().forEachStack(stack -> variants.add(normalize(stack.variant())));
        return new CanonicalItems(null, List.copyOf(variants), new BedrockItemVariant[variants.size()], renames, newest.version().toString());
    }

    private static ItemVariant normalize(ItemVariant variant) {
        return variant.blockState() == null ? variant : variant.withBlockState(null);
    }

    /**
     * @param key identifies the extension (e.g. the layer adding the items); the same key returns the same instance
     *            for as long as it is referenced
     * @param additional variants to append, e.g. base variants of custom items. Variants that already have an id
     *                   (here or earlier in the list) are skipped; a block state equal to the item's default is dropped
     * @param types the types of the custom items (and custom block items) the variants belong to: their handles are
     *              these types' own variants, so the palette hands back the objects {@code CustomItem#type()} owns.
     *              Variants of a custom item of an earlier extension use that item's type
     * @return these indices with the given variants appended, or this if there are none
     */
    public @NonNull CanonicalItems extend(@NonNull Object key, @NonNull List<ItemVariant> additional,
                                          @NonNull Collection<BedrockItemType> types) {
        if (additional.isEmpty()) {
            return this;
        }
        synchronized (extensions) {
            CanonicalItems existing = extensions.get(key);
            if (existing == null) {
                Set<ItemVariant> variants = new LinkedHashSet<>();
                for (ItemVariant variant : additional) {
                    ItemVariant normalized = normalize(variant);
                    if (lookup(normalized) == -1) {
                        variants.add(normalized);
                    }
                }
                if (variants.isEmpty()) {
                    return this;
                }
                List<ItemVariant> list = List.copyOf(variants);
                Map<String, BedrockItemType> byIdentifier = new HashMap<>();
                types.forEach(type -> byIdentifier.put(type.identifier(), type));
                BedrockItemVariant[] handles = new BedrockItemVariant[list.size()];
                for (int i = 0; i < handles.length; i++) {
                    handles[i] = customHandle(list.get(i), byIdentifier);
                }
                existing = new CanonicalItems(this, list, handles, renames, version);
                extensions.put(key, existing);
            }
            return existing;
        }
    }

    /**
     * @return the custom item's own variant (of its own type), or null for a variant of no custom item: its handle is
     *         made on first use
     */
    private @Nullable BedrockItemVariant customHandle(ItemVariant variant, Map<String, BedrockItemType> types) {
        BedrockItemType type = types.get(variant.identifier());
        if (type == null) {
            // An item of an earlier extension: its base variant has that item's handle
            int base = lookup(ItemVariant.of(variant.identifier()));
            BedrockItemVariant handle = base < vanillaSize() ? null : handle(base);
            if (handle == null || !handle.type().isCustom()) {
                return null;
            }
            type = handle.type();
        }
        return type.variant(variant.damage());
    }

    /**
     * @return the amount of canonical ids; valid ids are {@code 0} to {@code size() - 1}
     */
    public int size() {
        return offset + variants.length;
    }

    /**
     * @return the amount of canonical ids of the newest vanilla version; custom items follow them
     */
    public int vanillaSize() {
        return parent == null ? variants.length : parent.vanillaSize();
    }

    /**
     * @return@return the canonical id of a variant, or -1 if it is not one. A block state is ignored
     */
    public int index(@NonNull ItemVariant variant) {
        return lookup(normalize(variant));
    }

    /**
     * Maps a variant of <em>any</em> bundled version to the canonical ids, which the newest version defines. Items Bedrock renamed or
     * split are looked up under their new identifier and aux. A variant the newest version does not have (the item is gone,
     * or has no such aux value) is {@link Forwarding.Kind#UNKNOWN} with the reason.
     *
     * @since 1.0.0
     */
    public @NonNull Forwarding forward(@NonNull ItemVariant variant) {
        int exact = index(variant);
        if (exact != -1) {
            return new Forwarding(exact, Forwarding.Kind.EXACT, "");
        }
        Forwarding.Kind kind = Forwarding.Kind.ADAPTED;
        List<String> detail = new ArrayList<>();
        ItemVariant mapped = variant;
        String identifier = variant.identifier();
        Renames.Split split = renames.split(identifier, variant.damage());
        if (split != null) {
            mapped = mapped.withIdentifier(split.identifier()).withDamage(split.aux());
            identifier = split.identifier();
            kind = Forwarding.Kind.RENAMED;
            detail.add("split into " + split.identifier() + (split.aux() != 0 ? "@" + split.aux() : ""));
        }
        if (lookup(ItemVariant.of(identifier)) == -1) {
            String renamed = renames.item(identifier);
            if (renamed == null || lookup(ItemVariant.of(renamed)) == -1) {
                return Forwarding.unknown("item " + identifier + " does not exist in " + version + (renamed == null ? " and was not renamed"
                    : " (renamed to " + renamed + ", which does not exist either)"));
            }
            mapped = mapped.withIdentifier(renamed);
            kind = Forwarding.Kind.RENAMED;
            detail.add("renamed to " + renamed);
        }
        int found = index(mapped);
        if (found == -1) {
            return Forwarding.unknown("item " + mapped.identifier() + " exists in " + version + ", but has no variant with aux " + mapped.damage());
        }
        return new Forwarding(found, kind, String.join("; ", detail));
    }

    private int lookup(ItemVariant variant) {
        CanonicalItems current = this;
        while (current != null) {
            int found = current.index.getInt(variant);
            if (found != -1) {
                return found;
            }
            current = current.parent;
        }
        return -1;
    }

    /**
     * Canonical ids belong to the instance: the newest version it knows defines them, so two instances may number the
     * same variant differently. This translates between this lineage's vanilla ids and those of another lineage.
     *
     * @return for each vanilla canonical id of this lineage, the id of the same variant in the vanilla variants of
     *         {@code reference}, or -1 if it has none. Computed once, an array lookup afterwards
     */
    @Internal
    public int @NonNull [] vanillaIdsIn(@NonNull CanonicalItems reference) {
        return translation(reference).to();
    }

    /**
     * @return for each vanilla canonical id of {@code reference}, the id of the same variant in this lineage, or -1
     */
    @Internal
    public int @NonNull [] vanillaIdsFrom(@NonNull CanonicalItems reference) {
        return translation(reference).from();
    }

    private Translation translation(CanonicalItems reference) {
        CanonicalItems root = this.root;
        CanonicalItems other = reference.root;
        Translation current = root.translation;
        if (current == null || current.reference() != other) {
            synchronized (root) {
                current = root.translation;
                if (current == null || current.reference() != other) {
                    root.translation = current = Translation.of(other, root.variants.length, other.variants.length,
                        id -> other.index.getInt(root.variants[id]));
                }
            }
        }
        return current;
    }

    /**
     * @return the first indices of the lineage: the variants of the newest version
     */
    public @NonNull CanonicalItems root() {
        return root;
    }

    /**
     * @return the handle of a canonical id, or null if there is no such id. The handle of a custom item's variant is that
     *         item's own ({@code CustomItem#type()}, {@code CustomBlock#itemType()}); other handles are created once
     */
    public @Nullable BedrockItemVariant handle(int canonical) {
        if (canonical < 0 || canonical >= size()) {
            return null;
        }
        CanonicalItems current = this;
        while (canonical < current.offset) {
            current = current.parent;
        }
        int local = canonical - current.offset;
        BedrockItemVariant handle = current.handles[local];
        if (handle == null) {
            handle = Access.vanilla().itemVariant(this, canonical);
            current.handles[local] = handle;
        }
        return handle;
    }

    /**
     * @throws IndexOutOfBoundsException if there is no such id
     */
    public @NonNull ItemVariant variant(int canonical) {
        if (canonical < 0) {
            throw new IndexOutOfBoundsException(canonical);
        }
        CanonicalItems current = this;
        while (canonical < current.offset) {
            current = current.parent;
        }
        return current.variants[canonical - current.offset];
    }

    /**
     * @return true if the other indices are these, or these with variants appended
     * @since 1.0.0
     */
    public boolean extendsOrIs(@NonNull CanonicalItems other) {
        if (other == root) {
            return true;
        }
        CanonicalItems current = this;
        while (current != null) {
            if (current == other) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    @Override
    public String toString() {
        return "CanonicalItems{" + size() + " variants, " + (size() - vanillaSize()) + " custom}";
    }
}
