package org.geysermc.paletter.item;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Forwarding;
import org.geysermc.paletter.PaletteException;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.CanonicalBlocks;
import org.geysermc.paletter.data.Renames;
import org.geysermc.paletter.creative.CreativeLayout;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dense, version independent item variant indices ("canonical" ids), the item counterpart of
 * {@link org.geysermc.paletter.block.CanonicalBlocks}. The newest bundled version defines them, stable for a Paletter
 * release:
 * <ol>
 *     <li>the base variant ({@code identifier}, damage 0, no block state) of every item, in runtime id order;</li>
 *     <li>every other variant of its creative inventory (damage values, block states), in creative order.</li>
 * </ol>
 * Custom items and custom block items of layers are appended per lineage of layers, like custom block states.
 * <p>
 * A creative block state that is the item's default block (its first creative block state, which is what it is sent
 * with without one) is not a variant of its own; {@link #index(ItemVariant)} treats both spellings alike.
 *
 * @since 1.0.0
 */
public final class CanonicalItems {
    private final @Nullable CanonicalItems parent;
    private final int offset;
    private final ItemVariant[] variants;
    private final Object2IntMap<ItemVariant> index;
    private final Map<String, NbtMap> defaultStates;
    private final Map<Object, CanonicalItems> extensions = new WeakHashMap<>();
    private final Renames renames;
    private final String version;

    private CanonicalItems(@Nullable CanonicalItems parent, List<ItemVariant> variants, Map<String, NbtMap> defaultStates,
                           Renames renames, String version) {
        this.parent = parent;
        this.renames = renames;
        this.version = version;
        this.offset = parent == null ? 0 : parent.size();
        this.variants = variants.toArray(ItemVariant[]::new);
        this.defaultStates = Map.copyOf(defaultStates);
        Object2IntOpenHashMap<ItemVariant> index = new Object2IntOpenHashMap<>(variants.size());
        index.defaultReturnValue(-1);
        for (int i = 0; i < this.variants.length; i++) {
            if (index.put(this.variants[i], offset + i) != -1 || (parent != null && parent.index(this.variants[i]) != -1)) {
                throw new PaletteException("Duplicate canonical item variant " + this.variants[i]);
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
        Map<String, NbtMap> defaultStates = new HashMap<>();
        Set<ItemVariant> variants = new LinkedHashSet<>();
        List<VanillaItem> items = new ArrayList<>(newest.items());
        items.sort(Comparator.comparingInt(VanillaItem::runtimeId));
        for (VanillaItem item : items) {
            variants.add(ItemVariant.of(item.identifier()));
        }
        CreativeLayout creative = newest.creative();
        creative.forEachStack(stack -> {
            if (stack.blockState() != null) {
                defaultStates.putIfAbsent(stack.identifier(), stack.blockState());
            }
        });
        creative.forEachStack(stack -> {
            ItemVariant variant = normalize(stack.variant(), defaultStates);
            variants.add(variant);
        });
        return new CanonicalItems(null, List.copyOf(variants), defaultStates, renames, newest.version().toString());
    }

    private static ItemVariant normalize(ItemVariant variant, Map<String, NbtMap> defaultStates) {
        if (variant.blockState() != null && variant.blockState().equals(defaultStates.get(variant.identifier()))) {
            return variant.withBlockState(null);
        }
        return variant;
    }

    /**
     * @param key identifies the extension (e.g. the layer adding the items); the same key returns the same instance
     *            for as long as it is referenced
     * @param additional variants to append, e.g. base variants of custom items. Variants that already have an id
     *                   (here or earlier in the list) are skipped; a block state equal to the item's default is dropped
     * @return these indices with the given variants appended, or this if there are none
     */
    public @NonNull CanonicalItems extend(@NonNull Object key, @NonNull List<ItemVariant> additional) {
        if (additional.isEmpty()) {
            return this;
        }
        synchronized (extensions) {
            CanonicalItems existing = extensions.get(key);
            if (existing == null) {
                Map<String, NbtMap> defaults = root().defaultStates;
                Set<ItemVariant> variants = new LinkedHashSet<>();
                for (ItemVariant variant : additional) {
                    ItemVariant normalized = normalize(variant, defaults);
                    if (lookup(normalized) == -1) {
                        variants.add(normalized);
                    }
                }
                if (variants.isEmpty()) {
                    return this;
                }
                existing = new CanonicalItems(this, List.copyOf(variants), Map.of(), renames, version);
                extensions.put(key, existing);
            }
            return existing;
        }
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
     * @return the canonical id of a variant, or -1 if it is not one. A block state equal to the item's default is
     * ignored
     */
    public int index(@NonNull ItemVariant variant) {
        int found = lookup(variant);
        if (found == -1 && variant.blockState() != null) {
            ItemVariant normalized = normalize(variant, root().defaultStates);
            if (normalized != variant) {
                found = lookup(normalized);
            }
        }
        return found;
    }

    /**
     * Maps a variant of <em>any</em> bundled version to the canonical ids, which the newest version defines. The
     * block states of variants are mapped like {@link CanonicalBlocks#forward}; an item Bedrock renamed is looked up
     * under its new identifier. A variant the newest version does not have (the item is gone, or has no such damage
     * value or block state) is {@link Forwarding.Kind#UNKNOWN} with the reason.
     *
     * @param blocks the canonical blocks to map block states with
     * @since 1.0.0
     */
    public @NonNull Forwarding forward(@NonNull ItemVariant variant, @NonNull CanonicalBlocks blocks) {
        int exact = index(variant);
        if (exact != -1) {
            return new Forwarding(exact, Forwarding.Kind.EXACT, "");
        }
        Forwarding.Kind kind = Forwarding.Kind.ADAPTED;
        List<String> detail = new ArrayList<>();
        ItemVariant mapped = variant;
        String identifier = variant.identifier();
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
        if (mapped.blockState() != null) {
            Forwarding block = blocks.forward(mapped.blockState());
            if (!block.known()) {
                return Forwarding.unknown("block state of " + variant + ": " + block.detail());
            }
            if (block.kind() != Forwarding.Kind.EXACT) {
                detail.add("block state: " + block.detail());
            }
            mapped = mapped.withBlockState(blocks.state(block.canonical()));
        }
        int found = index(mapped);
        if (found == -1) {
            return Forwarding.unknown("item " + mapped.identifier() + " exists in " + version + ", but has no variant with "
                + (mapped.blockState() != null ? "block state " + mapped.blockState().getCompound("states") + " and " : "")
                + "damage " + mapped.damage());
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

    private CanonicalItems root() {
        CanonicalItems current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
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
