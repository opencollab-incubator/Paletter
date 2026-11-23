package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.data.DataVersion;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link VanillaVersion}: the oldest from nothing, a newer one from the version before it. What the generated data
 * calls, through its typed setters: each method returns the array of values (by {@link VanillaVersion#index}) the setters
 * write into. An array of the version before is copied on the first change, so what did not change stays shared.
 */
@Internal
public final class VanillaVersionBuilder {
    /** What {@link #everyBlock()} and {@link #everyItem()} hold for a key no setter wrote. */
    private static final Object UNSET = new Object();

    private final DataVersion version;
    private final Map<String, VanillaVersion.BlockEntry> blocks;
    private final Map<String, Object[]> items;
    private final Object[] blockDefaults;
    private final Object[] itemDefaults;
    private final Object[] blockSourceDefaults;
    private final Object[] itemSourceDefaults;
    private final Map<String, Object[]> blockSources;
    private final Map<String, Object[]> itemSources;
    /** The arrays made by this builder, which it may write into (by identity). */
    private final Set<Object> own = Collections.newSetFromMap(new IdentityHashMap<>());
    private Object @Nullable [] everyBlock;
    private Object @Nullable [] everyItem;

    VanillaVersionBuilder(DataVersion version, @Nullable VanillaVersion previous) {
        this.version = version;
        int blockKeys = BlockBehaviour.keys().size();
        int itemKeys = ItemBehaviour.keys().size();
        blocks = previous == null ? new HashMap<>() : new HashMap<>(previous.blocks);
        items = previous == null ? new HashMap<>() : new HashMap<>(previous.items);
        blockDefaults = previous == null ? new Object[blockKeys] : previous.blockDefaults.clone();
        itemDefaults = previous == null ? new Object[itemKeys] : previous.itemDefaults.clone();
        blockSourceDefaults = previous == null ? new Object[blockKeys] : previous.blockSourceDefaults.clone();
        itemSourceDefaults = previous == null ? new Object[itemKeys] : previous.itemSourceDefaults.clone();
        blockSources = previous == null ? new HashMap<>() : new HashMap<>(previous.blockSources);
        itemSources = previous == null ? new HashMap<>() : new HashMap<>(previous.itemSources);
    }

    // ---- Values ----

    /** @return the values every block of the version so far gets, and every block added after them starts from */
    public Object @NonNull [] everyBlock() {
        flush();
        everyBlock = filled(blockDefaults.length);
        return everyBlock;
    }

    /** @return the values of a block (replacing one of the same identifier), starting from the defaults */
    public Object @NonNull [] block(@NonNull String identifier, @NonNull String schema) {
        flush();
        Object[] values = blockDefaults.clone();
        own.add(values);
        blocks.put(identifier, new VanillaVersion.BlockEntry(schema, values));
        blockSources.remove(identifier);
        return values;
    }

    /** @return the values of a block of the version before, to change */
    public Object @NonNull [] change(@NonNull String identifier) {
        flush();
        VanillaVersion.BlockEntry entry = Objects.requireNonNull(blocks.get(identifier), identifier);
        return ownBlock(identifier, entry).values();
    }

    /** Removes a block: the version has no values for it. */
    public void remove(@NonNull String identifier) {
        flush();
        Objects.requireNonNull(blocks.remove(identifier), identifier);
        blockSources.remove(identifier);
    }

    /** @return the values every item of the version so far gets, and every item added after them starts from */
    public Object @NonNull [] everyItem() {
        flush();
        everyItem = filled(itemDefaults.length);
        return everyItem;
    }

    /** @return the values of an item (replacing one of the same identifier), starting from the defaults */
    public Object @NonNull [] item(@NonNull String identifier) {
        flush();
        Object[] values = itemDefaults.clone();
        own.add(values);
        items.put(identifier, values);
        itemSources.remove(identifier);
        return values;
    }

    /** @return the values of an item of the version before, to change */
    public Object @NonNull [] changeItem(@NonNull String identifier) {
        flush();
        return ownArray(items, Objects.requireNonNull(items.get(identifier), identifier), identifier);
    }

    /** Removes an item: the version has no values for it. */
    public void removeItem(@NonNull String identifier) {
        flush();
        Objects.requireNonNull(items.remove(identifier), identifier);
        itemSources.remove(identifier);
    }

    // ---- Provenance ----

    /** @return the capture that decides a key of blocks without one of their own, per key */
    public Object @NonNull [] blockSourceDefaults() {
        return blockSourceDefaults;
    }

    /** @return the capture that decides a key of items without one of their own, per key */
    public Object @NonNull [] itemSourceDefaults() {
        return itemSourceDefaults;
    }

    /** @return the captures of a block where they are not the default (null: the default), per key */
    public Object @NonNull [] blockSources(@NonNull String identifier) {
        return ownArray(blockSources, blockSources.computeIfAbsent(identifier, id -> new Object[blockDefaults.length]), identifier);
    }

    /** @return the captures of an item where they are not the default (null: the default), per key */
    public Object @NonNull [] itemSources(@NonNull String identifier) {
        return ownArray(itemSources, itemSources.computeIfAbsent(identifier, id -> new Object[itemDefaults.length]), identifier);
    }

    // ---- Building ----

    private VanillaVersion.BlockEntry ownBlock(String identifier, VanillaVersion.BlockEntry entry) {
        if (own.contains(entry.values())) {
            return entry;
        }
        VanillaVersion.BlockEntry copy = new VanillaVersion.BlockEntry(entry.schema(), entry.values().clone());
        own.add(copy.values());
        blocks.put(identifier, copy);
        return copy;
    }

    private Object[] ownArray(Map<String, Object[]> map, Object[] array, String identifier) {
        if (own.contains(array)) {
            return array;
        }
        Object[] copy = array.clone();
        own.add(copy);
        map.put(identifier, copy);
        return copy;
    }

    private static Object[] filled(int size) {
        Object[] values = new Object[size];
        Arrays.fill(values, UNSET);
        return values;
    }

    /** Applies what {@link #everyBlock()} and {@link #everyItem()} were given, before anything else changes. */
    private void flush() {
        if (everyBlock != null) {
            Object[] every = everyBlock;
            everyBlock = null;
            apply(every, blockDefaults);
            for (Map.Entry<String, VanillaVersion.BlockEntry> entry : Map.copyOf(blocks).entrySet()) {
                apply(every, ownBlock(entry.getKey(), entry.getValue()).values());
            }
        }
        if (everyItem != null) {
            Object[] every = everyItem;
            everyItem = null;
            apply(every, itemDefaults);
            for (Map.Entry<String, Object[]> entry : Map.copyOf(items).entrySet()) {
                apply(every, ownArray(items, entry.getValue(), entry.getKey()));
            }
        }
    }

    private static void apply(Object[] every, Object[] values) {
        for (int i = 0; i < every.length; i++) {
            if (every[i] != UNSET) {
                values[i] = every[i];
            }
        }
    }

    /** @param pool shares equal values (boxed numbers, strings) across blocks, items and versions */
    VanillaVersion build(Map<Object, Object> pool) {
        flush();
        for (Object array : own) {
            Object[] values = (Object[]) array;
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null && !(values[i] instanceof Cell) && !(values[i] instanceof Capture)) {
                    values[i] = pool.computeIfAbsent(values[i], v -> v);
                }
            }
        }
        return new VanillaVersion(version, Map.copyOf(blocks), Map.copyOf(items), blockSourceDefaults.clone(), itemSourceDefaults.clone(),
            Map.copyOf(blockSources), Map.copyOf(itemSources), blockDefaults.clone(), itemDefaults.clone());
    }
}
