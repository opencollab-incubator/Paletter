package org.geysermc.paletter.block;

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
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.internal.encode.BlockEncoder;
import org.geysermc.paletter.internal.encode.EncodeContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * has a dense position, {@link #indexOf}, in both modes; in hashed palettes vanilla definitions are shared between versions.
 *
 * @since 2.0.0
 */
public final class BlockPalette implements DefinitionRegistry<BlockDefinition> {
    /**
     * Numeric ids of custom blocks start here, to stay clear of vanilla block ids.
     */
    public static final int CUSTOM_BLOCK_ID_START = 10000;

    private static final long FNV1_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1_64_PRIME = 1099511628211L;

    /**
     * Canonical tables are split in pages of this many entries, so pages with equal content can be shared.
     */
    private static final int PAGE_BITS = 10;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    private final Table table;
    private final CanonicalBlocks canonical;
    private final Map<String, BlockFallback> fallbacks;
    private final BlockInterner interner;
    private final Map<NbtMap, BlockResolution> resolutions = new ConcurrentHashMap<>();
    private volatile @Nullable CanonicalTable canonicalTable;

    private BlockPalette(Table table, CanonicalBlocks canonical, Map<String, BlockFallback> fallbacks, BlockInterner interner) {
        this.table = table;
        this.canonical = canonical;
        this.fallbacks = fallbacks;
        this.interner = interner;
    }

    /**
     * Builds the vanilla palette of a version.
     *
     * @param canonical canonical ids of the newest version's states
     * @param interner shares blocks and tables between hashed palettes; one per Paletter
     */
    @Internal
    public static @NonNull BlockPalette build(@NonNull VanillaData data, @NonNull BlockNetworkIds mode, @NonNull CanonicalBlocks canonical,
                                              @NonNull BlockInterner interner) {
        return new BlockPalette(new Table(data, List.of(), List.of(), mode, interner), canonical, data.fallbacks().blocks(), interner);
    }

    /**
     * @return a palette sharing all states with this one, with additional fallbacks, or this palette if there are none
     */
    @Internal
    public @NonNull BlockPalette withFallbacks(@NonNull Map<String, BlockFallback> extraFallbacks) {
        if (extraFallbacks.isEmpty()) {
            return this;
        }
        Map<String, BlockFallback> merged = new HashMap<>(fallbacks);
        merged.putAll(extraFallbacks);
        return new BlockPalette(table, canonical, Map.copyOf(merged), interner);
    }

    /**
     * @param key what adds the blocks, typically the layer; canonical ids of custom blocks are cached per key, so
     *            every version compiling the same layer stack shares them
     * @param report receives what the version cannot express of the blocks
     * @param source the layer id, for the report
     * @return a palette with these custom blocks added, keeping this palette's fallbacks, or this palette if there are none
     */
    @Internal
    public @NonNull BlockPalette withCustomBlocks(@NonNull VanillaData data, @NonNull Object key, @NonNull List<CustomBlock> additional,
                                                  @NonNull PaletteReport report, @NonNull String source) {
        if (additional.isEmpty()) {
            return this;
        }
        List<CustomBlock> all = new ArrayList<>(table.customBlocks);
        all.addAll(additional);
        // Block ids are in load order, so the blocks already here keep their encoded data
        List<BlockPropertyData> encoded = new ArrayList<>(table.blockProperties.subList(data.dataDrivenBlocks().size(), table.blockProperties.size()));
        EncodeContext context = EncodeContext.of(data.version(), report, source);
        int blockId = CUSTOM_BLOCK_ID_START + table.customBlocks.size();
        for (CustomBlock block : additional) {
            encoded.add(new BlockPropertyData(block.identifier(), BlockEncoder.propertyData(block, blockId++, context)));
        }
        Table extended = new Table(data, all, encoded, table.mode, interner);
        return new BlockPalette(extended, canonical.extend(key, additional), fallbacks, interner);
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
        int index = indexOfRuntimeId(runtimeId);
        return index == -1 ? null : table.byIndex[index];
    }

    /**
     * @param index a dense position, see {@link #indexOf}
     * @throws IndexOutOfBoundsException if there is no such position
     */
    public @NonNull PaletteBlock byIndex(int index) {
        return table.byIndex[index];
    }

    /**
     * @return the dense position of a block in this palette, {@code 0} to {@code size() - 1} (equal to the runtime id
     * in {@link BlockNetworkIds#SEQUENTIAL} palettes), or -1 if it is not registered here
     */
    public int indexOf(@NonNull BlockDefinition definition) {
        int index = indexOfRuntimeId(definition.getRuntimeId());
        return index != -1 && table.byIndex[index] == definition ? index : -1;
    }

    private int indexOfRuntimeId(int runtimeId) {
        IntIndex byHash = table.byHash;
        if (byHash != null) {
            return byHash.get(runtimeId);
        }
        return runtimeId >= 0 && runtimeId < table.byIndex.length ? runtimeId : -1;
    }

    @Override
    public @Nullable BlockDefinition getDefinition(int runtimeId) {
        return byRuntimeId(runtimeId);
    }

    @Override
    public boolean isRegistered(@NonNull BlockDefinition definition) {
        return definition instanceof PaletteBlock && indexOf(definition) != -1;
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
        return canonicalTable().blocks[canonical >>> PAGE_BITS][canonical & PAGE_MASK];
    }

    /**
     * Like {@link #block(int)}, but only the runtime id. An array lookup.
     */
    public int runtimeId(int canonical) {
        return canonicalTable().runtimeIds[canonical >>> PAGE_BITS][canonical & PAGE_MASK];
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
        return canonicalOfRuntimeId(definition.getRuntimeId());
    }

    /**
     * Like {@link #canonical(BlockDefinition)}, by runtime id.
     */
    public int canonicalOfRuntimeId(int runtimeId) {
        int index = indexOfRuntimeId(runtimeId);
        return index == -1 ? -1 : canonicalTable().reverse[index];
    }

    /**
     * @return the canonical states that have no block of their own in this version and use air
     */
    public @NonNull List<NbtMap> missingCanonical() {
        return canonicalTable().missing;
    }

    /**
     * Builds the canonical tables now instead of on first use. Called by the palette compiler.
     */
    public void prepareCanonical() {
        canonicalTable();
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
        int pageCount = (size + PAGE_SIZE - 1) >>> PAGE_BITS;
        PaletteBlock[][] blocks = new PaletteBlock[pageCount][];
        int[][] runtimeIds = new int[pageCount][];
        int[] reverse = new int[table.byIndex.length];
        for (int i = 0; i < reverse.length; i++) {
            reverse[i] = canonical.index(table.byIndex[i].getState());
        }
        List<NbtMap> missing = new ArrayList<>();
        boolean share = table.mode == BlockNetworkIds.HASHED;
        for (int page = 0; page < pageCount; page++) {
            int start = page << PAGE_BITS;
            int length = Math.min(PAGE_SIZE, size - start);
            PaletteBlock[] pageBlocks = new PaletteBlock[length];
            int[] pageIds = new int[length];
            boolean vanillaOnly = true;
            for (int j = 0; j < length; j++) {
                int id = start + j;
                NbtMap state = canonical.state(id);
                PaletteBlock block = resolve(state).block();
                if (block == null) {
                    block = table.air;
                    missing.add(state);
                }
                pageBlocks[j] = block;
                pageIds[j] = block.getRuntimeId();
                vanillaOnly &= !block.isCustom();
                int index = indexOfRuntimeId(block.getRuntimeId());
                if (reverse[index] == -1) {
                    reverse[index] = id;
                }
            }
            // In hashed palettes vanilla blocks are shared between versions, so most pages are equal between them
            if (share && vanillaOnly) {
                pageBlocks = interner.intern(pageBlocks);
                pageIds = interner.intern(pageIds);
            }
            blocks[page] = pageBlocks;
            runtimeIds[page] = pageIds;
        }
        return new CanonicalTable(blocks, runtimeIds, reverse, List.copyOf(missing));
    }

    private record CanonicalTable(PaletteBlock[][] blocks, int[][] runtimeIds, int[] reverse, List<NbtMap> missing) {
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
        private final @Nullable IntIndex byHash;
        private final Map<NbtMap, PaletteBlock> byState;
        private final int @Nullable [] vanillaToRuntime;
        private final List<BlockPropertyData> blockProperties;
        private final List<CustomBlock> customBlocks;
        private final Map<String, NbtMap> defaults;
        private final PaletteBlock air;
        private volatile Map<String, Schema> schemas;

        private Table(VanillaData data, List<CustomBlock> customBlocks, List<BlockPropertyData> customProperties, BlockNetworkIds mode,
                      BlockInterner interner) {
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
            for (CustomBlock block : this.customBlocks) {
                if (vanillaNames.contains(block.identifier()) || customDefaults.containsKey(block.identifier())) {
                    throw new PaletteException("Custom block " + block.identifier() + " is registered twice, or collides with a vanilla block");
                }
                customStates.addAll(block.states());
                customDefaults.put(block.identifier(), block.defaultState());
            }
            properties.addAll(customProperties);

            if (!customStates.isEmpty()) {
                states.addAll(customStates);
                // The client sorts the whole palette by name hash as soon as custom blocks exist. The sort is stable,
                // so states of one block keep their order. Hashed ids do not depend on it, but keep one order anyway
                states.sort((a, b) -> Long.compareUnsigned(nameHash(BlockStates.name(a)), nameHash(BlockStates.name(b))));
            }

            this.byIndex = new PaletteBlock[states.size()];
            Object2ObjectOpenHashMap<NbtMap, PaletteBlock> byState = new Object2ObjectOpenHashMap<>(states.size());
            IntIndex byHash = mode == BlockNetworkIds.HASHED ? new IntIndex(states.size()) : null;
            for (int i = 0; i < states.size(); i++) {
                NbtMap state = states.get(i);
                boolean custom = customStates.contains(state);
                PaletteBlock block;
                if (byHash == null) {
                    block = new PaletteBlock(i, state, custom);
                } else if (custom) {
                    block = new PaletteBlock(BlockNetworkIds.hash(state), state, true);
                } else {
                    // A vanilla state has the same hash in every version: share the definition
                    block = interner.vanillaBlock(state);
                }
                if (byState.put(state, block) != null) {
                    throw new PaletteException("Duplicate block state in " + version + " palette: " + state);
                }
                if (byHash != null) {
                    int collision = byHash.put(block.getRuntimeId(), i);
                    if (collision != -1) {
                        throw new PaletteException("Block network id hash collision in the " + version + " palette: "
                            + byIndex[collision].getState() + " and " + state + " both hash to " + block.getRuntimeId());
                    }
                }
                byIndex[i] = block;
            }
            this.byState = byState;
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
