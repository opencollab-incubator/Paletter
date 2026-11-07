package org.geysermc.paletter.block;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.geysermc.paletter.PaletteException;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compiled block palette of one {@link org.geysermc.paletter.Palette}: vanilla states plus custom blocks of all
 * layers, with runtime ids assigned the way the client does. It is the block {@link DefinitionRegistry} for the codec.
 * <p>
 * Consumers map against the newest Bedrock version, either by state ({@link #resolve(NbtMap)}) or, on hot paths, by
 * canonical id ({@link #runtimeId(int)}, {@link #block(int)}: array lookups, see {@link CanonicalBlocks}). Differences
 * to older versions (renamed or missing blocks, added or removed states) are handled here.
 * <p>
 * Runtime ids depend on the palette's {@link BlockNetworkIds} mode: palette positions, or state hashes. Each state also
 * has a dense {@link PaletteBlock#index()} in both modes.
 */
public final class BlockPalette implements DefinitionRegistry<BlockDefinition> {
    /**
     * Numeric ids of custom blocks start here, to stay clear of vanilla block ids.
     */
    public static final int CUSTOM_BLOCK_ID_START = 10000;

    private static final long FNV1_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1_64_PRIME = 1099511628211L;

    private final Table table;
    private final CanonicalBlocks canonical;
    private final Map<String, BlockFallback> fallbacks;
    private final Map<NbtMap, BlockResolution> resolutions = new ConcurrentHashMap<>();
    private volatile @Nullable CanonicalTable canonicalTable;

    private BlockPalette(Table table, CanonicalBlocks canonical, Map<String, BlockFallback> fallbacks) {
        this.table = table;
        this.canonical = canonical;
        this.fallbacks = fallbacks;
    }

    /**
     * Builds the vanilla palette of a version.
     *
     * @param canonical canonical ids of the newest version's states
     */
    public static @NonNull BlockPalette build(@NonNull VanillaData data, @NonNull BlockNetworkIds mode, @NonNull CanonicalBlocks canonical) {
        return new BlockPalette(new Table(data, List.of(), mode), canonical, data.fallbacks().blocks());
    }

    /**
     * @return a palette sharing all states with this one, with additional fallbacks, or this palette if there are none
     */
    public @NonNull BlockPalette withFallbacks(@NonNull Map<String, BlockFallback> extraFallbacks) {
        if (extraFallbacks.isEmpty()) {
            return this;
        }
        Map<String, BlockFallback> merged = new HashMap<>(fallbacks);
        merged.putAll(extraFallbacks);
        return new BlockPalette(table, canonical, Map.copyOf(merged));
    }

    /**
     * @param key what adds the blocks, typically the layer; canonical ids of custom blocks are cached per key, so
     *            every version compiling the same layer stack shares them
     * @return a palette with these custom blocks added, keeping this palette's fallbacks, or this palette if there are none
     */
    public @NonNull BlockPalette withCustomBlocks(@NonNull VanillaData data, @NonNull Object key, @NonNull List<CustomBlock> additional) {
        if (additional.isEmpty()) {
            return this;
        }
        List<CustomBlock> all = new ArrayList<>(table.customBlocks);
        all.addAll(additional);
        Table extended = new Table(data, all, table.mode);
        return new BlockPalette(extended, canonical.extend(key, additional), fallbacks);
    }

    public @NonNull DataVersion version() {
        return table.version;
    }

    public @NonNull BlockNetworkIds networkIds() {
        return table.mode;
    }

    /**
     * @return the canonical ids this palette resolves: states of the newest version plus custom blocks of its layers
     */
    public @NonNull CanonicalBlocks canonical() {
        return canonical;
    }

    /**
     * @return the amount of block states
     */
    public int size() {
        return table.byIndex.length;
    }

    public @Nullable PaletteBlock byRuntimeId(int runtimeId) {
        Int2ObjectMap<PaletteBlock> byHash = table.byHash;
        if (byHash != null) {
            return byHash.get(runtimeId);
        }
        return runtimeId >= 0 && runtimeId < table.byIndex.length ? table.byIndex[runtimeId] : null;
    }

    /**
     * @param index a dense position, see {@link PaletteBlock#index()}
     * @throws IndexOutOfBoundsException if there is no such position
     */
    public @NonNull PaletteBlock byIndex(int index) {
        return table.byIndex[index];
    }

    @Override
    public @Nullable BlockDefinition getDefinition(int runtimeId) {
        return byRuntimeId(runtimeId);
    }

    @Override
    public boolean isRegistered(@NonNull BlockDefinition definition) {
        return definition instanceof PaletteBlock block && block.index() < table.byIndex.length && table.byIndex[block.index()] == block;
    }

    // ---- Canonical ids ----

    /**
     * The block to send for a canonical id: the exact state, else the state resolved for this version (see
     * {@link #resolve(NbtMap)}), else air. An array lookup; the table is built on first use and shared by every
     * palette using this block palette.
     *
     * @throws IndexOutOfBoundsException if the id is not one of {@link #canonical()}
     */
    public @NonNull PaletteBlock block(int canonical) {
        return canonicalTable().blocks[canonical];
    }

    /**
     * Like {@link #block(int)}, but only the runtime id. An array lookup.
     */
    public int runtimeId(int canonical) {
        return canonicalTable().runtimeIds[canonical];
    }

    /**
     * Maps a block back to a canonical id: the canonical id of its exact state, else the first canonical id that
     * resolves to it (e.g. a state of an older version that the newest version added a state value to).
     *
     * @return the canonical id, or -1 for null, a definition of another palette, or a state nothing resolves to
     */
    public int canonical(@Nullable BlockDefinition definition) {
        if (definition == null) {
            return -1;
        }
        PaletteBlock block = isRegistered(definition) ? (PaletteBlock) definition : byRuntimeId(definition.getRuntimeId());
        return block == null ? -1 : canonicalTable().reverse[block.index()];
    }

    /**
     * Like {@link #canonical(BlockDefinition)}, by runtime id.
     */
    public int canonicalOfRuntimeId(int runtimeId) {
        PaletteBlock block = byRuntimeId(runtimeId);
        return block == null ? -1 : canonicalTable().reverse[block.index()];
    }

    /**
     * @return how many canonical ids have no block of their own in this version and use air
     */
    public int missingCanonical() {
        return canonicalTable().missing;
    }

    private CanonicalTable canonicalTable() {
        CanonicalTable result = canonicalTable;
        if (result == null) {
            synchronized (this) {
                result = canonicalTable;
                if (result == null) {
                    canonicalTable = result = buildCanonicalTable();
                }
            }
        }
        return result;
    }

    private CanonicalTable buildCanonicalTable() {
        int size = canonical.size();
        PaletteBlock[] blocks = new PaletteBlock[size];
        int[] runtimeIds = new int[size];
        int[] reverse = new int[table.byIndex.length];
        Arrays.fill(reverse, -1);
        for (PaletteBlock block : table.byIndex) {
            reverse[block.index()] = canonical.index(block.getState());
        }
        int missing = 0;
        for (int i = 0; i < size; i++) {
            PaletteBlock block = resolve(canonical.state(i)).block();
            if (block == null) {
                block = table.air;
                missing++;
            }
            blocks[i] = block;
            runtimeIds[i] = block.getRuntimeId();
            if (reverse[block.index()] == -1) {
                reverse[block.index()] = i;
            }
        }
        return new CanonicalTable(blocks, runtimeIds, reverse, missing);
    }

    private record CanonicalTable(PaletteBlock[] blocks, int[] runtimeIds, int[] reverse, int missing) {
    }

    // ---- States ----

    /**
     * @param state a block state as {@code {name, states}}
     * @return the definition of exactly this state, or null
     */
    public @Nullable PaletteBlock exact(@NonNull NbtMap state) {
        return table.byState.get(BlockStates.normalize(state));
    }

    /**
     * Resolves a block state that is valid in the newest Bedrock version against this palette's version:
     * <ol>
     *     <li>an exact match is used as is;</li>
     *     <li>a block that does not exist (or is forcibly replaced, e.g. because it is experimental) is replaced by its fallback;</li>
     *     <li>state values are carried over where the (replacement) block has them; missing or invalid ones use the
     *     block's default state values.</li>
     * </ol>
     * Results are cached. On hot paths, prefer canonical ids ({@link #block(int)}).
     */
    public @NonNull BlockResolution resolve(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        BlockFallback forced = fallbacks.get(BlockStates.name(normalized));
        PaletteBlock exact = forced != null && forced.force() ? null : table.byState.get(normalized);
        if (exact != null) {
            return new BlockResolution(normalized, exact, BlockResolution.Kind.EXACT);
        }
        return resolutions.computeIfAbsent(normalized, this::adapt);
    }

    private BlockResolution adapt(NbtMap state) {
        String name = BlockStates.name(state);
        BlockResolution.Kind kind = BlockResolution.Kind.ADAPTED;
        boolean clearStates = false;
        BlockFallback forced = fallbacks.get(name);
        if (!table.schemas().containsKey(name) || (forced != null && forced.force())) {
            BlockFallback fallback = forced;
            if (fallback == null || !table.schemas().containsKey(fallback.target())) {
                return new BlockResolution(state, null, BlockResolution.Kind.MISSING);
            }
            name = fallback.target();
            clearStates = fallback.clearStates();
            kind = BlockResolution.Kind.FALLBACK;
        }

        Schema schema = table.schemas().get(name);
        if (clearStates) {
            return new BlockResolution(state, schema.defaultState, kind);
        }
        NbtMap requested = BlockStates.states(state);
        NbtMapBuilder adapted = NbtMap.builder();
        for (Map.Entry<String, Object> entry : schema.defaultState.states().entrySet()) {
            Object value = requested.get(entry.getKey());
            Set<Object> allowed = schema.values.get(entry.getKey());
            adapted.put(entry.getKey(), value != null && allowed.contains(value) ? value : entry.getValue());
        }
        PaletteBlock block = table.byState.get(BlockStates.of(name, adapted.build()));
        return new BlockResolution(state, block != null ? block : schema.defaultState, kind);
    }

    /**
     * Resolves a list of states at once. States that cannot be resolved at all map to {@code missing}; everything that
     * was not exact is recorded in the report.
     */
    public @NonNull PaletteBlock[] table(@NonNull List<NbtMap> states, @NonNull PaletteBlock missing, @NonNull PaletteReport report) {
        PaletteBlock[] result = new PaletteBlock[states.size()];
        Map<BlockResolution.Kind, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < result.length; i++) {
            BlockResolution resolution = resolve(states.get(i));
            counts.merge(resolution.kind(), 1, Integer::sum);
            if (resolution.kind() == BlockResolution.Kind.MISSING) {
                report.warn("blocks", "No " + version() + " block for " + resolution.requested() + ", and no fallback; using " + missing.getState());
            }
            result[i] = resolution.block() != null ? resolution.block() : missing;
        }
        report.info("blocks", "Resolved " + result.length + " states for " + version() + ": " + counts);
        return result;
    }

    /**
     * @return the default state of a block, or null if the block does not exist
     */
    public @Nullable PaletteBlock defaultState(@NonNull String identifier) {
        Schema schema = table.schemas().get(identifier);
        return schema == null ? null : schema.defaultState;
    }

    public boolean contains(@NonNull String identifier) {
        return table.schemas().containsKey(identifier);
    }

    /**
     * @return every state of a block, in palette order
     */
    public @NonNull List<PaletteBlock> states(@NonNull String identifier) {
        Schema schema = table.schemas().get(identifier);
        return schema == null ? List.of() : schema.states;
    }

    public @NonNull PaletteBlock air() {
        return table.air;
    }

    /**
     * @return block property data to send in the start game packet: vanilla data driven blocks and custom blocks
     */
    public @NonNull List<BlockPropertyData> blockProperties() {
        return table.blockProperties;
    }

    public @NonNull List<CustomBlock> customBlocks() {
        return table.customBlocks;
    }

    /**
     * Custom blocks are sorted in between vanilla blocks, shifting vanilla runtime ids. Data stored against vanilla
     * ids (e.g. cached chunks) can be translated with this. The identity in {@link BlockNetworkIds#HASHED} palettes.
     *
     * @param vanillaRuntimeId the runtime id in the plain vanilla palette of the same mode
     * @return the runtime id in this palette
     */
    public int remapVanilla(int vanillaRuntimeId) {
        return table.vanillaToRuntime == null ? vanillaRuntimeId : table.vanillaToRuntime[vanillaRuntimeId];
    }

    /**
     * @return true if vanilla runtime ids differ from this palette's, i.e. {@link #remapVanilla} is not the identity
     */
    public boolean isRemapped() {
        return table.vanillaToRuntime != null;
    }

    public @NonNull Map<String, BlockFallback> fallbacks() {
        return fallbacks;
    }

    /**
     * @return true if this palette has exactly the states (and runtime ids) of the other one
     */
    public boolean sharesStatesWith(@NonNull BlockPalette other) {
        return table == other.table;
    }

    @Override
    public String toString() {
        return "BlockPalette{" + version() + ", " + table.mode + ", " + size() + " states, " + table.customBlocks.size() + " custom blocks}";
    }

    /**
     * FNV-1a 64 bit hash of a block identifier; the client orders its palette by it once custom blocks exist.
     */
    public static long nameHash(@NonNull String identifier) {
        long hash = FNV1_64_OFFSET_BASIS;
        for (byte b : identifier.getBytes(StandardCharsets.UTF_8)) {
            hash *= FNV1_64_PRIME;
            hash ^= b;
        }
        return hash;
    }

    private record Schema(List<PaletteBlock> states, Map<String, Set<Object>> values, PaletteBlock defaultState) {
    }

    /**
     * Immutable state table, shared between all palettes deriving from the same set of custom blocks.
     */
    private static final class Table {
        private final DataVersion version;
        private final BlockNetworkIds mode;
        private final PaletteBlock[] byIndex;
        private final @Nullable Int2ObjectMap<PaletteBlock> byHash;
        private final Map<NbtMap, PaletteBlock> byState;
        private final int @Nullable [] vanillaToRuntime;
        private final List<BlockPropertyData> blockProperties;
        private final List<CustomBlock> customBlocks;
        private final Map<String, NbtMap> defaults;
        private final PaletteBlock air;
        private volatile Map<String, Schema> schemas;

        private Table(VanillaData data, List<CustomBlock> customBlocks, BlockNetworkIds mode) {
            this.version = data.version();
            this.mode = mode;
            this.customBlocks = List.copyOf(customBlocks);

            List<NbtMap> states = new ArrayList<>(data.blockStates());
            Set<NbtMap> customStates = new LinkedHashSet<>();
            List<BlockPropertyData> properties = new ArrayList<>(data.dataDrivenBlocks());
            Map<String, NbtMap> customDefaults = new HashMap<>();
            Set<String> vanillaNames = new LinkedHashSet<>();
            for (NbtMap state : data.blockStates()) {
                vanillaNames.add(BlockStates.name(state));
            }
            int blockId = CUSTOM_BLOCK_ID_START;
            for (CustomBlock block : this.customBlocks) {
                if (vanillaNames.contains(block.identifier()) || customDefaults.containsKey(block.identifier())) {
                    throw new PaletteException("Custom block " + block.identifier() + " is registered twice, or collides with a vanilla block");
                }
                customStates.addAll(block.states());
                customDefaults.put(block.identifier(), block.defaultState());
                properties.add(new BlockPropertyData(block.identifier(), block.propertyData(blockId++)));
            }

            if (!customStates.isEmpty()) {
                states.addAll(customStates);
                // The client sorts the whole palette by name hash as soon as custom blocks exist. The sort is stable,
                // so states of one block keep their order. Hashed ids do not depend on it, but keep one order anyway
                states.sort((a, b) -> Long.compareUnsigned(nameHash(BlockStates.name(a)), nameHash(BlockStates.name(b))));
            }

            this.byIndex = new PaletteBlock[states.size()];
            Object2ObjectOpenHashMap<NbtMap, PaletteBlock> byState = new Object2ObjectOpenHashMap<>(states.size());
            Int2ObjectOpenHashMap<PaletteBlock> byHash = mode == BlockNetworkIds.HASHED ? new Int2ObjectOpenHashMap<>(states.size()) : null;
            for (int i = 0; i < states.size(); i++) {
                NbtMap state = states.get(i);
                int runtimeId = byHash == null ? i : BlockNetworkIds.hash(state);
                PaletteBlock block = new PaletteBlock(runtimeId, i, state, customStates.contains(state));
                if (byState.put(state, block) != null) {
                    throw new PaletteException("Duplicate block state in " + version + " palette: " + state);
                }
                if (byHash != null) {
                    PaletteBlock collision = byHash.put(runtimeId, block);
                    if (collision != null) {
                        throw new PaletteException("Block network id hash collision in the " + version + " palette: "
                            + collision.getState() + " and " + state + " both hash to " + runtimeId);
                    }
                }
                byIndex[i] = block;
            }
            this.byState = byState;
            if (byHash != null) {
                byHash.trim();
            }
            this.byHash = byHash;

            if (customStates.isEmpty() || mode == BlockNetworkIds.HASHED) {
                this.vanillaToRuntime = null;
            } else {
                int[] remap = new int[data.blockStates().size()];
                for (int i = 0; i < remap.length; i++) {
                    remap[i] = byState.get(data.blockStates().get(i)).getRuntimeId();
                }
                this.vanillaToRuntime = remap;
            }
            this.blockProperties = List.copyOf(properties);
            Map<String, NbtMap> defaults = new HashMap<>(data.blockDefaults());
            defaults.putAll(customDefaults);
            this.defaults = Map.copyOf(defaults);

            PaletteBlock air = byState.get(BlockStates.of("minecraft:air"));
            if (air == null) {
                throw new PaletteException("The " + version + " palette has no air");
            }
            this.air = air;
        }

        private Map<String, Schema> schemas() {
            Map<String, Schema> schemas = this.schemas;
            if (schemas == null) {
                synchronized (this) {
                    schemas = this.schemas;
                    if (schemas == null) {
                        this.schemas = schemas = buildSchemas();
                    }
                }
            }
            return schemas;
        }

        private Map<String, Schema> buildSchemas() {
            Map<String, List<PaletteBlock>> byName = new HashMap<>();
            for (PaletteBlock block : byIndex) {
                byName.computeIfAbsent(block.identifier(), name -> new ArrayList<>()).add(block);
            }
            Map<String, Schema> schemas = new HashMap<>(byName.size());
            for (Map.Entry<String, List<PaletteBlock>> entry : byName.entrySet()) {
                Map<String, Set<Object>> values = new HashMap<>();
                for (PaletteBlock block : entry.getValue()) {
                    block.states().forEach((key, value) -> values.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value));
                }
                NbtMap knownDefault = defaults.get(entry.getKey());
                PaletteBlock defaultState = knownDefault == null ? null : byState.get(knownDefault);
                if (defaultState == null) {
                    // Palette dumps do not say which state is the default; the first is the best guess
                    defaultState = entry.getValue().getFirst();
                }
                schemas.put(entry.getKey(), new Schema(List.copyOf(entry.getValue()), Collections.unmodifiableMap(values), defaultState));
            }
            return Collections.unmodifiableMap(schemas);
        }
    }
}
