package org.geysermc.paletter.block;

import org.geysermc.paletter.Origin;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.block.IntIndex;

import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.internal.block.BlockInterner;
import org.geysermc.paletter.internal.block.BlockResolution;
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
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.component.BlockCompiler;
import org.geysermc.paletter.internal.component.WireContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.geysermc.paletter.vanilla.BedrockBlockState;

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
 * @since 1.0.0
 */
public final class BlockRegistry implements DefinitionRegistry<BlockDefinition> {
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
    private final @Nullable BlockFallback defaultFallback;
    private final BlockInterner interner;
    private final Map<NbtMap, BlockResolution> resolutions = new ConcurrentHashMap<>();
    private final @Nullable BlockRegistry parent;
    private volatile @Nullable CanonicalTable canonicalTable;

    private BlockRegistry(Table table, CanonicalBlocks canonical, Map<String, BlockFallback> fallbacks,
                         @Nullable BlockFallback defaultFallback, BlockInterner interner, @Nullable BlockRegistry parent) {
        this.parent = parent;
        this.table = table;
        this.canonical = canonical;
        this.fallbacks = fallbacks;
        this.defaultFallback = defaultFallback;
        this.interner = interner;
    }

    /**
     * Builds the vanilla palette of a version.
     *
     * @param canonical canonical ids of the newest version's states
     * @param interner shares blocks and tables between hashed palettes; one per Paletter
     */
    @Internal
    public static @NonNull BlockRegistry build(@NonNull VanillaData data, @NonNull BlockNetworkIds mode, @NonNull CanonicalBlocks canonical,
                                              @NonNull BlockInterner interner) {
        return new BlockRegistry(new Table(null, data, List.of(), Map.of(), List.of(), mode, interner), canonical, data.fallbacks().blocks(),
            BlockFallback.UNKNOWN, interner, null);
    }

    /**
     * @return a palette sharing all states with this one, with additional fallbacks, or this palette if there are none
     */
    @Internal
    public @NonNull BlockRegistry withFallbacks(@NonNull Map<String, BlockFallback> extraFallbacks) {
        if (extraFallbacks.isEmpty()) {
            return this;
        }
        Map<String, BlockFallback> merged = new HashMap<>(fallbacks);
        merged.putAll(extraFallbacks);
        return new BlockRegistry(table, canonical, Map.copyOf(merged), defaultFallback, interner, parent);
    }

    /**
     * @param fallback what canonical states without a block or fallback of their own use, or null for air
     * @return a palette sharing all states with this one, with this default fallback, or this palette if it has it
     */
    @Internal
    public @NonNull BlockRegistry withDefaultFallback(@Nullable BlockFallback fallback) {
        if (java.util.Objects.equals(fallback, defaultFallback)) {
            return this;
        }
        return new BlockRegistry(table, canonical, fallbacks, fallback, interner, parent);
    }

    /**
     * @return what canonical states without a block or fallback of their own use, or null if they are sent as air
     */
    @Internal
    public @Nullable BlockFallback defaultFallback() {
        return defaultFallback;
    }

