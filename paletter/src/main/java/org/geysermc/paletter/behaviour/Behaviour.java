package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.behaviour.WireRules;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
 * For hot paths, {@link #blocks(BehaviourKey)} is a dense table over the palette's block states (see
 * {@link BlockRegistry#indexOf}); without layers, {@code paletter.vanilla(version).behaviour()} gives the vanilla values
 * of a data version.
 *
 * @since 0.1.0
 */
public final class Behaviour {
    private final Palette palette;
    private final BehaviourStore store;
    private final Map<BehaviourKey<?>, BlockTable<?>> tables = new ConcurrentHashMap<>();
    /** The behaviour of the palette this one extends: the dense tables of its states are shared. */
    private final @Nullable Behaviour parent;

    private Behaviour(Palette palette, BehaviourStore store, @Nullable Behaviour parent) {
        this.palette = palette;
        this.store = store;
        this.parent = parent;
    }

    /**
     * Plumbing of {@link Palette#behaviour()}.
     *
     * @param parent the behaviour of the palette this one extends (its block states come first in this one), or null
     */
    @Internal
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull BehaviourStore store, @Nullable Behaviour parent) {
        return new Behaviour(Objects.requireNonNull(palette), Objects.requireNonNull(store), parent);
    }

    /**
     * @return the value of a block state of this palette, or null if nothing says
     * @throws IllegalArgumentException if the key is not a block key
     */
    public <T> @Nullable T get(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<T> key) {
        Value value = block(state, key);
        return value == null ? null : key.type().cast(value.value);
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
     * @return the value of an item of this palette, or null if nothing says
     * @throws IllegalArgumentException if the key is not an item key
     */
    public <T> @Nullable T get(@NonNull RegisteredItem item, @NonNull BehaviourKey<T> key) {
        Value value = item(item, key);
        return value == null ? null : key.type().cast(value.value);
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
        Value value = block(state, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return where the value of {@link #get(RegisteredItem, BehaviourKey)} comes from, see
     * {@link #provenance(RegisteredBlockState, BehaviourKey)}
     */
    public @Nullable Provenance provenance(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        Value value = item(item, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return true if the user set the value of this state with a component of their custom content, false if it is a
     * vanilla value, a BDS default or unknown
     */
    public boolean isUserSet(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        Provenance provenance = provenance(state, key);
        return provenance != null && provenance.userSet();
    }

    /**
     * @return true if the user set the value of this item with a component, false if it is a vanilla value, a BDS default
     * or unknown
     */
    public boolean isUserSet(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        Provenance provenance = provenance(item, key);
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
        return (BlockTable<T>) tables.computeIfAbsent(key, k -> {
            BlockRegistry registry = palette.blocks();
            if (parent != null && registry.extendsStatesOf(parent.palette.blocks())) {
                BlockTable<T> base = parent.blocks(key);
                if (registry.size() == base.size()) {
                    return base.with(registry);
                }
                return base.extend(registry, compute(registry, base.size(), key));
            }
            return new BlockTable<T>(registry, compute(registry, 0, key));
        });
    }

    private Object[] compute(BlockRegistry registry, int from, BehaviourKey<?> key) {
        Object[] values = new Object[registry.size() - from];
        for (int i = 0; i < values.length; i++) {
            Value value = block(registry.byIndex(from + i), key);
            values[i] = value == null ? null : value.value;
        }
        return values;
    }

    /** A value (null: unknown) and where it comes from. */
    private record Value(@Nullable Object value, Provenance provenance) {
    }

    private @Nullable Value block(RegisteredBlockState state, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        RegisteredBlock block = palette.blocks().block(state.identifier());
        if (state.isCustom()) {
            if (block == null) {
                return null;
            }
            return custom(key, block.properties(state), block.components(state).stream().map(c -> c.name()).toList(),
                block.origin().layer());
        }
        BehaviourStore.Resolved resolved = store.block(key, state.getRuntimeId(), palette.version());
        Value stored = resolved == null ? null : captured(resolved, palette.version().name());
        Value wire = block == null ? null : vanillaWire(key, block.properties(state));
        return choose(stored, wire);
    }

    private @Nullable Value item(RegisteredItem item, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.ITEM);
        List<String> names = item.components().stream().map(c -> c.name()).toList();
        if (item.isCustom()) {
            return custom(key, item.properties(), names, item.origin().layer());
        }
        if (!names.isEmpty()) {
            // A vanilla item a layer overrides: the components the layer set win over every vanilla value
            Value value = userSet(key, item.properties(), names, item.origin().layer());
            if (value != null) {
                return value;
            }
        }
        BehaviourStore.Resolved resolved = store.item(key, item.getIdentifier(), palette.version());
        Value stored = resolved == null ? null : captured(resolved, palette.version().name());
        return choose(stored, vanillaWire(key, item.properties()));
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
        String version = palette.version().name();
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
        String version = palette.version().name();
        return new Value(value, new Provenance(Provenance.Source.USER, "layer " + layer, version, version, List.of(), null,
            WireRules.unverified(key)));
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
        String version = palette.version().name();
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

    private static Value captured(BehaviourStore.Resolved resolved, String version) {
        BehaviourStore.Capture capture = resolved.capture();
        List<Provenance.Alternative> alternatives = resolved.disputes().stream()
            .map(d -> new Provenance.Alternative(d.capture().source(), d.capture().reference(), d.capture().version(), d.value()))
            .toList();
        return new Value(resolved.value(), new Provenance(capture.source(), capture.reference(), capture.version(), version,
            alternatives, resolved.postProcessing(), false));
    }

    private static void require(BehaviourKey<?> key, BehaviourKey.Owner owner) {
        if (key.owner() != owner) {
            throw new IllegalArgumentException(key + " is not a " + owner.name().toLowerCase(java.util.Locale.ROOT) + " key");
        }
    }
}
