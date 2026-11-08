package org.geysermc.paletter.block;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Forwarding;
import org.geysermc.paletter.PaletteException;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.data.Renames;
import org.geysermc.paletter.data.VanillaData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dense, version independent block state indices ("canonical" ids). Every state of the newest bundled version gets
 * one, in that version's vanilla palette order, so they are stable for a Paletter release. Custom blocks of layers are
 * appended, in layer order, per lineage of layers: the same layer stack gives the same indices in every version.
 * <p>
 * A consumer maps its own blocks to canonical ids once, and uses {@link org.geysermc.paletter.Palette#runtimeId(int)}
 * (an array lookup) to get the runtime id of a particular palette.
 *
 * @since 1.0.0
 */
public final class CanonicalBlocks {
    private final @Nullable CanonicalBlocks parent;
    private final int offset;
    private final NbtMap[] states;
    private final Object2IntMap<NbtMap> index;
    private final Object2IntMap<String> customBases;
    private final Object2IntMap<String> customCounts;
    private final Map<Object, CanonicalBlocks> extensions = new WeakHashMap<>();
    private final Renames renames;
    private final String version;
    private final Map<NbtMap, String> defaultStates;
    private volatile @Nullable Map<String, Schema> schemas;

    private CanonicalBlocks(@Nullable CanonicalBlocks parent, List<NbtMap> states, List<CustomBlock> blocks,
                            Renames renames, String version, Map<NbtMap, String> defaultStates) {
        this.parent = parent;
        this.renames = renames;
        this.version = version;
        this.defaultStates = defaultStates;
        this.offset = parent == null ? 0 : parent.size();
        this.states = states.toArray(NbtMap[]::new);
        Object2IntOpenHashMap<NbtMap> index = new Object2IntOpenHashMap<>(states.size());
        index.defaultReturnValue(-1);
        for (int i = 0; i < this.states.length; i++) {
            NbtMap state = BlockStates.normalize(this.states[i]);
            this.states[i] = state;
            if (index.put(state, offset + i) != -1 || (parent != null && parent.index(state) != -1)) {
                throw new PaletteException("Duplicate canonical block state " + state);
            }
        }
        index.trim();
        this.index = index;
        this.customBases = new Object2IntOpenHashMap<>(blocks.size());
        this.customBases.defaultReturnValue(-1);
        this.customCounts = new Object2IntOpenHashMap<>(blocks.size());
        int base = offset;
        for (CustomBlock block : blocks) {
            int count = block.stateCount();
            customBases.put(block.identifier(), base);
            customCounts.put(block.identifier(), count);
            base += count;
        }
    }

    /**
     * @param states the states of the newest version, in its vanilla runtime id order
     */
    public static @NonNull CanonicalBlocks of(@NonNull List<NbtMap> states) {
        return new CanonicalBlocks(null, states, List.of(), Renames.NONE, "the newest version", Map.of());
    }

    /**
     * @param newest the data of the newest version: its states, and its block defaults, which tell {@link #forward}
     *               what a state key that older versions lack is set to
     * @param renames blocks Bedrock renamed
     */
    @Internal
    public static @NonNull CanonicalBlocks of(@NonNull VanillaData newest, @NonNull Renames renames) {
        Map<NbtMap, String> defaults = new HashMap<>();
        newest.blockDefaults().forEach((name, state) -> defaults.put(BlockStates.normalize(state), name));
        return new CanonicalBlocks(null, newest.blockStates(), List.of(), renames, newest.version().toString(), defaults);
    }

    /**
     * @param key identifies the extension (e.g. the layer adding the states); the same key returns the same instance
     *            for as long as it is referenced
     * @return these indices with the given states appended, or this if there are none
     */
    public @NonNull CanonicalBlocks extend(@NonNull Object key, @NonNull List<CustomBlock> blocks) {
        if (blocks.isEmpty()) {
            return this;
        }
        synchronized (extensions) {
            CanonicalBlocks existing = extensions.get(key);
            if (existing == null) {
                List<NbtMap> states = new ArrayList<>();
                for (CustomBlock block : blocks) {
                    states.addAll(block.states());
                }
                existing = new CanonicalBlocks(this, states, blocks, renames, version, defaultStates);
                extensions.put(key, existing);
            }
            return existing;
        }
    }

    /**
     * @return the amount of canonical ids; valid ids are {@code 0} to {@code size() - 1}
     */
    public int size() {
        return offset + states.length;
    }

    /**
     * @return the amount of canonical ids of vanilla (newest version) states; custom states follow them
     */
    public int vanillaSize() {
        return parent == null ? states.length : parent.vanillaSize();
    }

    /**
     * @param state a block state as {@code {name, states}}; extra keys like {@code version} are ignored
     * @return its canonical id, or -1 if it is not a state of the newest version or of a custom block of this lineage
     */
    public int index(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        CanonicalBlocks current = this;
        while (current != null) {
            int found = current.index.getInt(normalized);
            if (found != -1) {
                return found;
            }
            current = current.parent;
        }
        return -1;
    }

    /**
     * Maps a state of <em>any</em> bundled version to the canonical ids, which the newest version defines:
     * <ol>
     *     <li>a state the newest version has (or a custom state of this lineage) is {@link Forwarding.Kind#EXACT};</li>
     *     <li>a block Bedrock renamed is looked up under its new identifier ({@link Forwarding.Kind#RENAMED});</li>
     *     <li>a block with other state keys or values is adapted like an older palette adapts newer states, the other
     *     way round: values the newest block still has are carried over, keys it added get the default, removed keys are
     *     dropped ({@link Forwarding.Kind#ADAPTED});</li>
     *     <li>otherwise {@link Forwarding.Kind#UNKNOWN} with the reason, and no canonical id.</li>
     * </ol>
     * Block fallbacks are not involved: they go from the newest version to an older one, this goes the other way.
     *
     * @since 1.0.0
     */
    public @NonNull Forwarding forward(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        int exact = index(normalized);
        if (exact != -1) {
            return new Forwarding(exact, Forwarding.Kind.EXACT, "");
        }
        return root().forwardVanilla(normalized);
    }

    private CanonicalBlocks root() {
        CanonicalBlocks current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    private Forwarding forwardVanilla(NbtMap state) {
        Map<String, Schema> schemas = schemas();
        String name = BlockStates.name(state);
        String target = name;
        Forwarding.Kind kind = Forwarding.Kind.ADAPTED;
        List<String> detail = new ArrayList<>();
        if (!schemas.containsKey(name)) {
            String renamed = renames.block(name);
            if (renamed == null || !schemas.containsKey(renamed)) {
                return Forwarding.unknown("block " + name + " does not exist in " + version + (renamed == null ? " and was not renamed"
                    : " (renamed to " + renamed + ", which does not exist either)"));
            }
            target = renamed;
            kind = Forwarding.Kind.RENAMED;
            detail.add("renamed to " + renamed);
        }
        Schema schema = schemas.get(target);
        NbtMap requested = BlockStates.states(state);
        NbtMap defaults = BlockStates.states(schema.defaultState);
        Map<String, Object> adapted = new HashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object value = requested.get(entry.getKey());
            if (value != null && schema.values.get(entry.getKey()).contains(value)) {
                adapted.put(entry.getKey(), value);
            } else {
                adapted.put(entry.getKey(), entry.getValue());
                detail.add(value == null ? "state " + entry.getKey() + " is new, set to its default " + entry.getValue()
                    : "state " + entry.getKey() + "=" + value + " does not exist anymore, reset to " + entry.getValue());
            }
        }
        for (String key : requested.keySet()) {
            if (!defaults.containsKey(key)) {
                detail.add("state " + key + " was removed");
            }
        }
        int found = index(BlockStates.of(target, adapted));
        if (found == -1) {
            // The combination does not exist; the default is the best remaining guess
            found = index(schema.defaultState);
            detail.add("that combination does not exist, using the default state");
        }
        return new Forwarding(found, kind, String.join("; ", detail));
    }

    private record Schema(Map<String, Set<Object>> values, NbtMap defaultState) {
    }

    private Map<String, Schema> schemas() {
        Map<String, Schema> schemas = this.schemas;
        if (schemas == null) {
            synchronized (this) {
                schemas = this.schemas;
                if (schemas == null) {
                    Map<String, Map<String, Set<Object>>> values = new HashMap<>();
                    Map<String, NbtMap> firsts = new HashMap<>();
                    for (NbtMap state : this.states) {
                        String name = BlockStates.name(state);
                        firsts.putIfAbsent(name, state);
                        Map<String, Set<Object>> perKey = values.computeIfAbsent(name, n -> new HashMap<>());
                        BlockStates.states(state).forEach((key, value) -> perKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value));
                    }
                    Map<String, Schema> built = new HashMap<>();
                    values.forEach((name, perKey) -> built.put(name, new Schema(perKey, firsts.get(name))));
                    defaultStates.forEach((state, name) -> {
                        if (index.containsKey(state) && built.containsKey(name)) {
                            built.put(name, new Schema(built.get(name).values, state));
                        }
                    });
                    this.schemas = schemas = Map.copyOf(built);
                }
            }
        }
        return schemas;
    }

    /**
     * @return the state of a canonical id
     * @throws IndexOutOfBoundsException if there is no such id
     */
    public @NonNull NbtMap state(int canonical) {
        if (canonical < 0) {
            throw new IndexOutOfBoundsException(canonical);
        }
        CanonicalBlocks current = this;
        while (canonical < current.offset) {
            current = current.parent;
        }
        return current.states[canonical - current.offset];
    }

    /**
     * @param identifier a custom block identifier
     * @param stateCount the amount of states the caller expects the block to have
     * @return the canonical id of the first state of a custom block of this lineage with that many states, or -1. Its
     *         states follow in {@link CustomBlock#states()} order
     */
    @Internal
    public int customBase(@NonNull String identifier, int stateCount) {
        CanonicalBlocks current = this;
        while (current != null) {
            int base = current.customBases.getInt(identifier);
            if (base != -1) {
                return current.customCounts.getInt(identifier) == stateCount ? base : -1;
            }
            current = current.parent;
        }
        return -1;
    }

    /**
     * @return true if the other indices are these, or these with states appended
     */
    public boolean extendsOrIs(@NonNull CanonicalBlocks other) {
        CanonicalBlocks current = this;
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
        return "CanonicalBlocks{" + size() + " states, " + (size() - vanillaSize()) + " custom}";
    }
}
