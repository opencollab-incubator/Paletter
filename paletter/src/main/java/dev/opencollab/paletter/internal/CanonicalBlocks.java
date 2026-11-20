package dev.opencollab.paletter.internal;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.PaletteException;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.block.StateIndex;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Not API: consumers hold {@link dev.opencollab.paletter.vanilla.BedrockBlockState} handles and never see these ids; the
 * handles carry them in a hidden field and {@code Palette#runtimeId(BedrockBlockState)} is an array
 * lookup with them.
 *
 * @since 1.0.0
 */
public final class CanonicalBlocks {
    private final @Nullable CanonicalBlocks parent;
    private final CanonicalBlocks root;
    private final int offset;
    private final NbtMap[] states;
    private final CustomBlock[] customs;
    /**
     * The types {@link #customs} own ({@link CustomBlock#type()}): a custom id gives its handle from this array, so the
     * palette hands back the states of the registered block.
     */
    private final BedrockBlockType[] customTypes;
    /** The canonical id of the first state of each of {@link #customs}, ascending. */
    private final int[] customStarts;
    /** Own states by state, keyed by {@link BlockStates#hash}. */
    private final StateIndex index;
    private final Object2IntMap<String> customBases;
    private final Object2IntMap<String> customCounts;
    private final Map<Object, CanonicalBlocks> extensions = new WeakHashMap<>();
    private final Renames renames;
    private final String version;
    /** The default state of each block of the newest version, by block (normalized). */
    private final Map<String, NbtMap> defaultStates;
    private final Lazy<Map<String, Schema>> schemas = new Lazy<>(this::buildSchemas);
    /** Of a root only: its vanilla ids and those of the one other root asked about last. */
    private volatile @Nullable Translation translation;

    private CanonicalBlocks(@Nullable CanonicalBlocks parent, List<NbtMap> states, List<CustomBlock> blocks,
                            Renames renames, String version, Map<String, NbtMap> defaultStates) {
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        this.renames = renames;
        this.version = version;
        this.defaultStates = defaultStates;
        this.offset = parent == null ? 0 : parent.size();
        this.states = states.toArray(NbtMap[]::new);
        for (int i = 0; i < this.states.length; i++) {
            this.states[i] = BlockStates.normalize(this.states[i]);
        }
        this.index = new StateIndex(this.states);
        for (int i = 0; i < this.states.length; i++) {
            NbtMap state = this.states[i];
            if (index.add(i) != -1 || (parent != null && parent.index(state) != -1)) {
                throw new PaletteException("Duplicate canonical block state " + state, null, BlockStates.name(state), null);
            }
        }
        this.customBases = new Object2IntOpenHashMap<>(blocks.size());
        this.customBases.defaultReturnValue(-1);
        this.customCounts = new Object2IntOpenHashMap<>(blocks.size());
        this.customs = blocks.toArray(CustomBlock[]::new);
        this.customTypes = new BedrockBlockType[customs.length];
        this.customStarts = new int[customs.length];
        int base = offset;
        for (int i = 0; i < customs.length; i++) {
            CustomBlock block = customs[i];
            customTypes[i] = block.type();
            customStarts[i] = base;
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
        Map<String, NbtMap> defaults = new HashMap<>();
        newest.blockDefaults().forEach((name, state) -> defaults.put(name, BlockStates.normalize(state)));
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
     * @return the default state BDS places for a vanilla block of the newest version (normalized), or null if the data has none
     */
    @Internal
    public @Nullable NbtMap defaultState(@NonNull String name) {
        return root.defaultStates.get(name);
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
            int found = current.index.indexOf(normalized);
            if (found != -1) {
                return current.offset + found;
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

    /**
     * @return the handle of a canonical id, or null if there is no such id
     */
    public @Nullable BedrockBlockState handle(int canonical) {
        return BedrockBlockState.fromCanonical(this, canonical);
    }

    /**
     * Canonical ids belong to the instance: the newest version it knows defines them, so two instances may number the
     * same state differently. This translates between this lineage's vanilla ids and those of another lineage.
     *
     * @return for each vanilla canonical id of this lineage, the id of the same state in the vanilla states of
     *         {@code reference}, or -1 if it has none. Computed once, an array lookup afterwards
     */
    @Internal
    public int @NonNull [] vanillaIdsIn(@NonNull CanonicalBlocks reference) {
        return translation(reference).to();
    }

    /**
     * @return for each vanilla canonical id of {@code reference}, the id of the same state in this lineage, or -1
     */
    @Internal
    public int @NonNull [] vanillaIdsFrom(@NonNull CanonicalBlocks reference) {
        return translation(reference).from();
    }

    private Translation translation(CanonicalBlocks reference) {
        CanonicalBlocks root = this.root;
        CanonicalBlocks other = reference.root;
        Translation current = root.translation;
        if (current == null || current.reference() != other) {
            synchronized (root) {
                current = root.translation;
                if (current == null || current.reference() != other) {
                    root.translation = current = Translation.of(other, root.states.length, other.states.length,
                        id -> other.index.indexOf(root.states[id]));
                }
            }
        }
        return current;
    }

    /**
     * @return the version whose states define these ids
     */
    @Internal
    public @NonNull String version() {
        return version;
    }

    /**
     * @return the first indices of the lineage: the vanilla states of the newest version
     */
    public @NonNull CanonicalBlocks root() {
        return root;
    }

    /**
     * @return the type of the custom block that owns a canonical id of this lineage, or null if it is a vanilla state
     *         or no id of this lineage
     */
    @Internal
    public @Nullable BedrockBlockType customTypeAt(int canonical) {
        CanonicalBlocks owner = customOwner(canonical);
        return owner == null ? null : owner.customTypes[owner.customIndex(canonical)];
    }

    /**
     * @return the canonical id of the first state of the custom block that owns a canonical id of this lineage, or -1
     *         if it is a vanilla state or no id of this lineage
     */
    @Internal
    public int customStartAt(int canonical) {
        CanonicalBlocks owner = customOwner(canonical);
        return owner == null ? -1 : owner.customStarts[owner.customIndex(canonical)];
    }

    /**
     * @return the extension that added a custom canonical id, or null for a vanilla id or one past this lineage
     */
    private @Nullable CanonicalBlocks customOwner(int canonical) {
        if (canonical < vanillaSize() || canonical >= size()) {
            return null;
        }
        CanonicalBlocks current = this;
        while (canonical < current.offset) {
            current = current.parent;
        }
        return current;
    }

    /**
     * @return the index in {@link #customs} of the block that owns a canonical id of this extension
     */
    private int customIndex(int canonical) {
        int found = Arrays.binarySearch(customStarts, canonical);
        // Not a first state: the block before the insertion point owns it
        return found >= 0 ? found : -found - 2;
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

    /**
     * Drops the value sets {@link #forward} adapts states with (about 0.8 MB), built again on the next forward. Compiling
     * a palette forwards the creative stack states of older versions; once every palette is compiled, only a lookup of
     * a state that is not canonical does (and then keeps them again).
     */
    @Internal
    public void releaseSchemas() {
        root.schemas.release();
    }

    /** For tests: whether the value sets are built. */
    @Internal
    public boolean hasSchemas() {
        return root.schemas.peek() != null;
    }

    private Map<String, Schema> schemas() {
        return schemas.get();
    }

    private Map<String, Schema> buildSchemas() {
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
        defaultStates.forEach((name, state) -> {
            if (index.indexOf(state) != -1 && built.containsKey(name)) {
                built.put(name, new Schema(built.get(name).values, state));
            }
        });
        return Map.copyOf(built);
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
     * @return the type of the custom block of this lineage with an identifier, or null
     */
    @Internal
    public @Nullable BedrockBlockType customType(@NonNull String identifier) {
        for (CanonicalBlocks current = this; current != null; current = current.parent) {
            int base = current.customBases.getInt(identifier);
            if (base != -1) {
                return current.customTypeAt(base);
            }
        }
        return null;
    }

    /**
     * @return true if the other indices are these, or these with states appended
     */
    public boolean extendsOrIs(@NonNull CanonicalBlocks other) {
        if (other == root) {
            return true;
        }
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
