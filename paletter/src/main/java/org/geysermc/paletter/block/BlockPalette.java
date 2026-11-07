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
import org.geysermc.paletter.attach.AttachmentKey;
import org.geysermc.paletter.attach.AttachmentStore;
import org.geysermc.paletter.attach.Attachments;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.VanillaData;

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
 * Consumers map against the newest Bedrock version and call {@link #resolve(NbtMap)}; differences to older
 * versions (renamed or missing blocks, added or removed states) are handled here.
 */
public final class BlockPalette implements DefinitionRegistry<BlockDefinition> {
    /**
     * Numeric ids of custom blocks start here, to stay clear of vanilla block ids.
     */
    public static final int CUSTOM_BLOCK_ID_START = 10000;

    private static final long FNV1_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1_64_PRIME = 1099511628211L;

    private final Table table;
    private final Map<String, BlockFallback> fallbacks;
    private final AttachmentStore<NbtMap> attachments;
    private final Map<NbtMap, BlockResolution> resolutions = new ConcurrentHashMap<>();

    private BlockPalette(Table table, Map<String, BlockFallback> fallbacks, AttachmentStore<NbtMap> attachments) {
        this.table = table;
        this.fallbacks = fallbacks;
        this.attachments = attachments;
    }

    /**
     * Builds the palette of a version with the given custom blocks.
     */
    public static @NonNull BlockPalette build(@NonNull VanillaData data, @NonNull List<CustomBlock> customBlocks) {
        return new BlockPalette(new Table(data, customBlocks), data.fallbacks().blocks(), new AttachmentStore<>());
    }

    /**
     * @return a palette sharing all states with this one, with additional fallbacks and the given attachment store.
     * This palette is not changed
     */
    public @NonNull BlockPalette derive(@NonNull Map<String, BlockFallback> extraFallbacks, @NonNull AttachmentStore<NbtMap> newAttachments) {
        Map<String, BlockFallback> merged = fallbacks;
        if (!extraFallbacks.isEmpty()) {
            merged = new HashMap<>(fallbacks);
            merged.putAll(extraFallbacks);
            merged = Map.copyOf(merged);
        }
        return new BlockPalette(table, merged, newAttachments);
    }

    /**
     * @return a palette with these custom blocks added, keeping this palette's fallbacks and attachments
     */
    public @NonNull BlockPalette withCustomBlocks(@NonNull VanillaData data, @NonNull List<CustomBlock> additional) {
        if (additional.isEmpty()) {
            return this;
        }
        List<CustomBlock> all = new ArrayList<>(table.customBlocks);
        all.addAll(additional);
        return new BlockPalette(new Table(data, all), fallbacks, attachments.copy());
    }

    public @NonNull DataVersion version() {
        return table.version;
    }

    /**
     * @return the amount of block states
     */
    public int size() {
        return table.byRuntimeId.length;
    }

    public @Nullable PaletteBlock byRuntimeId(int runtimeId) {
        return runtimeId >= 0 && runtimeId < table.byRuntimeId.length ? table.byRuntimeId[runtimeId] : null;
    }

    @Override
    public @Nullable BlockDefinition getDefinition(int runtimeId) {
        return byRuntimeId(runtimeId);
    }

    @Override
    public boolean isRegistered(@NonNull BlockDefinition definition) {
        return definition instanceof PaletteBlock block && byRuntimeId(block.getRuntimeId()) == block;
    }

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
     * Results are cached.
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
     * Resolves a list of states at once, e.g. a consumer's Java to Bedrock block mapping. States that cannot be
     * resolved at all map to {@code missing}; everything that was not exact is recorded in the report.
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
     * @return every state of a block, in runtime id order
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
     * ids (e.g. cached chunks) can be translated with this.
     *
     * @param vanillaRuntimeId the runtime id in the plain vanilla palette
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

    public @NonNull Attachments attachments(@NonNull BlockDefinition definition) {
        return definition instanceof PaletteBlock block ? attachments.get(block.getState()) : Attachments.EMPTY;
    }

    public <T> @Nullable T attachment(@NonNull BlockDefinition definition, @NonNull AttachmentKey<T> key) {
        return attachments(definition).get(key);
    }

    /**
     * @return the attachment store; only for building derived palettes
     */
    public @NonNull AttachmentStore<NbtMap> attachmentStore() {
        return attachments;
    }

    public @NonNull Map<String, BlockFallback> fallbacks() {
        return fallbacks;
    }

    @Override
    public String toString() {
        return "BlockPalette{" + version() + ", " + size() + " states, " + table.customBlocks.size() + " custom blocks}";
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
        private final PaletteBlock[] byRuntimeId;
        private final Map<NbtMap, PaletteBlock> byState;
        private final int @Nullable [] vanillaToRuntime;
        private final List<BlockPropertyData> blockProperties;
        private final List<CustomBlock> customBlocks;
        private final Map<String, NbtMap> defaults;
        private final PaletteBlock air;
        private volatile Map<String, Schema> schemas;

        private Table(VanillaData data, List<CustomBlock> customBlocks) {
            this.version = data.version();
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
                // so states of one block keep their order
                states.sort((a, b) -> Long.compareUnsigned(nameHash(BlockStates.name(a)), nameHash(BlockStates.name(b))));
            }

            this.byRuntimeId = new PaletteBlock[states.size()];
            Object2ObjectOpenHashMap<NbtMap, PaletteBlock> byState = new Object2ObjectOpenHashMap<>(states.size());
            for (int i = 0; i < states.size(); i++) {
                NbtMap state = states.get(i);
                PaletteBlock block = new PaletteBlock(i, state, customStates.contains(state));
                if (byState.put(state, block) != null) {
                    throw new PaletteException("Duplicate block state in " + version + " palette: " + state);
                }
                byRuntimeId[i] = block;
            }
            this.byState = byState;

            if (customStates.isEmpty()) {
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
            for (PaletteBlock block : byRuntimeId) {
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
