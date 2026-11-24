package dev.opencollab.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockBehaviour;
import dev.opencollab.paletter.behaviour.BlockValues;
import dev.opencollab.paletter.behaviour.ItemBehaviour;
import dev.opencollab.paletter.behaviour.ItemValues;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.RegisteredBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.block.IntIndex;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.property.Properties;
import org.cloudburstmc.nbt.NbtMap;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The behaviour values of the blocks and items of one {@link Palette}, behind {@link Palette#resolve(dev.opencollab.paletter.vanilla.BedrockBlockState)} and
 * {@link dev.opencollab.paletter.item.ResolvedItem#behaviour()}. Thread safe.
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
    private final BlockRegistry blocks;
    /**
     * The vanilla values, asked for only when a column or a provenance is built: a resolution and its handle need none.
     * A {@code Paletter} built with {@code behaviour(false)} gives a supplier that throws, so nothing loads or keeps them
     * (the generated classes are not even loaded).
     */
    private final Supplier<VanillaBehaviour> data;
    /** The vanilla values of the palette's version, found on first use: a {@link VanillaVersion} or {@link #NONE}. */
    private volatile @Nullable Object version;
    private static final Object NONE = new Object();
    /** The behaviour of the palette this one extends, whose states come first here; null if there is none. */
    private final @Nullable Behaviour parent;
    /** The behaviour of the palette this one extends, whose items this one shares by instance; null if there is none. */
    private final @Nullable Behaviour itemParent;
    /** The number of states of {@link #parent}: the states from this position on are this behaviour's own. */
    private final int base;
    /** The columns of the own states by key ordinal, null until built; written under the lock, read without it. */
    private final @Nullable Column[] blockColumns = new Column[KEYS];
    /**
     * The own items, in the order of their columns: the items the palette's registry itself defines, which are all of
     * them for a vanilla palette and the added and overridden ones for an extension (the others are the parent's, with
     * the parent's columns, see {@link #item}).
     */
    private final List<RegisteredItem> items;
    /** The position of an own item in {@link #items} by runtime id, or -1. */
    private final IntIndex positions;
    /** The handles of the own items by position, made on first use ({@link #itemHandle}). */
    private final @Nullable ItemValues[] itemHandles;
    private final @Nullable Column[] itemColumns = new Column[KEYS];

    private Behaviour(Palette palette, Supplier<VanillaBehaviour> data, @Nullable Behaviour parent) {
        this.palette = palette;
        this.data = data;
        this.blocks = Access.palette().blocks(palette);
        boolean extending = parent != null && blocks.extendsStatesOf(parent.blocks);
        this.parent = extending ? parent : null;
        this.itemParent = parent;
        this.base = extending ? parent.blocks.size() : 0;
        this.items = Access.palette().items(palette).ownDefinitions();
        this.positions = new IntIndex(items.size());
        this.itemHandles = new ItemValues[items.size()];
        for (int i = 0; i < items.size(); i++) {
            positions.put(items.get(i).getRuntimeId(), i);
        }
    }

    /**
     * @param parent the behaviour of the palette this one extends (its block states come first in this one), or null
     */
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull VanillaBehaviour data, @Nullable Behaviour parent) {
        Objects.requireNonNull(data);
        return of(palette, () -> data, parent);
    }

    /**
     * @param data the vanilla values, asked for the first time a column or a provenance needs them
     * @param parent the behaviour of the palette this one extends (its block states come first in this one), or null
     */
    public static @NonNull Behaviour of(@NonNull Palette palette, @NonNull Supplier<VanillaBehaviour> data, @Nullable Behaviour parent) {
        return new Behaviour(Objects.requireNonNull(palette), Objects.requireNonNull(data), parent);
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
        // A loop, not a call on the parent: the JIT does not inline recursion, and the handle would escape
        Behaviour owner = this;
        while (index < owner.base) {
            owner = owner.parent;
        }
        return new BlockValues(owner, index - owner.base);
    }

    /**
     * @param index the index of a {@link BlockValues} of this behaviour
     * @return its state
     */
    public @NonNull RegisteredBlockState stateAt(int index) {
        return blocks.byIndex(base + index);
    }

    /**
     * @return the handle of an item of the palette, of the palette this one extends for an item they share (the same instance), see {@link #block(int)}; null if the item is not of the palette
     */
    public @Nullable ItemValues item(@NonNull RegisteredItem item) {
        // The oldest behaviour that has this instance owns the handle, so one item has one handle in a palette and its extensions.
        // A loop, not a call on the parent, for the same reason as in {@link #block(int)}
        Behaviour owner = null;
        int found = -1;
        for (Behaviour candidate = this; candidate != null; candidate = candidate.itemParent) {
            int position = candidate.positionOf(item);
            if (position >= 0) {
                owner = candidate;
                found = position;
            }
        }
        return owner == null ? null : owner.itemHandle(found);
    }

    /**
     * The handle of an own item, made once: unlike {@link #block(int)}'s, the lookup of an item (a loop over the palettes and
     * an index probe) is too large for the JIT to inline into every caller, so a handle made per call is not scalar-replaced
     * and allocates (24 bytes per lookup in {@code HotPathTest} once the suite has made the call sites polymorphic).
     * {@link ItemValues} is immutable with final fields: a race makes two equal handles, of which one stays.
     */
    private ItemValues itemHandle(int position) {
        ItemValues handle = itemHandles[position];
        if (handle == null) {
            itemHandles[position] = handle = new ItemValues(this, position);
        }
        return handle;
    }

    private int positionOf(RegisteredItem item) {
        int position = positions.get(item.getRuntimeId());
        return position < 0 || items.get(position) != item ? -1 : position;
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

    /**
     * Reads the vanilla values and builds every block and item column now, instead of on the first lookup of each key.
     * Called for the palettes a {@code Paletter} compiles (not with {@code behaviour(false)}).
     */
    public void loadAll() {
        data.get();
        for (BehaviourKey<?> key : BlockBehaviour.keys()) {
            blockColumn(key);
        }
        for (BehaviourKey<?> key : ItemBehaviour.keys()) {
            itemColumn(key);
        }
    }

    /** For tests: whether every column is built. */
    public boolean loaded() {
        for (BehaviourKey<?> key : BlockBehaviour.keys()) {
            if (blockColumns[key.ordinal()] == null) {
                return false;
            }
        }
        for (BehaviourKey<?> key : ItemBehaviour.keys()) {
            if (itemColumns[key.ordinal()] == null) {
                return false;
            }
        }
        return true;
    }

    private synchronized Column buildBlocks(BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.BLOCK);
        // Throws here, for every key alike, when the Paletter has no behaviour values (Builder#behaviour(false))
        data.get();
        Column column = blockColumns[key.ordinal()];
        if (column == null) {
            BlockRegistry registry = blocks;
            Object[] values = new Object[registry.size() - base];
            for (int i = 0; i < values.length; i++) {
                values[i] = value(registry.byIndex(base + i), key);
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
        data.get();
        Column column = itemColumns[key.ordinal()];
        if (column == null) {
            Object[] values = new Object[items.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = value(items.get(i), key);
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
        data.get();
        return state.isCustom() ? customBlock(state, key) : vanilla(state, key);
    }

    public @Nullable Value resolve(@NonNull RegisteredItem item, @NonNull BehaviourKey<?> key) {
        require(key, BehaviourKey.Owner.ITEM);
        data.get();
        return item.isCustom() || !item.components().isEmpty() ? customItem(item, key) : vanilla(item, key);
    }

    /** @return the bundled values of the palette's version (of the newest older one), or null if there are none */
    private @Nullable VanillaVersion values() {
        Object found = version;
        if (found == null) {
            VanillaVersion values = data.get().version(palette.version());
            version = found = values == null ? NONE : values;
        }
        return found == NONE ? null : (VanillaVersion) found;
    }

    /**
     * A bundled value of a block state ({@code states}) or of an item ({@code states} empty); the capture that decided it is
     * looked up only when asked for (the value path needs it only where there is a wire value too).
     */
    private record Hit(@NonNull Object value, @NonNull VanillaVersion values, @NonNull BehaviourKey<?> key, @NonNull String identifier,
                       @NonNull NbtMap states, boolean item) {
        Capture capture() {
            return Objects.requireNonNull(item ? values.itemCapture(identifier, key) : values.capture(identifier, key, states));
        }
    }

    /** @return the bundled value of a vanilla block state, or null if there is none */
    private @Nullable Hit blockHit(RegisteredBlockState state, BehaviourKey<?> key) {
        VanillaVersion values = values();
        VanillaVersion.BlockEntry entry = values == null ? null : values.block(state.identifier());
        if (entry == null || !entry.covers(state.states())) {
            return null;
        }
        Object value = VanillaVersion.value(entry, key, state.states());
        return value == null ? null : new Hit(value, values, key, state.identifier(), state.states(), false);
    }

    /** @return the bundled value of a vanilla item, or null if there is none */
    private @Nullable Hit itemHit(RegisteredItem item, BehaviourKey<?> key) {
        VanillaVersion values = values();
        Object value = values == null ? null : values.item(item.getIdentifier(), key);
        return value == null ? null : new Hit(value, values, key, item.getIdentifier(), NbtMap.EMPTY, true);
    }

    private @Nullable Value vanilla(RegisteredBlockState state, BehaviourKey<?> key) {
        RegisteredBlock block = blocks.block(state.identifier());
        Value wire = block == null ? null : vanillaWire(key, block.properties(state));
        return choose(blockHit(state, key), wire);
    }

    private @Nullable Value vanilla(RegisteredItem item, BehaviourKey<?> key) {
        return choose(itemHit(item, key), vanillaWire(key, item.properties()));
    }

    private @Nullable Value customBlock(RegisteredBlockState state, BehaviourKey<?> key) {
        RegisteredBlock block = blocks.block(state.identifier());
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
    private @Nullable Value choose(@Nullable Hit stored, @Nullable Value wire) {
        return switch (choice(stored, wire != null)) {
            case NONE -> null;
            case STORED -> captured(stored);
            case WIRE -> wire;
        };
    }

    private enum Choice { NONE, STORED, WIRE }

    /**
     * The one decision both paths make: which of the stored capture and the wire value gives the value (or neither). The value
     * path and the provenance path only differ in what they build from the answer.
     */
    private Choice choice(@Nullable Hit stored, boolean hasWire) {
        if (!hasWire) {
            return stored == null ? Choice.NONE : Choice.STORED;
        }
        if (stored == null) {
            return Choice.WIRE;
        }
        Capture capture = stored.capture();
        boolean inherited = !capture.version().name().equals(palette.version().name());
        return !capture.source().thirdParty() && !inherited ? Choice.STORED : Choice.WIRE;
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
        Object value = userValue(key, properties, components);
        if (value == null) {
            return null;
        }
        DataVersion version = palette.version();
        return new Value(value, new Provenance(Provenance.Source.USER, "layer " + layer, version, version));
    }

    private static @Nullable Object userValue(BehaviourKey<?> key, Properties properties, List<String> components) {
        if (components.stream().noneMatch(WireRules.components(key)::contains)) {
            return null;
        }
        return WireRules.value(key, properties);
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
        VanillaBehaviour.Default fallback = data.get().customDefault(key);
        if (fallback == null) {
            return new Value(null, Provenance.unknownDefault(version));
        }
        return new Value(fallback.value(), new Provenance(Provenance.Source.BDS_DEFAULT,
            fallback.source() + " " + fallback.reference(), version, version));
    }

    private Value captured(Hit hit) {
        Capture capture = hit.capture();
        VanillaBehaviour all = data.get();
        List<Provenance.Alternative> alternatives = all.disputes(hit.values(), capture, hit.key(), hit.identifier(), hit.states()).stream()
            .map(d -> new Provenance.Alternative(d.capture().source(), d.capture().reference(), d.capture().version(), d.value()))
            .toList();
        return new Value(hit.value(), new Provenance(capture.source(), capture.reference(), capture.version(), palette.version(),
            alternatives, all.note(hit.values(), capture, hit.key(), hit.identifier(), hit.states())));
    }

    // ---- Values only: what the columns are built from. The decisions of the methods above without the provenance, which
    // costs more than the value and is not needed for a column. BehaviourTest.theHandlesHoldTheValuesOfTheProvenancePath holds the two to the same values. ----

    private @Nullable Object value(RegisteredBlockState state, BehaviourKey<?> key) {
        RegisteredBlock block = blocks.block(state.identifier());
        if (state.isCustom()) {
            return block == null ? null
                : customValue(key, block.properties(state), block.components(state).stream().map(c -> c.name()).toList());
        }
        return chooseValue(blockHit(state, key), block == null ? null : WireRules.value(key, block.properties(state)));
    }

    private @Nullable Object value(RegisteredItem item, BehaviourKey<?> key) {
        if (!item.isCustom() && item.components().isEmpty()) {
            return vanillaValue(item, key);
        }
        List<String> names = item.components().stream().map(c -> c.name()).toList();
        if (item.isCustom()) {
            return customValue(key, item.properties(), names);
        }
        Object user = userValue(key, item.properties(), names);
        return user != null ? user : vanillaValue(item, key);
    }

    private @Nullable Object vanillaValue(RegisteredItem item, BehaviourKey<?> key) {
        return chooseValue(itemHit(item, key), WireRules.value(key, item.properties()));
    }

    private @Nullable Object customValue(BehaviourKey<?> key, Properties properties, List<String> components) {
        Object user = userValue(key, properties, components);
        if (user != null) {
            return user;
        }
        Object sent = WireRules.value(key, properties);
        if (sent != null) {
            return sent;
        }
        VanillaBehaviour.Default fallback = data.get().customDefault(key);
        return fallback == null ? null : fallback.value();
    }

    /** {@link #choose} on values: a capture of this very version from a direct source beats the wire. */
    private @Nullable Object chooseValue(@Nullable Hit stored, @Nullable Object wire) {
        return switch (choice(stored, wire != null)) {
            case NONE -> null;
            case STORED -> stored.value();
            case WIRE -> wire;
        };
    }

    private static void require(BehaviourKey<?> key, BehaviourKey.Owner owner) {
        if (key.owner() != owner) {
            throw new IllegalArgumentException(key + " is not a " + owner.name().toLowerCase(java.util.Locale.ROOT) + " key");
        }
    }
}
