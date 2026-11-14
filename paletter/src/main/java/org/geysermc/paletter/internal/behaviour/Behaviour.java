package org.geysermc.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockValues;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.ItemValues;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.property.Properties;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The behaviour values of the blocks and items of one {@link Palette}, behind {@link Palette#blockBehaviour} and
 * {@link Palette#itemBehaviour}. Thread safe.
 * <p>
 * Vanilla values come from the bundled captures of the palette's data version (an older capture's value when the
 * version has none, see {@link Provenance#inherited()}); keys the captures lack fall back to what the component data
 * BDS sends says (use duration of food, ...). Values come from BDS directly wherever possible (what BDS sends,
 * Paletter's own captures) and from third-party dumps otherwise ({@link Provenance#thirdParty()}); sources that disagree
 * without BDS evidence settling it are {@link Provenance#disputed()}.
 * <p>
 * Custom blocks and items, and vanilla items a layer overrides, are read from the component data the palette sends for
 * them, converted to the engine units of the key: e.g. {@code minecraft:friction} 0.4 is {@link BlockBehaviour#FRICTION}
 * 0.6, {@code minecraft:collision_box} in pixels is a {@code BlockShape} in blocks. A custom block or item without the
 * component gets BDS's default for the key, if it is known; else there is no value ({@link Provenance#unknownDefault()}).
 * <p>
 * Values are kept in dense columns, one per key ({@link BehaviourKey#ordinal()}), built the first time a key is asked
 * for. The handles ({@link BlockValues}, {@link ItemValues}) are a behaviour and a position in its columns. A palette
 * that extends another one ({@link Palette#extend}) has the other one's states first: their handles and columns are the
 * other palette's (their values are the same), and only the states the layers added have columns here. A {@link Provenance} is only built by {@link #provenance} and {@link #explain}.
 */
@Internal
public final class Behaviour {
    private static final int KEYS = BlockBehaviour.keys().size() + ItemBehaviour.keys().size();

    private final Palette palette;
    private final BehaviourStore store;
    /** The behaviour of the palette this one extends, whose states come first here; null if there is none. */
    private final @Nullable Behaviour parent;
    /** The number of states of {@link #parent}: the states from this position on are this behaviour's own. */
    private final int base;
    /** The columns of the own states by key ordinal, null until built; written under the lock, read without it. */
    private final @Nullable Column[] blockColumns = new Column[KEYS];
    /** The items, in the order of their columns: {@link org.geysermc.paletter.item.ItemRegistry#definitions()}. */
    private final List<RegisteredItem> items;
    /** {@code positions[runtimeId - minRuntimeId]} is the position of an item, or -1. */
    private final int minRuntimeId;
    private final int[] positions;
    private final @Nullable Column[] itemColumns = new Column[KEYS];

    private Behaviour(Palette palette, BehaviourStore store, @Nullable Behaviour parent) {
        this.palette = palette;
        this.store = store;
        BlockRegistry registry = palette.blocks();
        boolean extending = parent != null && registry.extendsStatesOf(parent.palette.blocks());
        this.parent = extending ? parent : null;
        this.base = extending ? parent.palette.blocks().size() : 0;
        this.items = palette.items().definitions();
        this.minRuntimeId = items.stream().mapToInt(RegisteredItem::getRuntimeId).min().orElse(0);
        int max = items.stream().mapToInt(RegisteredItem::getRuntimeId).max().orElse(-1);
        this.positions = new int[Math.max(0, max - minRuntimeId + 1)];
        Arrays.fill(positions, -1);
        for (int i = 0; i < items.size(); i++) {
            positions[items.get(i).getRuntimeId() - minRuntimeId] = i;
        }
    }

    /**
     * @param parent the behaviour of the palette this one extends (its block states come first in this one), or null
     */
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull BehaviourStore store, @Nullable Behaviour parent) {
        return new Behaviour(Objects.requireNonNull(palette), Objects.requireNonNull(store), parent);
    }

    // ---- Handles and columns ----

    /**
     * A handle is two fields, made on each call: inlined into the caller, the JIT keeps them in registers and allocates
     * nothing (a cached handle would cost a memory load more per lookup).
     *
     * @param index a dense index of the palette's {@link BlockRegistry}, see {@link BlockRegistry#indexOf}
     * @return the handle of the state there: of the palette this one extends for a state they share
     */
    public @NonNull BlockValues block(int index) {
        return index < base ? parent.block(index) : new BlockValues(this, index - base);
    }

    /**
     * @param index the index of a {@link BlockValues} of this behaviour
     * @return its state
     */
    public @NonNull RegisteredBlockState stateAt(int index) {
        return palette.blocks().byIndex(base + index);
    }

    /**
     * @return the handle of an item of the palette, see {@link #block(int)}; null if the item is not of the palette
     */
    public @Nullable ItemValues item(@NonNull RegisteredItem item) {
        int at = item.getRuntimeId() - minRuntimeId;
        int position = at < 0 || at >= positions.length ? -1 : positions[at];
        return position < 0 || items.get(position) != item ? null : new ItemValues(this, position);
    }

    /**
     * @param index the index of an {@link ItemValues} of this behaviour
     * @return its item
     */
    public @NonNull RegisteredItem itemAt(int index) {
        return items.get(index);
    }

    /**
     * @return the values of a key for the own states of this behaviour, by the index of their {@link BlockValues}
     * @throws IllegalArgumentException if the key is not a block key
     */
    public @NonNull Column blockColumn(@NonNull BehaviourKey<?> key) {
        Column column = blockColumns[key.ordinal()];
        return column != null ? column : buildBlocks(key);
    }

    private synchronized Column buildBlocks(BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        Column column = blockColumns[key.ordinal()];
        if (column == null) {
            BlockRegistry registry = palette.blocks();
            Object[] values = new Object[registry.size() - base];
            for (int i = 0; i < values.length; i++) {
                Value value = resolve(registry.byIndex(base + i), key);
                values[i] = value == null ? null : value.value;
            }
            blockColumns[key.ordinal()] = column = Column.of(key.type(), values);
        }
        return column;
    }

    /**
     * @return the values of a key for the items of this behaviour, by the index of their {@link ItemValues}
     * @throws IllegalArgumentException if the key is not an item key
     */
    public @NonNull Column itemColumn(@NonNull BehaviourKey<?> key) {
        Column column = itemColumns[key.ordinal()];
        return column != null ? column : buildItems(key);
    }

    private synchronized Column buildItems(BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.ITEM);
        Column column = itemColumns[key.ordinal()];
        if (column == null) {
            Object[] values = new Object[items.size()];
            for (int i = 0; i < values.length; i++) {
                Value value = resolve(items.get(i), key);
                values[i] = value == null ? null : value.value;
            }
            itemColumns[key.ordinal()] = column = Column.of(key.type(), values);
        }
        return column;
    }

    // ---- Where a value comes from ----

    /**
     * @return where the value of a block state comes from: always present for custom blocks (a default that is not
     * known has {@link Provenance#unknownDefault()}), null for vanilla states nothing says anything about
     */
    public @Nullable Provenance provenance(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        Value value = resolve(state, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return where the value of an item comes from, like {@link #provenance(RegisteredBlockState, BehaviourKey)}
     */
    public @Nullable Provenance provenance(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        Value value = resolve(item, key);
        return value == null ? null : value.provenance;
    }

    /**
     * @return the value of a block state and where it comes from, in one line
     */
    public @NonNull String explain(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        return explain(resolve(state, key), state, key);
    }

    /**
     * @return the value of an item and where it comes from, in one line
     */
    public @NonNull String explain(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        return explain(resolve(item, key), item, key);
    }

    /** @return the exception of a {@code get} that finds no value, saying why */
    public @NonNull IllegalStateException missing(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        return new IllegalStateException(key + " of " + state + " has no value (getOrElse takes a fallback): " + explain(state, key));
    }

    /** @return the exception of a {@code get} that finds no value, saying why */
    public @NonNull IllegalStateException missing(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        return new IllegalStateException(key + " of " + item + " has no value (getOrElse takes a fallback): " + explain(item, key));
    }

    private String explain(@Nullable Value value, Object subject, BehaviourKey<?> key) {
        String version = palette.version().name();
        if (value == null) {
            return "no value: no source has " + key + " for " + subject + " in " + version;
        }
        Provenance provenance = value.provenance;
        if (value.value == null) {
            return "no value: " + provenance.reference() + " (" + provenance.source() + "), " + version;
        }
        StringBuilder text = new StringBuilder().append(value.value).append(" from ").append(shortHashes(provenance.reference()))
            .append(" (").append(provenance.source()).append(provenance.thirdParty() ? ", third party" : "").append("), ")
            .append(provenance.captured().name());
        if (provenance.inherited()) {
            text.append(", inherited by ").append(version);
        }
        if (provenance.postProcessed()) {
            text.append("; post-processed: ").append(provenance.postProcessing());
        }
        for (Provenance.Alternative alternative : provenance.alternatives()) {
            text.append("; disputed: ").append(alternative.value()).append(" from ").append(shortHashes(alternative.reference()))
                .append(" (").append(alternative.source()).append("), ").append(alternative.captured().name());
        }
        return text.toString();
    }

    /** {@code CloudburstMC/Data@0021592b1a42...} as {@code CloudburstMC/Data@0021592}. */
    private static String shortHashes(String reference) {
        return reference.replaceAll("@([0-9a-f]{7})[0-9a-f]{33}\\b", "@$1");
    }

    // ---- Resolution: the value of a key and where it comes from; the columns are built from it ----

    /** A value (null: unknown) and where it comes from. */
    public record Value(@Nullable Object value, @NonNull Provenance provenance) {
    }

    public @Nullable Value resolve(@NonNull RegisteredBlockState state, @NonNull BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        return state.isCustom() ? customBlock(state, key) : vanilla(state, key);
    }

    public @Nullable Value resolve(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
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