    /**
     * @param key what adds the blocks, typically the layer; canonical ids of custom blocks are cached per key, so
     *            every version compiling the same layer stack shares them
     * @param report receives what the version cannot express of the blocks
     * @param source the layer id, for the report
     * @return a palette with these custom blocks added, keeping this palette's fallbacks, or this palette if there are none
     */
    @Internal
    public @NonNull BlockRegistry withCustomBlocks(@NonNull VanillaData data, @NonNull Object key, @NonNull List<CustomBlock> additional,
                                                  @NonNull PaletteReport report, @NonNull String source) {
        if (additional.isEmpty()) {
            return this;
        }
        List<CustomBlock> all = new ArrayList<>(table.customBlocks);
        all.addAll(additional);
        // BDS numbers custom blocks by identifier in descending order, whatever order they were registered in (verified
        // on 1.26.63.1), so ids of the blocks already here can change: encode all of them again
        List<String> byIdentifier = new ArrayList<>(all.size());
        all.forEach(block -> byIdentifier.add(block.identifier()));
        byIdentifier.sort(java.util.Comparator.reverseOrder());
        Map<String, Integer> blockIds = new HashMap<>();
        for (int i = 0; i < byIdentifier.size(); i++) {
            blockIds.put(byIdentifier.get(i), CUSTOM_BLOCK_ID_START + i);
        }
        List<BlockPropertyData> encoded = new ArrayList<>(all.size());
        WireContext context = WireContext.of(data, report, source);
        // The blocks already here were reported by their own layers: encode them again into a report nobody reads
        WireContext earlier = WireContext.of(data, new PaletteReport(), source);
        for (int i = 0; i < all.size(); i++) {
            CustomBlock block = all.get(i);
            WireContext blockContext = i < table.customBlocks.size() ? earlier : context;
            encoded.add(new BlockPropertyData(block.identifier(), BlockCompiler.propertyData(block, blockIds.get(block.identifier()), blockContext)));
        }
        // The property list BDS sends follows its block ids too
        encoded.sort(java.util.Comparator.comparing(BlockPropertyData::getName).reversed());
        Map<String, Origin> origins = new HashMap<>(table.origins);
        additional.forEach(block -> origins.put(block.identifier(), Origin.custom(source)));
        // Hashed ids do not depend on the other states: only the added states get a table, over this one. Sequential ids
        // are positions, which the client re-sorts as a whole, so that table is rebuilt
        boolean patch = table.mode == BlockNetworkIds.HASHED;
        Table extended = new Table(patch ? table : null, data, all, Map.copyOf(origins), encoded, table.mode, interner);
        return new BlockRegistry(extended, canonical.extend(key, additional), fallbacks, defaultFallback, interner, patch ? this : null);
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
@Internal
    public @NonNull CanonicalBlocks canonical() {
        return canonical;
    }

    /**
     * @return the amount of block states
     */
    public int size() {
        return table.size();
    }

    public @Nullable RegisteredBlockState byRuntimeId(int runtimeId) {
        int index = indexOfRuntimeId(runtimeId);
        return index == -1 ? null : table.byIndex(index);
    }

    /**
     * @param index a dense position, see {@link #indexOf}
     * @throws IndexOutOfBoundsException if there is no such position
     */
    public @NonNull RegisteredBlockState byIndex(int index) {
        return table.byIndex(index);
    }

    /**
     * @return the dense position of a block in this palette, {@code 0} to {@code size() - 1} (equal to the runtime id
     * in {@link BlockNetworkIds#SEQUENTIAL} palettes), or -1 if it is not registered here
     */
    public int indexOf(@NonNull BlockDefinition definition) {
        int index = indexOfRuntimeId(definition.getRuntimeId());
        return index != -1 && table.byIndex(index) == definition ? index : -1;
    }

    private int indexOfRuntimeId(int runtimeId) {
        return table.indexOfRuntimeId(runtimeId);
    }

    @Override
    public @Nullable BlockDefinition getDefinition(int runtimeId) {
        return byRuntimeId(runtimeId);
    }

    @Override
    public boolean isRegistered(@NonNull BlockDefinition definition) {
        return definition instanceof RegisteredBlockState && indexOf(definition) != -1;
    }

    // ---- Canonical ids ----

    /**
     * The block to send for a canonical id: the exact state, else the state resolved for this version (see
     * {@link #resolve(NbtMap)}), else air. An array lookup; the table is built on first use and shared by every
     * palette using this block palette.
     *
     * @throws IndexOutOfBoundsException if the id is not one of {@link #canonical()}
     */
@Internal
    public @NonNull RegisteredBlockState block(int canonical) {
        return canonicalTable().blocks[canonical >>> PAGE_BITS][canonical & PAGE_MASK];
    }

    /**
     * Like {@link #block(int)}, but only the runtime id. An array lookup.
     */
@Internal
    public int runtimeId(int canonical) {
        return canonicalTable().runtimeIds[canonical >>> PAGE_BITS][canonical & PAGE_MASK];
    }

    /**
     * Maps a block back to a canonical id: the canonical id of its exact state, else the first canonical id that
     * resolves to it (e.g. a state of an older version that the newest version added a state value to).
     *
     * @return the canonical id, or -1 for null, a definition of another palette, or a state nothing resolves to
     */
@Internal
    public int canonical(@Nullable BlockDefinition definition) {
        if (definition == null) {
            return -1;
        }
        return canonicalOfRuntimeId(definition.getRuntimeId());
    }

    /**
     * Like {@link #canonical(BlockDefinition)}, by runtime id.
     */
@Internal
    public int canonicalOfRuntimeId(int runtimeId) {
        int index = indexOfRuntimeId(runtimeId);
        return index == -1 ? -1 : canonicalTable().reverse[index];
    }

    /**
     * @return the canonical states that have no block (or fallback) of their own in this version and use the default
     *         fallback ({@link #defaultFallback()}), or air without one
     */
@Internal
    public @NonNull List<NbtMap> missingCanonical() {
        return canonicalTable().missing;
    }

    /**
     * Builds the canonical tables now instead of on first use. Called by the palette compiler.
     */
@Internal
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
        RegisteredBlockState[][] blocks = new RegisteredBlockState[pageCount][];
        int[][] runtimeIds = new int[pageCount][];
        int[] reverse = new int[table.size()];
        Set<NbtMap> missing = new LinkedHashSet<>();
        int firstPage = 0;
        int firstIndex = 0;
        // A patch over a registry with the same fallbacks resolves the canonical ids below the parent's size the same
        // way, so it reuses the parent's full pages; only the page the new ids start in, and later ones, are built
        BlockRegistry base = parent;
        if (base != null && base.fallbacks.equals(fallbacks) && java.util.Objects.equals(base.defaultFallback, defaultFallback)
            && base.canonical.size() <= size) {
            CanonicalTable parentTable = base.canonicalTable();
            firstPage = base.canonical.size() >>> PAGE_BITS;
            System.arraycopy(parentTable.blocks, 0, blocks, 0, firstPage);
            System.arraycopy(parentTable.runtimeIds, 0, runtimeIds, 0, firstPage);
            firstIndex = table.parentSize;
            System.arraycopy(parentTable.reverse, 0, reverse, 0, firstIndex);
            missing.addAll(parentTable.missing);
        }
        for (int i = firstIndex; i < reverse.length; i++) {
            reverse[i] = canonical.index(table.byIndex(i).getState());
        }
        boolean share = table.mode == BlockNetworkIds.HASHED;
        for (int page = firstPage; page < pageCount; page++) {
            int start = page << PAGE_BITS;
            int length = Math.min(PAGE_SIZE, size - start);
            RegisteredBlockState[] pageBlocks = new RegisteredBlockState[length];
            int[] pageIds = new int[length];
            boolean vanillaOnly = true;
            for (int j = 0; j < length; j++) {
                int id = start + j;
                NbtMap state = canonical.state(id);
                RegisteredBlockState block = resolve(state).block();
                if (block == null) {
                    RegisteredBlockState fallback = defaultBlock();
                    block = fallback != null ? fallback : table.air;
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

    private record CanonicalTable(RegisteredBlockState[][] blocks, int[][] runtimeIds, int[] reverse, List<NbtMap> missing) {
    }

    // ---- States ----

    /**
     * @param state a block state as {@code {name, states}}
     * @return the definition of exactly this state, or null
     */
    public @Nullable RegisteredBlockState exact(@NonNull NbtMap state) {
        return table.byState(BlockStates.normalize(state));
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
    @Internal
    public @NonNull BlockResolution resolve(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        BlockFallback forced = fallbacks.get(BlockStates.name(normalized));
        RegisteredBlockState exact = forced != null && forced.force() ? null : table.byState(normalized);
        if (exact != null) {
            return new BlockResolution(normalized, exact, BlockResolution.Kind.EXACT);
        }
        return resolutions.computeIfAbsent(normalized, this::adapt);
    }

    private BlockResolution adapt(NbtMap state) {
        String name = BlockStates.name(state);
        BlockResolution.Kind kind = BlockResolution.Kind.ADAPTED;
        BedrockBlockState exactState = null;
        BlockFallback forced = fallbacks.get(name);
        if (table.schema(name) == null || (forced != null && forced.force())) {
            BlockFallback fallback = forced;
            if (fallback == null || table.schema(fallback.target().identifier()) == null) {
                return new BlockResolution(state, null, BlockResolution.Kind.MISSING);
            }
            name = fallback.target().identifier();
            exactState = fallback.state();
            kind = BlockResolution.Kind.FALLBACK;
        }

        Schema schema = table.schema(name);
        if (exactState != null) {
            RegisteredBlockState exact = table.byState(BlockStates.normalize(exactState.toNbt()));
            return new BlockResolution(state, exact != null ? exact : schema.defaultState, kind);
        }
        NbtMap requested = BlockStates.states(state);
        NbtMapBuilder adapted = NbtMap.builder();
        for (Map.Entry<String, Object> entry : schema.defaultState.states().entrySet()) {
            Object value = requested.get(entry.getKey());
            Set<Object> allowed = schema.values.get(entry.getKey());
            adapted.put(entry.getKey(), value != null && allowed.contains(value) ? value : entry.getValue());
        }
        RegisteredBlockState block = table.byState(BlockStates.of(name, adapted.build()));
        return new BlockResolution(state, block != null ? block : schema.defaultState, kind);
    }

    /**
     * Resolves a list of states at once. States that cannot be resolved at all map to {@code missing}; everything that
     * was not exact is recorded in the report.
     */
    public @NonNull RegisteredBlockState[] table(@NonNull List<NbtMap> states, @NonNull RegisteredBlockState missing, @NonNull PaletteReport report) {
        RegisteredBlockState[] result = new RegisteredBlockState[states.size()];
        Map<BlockResolution.Kind, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < result.length; i++) {
            BlockResolution resolution = resolve(states.get(i));
            if (resolution.block() == null && defaultBlock() != null) {
                resolution = new BlockResolution(resolution.requested(), defaultBlock(), BlockResolution.Kind.DEFAULT);
            }
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
    public @Nullable RegisteredBlockState defaultState(@NonNull String identifier) {
        Schema schema = table.schema(identifier);
        return schema == null ? null : schema.defaultState;
    }

    /** The default state of the default fallback's target, or null if there is none (or it is not in this version). */
    private @Nullable RegisteredBlockState defaultBlock() {
        return defaultFallback == null ? null : defaultState(defaultFallback.target().identifier());
    }

    public boolean contains(@NonNull String identifier) {
        return table.schema(identifier) != null;
    }

    /**
     * @return every state of a block, in palette order
     */
    public @NonNull List<RegisteredBlockState> states(@NonNull String identifier) {
        Schema schema = table.schema(identifier);
        return schema == null ? List.of() : schema.states;
    }

    /**
     * @return the block with this identifier in this palette, or null
     */
    public @Nullable RegisteredBlock block(@NonNull String identifier) {
        return table.block(identifier);
    }

    /**
     * @return the block a state of this palette belongs to
     * @throws IllegalArgumentException if the state is not of this palette
     */
    public @NonNull RegisteredBlock block(@NonNull RegisteredBlockState state) {
        RegisteredBlock block = table.block(state.identifier());
        if (block == null || exact(state.getState()) != state) {
            throw new IllegalArgumentException(state + " is not a state of this palette");
        }
        return block;
    }

    /**
     * @return every block of this palette: vanilla and custom
     */
    public @NonNull Collection<RegisteredBlock> blocks() {
        Map<String, RegisteredBlock> blocks = new LinkedHashMap<>();
        table.collectBlocks(blocks);
        return List.copyOf(blocks.values());
    }

    /**
     * @return the amount of states in this palette's own table: all of them, except in a patch over another palette
     *         (hashed palettes with added custom blocks), which holds only the added ones
     */
    @Internal
    int ownStateCount() {
        return table.own.length;
    }

    public @NonNull RegisteredBlockState air() {
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
    public boolean sharesStatesWith(@NonNull BlockRegistry other) {
        return table == other.table;
    }

    @Override
    public String toString() {
        return "BlockRegistry{" + version() + ", " + table.mode + ", " + size() + " states, " + table.customBlocks.size() + " custom blocks}";
    }

    /**
     * FNV-1 64 bit hash (multiply, then xor: not FNV-1a) of a block identifier; the client orders its palette by it once custom blocks exist.
     */
    public static long nameHash(@NonNull String identifier) {
        long hash = FNV1_64_OFFSET_BASIS;
        for (byte b : identifier.getBytes(StandardCharsets.UTF_8)) {
            hash *= FNV1_64_PRIME;
            hash ^= b;
        }
        return hash;
    }

    private record HashedState(long hash, NbtMap state) {
    }

    private record Schema(List<RegisteredBlockState> states, Map<String, Set<Object>> values, RegisteredBlockState defaultState) {
    }

    /**
     * Immutable state table, shared between all palettes deriving from the same set of custom blocks. A table over a
     * parent holds only the states of the custom blocks added to it, and delegates the rest.
     */
    private static final class Table {
        private final @Nullable Table parent;
        private final int parentSize;
        private final DataVersion version;
        private final BlockNetworkIds mode;
        /** Own states: all of them without a parent. */
        private final RegisteredBlockState[] own;
        private final @Nullable IntIndex ownByHash;
        private final Map<NbtMap, RegisteredBlockState> ownByState;
        private final int @Nullable [] vanillaToRuntime;
        private final List<BlockPropertyData> blockProperties;
        /** Every custom block, including the parent's. */
        private final List<CustomBlock> customBlocks;
        private final Map<String, Origin> origins;
        private final Map<String, RegisteredBlock> ownBlocks;
        private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
        private final RegisteredBlockState air;

        private Table(@Nullable Table parent, VanillaData data, List<CustomBlock> customBlocks, Map<String, Origin> origins,
                      List<BlockPropertyData> customProperties, BlockNetworkIds mode, BlockInterner interner) {
            this.parent = parent;
            this.parentSize = parent == null ? 0 : parent.size();
            this.version = data.version();
            this.mode = mode;
            this.customBlocks = List.copyOf(customBlocks);
            this.origins = origins;
            List<CustomBlock> ownCustom = parent == null ? this.customBlocks : this.customBlocks.subList(parent.customBlocks.size(), this.customBlocks.size());

            List<NbtMap> states = new ArrayList<>(parent == null ? data.blockStates() : List.of());
            Set<NbtMap> customStates = new LinkedHashSet<>();
            Set<String> vanillaNames = new LinkedHashSet<>();
            if (parent == null) {
                for (NbtMap state : data.blockStates()) {
                    vanillaNames.add(BlockStates.name(state));
                }
            }
            Map<String, CustomBlock> customByName = new HashMap<>();
            for (CustomBlock block : ownCustom) {
                if (vanillaNames.contains(block.identifier()) || customByName.containsKey(block.identifier())
                    || parent != null && parent.block(block.identifier()) != null) {
                    throw new PaletteException("Custom block " + block.identifier() + " is registered twice, or collides with a vanilla block");
                }
                customStates.addAll(block.states());
                customByName.put(block.identifier(), block);
            }

            if (!customStates.isEmpty()) {
                states.addAll(customStates);
                // The client sorts the whole palette by name hash as soon as custom blocks exist. The sort is stable,
                // so states of one block keep their order. Hashed ids do not depend on it, but keep one order anyway
                Map<String, Long> hashes = new HashMap<>();
                List<HashedState> hashed = new ArrayList<>(states.size());
                for (NbtMap state : states) {
                    hashed.add(new HashedState(hashes.computeIfAbsent(BlockStates.name(state), BlockRegistry::nameHash), state));
                }
                hashed.sort((a, b) -> Long.compareUnsigned(a.hash(), b.hash()));
                for (int i = 0; i < states.size(); i++) {
                    states.set(i, hashed.get(i).state());
                }
            }

            this.own = new RegisteredBlockState[states.size()];
            Object2ObjectOpenHashMap<NbtMap, RegisteredBlockState> byState = new Object2ObjectOpenHashMap<>(states.size());
            IntIndex byHash = mode == BlockNetworkIds.HASHED ? new IntIndex(states.size()) : null;
            for (int i = 0; i < states.size(); i++) {
                NbtMap state = states.get(i);
                boolean custom = customStates.contains(state);
                RegisteredBlockState block;
                if (byHash == null) {
                    block = new RegisteredBlockState(i, state, custom);
                } else if (custom) {
                    block = new RegisteredBlockState(BlockNetworkIds.hash(state), state, true);
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
                            + own[collision].getState() + " and " + state + " both hash to " + block.getRuntimeId());
                    }
                    int inParent = parent == null ? -1 : parent.indexOfRuntimeId(block.getRuntimeId());
                    if (inParent != -1) {
                        throw new PaletteException("Block network id hash collision in the " + version + " palette: "
                            + parent.byIndex(inParent).getState() + " and " + state + " both hash to " + block.getRuntimeId());
                    }
                }
                own[i] = block;
            }
            this.ownByState = byState;
            this.ownByHash = byHash;

            if (customStates.isEmpty() || mode == BlockNetworkIds.HASHED) {
                this.vanillaToRuntime = null;
            } else {
                int[] remap = new int[data.blockStates().size()];
                for (int i = 0; i < remap.length; i++) {
                    remap[i] = byState.get(data.blockStates().get(i)).getRuntimeId();
                }
                this.vanillaToRuntime = remap;
            }

            List<BlockPropertyData> properties = new ArrayList<>(data.dataDrivenBlocks());
            properties.addAll(customProperties);
            this.blockProperties = List.copyOf(properties);

            // Group the states into blocks, in palette order
            Map<String, List<RegisteredBlockState>> byName = new LinkedHashMap<>();
            for (RegisteredBlockState state : own) {
                byName.computeIfAbsent(state.identifier(), name -> new ArrayList<>()).add(state);
            }
            Map<String, NbtMap> propertyData = new HashMap<>();
            for (BlockPropertyData entry : properties) {
                propertyData.put(entry.getName(), entry.getProperties());
            }
            Map<String, RegisteredBlock> blocks = new LinkedHashMap<>(byName.size());
            for (Map.Entry<String, List<RegisteredBlockState>> entry : byName.entrySet()) {
                String name = entry.getKey();
                CustomBlock custom = customByName.get(name);
                if (custom != null) {
                    blocks.put(name, new RegisteredBlock(name, origins.get(name), entry.getValue(), custom, propertyData.get(name), custom.defaultState()));
                } else if (byHash != null) {
                    blocks.put(name, interner.vanillaType(name, entry.getValue(), propertyData.get(name), data.blockDefaults().get(name)));
                } else {
                    blocks.put(name, new RegisteredBlock(name, Origin.VANILLA, entry.getValue(), null, propertyData.get(name), data.blockDefaults().get(name)));
                }
            }
            if (parent != null) {
                // The numeric block ids inside the property data of the parent's custom blocks change when blocks are
                // added: the patch has blocks of its own for them, over the same states
                for (CustomBlock custom : this.customBlocks.subList(0, parent.customBlocks.size())) {
                    RegisteredBlock earlier = parent.block(custom.identifier());
                    blocks.put(custom.identifier(), new RegisteredBlock(custom.identifier(), origins.get(custom.identifier()),
                        earlier.states(), custom, propertyData.get(custom.identifier()), custom.defaultState()));
                }
            }
            this.ownBlocks = Collections.unmodifiableMap(blocks);

            RegisteredBlockState air = parent != null ? parent.air : byState.get(BlockStates.of("minecraft:air"));
            if (air == null) {
                throw new PaletteException("The " + version + " palette has no air");
            }
            this.air = air;
        }

        int size() {
            return parentSize + own.length;
        }

        RegisteredBlockState byIndex(int index) {
            return index < parentSize ? parent.byIndex(index) : own[index - parentSize];
        }

        int indexOfRuntimeId(int runtimeId) {
            if (ownByHash == null) {
                return runtimeId >= 0 && runtimeId < own.length ? runtimeId : -1;
            }
            int index = ownByHash.get(runtimeId);
            if (index != -1) {
                return parentSize + index;
            }
            return parent == null ? -1 : parent.indexOfRuntimeId(runtimeId);
        }

        @Nullable RegisteredBlockState byState(NbtMap state) {
            RegisteredBlockState found = ownByState.get(state);
            return found != null || parent == null ? found : parent.byState(state);
        }

        @Nullable RegisteredBlock block(String identifier) {
            RegisteredBlock found = ownBlocks.get(identifier);
            return found != null || parent == null ? found : parent.block(identifier);
        }

        /** Blocks of this table replace those of the parent with the same identifier. */
        void collectBlocks(Map<String, RegisteredBlock> into) {
            if (parent != null) {
                parent.collectBlocks(into);
            }
            into.putAll(ownBlocks);
        }

        @Nullable Schema schema(String identifier) {
            Schema schema = schemas.get(identifier);
            if (schema != null) {
                return schema;
            }
            RegisteredBlock block = ownBlocks.get(identifier);
            if (block == null) {
                return parent == null ? null : parent.schema(identifier);
            }
            Map<String, Set<Object>> values = new HashMap<>();
            for (RegisteredBlockState state : block.states()) {
                state.states().forEach((key, value) -> values.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value));
            }
            schema = new Schema(block.states(), Collections.unmodifiableMap(values), block.defaultState());
            Schema existing = schemas.putIfAbsent(identifier, schema);
            return existing != null ? existing : schema;
        }
    }
}
