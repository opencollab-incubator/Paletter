package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.behaviour.WireRules;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The behaviour values of the blocks and items of one {@link Palette}: {@code palette.behaviour()}. Thread safe.
 * <p>
 * Vanilla values come from the bundled captures of the palette's data version (an older capture's value when the
 * version has none, see {@link Provenance#inherited()}); keys the captures lack fall back to what the component data
 * BDS sends says (use duration of food, ...). Values come from BDS directly wherever possible (what BDS sends,
 * Paletter's own captures) and from third-party dumps otherwise ({@link Provenance#thirdParty()}); sources that disagree
 * without BDS evidence settling it are {@link Provenance#disputed()}.
 * <p>
 * Custom blocks and items, and vanilla items a layer overrides, are read from the component data the palette sends for
 * them, converted to the engine units of the key: e.g. {@code minecraft:friction} 0.4 is {@link BlockBehaviour#FRICTION}
 * 0.6, {@code minecraft:collision_box} in pixels is a {@link BlockShape} in blocks. {@link Provenance#userSet()} tells a
 * value the user set from a vanilla value or a BDS default: a custom block or item without the component gets BDS's
 * default for the key, if it is known. A null value means nothing says: a key without a source, or a default that is not
 * known yet ({@link Provenance#unknownDefault()}).
 * <p>
 * Values are looked up in dense tables, one per key, built the first time a key is asked for and kept:
 * {@link #blocks(BehaviourKey)} over the palette's block states (see {@link BlockRegistry#indexOf}) and
 * {@link #items(BehaviourKey)} over its items. {@code get} reads them (and boxes the value); hot paths read the tables
 * through their primitive accessors, which do not allocate. A {@link Provenance} is only built by {@code provenance}.
 * Without layers, {@code paletter.vanilla(version).behaviour()} gives the vanilla values of a data version.
 *
 * @since 0.1.0
 */
public final class Behaviour {
    private final Palette palette;
    private final BehaviourStore store;
    /** The tables built so far, one holder per key: a table is built outside the map's lock, by one thread. */
    private final Map<BehaviourKey<?>, Lazy<BlockTable<?>>> blockTables = new ConcurrentHashMap<>();
    private final Map<BehaviourKey<?>, Lazy<ItemTable<?>>> itemTables = new ConcurrentHashMap<>();
    /** The behaviour of the palette this one extends: the dense tables of its states are shared. */
    private final @Nullable Behaviour parent;

    private Behaviour(Palette palette, BehaviourStore store, @Nullable Behaviour parent) {
        this.palette = palette;
        this.store = store;
        this.parent = parent;
    }

    /**
     * Plumbing of {@link Palette#behaviour()}: public for it, in another package, not part of the API.
     *
     * @param parent the behaviour of the palette this one extends (its block states come first in this one), or null
     */
    @Internal
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull BehaviourStore store, @Nullable Behaviour parent) {
        return new Behaviour(Objects.requireNonNull(palette), Objects.requireNonNull(store), parent);
    }

    /**
     * @return the value of a block state of this palette, or null if nothing says or the state is not of this palette
     * @throws IllegalArgumentException if the key is not a block key
     */
    public <T> @Nullable T get(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<T> key) {
        return blocks(key).get(state);
    }

    /**
     * @return the value of a block state, resolved in this palette like {@link Palette#blockOrNull}; null if the palette
     * does not have the state or nothing says
     */
    public <T> @Nullable T get(@NonNull BedrockBlockState state, @NonNull BehaviourKey<T> key) {
        RegisteredBlockState registered = palette.blockOrNull(state);
        return registered == null ? null : get(registered, key);
    }

    /**
     * @return the value of an item of this palette, or null if nothing says or the item is not of this palette
     * @throws IllegalArgumentException if the key is not an item key
     */
    public <T> @Nullable T get(@NonNull RegisteredItem item, @NonNull BehaviourKey<T> key) {
        return items(key).get(item);
    }

    /**
     * @return the value of an item type, or null if the palette does not have the item or nothing says
     */
    public <T> @Nullable T get(@NonNull BedrockItemType type, @NonNull BehaviourKey<T> key) {
        RegisteredItem item = palette.item(type);
        return item == null ? null : get(item, key);
    }

    /**
     * @return where the value of {@link #get(RegisteredBlockState, BehaviourKey)} comes from: always present for custom
     * blocks (a default that is not known has {@link Provenance#unknownDefault()}), null for vanilla states nothing says
     * anything about
     */
    public @Nullable Provenance provenance(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        Value value = resolve(state, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return where the value of {@link #get(BedrockBlockState, BehaviourKey)} comes from, see
     * {@link #provenance(RegisteredBlockState, BehaviourKey)}; null if the palette does not have the state
     */
    public @Nullable Provenance provenance(@NonNull BedrockBlockState state, @NonNull BehaviourKey<?> key) {
        RegisteredBlockState registered = palette.blockOrNull(state);
        return registered == null ? null : provenance(registered, key);
    }

    /**
     * @return where the value of {@link #get(RegisteredItem, BehaviourKey)} comes from, see
     * {@link #provenance(RegisteredBlockState, BehaviourKey)}
     */
    public @Nullable Provenance provenance(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        Value value = resolve(item, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return where the value of {@link #get(BedrockItemType, BehaviourKey)} comes from, see
     * {@link #provenance(RegisteredBlockState, BehaviourKey)}; null if the palette does not have the item
     */
    public @Nullable Provenance provenance(@NonNull BedrockItemType type, @NonNull BehaviourKey<?> key) {
        RegisteredItem item = palette.item(type);
        return item == null ? null : provenance(item, key);
    }

    /**
     * @return true if the user set the value of this state with a component of their custom content, false if it is a
     * vanilla value, a BDS default or unknown
     */
    public boolean isUserSet(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        return isUserSet(provenance(state, key));
    }

    /**
     * @return like {@link #isUserSet(RegisteredBlockState, BehaviourKey)}, false if the palette does not have the state
     */
    public boolean isUserSet(@NonNull BedrockBlockState state, @NonNull BehaviourKey<?> key) {
        return isUserSet(provenance(state, key));
    }

    /**
     * @return true if the user set the value of this item with a component, false if it is a vanilla value, a BDS default
     * or unknown
     */
    public boolean isUserSet(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        return isUserSet(provenance(item, key));
    }

    /**
     * @return like {@link #isUserSet(RegisteredItem, BehaviourKey)}, false if the palette does not have the item
     */
    public boolean isUserSet(@NonNull BedrockItemType type, @NonNull BehaviourKey<?> key) {
        return isUserSet(provenance(type, key));
    }

    private static boolean isUserSet(@Nullable Provenance provenance) {
        return provenance != null && provenance.userSet();
    }

    /**
     * @return the values of every block state of this palette, built on first use and kept. For a palette that extends
     * another one ({@link Palette#extend}), the values of the other palette's states are the other palette's table's
     * (shared, not copied); only the states the layers added are computed
     * @throws IllegalArgumentException if the key is not a block key
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull BlockTable<T> blocks(@NonNull BehaviourKey<T> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        return (BlockTable<T>) blockTables.computeIfAbsent(key, k -> new Lazy<>(() -> buildBlocks(k))).get();
    }

    private BlockTable<?> buildBlocks(BehaviourKey<?> key) {
        BlockRegistry registry = palette.blocks();
        if (parent != null && registry.extendsStatesOf(parent.palette.blocks())) {
            BlockTable<?> base = parent.blocks(key);
            if (registry.size() == base.size()) {
                return base.with(registry);
            }
            return base.extend(registry, computeBlocks(registry, base.size(), key));
        }
        return new BlockTable<>(registry, Column.of(key.type(), computeBlocks(registry, 0, key)), key.type());
    }

    private Object[] computeBlocks(BlockRegistry registry, int from, BehaviourKey<?> key) {
        Object[] values = new Object[registry.size() - from];
        for (int i = 0; i < values.length; i++) {
            Value value = resolve(registry.byIndex(from + i), key);
            values[i] = value == null ? null : value.value;
        }
        return values;
    }

    /**
     * @return the values of every item of this palette, built on first use and kept
     * @throws IllegalArgumentException if the key is not an item key
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull ItemTable<T> items(@NonNull BehaviourKey<T> key) {
        require(key, BehaviourKey.Owner.ITEM);
        return (ItemTable<T>) itemTables.computeIfAbsent(key, k -> new Lazy<>(() -> buildItems(k))).get();
    }

    private ItemTable<?> buildItems(BehaviourKey<?> key) {
        ItemRegistry registry = palette.items();
        Object[] values = registry.definitions().stream().map(item -> {
            Value value = resolve(item, key);
            return value == null ? null : value.value;
        }).toArray();
        return new ItemTable<>(registry, Column.of(key.type(), values));
    }

    // ---- Resolution: the value of a key and where it comes from; the tables are built from it ----

    /** A value (null: unknown) and where it comes from. */
    record Value(@Nullable Object value, Provenance provenance) {
    }

    /** A value built on first use by one thread, holding no lock but its own. */
    private static final class Lazy<T> {
        private @Nullable Supplier<T> supplier;
        private volatile @Nullable T value;

        Lazy(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            T result = value;
            if (result == null) {
                synchronized (this) {
                    result = value;
                    if (result == null) {
                        value = result = supplier.get();
                        supplier = null;
                    }
                }
            }
            return result;
        }
    }

    @Nullable Value resolve(RegisteredBlockState state, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        return state.isCustom() ? customBlock(state, key) : vanilla(state, key);
    }

    @Nullable Value resolve(RegisteredItem item, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.ITEM);
        return item.isCustom() || !item.components().isEmpty() ? customItem(item, key) : vanilla(item, key);
    }

    private @Nullable Value vanilla(RegisteredBlockState state, BehaviourKey<?> key) {
        RegisteredBlock block = palette.blocks().block(state.identifier());
        BehaviourStore.Resolved resolved = store.block(key, state.getRuntimeId(), palette.version());
        Value wire = block == null ? null : vanillaWire(key, block.properties(state));
        return choose(resolved == null ? null : captured(resolved), wire);
    }

    private @Nullable Value vanilla(RegisteredItem item, BehaviourKey<?> key) {
        BehaviourStore.Resolved resolved = store.item(key, item.getIdentifier(), palette.version());
        return choose(resolved == null ? null : captured(resolved), vanillaWire(key, item.properties()));
    }

    private @Nullable Value customBlock(RegisteredBlockState state, BehaviourKey<?> key) {
        RegisteredBlock block = palette.blocks().block(state.identifier());
        if (block == null) {
            return null;
        }
        return custom(key, block.properties(state), block.components(state).stream().map(c -> c.name()).toList(),
            block.origin().layer());
    }

    private @Nullable Value customItem(RegisteredItem item, BehaviourKey<?> key) {
        List<String> names = item.components().stream().map(c -> c.name()).toList();
        if (item.isCustom()) {
            return custom(key, item.properties(), names, item.origin().layer());
        }
        // A vanilla item a layer overrides: the components the layer set win over every vanilla value
        Value value = userSet(key, item.properties(), names, item.origin().layer());
        return value != null ? value : vanilla(item, key);
    }

    /**
     * Direct evidence of the version beats a third-party dump and a capture of an older version; among direct sources
     * of the version the stronger kind decides ({@link Provenance.Source}: captures before the wire).
     */
    private static @Nullable Value choose(@Nullable Value stored, @Nullable Value wire) {
        if (wire == null) {
            return stored;
        }
        if (stored == null) {
            return wire;
        }
        Provenance provenance = stored.provenance;
        return !provenance.thirdParty() && !provenance.inherited() ? stored : wire;
    }

    private @Nullable Value vanillaWire(BehaviourKey<?> key, Properties properties) {
        Object value = WireRules.value(key, properties);
        if (value == null) {
            return null;
        }
        DataVersion version = palette.version();
        return new Value(value, new Provenance(Provenance.Source.WIRE, "component data", version, version));
    }

    /** @return the value of a component the user set, or null if the user did not set the key */
    private @Nullable Value userSet(BehaviourKey<?> key, Properties properties, List<String> components, @Nullable String layer) {
        if (components.stream().noneMatch(WireRules.components(key)::contains)) {
            return null;
        }
        Object value = WireRules.value(key, properties);
        if (value == null) {
            return null;
        }
        DataVersion version = palette.version();
        return new Value(value, new Provenance(Provenance.Source.USER, "layer " + layer, version, version));
    }

    /**
     * A custom block or item: the user's component, else what Paletter sends for it anyway, else BDS's default for the
     * key, else unknown.
     */
    private Value custom(BehaviourKey<?> key, Properties properties, List<String> components, @Nullable String layer) {
        Value user = userSet(key, properties, components, layer);
        if (user != null) {
            return user;
        }
        DataVersion version = palette.version();
        Object sent = WireRules.value(key, properties);
        if (sent != null) {
            return new Value(sent, new Provenance(Provenance.Source.WIRE, "component data Paletter sends, not set by the user",
                version, version));
        }
        BehaviourStore.Default fallback = store.defaultOf(key);
        if (fallback == null) {
            return new Value(null, Provenance.unknownDefault(version));
        }
        return new Value(fallback.value(), new Provenance(Provenance.Source.BDS_DEFAULT,
            fallback.source() + " " + fallback.reference(), version, version));
    }

    private Value captured(BehaviourStore.Resolved resolved) {
        BehaviourStore.Capture capture = resolved.capture();
        List<Provenance.Alternative> alternatives = resolved.disputes().stream()
            .map(d -> new Provenance.Alternative(d.capture().source(), d.capture().reference(), d.capture().version(), d.value()))
            .toList();
        return new Value(resolved.value(), new Provenance(capture.source(), capture.reference(), capture.version(), palette.version(),
            alternatives, resolved.postProcessing()));
    }

    private static void require(BehaviourKey<?> key, BehaviourKey.Owner owner) {
        if (key.owner() != owner) {
            throw new IllegalArgumentException(key + " is not a " + owner.name().toLowerCase(java.util.Locale.ROOT) + " key");
        }
    }
}
