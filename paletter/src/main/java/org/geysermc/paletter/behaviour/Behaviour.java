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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The behaviour values of the blocks and items of one {@link Palette}: {@code palette.behaviour()}. Thread safe.
 * <p>
 * Vanilla values come from the bundled captures of the palette's data version (an older capture's value when the
 * version has none, see {@link Provenance#inherited()}); keys the captures lack fall back to what the component data
 * BDS sends says (use duration of food, ...). Custom blocks and items, and vanilla items a layer overrides, are read
 * from the component data the palette sends for them, converted to the engine units of the key: e.g.
 * {@code minecraft:friction} 0.4 is {@link BlockBehaviour#FRICTION} 0.6, {@code minecraft:collision_box} in pixels is a
 * {@link BlockShape} in blocks. A null value means nothing says: a key without a source, or a custom block without
 * the component.
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

    private Behaviour(Palette palette, BehaviourStore store) {
        this.palette = palette;
        this.store = store;
    }

    /**
     * Plumbing of {@link Palette#behaviour()}.
     */
    @Internal
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull BehaviourStore store) {
        return new Behaviour(Objects.requireNonNull(palette), Objects.requireNonNull(store));
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
     * @return where the value of {@link #get(RegisteredBlockState, BehaviourKey)} comes from, or null if there is none
     */
    public @Nullable Provenance provenance(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        Value value = block(state, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return where the value of {@link #get(RegisteredItem, BehaviourKey)} comes from, or null if there is none
     */
    public @Nullable Provenance provenance(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        Value value = item(item, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return the values of every block state of this palette, built on first use and kept
     * @throws IllegalArgumentException if the key is not a block key
     */
    @SuppressWarnings("unchecked")
    public <T> @NonNull BlockTable<T> blocks(@NonNull BehaviourKey<T> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        return (BlockTable<T>) tables.computeIfAbsent(key, k -> {
            BlockRegistry registry = palette.blocks();
            Object[] values = new Object[registry.size()];
            for (int i = 0; i < values.length; i++) {
                Value value = block(registry.byIndex(i), key);
                values[i] = value == null ? null : value.value;
            }
            return new BlockTable<>(registry, values);
        });
    }

    private record Value(Object value, Provenance provenance) {
    }

    private @Nullable Value block(RegisteredBlockState state, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        String version = palette.version().name();
        if (!state.isCustom()) {
            BehaviourStore.Resolved resolved = store.block(key, state.getRuntimeId(), palette.version());
            if (resolved != null) {
                return captured(resolved, version);
            }
        }
        RegisteredBlock block = palette.blocks().block(state.identifier());
        if (block == null) {
            return null;
        }
        return wire(key, block.properties(state), state.isCustom(), block.origin().layer());
    }

    private @Nullable Value item(RegisteredItem item, BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.ITEM);
        // Custom items and vanilla items a layer overrides say it with their components
        boolean edited = item.isCustom() || !item.components().isEmpty();
        if (edited) {
            Value value = wire(key, item.properties(), true, item.origin().layer());
            if (value != null) {
                return value;
            }
        }
        if (!item.isCustom()) {
            BehaviourStore.Resolved resolved = store.item(key, item.getIdentifier(), palette.version());
            if (resolved != null) {
                return captured(resolved, palette.version().name());
            }
        }
        return edited ? null : wire(key, item.properties(), false, null);
    }

    private static Value captured(BehaviourStore.Resolved resolved, String version) {
        BehaviourStore.Capture capture = resolved.capture();
        return new Value(resolved.value(), new Provenance(capture.source(), capture.reference(), capture.version(), version));
    }

    private @Nullable Value wire(BehaviourKey<?> key, Properties properties, boolean custom, @Nullable String layer) {
        Object value = WireRules.value(key, properties);
        if (value == null) {
            return null;
        }
        String version = palette.version().name();
        Provenance provenance = custom
            ? new Provenance(Provenance.Source.COMPONENT, "layer " + layer, version, version)
            : new Provenance(Provenance.Source.WIRE, "component data", version, version);
        return new Value(value, provenance);
    }

    private static void require(BehaviourKey<?> key, BehaviourKey.Owner owner) {
        if (key.owner() != owner) {
            throw new IllegalArgumentException(key + " is not a " + owner.name().toLowerCase(java.util.Locale.ROOT) + " key");
        }
    }
}
