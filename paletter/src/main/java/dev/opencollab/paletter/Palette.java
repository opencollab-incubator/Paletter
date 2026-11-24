package dev.opencollab.paletter;

import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.internal.behaviour.Behaviour;
import dev.opencollab.paletter.internal.behaviour.VanillaBehaviour;
import dev.opencollab.paletter.internal.Canonicals;
import dev.opencollab.paletter.internal.Lazy;
import dev.opencollab.paletter.internal.Forwarding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import dev.opencollab.paletter.block.VoxelShapes;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.internal.block.BlockRegistry;
import dev.opencollab.paletter.internal.block.BlockResolution;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.block.ResolvedBlock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.creative.CreativeContent;
import dev.opencollab.paletter.creative.CreativeGroup;
import dev.opencollab.paletter.creative.CreativeLayout;
import dev.opencollab.paletter.creative.CreativeNode;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.creative.ItemMatcher;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.item.ItemRegistry;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.internal.block.RegisteredBlock;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.ItemKey;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Everything a Bedrock client of one version needs to know about items and blocks: vanilla data plus a stack of
 * {@link PaletteLayer}s, compiled. Build one with {@link Paletter}; {@link #extend(PaletteLayer)} adds a layer.
 * <p>
 * Thread safety: a palette is immutable and safe to share between threads and sessions. Its lazily built caches
 * (behaviour, version indexes) and {@link #extend(PaletteLayer)} synchronize internally; no method needs external locking.
 * <p>
 * Null handling: parameters and return values are {@code @NonNull} unless the signature says {@code @Nullable}. Methods
 * that take a key ({@code resolve}, {@code blockDefinition(BedrockBlockState)}, {@code runtimeId}) answer what is sent for
 * it, fallbacks applied, and throw {@link IllegalArgumentException} where nothing can be sent (e.g. a custom block of a
 * layer the palette lacks); their {@code orElse} overloads return the default instead. Lookups by identifier, NBT, state
 * values or runtime id answer what the palette itself has, never a fallback, and return null where nothing matches.
 * Relations ({@code blockOf}, {@code itemOf}, {@code experimentOf}) return null where there is none. Nothing throws for
 * data a client sent.
 * <p>
 * Vocabulary: keys ({@link BedrockBlockState}, {@link BedrockItemVariant}, ...) identify content independent of any version;
 * definitions ({@link RegisteredBlockState}, {@link RegisteredItem}) are what the palette sends to the client; resolutions
 * ({@link ResolvedBlock}, {@link ResolvedItem}) are what a palette makes of a key: the definition, whether a fallback was
 * used, and the behaviour values.
 * <p>
 * Sending it to a client:
 * <pre>{@code
 * palette.applyTo(session.getCodecHelper());     // item and block definitions for the codec
 * palette.applyTo(startGamePacket);              // block properties and the block network id mode
 * session.sendPacket(palette.voxelShapesPacket());   // always, before start game
 * session.sendPacket(palette.itemComponentPacket());
 * session.sendPacket(palette.creativeContentPacket());
 * }</pre>
 * Translating content uses keys, version independent objects ({@link BedrockBlockState}, {@link BedrockItemVariant}):
 * {@link #blockState(NbtMap, Lookup)} and {@link #itemVariant(String, int, Lookup)} find them once, then
 * {@link #runtimeId(BedrockBlockState)} and {@link #blockDefinition(BedrockBlockState)} for blocks, {@link #resolve(ItemKey)}
 * for items: field reads and array lookups that do not allocate. {@link #encode} and {@link #decode} translate whole
 * stacks at the protocol boundary; {@code encode} builds the {@link ItemData} it returns. The ids behind the keys are not
 * API.
 *
 * @since 1.0.0
 */
public final class Palette {
    private static final String[] NO_STRINGS = new String[0];

    private final VanillaData data;
    private final List<PaletteLayer> layers;
    private final BlockRegistry blocks;
    private final ItemRegistry items;
    private final CreativeLayout creativeLayout;
    private final CreativeContent creative;
    private final PaletteReport report;
    private final CreativeOrdering ordering;
    private final VanillaContent.ExperimentGating gating;
    /**
     * The extensions by layer: a weak reference to a compiled one (the extension references its layer, so a strong value
     * would keep the key alive forever), or the future of one being compiled.
     */
    private final Map<PaletteLayer, Object> extensions = new WeakHashMap<>();
    private final Supplier<VanillaBehaviour> behaviourData;
    private final Shapes shapes;
    private volatile Behaviour behaviour;
    /**
     * The resolutions handed out by {@link #resolve(BedrockBlockState)} (by canonical id) and
     * {@link #resolveBlock(int)} (by palette index), made on first use: a lookup allocates nothing. Threads may race to
     * fill a slot and publish equal instances; that is safe because {@link ResolvedBlock}'s fields are final.
     * <p>
     * An extended palette keeps slots only for the canonical ids and block states of its own layer: those of its parent
     * are the parent's, with the same definition, fallback and behaviour (a layer cannot change them), so it asks the
     * parent. The tables start at the parent's sizes.
     */
    private volatile ResolvedBlock[] resolvedByCanonical;
    private volatile ResolvedBlock[] resolvedByIndex;
    /** The resolutions of states of constants the bundled release lacks and this palette too, made on first use. */
    private final Lazy<Map<BedrockBlockState, ResolvedBlock>> unavailableBlocks = new Lazy<>(ConcurrentHashMap::new);
    private final int parentCanonicalSize;
    private final int parentSize;
    /** Whether the newest version, which defines the canonical ids, is this palette's: then every vanilla canonical state is in it. */
    private final boolean newestVersion;
    private final Lazy<CanonicalItems> versionItems;
    private final Lazy<Map<String, NbtMap>> firstStates;
    /** The identifiers the creative layout mentions, see {@link #creativeMentions}; made on first use. */
    private final Lazy<Set<String>> creativeIdentifiers;
    /** The palette this one extends, or is a copy of: its block states are a prefix of this one's. Null for vanilla palettes. */
    private final @Nullable Palette parent;

    Palette(VanillaData data, List<PaletteLayer> layers, BlockRegistry blocks, ItemRegistry items,
            CreativeLayout creativeLayout, CreativeContent creative, PaletteReportBuilder report, CreativeOrdering ordering,
            VanillaContent.ExperimentGating gating, Supplier<VanillaBehaviour> behaviourData, Shapes shapes, @Nullable Palette parent) {
        this.parent = parent;
        this.newestVersion = blocks.canonical().root().version().equals(data.version().toString());
        this.parentCanonicalSize = parent == null ? 0 : parent.blocks.canonical().size();
        this.parentSize = parent == null ? 0 : parent.blocks.size();
        this.behaviourData = behaviourData;
        this.shapes = shapes;
        this.gating = gating;
        this.data = data;
        this.layers = List.copyOf(layers);
        this.blocks = blocks;
        this.items = items;
        this.creativeLayout = creativeLayout;
        this.creative = creative;
        this.report = report.build(data.version());
        this.ordering = ordering;
        this.versionItems = new Lazy<>(() -> parent != null ? parent.versionItems() : CanonicalItems.of(data));
        this.firstStates = new Lazy<>(() -> {
            if (parent != null) {
                return parent.firstStates();
            }
            Map<String, NbtMap> firsts = new HashMap<>();
            for (NbtMap state : data.blockStates()) {
                firsts.putIfAbsent(BlockStates.name(state), state);
            }
            return firsts;
        });
        this.creativeIdentifiers = new Lazy<>(this::collectCreativeIdentifiers);
        // Last: the registry may be asked for the behaviour of this palette from now on
        items.bind(this);
    }

    /**
     * Returns the creative ordering of the Paletter that compiled this palette; extensions use it too.
     *
     * @return the ordering
     */
    CreativeOrdering ordering() {
        return ordering;
    }

    VanillaContent.ExperimentGating gating() {
        return gating;
    }

    /**
     * The voxel shapes of a palette and what they were compiled from.
     *
     * @param vanilla the vanilla shapes of the palette's version
     * @param compiled the shapes of the palette
     */
    record Shapes(@NonNull List<VoxelShapeStore.Entry> vanilla, @NonNull VoxelShapes compiled) {
    }

    Shapes shapes() {
        return shapes;
    }

    Supplier<VanillaBehaviour> behaviourData() {
        return behaviourData;
    }

    /**
     * @return true if a stack of this palette's creative layout, or the icon of a group, is an item or a block state with one
     *         of these identifiers
     */
    boolean creativeMentions(Set<String> identifiers) {
        Set<String> mentioned = creativeIdentifiers();
        return identifiers.stream().anyMatch(mentioned::contains);
    }

    /**
     * @return the identifiers of the items and blocks the creative layout mentions in a stack or a group icon. Collected once:
     *         sessions extending this palette ask
     */
    Set<String> creativeIdentifiers() {
        return creativeIdentifiers.get();
    }

    private Set<String> collectCreativeIdentifiers() {
        Set<String> mentioned = new HashSet<>();
        creativeLayout.forEachStack(stack -> mention(stack, mentioned));
        for (CreativeCategory category : CreativeCategory.values()) {
            for (CreativeNode node : creativeLayout.nodes(category)) {
                if (node instanceof CreativeGroup group && group.icon() != null) {
                    mention(group.icon(), mentioned);
                }
            }
        }
        return mentioned;
    }

    private static void mention(BedrockItemStack stack, Set<String> into) {
        into.add(stack.type().identifier());
        if (stack.blockState() != null) {
            into.add(stack.blockState().type().identifier());
        }
    }

    /**
     * Internal: returns the behaviour values of this palette, behind {@link #resolve(BedrockBlockState)} and
     * {@link ResolvedItem#behaviour()}. Built on first use.
     *
     * @return the behaviour values
     */
    @Internal
    public @NonNull Behaviour behaviour() {
        Behaviour result = behaviour;
        return result != null ? result : buildBehaviour();
    }

    /** Out of line and small: a hot caller can inline {@link #behaviour()} whole. */
    private synchronized Behaviour buildBehaviour() {
        Behaviour result = behaviour;
        if (result == null) {
            behaviour = result = Behaviour.of(this, behaviourData, parent == null ? null : parent.behaviour());
        }
        return result;
    }

    /**
     * Builds now everything a lookup on this palette (and on the palette it extends) would build on first use: the
     * behaviour values and their columns, the resolution tables, the item tables, the keys of the blocks a preview
     * version adds or changes. Called for the palettes a {@link Paletter} compiles, so no lookup on them waits for data. Without
     * {@code behaviour} the behaviour values are left out (they are switched off).
     */
    void loadEagerly(boolean withBehaviour) {
        if (parent != null) {
            parent.loadEagerly(withBehaviour);
        }
        resolvedByCanonicalTable();
        resolvedByIndexTable();
        unavailableBlocks.get();
        versionItems.get();
        firstStates.get();
        creativeIdentifiers.get();
        items.loadEagerly();
        // The handles of the blocks a preview added or changed are made with the first handle of such a palette
        BedrockBlockState.fromCanonical(blocks.canonical(), 0);
        Behaviour values = behaviour();
        if (withBehaviour) {
            values.loadAll();
        }
    }

    /** For tests: whether {@link #loadEagerly} left nothing to build on first use. */
    boolean loadedEagerly(boolean withBehaviour) {
        return (parent == null || parent.loadedEagerly(withBehaviour)) && resolvedByCanonical != null && resolvedByIndex != null
            && unavailableBlocks.peek() != null && versionItems.peek() != null && firstStates.peek() != null
            && creativeIdentifiers.peek() != null && behaviour != null && (!withBehaviour || behaviour.loaded());
    }

    /**
     * What this palette sends for a block state and what that does: the definition (fallbacks applied, as
     * {@link #blockDefinition(BedrockBlockState)}), whether a fallback was used, and its behaviour values (friction,
     * destroy time, collision shape, light, ...): {@code palette.resolve(state).behaviour().get(BlockBehaviour.FRICTION)}.
     * Vanilla values come from the bundled captures of the palette's version, custom ones from their components.
     * Behaviour is only reachable through a resolution, so the palette that resolved it is the one that answers.
     * <p>
     * A resolution is made once per state and kept by the palette, so a lookup allocates nothing. Compare resolutions with {@code equals}.
     *
     * @param state the block state, a key
     * @return the resolution of the state
     * @throws IllegalArgumentException like {@link #blockDefinition(BedrockBlockState)}. Use
     *         {@link #resolve(BedrockBlockState, ResolvedBlock)} for states that may be missing
     * @since 1.0.0
     */
    public @NonNull ResolvedBlock resolve(@NonNull BedrockBlockState state) {
        int canonical = state.canonicalIn(blocks.canonical());
        return canonical != -1 ? resolveCanonical(canonical) : requireUnavailable(state);
    }

    /**
     * Resolves a block state like {@link #resolve(BedrockBlockState)}, but returns a default where that throws.
     *
     * @param state the block state, a key
     * @param orElse what to return where {@link #resolve(BedrockBlockState)} throws, may be null: like
     *         {@link #blockDefinition(BedrockBlockState, RegisteredBlockState)}, not the configured {@code Fallbacks},
     *         which apply before it
     * @return the resolution of a state, or {@code orElse} if the state is not in this palette
     * @since 1.0.0
     */
    public @Nullable ResolvedBlock resolve(@NonNull BedrockBlockState state, @Nullable ResolvedBlock orElse) {
        int canonical = state.canonicalIn(blocks.canonical());
        if (canonical != -1) {
            return resolveCanonical(canonical);
        }
        ResolvedBlock unavailable = unavailable(state);
        return unavailable != null ? unavailable : orElse;
    }

    /**
     * What this palette sends for a state of a vanilla constant the newest bundled release lacks
     * ({@link BedrockBlockType#isAvailable()}) and this palette's canonical blocks lack too: like a block a version
     * lacks, its {@link Fallbacks} entry, else the default fallback, else air. Resolved once per state and kept.
     *
     * @return the resolution, or null for any other state that is not in this palette
     */
    private @Nullable ResolvedBlock unavailable(BedrockBlockState state) {
        BedrockBlockType type = state.type();
        if (type.isCustom() || type.isAvailable()) {
            return null;
        }
        Map<BedrockBlockState, ResolvedBlock> cache = unavailableBlocks.get();
        ResolvedBlock resolved = cache.get(state);
        if (resolved == null) {
            BlockResolution resolution = blocks.resolveOrDefault(state.toNbt());
            RegisteredBlockState block = resolution.block();
            ResolutionKind kind = resolution.kind() == BlockResolution.Kind.DEFAULT && blocks.defaultFallback() == null
                ? ResolutionKind.MISSING : ResolutionKind.valueOf(resolution.kind().name());
            resolved = new ResolvedBlock(block, kind, behaviour().block(blocks.indexOfRuntimeId(block.getRuntimeId())));
            ResolvedBlock raced = cache.putIfAbsent(state, resolved);
            if (raced != null) {
                resolved = raced;
            }
        }
        return resolved;
    }

    private ResolvedBlock requireUnavailable(BedrockBlockState state) {
        ResolvedBlock resolved = unavailable(state);
        if (resolved == null) {
            throw new IllegalArgumentException(state + " is not in " + this);
        }
        return resolved;
    }

    private ResolvedBlock resolveCanonical(int canonical) {
        int own = canonical - parentCanonicalSize;
        if (own < 0) {
            return parent.resolveCanonical(canonical);
        }
        ResolvedBlock[] cache = resolvedByCanonical;
        if (cache == null) {
            cache = resolvedByCanonicalTable();
        }
        ResolvedBlock resolved = cache[own];
        if (resolved == null) {
            RegisteredBlockState block = blocks.block(canonical);
            int index = blocks.indexOfRuntimeId(block.getRuntimeId());
            // A state that is the block's own resolves to what its block resolves to by index: one instance for both lookups
            ResolutionKind kind = kindOf(canonical, block);
            cache[own] = resolved = kind == ResolutionKind.EXACT ? resolveIndex(index) : new ResolvedBlock(block, kind, behaviour().block(index));
        }
        return resolved;
    }

    /** How the canonical table resolved a canonical id to its block. */
    private ResolutionKind kindOf(int canonical, RegisteredBlockState block) {
        if (blocks.isMissing(canonical)) {
            return blocks.defaultFallback() != null ? ResolutionKind.DEFAULT : ResolutionKind.MISSING;
        }
        if (blocks.isFallback(canonical)) {
            return ResolutionKind.FALLBACK;
        }
        return blocks.canonical().index(block.getState()) == canonical ? ResolutionKind.EXACT : ResolutionKind.ADAPTED;
    }

    private synchronized ResolvedBlock[] resolvedByCanonicalTable() {
        ResolvedBlock[] cache = resolvedByCanonical;
        if (cache == null) {
            resolvedByCanonical = cache = new ResolvedBlock[blocks.canonical().size() - parentCanonicalSize];
        }
        return cache;
    }

    private synchronized ResolvedBlock[] resolvedByIndexTable() {
        ResolvedBlock[] cache = resolvedByIndex;
        if (cache == null) {
            resolvedByIndex = cache = new ResolvedBlock[blocks.size() - parentSize];
        }
        return cache;
    }

    private ResolvedBlock resolveIndex(int index) {
        int own = index - parentSize;
        if (own < 0) {
            return parent.resolveIndex(index);
        }
        ResolvedBlock[] cache = resolvedByIndex;
        if (cache == null) {
            cache = resolvedByIndexTable();
        }
        ResolvedBlock resolved = cache[own];
        if (resolved == null) {
            cache[own] = resolved = new ResolvedBlock(blocks.byIndex(index), ResolutionKind.EXACT, behaviour().block(index));
        }
        return resolved;
    }

    /**
     * What this palette does with a block it sent, by runtime id: for ids a server stores itself or reads from
     * packets, see {@link #blockDefinition(int)}. Never a fallback: the block is what is sent.
     *
     * @param runtimeId a block runtime id of this palette
     * @return the resolution, or null if this palette has no block with this runtime id
     * @since 1.0.0
     */
    public @Nullable ResolvedBlock resolveBlock(int runtimeId) {
        int index = blocks.indexOfRuntimeId(runtimeId);
        if (index == -1) {
            return null;
        }
        return resolveIndex(index);
    }

    /**
     * Returns the Bedrock data version of this palette: the clients it is for.
     *
     * @return the data version
     * @since 1.0.0
     */
    public @NonNull DataVersion version() {
        return data.version();
    }

    /**
     * Internal: returns the bundled vanilla data of this palette's version.
     *
     * @return the vanilla data
     */
    @Internal
    public @NonNull VanillaData vanillaData() {
        return data;
    }

    /**
     * Lists the layers this palette was compiled from.
     *
     * @return the layers, in order, unmodifiable
     * @since 1.0.0
     */
    public @NonNull List<PaletteLayer> layers() {
        return layers;
    }

    /**
     * Internal: the block registry. Use {@link #blockDefinition(BedrockBlockState)},
     * {@link #blockState(BlockDefinition)} and the other methods of this class.
     *
     * @return the block registry
     */
    @Internal
    public @NonNull BlockRegistry blocks() {
        return blocks;
    }

    /**
     * Internal: the item registry. Use {@link #itemDefinition(String)}, {@link #itemDefinitions()}, {@link #itemTags()}
     * and the other methods of this class.
     *
     * @return the item registry
     */
    @Internal
    public @NonNull ItemRegistry items() {
        return items;
    }

    // ---- Keys ----

    /**
     * Finds the key of a block state, which everything else about blocks takes.
     *
     * @param state a block state as {@code {name, states}}; extra keys are ignored
     * @param lookup whether the state must exist as is in this palette's version, see {@link Lookup}
     * @return its key, or null if the lookup does not accept the state: it is newer than this palette's version,
     *         removed, or behind an experiment this palette does not enable (see {@link #explainUnknown(NbtMap, Lookup)}).
     *         Custom blocks of the palette's layers are always accepted. With {@link Lookup#EXACT} the key round trips:
     *         {@code blockDefinition(key)} is the state's own block (null if no key is sent as it, e.g. a state whose
     *         block the newest data renamed)
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockState(@NonNull NbtMap state, @NonNull Lookup lookup) {
        CanonicalBlocks canonical = blocks.canonical();
        int id = canonical.index(state);
        if (id >= canonical.vanillaSize()) {
            return canonical.handle(id);
        }
        if (gating.missingBlocks().contains(BlockStates.name(state))) {
            return null;
        }
        if (!(newestVersion ? id != -1 : inVersion(state)) && (lookup == Lookup.EXACT || id != -1)) {
            return null;
        }
        if (id == -1 && lookup == Lookup.EXACT) {
            // A state of this version that the newest data lacks: its forward mapping may be sent as another block here
            // (a renamed block this version does not have), so the key is the one this palette sends as exactly the state
            int sent = blocks.canonical(blocks.exact(state));
            return sent == -1 || blocks.isFallback(sent) ? null : canonical.handle(sent);
        }
        return canonical.handle(Canonicals.lookupBlock(canonical, state));
    }

    /**
     * The same as {@link #blockState(NbtMap, Lookup)}, without NBT: for the state of a Java block, e.g. a placement
     * filter's name and states, or {@code state.type().identifier()} and {@link BedrockBlockState#values()}, which
     * round trip with {@link Lookup#EXACT}.
     *
     * @param lookup whether the state must exist as is in this palette's version, see {@link Lookup}
     * @param identifier a block identifier
     * @param values the state values by state name: {@link Boolean}, {@link Integer} or {@link String}, as the block
     *               has them. All of the block's states must be there, and no others; a value of another type is no
     *               match
     * @return its key, or null if there is no such state or the lookup does not accept it
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockState(@NonNull String identifier, @NonNull Map<String, ?> values, @NonNull Lookup lookup) {
        CanonicalBlocks canonical = blocks.canonical();
        BedrockBlockType custom = canonical.customType(identifier);
        if (custom != null) {
            return custom.stateOf(values);
        }
        BedrockBlockType vanilla = BedrockBlockType.vanillaType(identifier);
        BedrockBlockState state = vanilla == null ? null : vanilla.stateOf(values);
        if (state == null) {
            // Not a state of the newest version: older ones and renames are found by their NBT
            Map<String, Object> nbt = nbtValues(values);
            return nbt == null ? null : blockState(BlockStates.of(identifier, nbt), lookup);
        }
        if (gating.missingBlocks().contains(identifier)
            || !newestVersion && !inVersion(state.toNbt())) {
            return null;
        }
        return state;
    }

    /** Descriptor values as block state NBT (booleans are bytes), or null if one is not a boolean, int or string. */
    private static @Nullable Map<String, Object> nbtValues(Map<String, ?> values) {
        Map<String, Object> nbt = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean b) {
                value = (byte) (b ? 1 : 0);
            } else if (!(value instanceof Integer || value instanceof String)) {
                return null;
            }
            nbt.put(entry.getKey(), value);
        }
        return nbt;
    }

    /**
     * Finds the key of an item variant (an item type and its aux value).
     *
     * @param identifier the item identifier such as {@code minecraft:stick}
     * @param aux the aux value; 0 is the base variant
     * @param lookup whether the variant must exist as is in this palette's version, see {@link Lookup}
     * @return its key, or null if the lookup does not accept the variant, for the reasons of
     *         {@link #blockState(NbtMap, Lookup)}. Aux 0 is the base variant, which every item has. An aux that names no
     *         variant of the item here (a legacy damage value, a number nobody listed) is no variant either, so
     *         untrusted input never makes keys: send such a value as a stack's aux
     *         ({@code BedrockItemStack.builder(type).aux(aux)}), as {@link #decode} gives it
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant itemVariant(@NonNull String identifier, int aux, @NonNull Lookup lookup) {
        ItemVariant variant = ItemVariant.of(identifier, aux);
        CanonicalItems canonical = items.canonical();
        int id = canonical.index(variant);
        if (id >= canonical.vanillaSize()) {
            return canonical.handle(id);
        }
        if (versionItems().index(variant) == -1 && (lookup == Lookup.EXACT || id != -1)) {
            return null;
        }
        return canonical.handle(Canonicals.item(canonical, variant));
    }

    /**
     * Finds the key of an item type.
     *
     * @param identifier an item identifier
     * @param lookup whether the item must exist as is in this palette's version, see {@link Lookup}
     * @return the item type, or null if the lookup does not accept the identifier
     * @since 1.0.0
     */
    public @Nullable BedrockItemType itemType(@NonNull String identifier, @NonNull Lookup lookup) {
        BedrockItemVariant base = itemVariant(identifier, 0, lookup);
        return base == null ? null : base.type();
    }

    /**
     * Finds the key of a block type.
     *
     * @param identifier a block identifier
     * @param lookup whether the block must exist as is in this palette's version, see {@link Lookup}
     * @return the block type, or null if the lookup does not accept the identifier. {@link Lookup#RENAMED} also finds
     *         a vanilla block under the name an older version gave it. A custom block of the palette's layers is always
     *         found: the {@code type()} of the {@code CustomBlock}
     * @since 1.0.0
     */
    public @Nullable BedrockBlockType blockType(@NonNull String identifier, @NonNull Lookup lookup) {
        BedrockBlockType custom = blocks.canonical().customType(identifier);
        if (custom != null) {
            return custom;
        }
        NbtMap state = firstStates().get(identifier);
        if (state == null) {
            if (lookup == Lookup.EXACT) {
                return null;
            }
            state = BlockStates.of(identifier);
        }
        BedrockBlockState handle = blockState(state, lookup);
        return handle == null ? null : handle.type();
    }

    /**
     * Explains why a block state has no key, for logs and error messages.
     *
     * @param state the block state, as for {@link #blockState(NbtMap, Lookup)}
     * @param lookup the lookup that refused it
     * @return why a block state has no key under a lookup (see {@link #blockState(NbtMap, Lookup)}), or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull NbtMap state, @NonNull Lookup lookup) {
        String name = BlockStates.name(state);
        String toggle = gating.disabledBlocks().get(name);
        return blockState(state, lookup) != null ? null
            : refused(name, lookup, toggle != null ? disabled(toggle) : null,
                forwardingForExplanation(state), () -> differingKeys(state));
    }

    /** What the lookup refused: the strict forwarding, unless it only refused a value, which the differing keys name. */
    private Forwarding forwardingForExplanation(NbtMap state) {
        Forwarding strict = Canonicals.lookupForwardBlock(blocks.canonical(), state);
        if (strict.known()) {
            return strict;
        }
        Forwarding lenient = Canonicals.forwardBlock(blocks.canonical(), state);
        return lenient.known() ? lenient : strict;
    }

    /**
     * Explains why an item variant has no key, for logs and error messages.
     *
     * @param identifier the item identifier
     * @param aux the aux value
     * @param lookup the lookup that refused it
     * @return why an item has no key under a lookup (see {@link #itemVariant(String, int, Lookup)}), or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull String identifier, int aux, @NonNull Lookup lookup) {
        String toggle = gating.disabledItems().get(identifier);
        String definite = toggle != null ? disabled(toggle)
            : gating.removed().contains(identifier) ? "it was removed by Paletter.Builder.removeItems" : null;
        return itemVariant(identifier, aux, lookup) != null ? null
            : refused(identifier + "@" + aux, lookup, definite, Canonicals.forwardItem(items.canonical(), ItemVariant.of(identifier, aux)),
                () -> data.items().stream().noneMatch(item -> item.identifier().equals(identifier))
                    ? "; no item " + identifier + " in " + data.version() : "; the item exists, but not with aux " + aux);
    }

    private static String disabled(String toggle) {
        return "it is behind the disabled experiment " + toggle;
    }

    /**
     * @param definite why the content is missing, if that is the whole story (a disabled experiment, a removed item)
     * @param forwarding what the newest version has instead: no equivalent at all is the whole story, a changed or
     *                   renamed equivalent is said before what differs in this version's data
     * @param notInData what to add when the content is simply not in the data of the version
     */
    private String refused(String content, Lookup lookup, @Nullable String definite, Forwarding forwarding, Supplier<String> notInData) {
        String why = definite;
        if (why == null && !forwarding.known()) {
            why = forwarding.detail();
        }
        if (why == null) {
            boolean changed = forwarding.kind() != Forwarding.Kind.EXACT && !forwarding.detail().isEmpty();
            why = (changed ? forwarding.detail() + "; " : "") + "it is not in the data of " + data.version() + notInData.get();
        }
        return content + " is not accepted by the lookup (" + lookup + "): " + why;
    }

    /**
     * @return which keys of a block state differ from the closest state of the same block in this palette's version
     */
    private String differingKeys(NbtMap state) {
        String name = BlockStates.name(state);
        NbtMap wanted = BlockStates.states(state);
        List<String> fewest = null;
        for (NbtMap known : data.blockStates()) {
            if (!BlockStates.name(known).equals(name)) {
                continue;
            }
            List<String> differing = new ArrayList<>();
            NbtMap values = BlockStates.states(known);
            for (String key : new TreeSet<>(union(wanted.keySet(), values.keySet()))) {
                Object want = wanted.get(key);
                Object have = values.get(key);
                if (have == null) {
                    differing.add("key " + key + " is not a state of " + name);
                } else if (want == null) {
                    differing.add("key " + key + " is not set (states have e.g. " + have + ")");
                } else if (!comparable(want).equals(comparable(have))) {
                    differing.add("key " + key + " is " + want + ", the closest known state has " + have);
                }
            }
            if (fewest == null || differing.size() < fewest.size()) {
                fewest = differing;
            }
        }
        if (fewest == null) {
            return "; no block " + name + " in " + data.version();
        }
        return fewest.isEmpty() ? "" : "; compared with the closest known state of " + name + ": " + String.join(", ", fewest);
    }

    /** A state value as a number, so a byte, an int and a boolean of the same value are equal. */
    private static Object comparable(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1L : 0L;
        }
        return value instanceof Number number ? (Object) number.longValue() : value;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    /**
     * Whether a vanilla state is one of this palette's version, as written in its data: its block table has those (and the
     * custom states of the layers, which canonical ids find before this is asked).
     */
    private boolean inVersion(NbtMap state) {
        return blocks.exact(state) != null;
    }

    /** The item variants of this palette's version, as written in its data. Shared with the palette this one extends. */
    CanonicalItems versionItems() {
        return versionItems.get();
    }

    /** The first state of each block of this palette's version. Shared with the palette this one extends. */
    private Map<String, NbtMap> firstStates() {
        return firstStates.get();
    }

    /**
     * Lists every item variant this palette knows.
     *
     * @return every item variant this palette knows, in no particular order: the vanilla ones of the newest data, the
     *         custom items and variants of its layers
     * @since 1.0.0
     */
    public @NonNull List<BedrockItemVariant> itemVariants() {
        CanonicalItems canonical = items.canonical();
        List<BedrockItemVariant> variants = new ArrayList<>(canonical.size());
        for (int id = 0; id < canonical.size(); id++) {
            variants.add(canonical.handle(id));
        }
        return Collections.unmodifiableList(variants);
    }

    /**
     * Returns the definition (what is sent to the client) of a block state in this palette.
     *
     * @param state the block state, a key
     * @return the block to send for a state; resolved for this version with its fallbacks, air if nothing fits. A field
     *         read and an array load, it does not allocate
     * @throws IllegalArgumentException if the state is not in this palette: a custom block of a layer it does not have,
     *         e.g. one a session palette shows from another session. Never for a vanilla state when the data is
     *         bundled: every version sends something for it. Use {@link #blockDefinition(BedrockBlockState, RegisteredBlockState)}
     *         for states that may be missing
     * @since 1.0.0
     */
    public @NonNull RegisteredBlockState blockDefinition(@NonNull BedrockBlockState state) {
        int canonical = state.canonicalIn(blocks.canonical());
        return canonical != -1 ? blocks.block(canonical) : requireUnavailable(state).definition();
    }

    /**
     * Returns the definition of a block state like {@link #blockDefinition(BedrockBlockState)}, but returns a default
     * where that throws.
     *
     * @param state the block state, a key
     * @param orElse what to return where {@link #blockDefinition(BedrockBlockState)} throws, may be null; not the
     *         configured {@code Fallbacks}, which apply before it
     * @return the block to send for a state, or {@code orElse} if the state is not in this palette
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockDefinition(@NonNull BedrockBlockState state,
                                                          @Nullable RegisteredBlockState orElse) {
        int canonical = state.canonicalIn(blocks.canonical());
        if (canonical != -1) {
            return blocks.block(canonical);
        }
        ResolvedBlock unavailable = unavailable(state);
        return unavailable != null ? unavailable.definition() : orElse;
    }

    /**
     * Returns the block runtime id of a block state in this palette.
     *
     * @param state the block state, a key
     * @return the runtime id to send for a state: its hash, see {@link BlockHashes}. A field read and an array load, it
     *         does not allocate
     * @throws IllegalArgumentException like {@link #blockDefinition(BedrockBlockState)}. Use
     *         {@link #runtimeId(BedrockBlockState, int)} for states that may be missing
     * @since 1.0.0
     */
    public int runtimeId(@NonNull BedrockBlockState state) {
        int canonical = state.canonicalIn(blocks.canonical());
        return canonical != -1 ? blocks.runtimeId(canonical) : requireUnavailable(state).definition().getRuntimeId();
    }

    /**
     * Returns the block runtime id of a block state like {@link #runtimeId(BedrockBlockState)}, but returns a default
     * where that throws.
     *
     * @param state the block state, a key
     * @param orElse what to return where {@link #runtimeId(BedrockBlockState)} throws, e.g. the runtime id of air
     * @return the runtime id to send for a state, or {@code orElse} if the state is not in this palette
     * @since 1.0.0
     */
    public int runtimeId(@NonNull BedrockBlockState state, int orElse) {
        int canonical = state.canonicalIn(blocks.canonical());
        if (canonical != -1) {
            return blocks.runtimeId(canonical);
        }
        ResolvedBlock unavailable = unavailable(state);
        return unavailable != null ? unavailable.definition().getRuntimeId() : orElse;
    }

    /**
     * The block an item variant is sent with in this palette's version, in its default state, as a key: a
     * {@link BedrockBlockState}, for every kind of variant (vanilla, extra, custom block items, fallbacks). Paletter alone
     * decides it. A stack may carry a block state of its own, see {@link #encode}.
     * {@code blockDefinition(blockOf(variant))} is what is sent, and {@code resolve(variant).block()} the same without the
     * key. It follows the palette: the
     * fallback's block in an old version, the custom block of a layer. {@link #runtimeId(BedrockBlockState)} gives its
     * runtime id here.
     *
     * @param key the item type or variant
     * @return the block's key, or null if the item is not a block item here (or no state of the newest data
     *         resolves to the block, see {@link #blockState(BlockDefinition)}). Null too for an item that places a block
     *         but that BDS sends without one, as its captured creative stacks show (doors, signs, beds, seeds, ...): this
     *         is the block sent with the item, not the block it places ({@link #itemOf} is the other direction)
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockOf(@NonNull ItemKey key) {
        RegisteredBlockState block = items.blockOf(key);
        return block == null ? null : blocks.key(block);
    }

    /**
     * The item the Script API gives for the block's default permutation: the block's own item for a custom block (registered
     * with the block, see {@link dev.opencollab.paletter.block.CustomBlock#item()}, or the item that replaces it), else the item
     * of the bundled block to item relation of the data. Whether the client's pick block gives this item is
     * <b>unverified</b>.
     *
     * @param block the block type
     * @return the item's type, or null if the block has no item here (a custom block with
     *         {@link dev.opencollab.paletter.block.CustomBlock.BlockItem#NONE}, or a block no item places)
     * @since 1.0.0
     */
    public @Nullable BedrockItemType itemOf(@NonNull BedrockBlockType block) {
        RegisteredItem custom = items.customBlockItem(block.identifier());
        if (custom != null) {
            return custom.type();
        }
        String item = data.blockItems().get(block.identifier());
        return item == null || items.getDefinition(item) == null ? null : BedrockItemType.fromIdentifier(item);
    }

    /**
     * What this palette sends for an item variant, without building an {@link ItemData}: the item definition, aux value
     * and block of {@link #encode} (fallbacks applied), as one cached record. For the variants of the newest version it
     * is a field read and an array load, it does not allocate. Use it to read the definition or aux value of many items;
     * a stack's own block state, count and NBT are not part of it. {@link ResolvedItem#kind()} tells whether the
     * variant is sent as another item, {@link ResolvedItem#variant()} which. Whether this version registers an item
     * itself is {@link #itemDefinition(String)}.
     *
     * @param key the item type or variant
     * @return the resolution
     * @throws IllegalArgumentException if nothing is sent for the item: this version does not have it, and neither it
     *         nor the {@link Fallbacks} default has a fallback ({@link Fallbacks#NONE}). Use
     *         {@link #resolve(ItemKey, ResolvedItem)} where that may happen
     * @since 1.0.0
     */
    public @NonNull ResolvedItem resolve(@NonNull ItemKey key) {
        ResolvedItem resolved = items.resolve(key);
        if (resolved == null) {
            throw new IllegalArgumentException("Nothing is sent for " + key + " in " + this + ": the version lacks it and there is no fallback");
        }
        return resolved;
    }

    /**
     * Resolves an item like {@link #resolve(ItemKey)}, but returns a default where that throws.
     *
     * @param key the item type or variant
     * @param orElse what to return where {@link #resolve(ItemKey)} throws, may be null
     * @return the resolution, or {@code orElse} if nothing is sent for the item
     * @since 1.0.0
     */
    public @Nullable ResolvedItem resolve(@NonNull ItemKey key, @Nullable ResolvedItem orElse) {
        ResolvedItem resolved = items.resolve(key);
        return resolved != null ? resolved : orElse;
    }

    /**
     * Returns the definition of an item by identifier.
     *
     * @param identifier an item identifier such as {@code minecraft:stick}
     * @return the item with this identifier as registered in this palette, or null if it has none or it is behind a
     *         disabled experiment toggle (see {@link #itemDefinitions()}); fallbacks are not applied, see {@link #encode}
     * @since 1.0.0
     */
    public @Nullable RegisteredItem itemDefinition(@NonNull String identifier) {
        return items.getDefinition(identifier);
    }

    /**
     * Lists the item definitions of this palette: the item registry to send. Like BDS's in a world without experiments,
     * it has the items behind a disabled experiment toggle too, which BDS registers in such a world as well; every other
     * part of the palette treats them as missing (no lookup finds them, they resolve through the {@link Fallbacks}, the
     * creative content leaves them out). {@link #itemDefinition(int)} finds them, {@link #itemDefinition(String)} does not.
     *
     * @return every item registered in this palette, in runtime id order
     * @since 1.0.0
     */
    public @NonNull List<RegisteredItem> itemDefinitions() {
        return items.definitions();
    }

    /**
     * Lists the item tags of this palette.
     *
     * @return the item tags, tag to item identifiers: the vanilla tags plus the tags of custom items and layers
     * @since 1.0.0
     */
    public @NonNull Map<String, List<String>> itemTags() {
        return items.tags();
    }

    /**
     * The key of a block this palette sends: its exact state, else the first state that resolves to it (several states
     * may share a fallback). For a definition of this palette, {@code blockDefinition(blockState(definition))} is the
     * definition again. The block is looked up by runtime id, the hash of its state: a definition of another palette
     * gives the key of the block with the same runtime id here, as this palette knows it (a custom block of this
     * palette's own layer).
     *
     * @param definition a block definition, usually one this palette sent
     * @return the key, or null if this palette has no block with the definition's runtime id, no state resolves to it,
     *         or the definition is not a {@link RegisteredBlockState}
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockState(@NonNull BlockDefinition definition) {
        return definition instanceof RegisteredBlockState block ? blocks.key(block) : null;
    }

    /**
     * Finds the key of the block with a runtime id.
     *
     * @param runtimeId a block runtime id of this palette (block network ids are always hashes)
     * @return the key of the block with this runtime id, or null if this palette has no such block. For ids decoded from
     *         packets the codec helper already gives definitions; use this for ids a server stores itself, e.g. chunk
     *         palettes
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockState(int runtimeId) {
        RegisteredBlockState block = blocks.byRuntimeId(runtimeId);
        return block == null ? null : blocks.key(block);
    }

    /**
     * Finds the definition of the block with a runtime id.
     *
     * @param runtimeId a block runtime id of this palette (block network ids are always hashes)
     * @return the block with this runtime id as sent, or null if this palette has no such block. For ids decoded from
     *         packets the codec helper already gives definitions; use this for ids a server stores itself, e.g. chunk
     *         palettes
     * @since 1.0.0
     */
    public @Nullable RegisteredBlockState blockDefinition(int runtimeId) {
        return blocks.byRuntimeId(runtimeId);
    }

    /**
     * Finds the definition of the item with a runtime id.
     *
     * @param runtimeId an item runtime id of this palette (negative for the plain item of a custom block)
     * @return the item with this runtime id as registered in this palette, or null if it has none. For ids decoded from
     *         packets the codec helper already gives definitions; use this for ids a server stores itself
     * @since 1.0.0
     */
    public @Nullable RegisteredItem itemDefinition(int runtimeId) {
        return items.getDefinition(runtimeId);
    }

    // ---- Stacks ----

    /**
     * Encodes a stack for this palette's version: the item with the aux value, count and NBT, and the block the stack
     * places (its own block state if it has one and the item places blocks, else the item's default block). An item
     * this version does not have, or this palette does not know at all (an identifier nobody registered), is
     * replaced by the same stand-in: the item's fallback, else the default one of the {@link Fallbacks}
     * ({@code minecraft:unknown} unless configured otherwise), whichever way the item is looked up.
     *
     * @param stack the stack, built from keys
     * @return the item data, or {@link ItemData#AIR} only if there is neither a fallback for the item nor a default one
     *         ({@link Fallbacks#NONE})
     * @since 1.0.0
     */
    public @NonNull ItemData encode(@NonNull BedrockItemStack stack) {
        return items.encode(stack);
    }

    /**
     * Decodes a stack the client sent. The block state is set only if the stack's block differs from the item's default
     * block. The stack of a fallback shared by several variants, or of an item that is not a variant of the newest
     * version, is not recognized.
     *
     * @param data the stack as the client sent it
     * @return the stack, or null for air or an item this palette cannot identify
     * @since 1.0.0
     */
    public @Nullable BedrockItemStack decode(@NonNull ItemData data) {
        return items.decode(data);
    }

    /**
     * Whether two stacks are the same stack of items as far as this palette can tell: the same item definition, damage,
     * count, NBT, {@code canPlace}, {@code canBreak}, blocking ticks and block. The net id is not part of it.
     * <p>
     * Definitions compare by identity, as in Cloudburst ({@code ItemData#equals}, which also ignores the block). A
     * definition that is not this palette's own instance (another palette's, or Geyser's own) is first mapped to this
     * palette's entry of the same identifier and runtime id, so a stack of a base palette matches the same stack of a
     * palette extending it. A definition this palette does not have matches only itself. The same goes for block
     * definitions. A missing NBT tag and an empty one are the same, so are missing and empty block lists.
     * <p>
     * Stacks that differ only in count, or only in NBT, never match.
     *
     * @param a the first stack
     * @param b the second stack
     * @return true if the stacks are the same
     * @since 1.0.0
     */
    public boolean sameStack(@NonNull ItemData a, @NonNull ItemData b) {
        if (a == b) {
            return true;
        }
        // The cheap checks first: the NBT compare is deep
        return a.getCount() == b.getCount()
            && a.getDamage() == b.getDamage()
            && a.getBlockingTicks() == b.getBlockingTicks()
            && sameItem(a.getDefinition(), b.getDefinition())
            && sameBlock(a.getBlockDefinition(), b.getBlockDefinition())
            && sameTag(a.getTag(), b.getTag())
            && sameList(a.getCanPlace(), b.getCanPlace())
            && sameList(a.getCanBreak(), b.getCanBreak());
    }

    private boolean sameItem(ItemDefinition a, ItemDefinition b) {
        return a == b || ownItem(a) == ownItem(b);
    }

    private boolean sameBlock(@Nullable BlockDefinition a, @Nullable BlockDefinition b) {
        return a == b || ownBlock(a) == ownBlock(b);
    }

    private static boolean sameTag(@Nullable NbtMap a, @Nullable NbtMap b) {
        boolean emptyA = a == null || a.isEmpty();
        boolean emptyB = b == null || b.isEmpty();
        return emptyA || emptyB ? emptyA == emptyB : a.equals(b);
    }

    private static boolean sameList(String @Nullable [] a, String @Nullable [] b) {
        return Arrays.equals(a == null ? NO_STRINGS : a, b == null ? NO_STRINGS : b);
    }

    /**
     * @return this palette's entry for a definition (itself if it is one), else the definition
     */
    private ItemDefinition ownItem(ItemDefinition definition) {
        if (items.isRegistered(definition)) {
            return definition;
        }
        RegisteredItem own = items.getDefinition(definition.getRuntimeId());
        return own != null && own.getIdentifier().equals(definition.getIdentifier()) ? own : definition;
    }

    private @Nullable BlockDefinition ownBlock(@Nullable BlockDefinition definition) {
        if (definition == null || blocks.isRegistered(definition)) {
            return definition;
        }
        RegisteredBlockState own = blocks.byRuntimeId(definition.getRuntimeId());
        // A block of another palette says what it is; any other definition is trusted by its runtime id
        return own != null && (!(definition instanceof RegisteredBlockState other) || other.identifier().equals(own.identifier()))
            ? own : definition;
    }

    // ---- Debugging ----

    /**
     * Explains what this palette does with a block state: canonical, runtime and hashed id, how it was resolved for
     * this version and which state is sent. Slow, for debugging only.
     *
     * @param state the block state, as for {@link #blockState(NbtMap, Lookup)}
     * @return the explanation
     * @since 1.0.0
     */
    public @NonNull BlockExplanation explain(@NonNull NbtMap state) {
        NbtMap normalized = BlockStates.normalize(state);
        int canonical = Canonicals.block(blocks.canonical(), normalized);
        BlockResolution resolution = blocks.resolve(normalized);
        ResolutionKind kind = resolution.block() != null ? ResolutionKind.valueOf(resolution.kind().name())
            : blocks.defaultFallback() != null ? ResolutionKind.DEFAULT : ResolutionKind.MISSING;
        RegisteredBlockState sent = kind == ResolutionKind.MISSING ? null
            : canonical != -1 ? blocks.block(canonical) : resolution.block();
        List<String> chain = new ArrayList<>(List.of(BlockStates.name(normalized)));
        if (sent != null && !sent.identifier().equals(chain.getFirst())) {
            chain.add(sent.identifier());
        }
        return new BlockExplanation(normalized, blocks.canonical().handle(canonical), kind, List.copyOf(chain), sent == null ? null : sent.getState(),
            sent == null ? -1 : sent.getRuntimeId(), sent == null ? -1 : BlockHashes.hash(sent.getState()));
    }

    /**
     * Explains what this palette does with an item: canonical and runtime id, how it was resolved for this
     * version, the block it is sent with and its place in the creative inventory. Slow, for debugging only.
     *
     * @param identifier the item identifier
     * @param aux the aux value
     * @param blockState the block state the stack carries, or null if it has none
     * @return the explanation
     * @since 1.0.0
     */
    public @NonNull ItemExplanation explain(@NonNull String identifier, int aux, @Nullable NbtMap blockState) {
        ItemVariant variant = ItemVariant.of(identifier, aux);
        int canonical = Canonicals.item(items.canonical(), variant);
        ResolvedItem direct = items.resolve(variant);
        ResolvedItem sent = canonical != -1 ? items.item(canonical) : direct;
        ResolutionKind kind = direct != null ? direct.kind() : sent != null ? ResolutionKind.DEFAULT : ResolutionKind.MISSING;
        List<String> chain = new ArrayList<>(List.of(identifier));
        String item = sent == null ? null : sent.definition().getIdentifier();
        if (item != null && !item.equals(identifier)) {
            chain.add(item);
        }
        RegisteredBlockState block = sent == null ? null : sent.block();
        if (blockState != null && sent != null && !sent.fallback() && block != null) {
            RegisteredBlockState own = blocks.resolve(blockState).block();
            if (own != null) {
                block = own;
            }
        }
        ItemMatcher matcher = ItemMatcher.id(identifier).and(ItemMatcher.aux(aux));
        if (blockState != null) {
            matcher = matcher.and(ItemMatcher.blockState(blockState));
        }
        return new ItemExplanation(identifier, aux, blockState == null ? null : BlockStates.normalize(blockState),
            items.canonical().handle(canonical), kind, List.copyOf(chain), item,
            sent == null ? -1 : sent.definition().getRuntimeId(), sent == null ? 0 : sent.aux(),
            block == null ? null : BlockExplanation.describe(block.getState()), block == null ? -1 : block.getRuntimeId(),
            creativeLayout().contains(matcher), creativeLayout().groupOf(matcher));
    }

    /**
     * Returns the creative layout, version independent, after all layers.
     *
     * @return the version independent creative layout after all layers
     * @since 1.0.0
     */
    public @NonNull CreativeLayout creativeLayout() {
        return creativeLayout;
    }

    /**
     * Returns the creative inventory as compiled for this version.
     *
     * @return the creative inventory as sent to this version's clients
     * @since 1.0.0
     */
    public @NonNull CreativeContent creative() {
        return creative;
    }

    /**
     * Returns the report of the compilation: warnings and errors about the content.
     *
     * @return what happened while compiling this palette (including its parents)
     * @since 1.0.0
     */
    public @NonNull PaletteReport report() {
        return report;
    }

    /**
     * Compiles one more layer on top of this palette, e.g. for one session. The result is cached while it is reachable.
     * The layer is compiled once: concurrent calls for it wait for the first and return the same palette, while calls
     * for other layers compile at the same time.
     * <p>
     * A layer that cannot be compiled (a duplicate identifier or runtime id, across or within layers, a block hash
     * collision, an exception from a component encoder or a callback) throws a {@link PaletteException} naming the layer
     * and the content identifier. This palette is unchanged then, and no palette or failure is cached: the same layer
     * throws again, and other layers can still be applied.
     *
     * @param layer the layer to add
     * @return the palette with the layer
     * @throws PaletteException if the layer cannot be compiled
     * @since 1.0.0
     */
    public @NonNull Palette extend(@NonNull PaletteLayer layer) {
        CompletableFuture<Palette> compiling;
        CompletableFuture<?> other = null;
        synchronized (extensions) {
            Object existing = extensions.get(Objects.requireNonNull(layer));
            if (existing instanceof WeakReference<?> reference && reference.get() instanceof Palette palette) {
                return palette;
            }
            if (existing instanceof CompletableFuture<?> future) {
                other = future;
                compiling = null;
            } else {
                compiling = new CompletableFuture<>();
                extensions.put(layer, compiling);
            }
        }
        if (other != null) {
            // Compiled by another thread: waited for outside the lock, so other layers are not held up
            return await(other);
        }
        try {
            Palette extended = PaletteCompiler.extend(this, layer);
            synchronized (extensions) {
                extensions.put(layer, new WeakReference<>(extended));
            }
            compiling.complete(extended);
            return extended;
        } catch (RuntimeException | Error e) {
            // Nothing is cached for a failure: the next call compiles again
            synchronized (extensions) {
                extensions.remove(layer, compiling);
            }
            compiling.completeExceptionally(e);
            throw e;
        }
    }

    /** The palette another thread is compiling for a layer, or what it threw. */
    private static Palette await(CompletableFuture<?> compiling) {
        try {
            return (Palette) compiling.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * Sets the item and block definition registries of a connection's codec.
     *
     * @param helper the codec helper of one connection; it is modified
     * @since 1.0.0
     */
    public void applyTo(@NonNull BedrockCodecHelper helper) {
        helper.setItemDefinitions(items);
        helper.setBlockDefinitions(blocks);
    }

    /**
     * Adds the block properties (vanilla data driven and custom blocks) to a start game packet, and sets
     * {@code blockNetworkIdsHashed}: block runtime ids are always hashed. The packet keeps one entry per block name: an
     * entry it already has for a block of this palette (from another palette, say) is replaced by this palette's, in
     * place. Applying a palette again to the same packet changes nothing.
     *
     * @param packet the start game packet to send; it is modified
     * @since 1.0.0
     */
    public void applyTo(@NonNull StartGamePacket packet) {
        List<BlockPropertyData> existing = packet.getBlockProperties();
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) {
            byName.putIfAbsent(existing.get(i).getName(), i);
        }
        for (BlockPropertyData property : blocks.blockProperties()) {
            Integer index = byName.get(property.getName());
            if (index == null) {
                byName.put(property.getName(), existing.size());
                existing.add(property);
            } else if (!existing.get(index).equals(property)) {
                existing.set(index, property);
            }
        }
        packet.setBlockNetworkIdsHashed(true);
        addExperiments(packet.getExperiments());
    }

    /**
     * Adds the experiments of {@link #experiments()} to a resource pack stack packet (the client reads the enabled
     * experiments from there as well).
     *
     * @param packet the packet to send; it is modified
     * @since 1.0.0
     */
    public void applyTo(@NonNull ResourcePackStackPacket packet) {
        addExperiments(packet.getExperiments());
    }

    private void addExperiments(List<ExperimentData> experiments) {
        for (String toggle : gating.enabled()) {
            if (experiments.stream().noneMatch(existing -> existing.getName().equals(toggle))) {
                experiments.add(new ExperimentData(toggle, true));
            }
        }
    }

    /**
     * The experiment toggles this palette needs, because the Paletter enabled them ({@code experiments(...)})
     * and this version has content behind them: {@link #applyTo(StartGamePacket)} sends them. Empty by default, the
     * content behind a toggle is missing then (see {@link Paletter.Builder#experiments(String...)}); {@link Paletter.Builder#allExperiments()}
     * enables all of them.
     *
     * @return the toggle ids, sorted
     * @since 1.0.0
     */
    public @NonNull List<String> experiments() {
        return gating.enabled();
    }

    /**
     * Finds the experiment an item needs in this palette.
     *
     * @param type the item type
     * @return the enabled experiment toggle that makes an item present in this palette, or null if the item needs none.
     *         (Definitions are shared between palettes, so the registered item does not know; ask the palette.)
     * @since 1.0.0
     */
    public @Nullable String experimentOf(@NonNull BedrockItemType type) {
        return gating.ofItem(type.identifier());
    }

    /**
     * Finds the experiment a block needs in this palette.
     *
     * @param type the block type
     * @return the enabled experiment toggle that makes a block present in this palette, or null if the block needs none
     * @since 1.0.0
     */
    public @Nullable String experimentOf(@NonNull BedrockBlockType type) {
        return gating.ofBlock(type.identifier());
    }

    /**
     * Returns the voxel shapes of this palette.
     *
     * @return the voxel shapes of this palette (the shapes Bedrock culls block faces with): the vanilla ones BDS of this
     *         version sends, plus the ones the layers define and what Bedrock derives from them for custom blocks
     * @since 1.0.0
     */
    public @NonNull VoxelShapes voxelShapes() {
        return shapes.compiled();
    }

    /**
     * BDS sends this packet to every client, before start game, also when the world has no custom shapes. Send it
     * the same way: always.
     *
     * @return the voxel shapes packet to send before start game, identical to the one BDS sends for the same content
     * @since 1.0.0
     */
    public @NonNull VoxelShapesPacket voxelShapesPacket() {
        return shapes.compiled().toPacket();
    }

    /**
     * Builds the item component packet: the data driven components of the custom items and vanilla overrides.
     *
     * @return the item component packet to send to the client
     * @since 1.0.0
     */
    public @NonNull ItemComponentPacket itemComponentPacket() {
        return items.toPacket();
    }

    /**
     * Builds the creative content packet from {@link #creative()}.
     *
     * @return the creative content packet to send to the client
     * @since 1.0.0
     */
    public @NonNull CreativeContentPacket creativeContentPacket() {
        return creative.toPacket();
    }

    @Override
    public String toString() {
        List<String> ids = new ArrayList<>();
        layers.forEach(layer -> ids.add(layer.id()));
        return "Palette{" + version() + ", layers=" + ids + ", " + blocks.size() + " block states, "
            + items.definitions().size() + " items, " + creative.size() + " creative entries}";
    }
}
