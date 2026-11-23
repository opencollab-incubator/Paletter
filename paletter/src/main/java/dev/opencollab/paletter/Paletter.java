package dev.opencollab.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import dev.opencollab.paletter.annotation.Internal;
import dev.opencollab.paletter.internal.PaletteReportBuilder;
import dev.opencollab.paletter.internal.block.BlockInterner;
import dev.opencollab.paletter.internal.block.BlockStates;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import dev.opencollab.paletter.creative.CreativeOrdering;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.data.VerifiedBuild;
import dev.opencollab.paletter.internal.creative.RawLayout;
import dev.opencollab.paletter.internal.creative.RawStack;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.property.Properties;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Entry point: vanilla Bedrock data for every supported version, and the palettes compiled from it.
 * <pre>{@code
 * Paletter paletter = Paletter.bundled();
 * PaletteLayer layer = PaletteLayer.builder("server")...build();
 *
 * // Once per client version, shared by all sessions on it
 * Palette palette = paletter.palette(codec);
 * // Optionally per session, sharing everything it does not change
 * Palette sessionPalette = palette.extend(sessionLayer);
 * }</pre>
 * {@link Builder#build()} compiles the palette of every served version, with everything its lookups use, so no later
 * call waits for data ({@link #vanilla(DataVersion)} of a version that is not served compiles on its first call, and
 * {@link Palette#extend} compiles when called). Instances are thread safe. Block states and item components that are equal
 * across versions are the same instances. Share one instance for the whole application: every per-version palette it
 * compiles is cached in it.
 * <p>
 * Null handling: parameters and return values are {@code @NonNull} unless the signature says {@code @Nullable}; passing
 * null where it is not allowed is a bug and throws {@link NullPointerException}. A lookup that finds nothing returns null
 * ({@link #version(String)}).
 * <p>
 * Errors: methods throw only for configuration errors, which are reported by {@link IllegalArgumentException} (a version
 * that does not exist) or {@link PaletteException} (content that cannot be compiled). Problems with data the
 * library compiles that do not stop it are entries of the {@link PaletteReport} instead. A data source that cannot load
 * its files throws {@link java.io.UncheckedIOException}.
 * <p>
 * Concepts: a {@link Palette} is the compiled content of one client version. Content is named by keys (version independent
 * identities, such as {@link BedrockBlockType}); a palette hands out definitions (what is sent) and resolutions (what it
 * makes of a key). See {@link Palette}.
 * <p>
 * Canonical ids ({@link #canonicalBlocks()}, {@link #canonicalItems()}) are defined by the newest
 * version, and valid in every palette of this instance.
 *
 * @since 1.0.0
 */
public final class Paletter {
    private final PaletteDataSource source;
    private final boolean previews;
    /** {@link Builder#behaviour(boolean)}. */
    private final boolean behaviour;
    private final List<DataVersion> versions;
    private final Fallbacks fallbacks;
    private final VanillaContent vanillaContent;
    private final CreativeOrdering creativeOrdering;
    private final Consumer<PaletteReport> reports;
    private final List<PaletteLayer> globalLayers;
    private final Map<DataVersion, Palette> compiled = new ConcurrentHashMap<>();
    private final Map<DataVersion, VanillaData> data = new ConcurrentHashMap<>();
    private final Map<DataVersion, Palette> vanilla = new ConcurrentHashMap<>();
    private final Map<Palette, Palette> newerThanData = new ConcurrentHashMap<>();
    private final Object internLock = new Object();
    /** Equal states and component data of the versions loaded so far, see {@link #intern}; dropped when all are loaded. */
    private @Nullable Map<NbtMap, NbtMap> interned = new HashMap<>();
    /** The same for block states, compared by their two values ({@link BlockStates#STRATEGY}): the states themselves are not iterated. */
    private @Nullable Map<NbtMap, NbtMap> internedStates = new Object2ObjectOpenCustomHashMap<>(BlockStates.STRATEGY);
    private volatile boolean internedReleased;
    /** Whether {@link #releaseCompileData} ran. */
    private volatile boolean compileDataReleased;
    private final BlockInterner blockInterner = new BlockInterner();
    private volatile CanonicalBlocks canonicalBlocks;
    private volatile CanonicalItems canonicalItems;

    private Paletter(Builder builder) {
        this.source = builder.source;
        this.previews = builder.previews;
        this.behaviour = builder.behaviour;
        this.versions = source.versions().stream().filter(this::available).filter(builder.versions).toList();
        this.fallbacks = builder.fallbacks;
        this.vanillaContent = builder.vanillaContent.copy();
        for (DataVersion version : vanillaContent.experimentVersions()) {
            if (!versions.contains(version)) {
                throw new PaletteException("Experiments are enabled for " + version + ", which this Paletter does not serve (served: "
                    + versions + ")");
            }
        }
        this.creativeOrdering = builder.creativeOrdering;
        this.reports = builder.reports;
        this.globalLayers = List.copyOf(builder.layers);
    }

    /**
     * Starts the configuration of a Paletter.
     *
     * @return a builder with the defaults: bundled data, all versions,
     *         {@link Fallbacks#UNKNOWN}, all vanilla content, {@link CreativeOrdering#BDS}, no layers
     * @since 1.0.0
     */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * Returns the shared Paletter over the data bundled in the library, with the defaults of {@link #builder()}.
     * <p>
     * Its first call compiles every bundled version, behaviour included, and the instance is kept for the life of the
     * JVM. To look up versions only, use {@link #bundledVersions()} or {@link #bundledVersion(int)}, which compile nothing.
     *
     * @return a Paletter reading the data bundled in the library
     * @since 1.0.0
     */
    public static @NonNull Paletter bundled() {
        return Holder.BUNDLED;
    }

    /**
     * Lists the release data versions bundled in the library, without compiling a palette.
     *
     * @return the bundled release data versions, oldest first, unmodifiable
     * @since 1.0.0
     */
    public static @NonNull List<DataVersion> bundledVersions() {
        return Loaders.BUNDLED.versions();
    }

    /**
     * Finds the bundled release data version serving a protocol version, without compiling a palette.
     *
     * @param protocolVersion a Bedrock protocol version number
     * @return the newest bundled release data version not newer than the protocol version
     * @throws IllegalArgumentException if the protocol version is older than all bundled data
     * @since 1.0.0
     */
    public static @NonNull DataVersion bundledVersion(int protocolVersion) {
        return Loaders.BUNDLED.version(protocolVersion);
    }

    /**
     * Lists the data versions this instance serves.
     *
     * @return the data versions this instance serves (those the builder's predicate accepts), oldest first, unmodifiable
     * @since 1.0.0
     */
    public @NonNull List<DataVersion> versions() {
        return versions;
    }

    /**
     * Returns the newest data version this instance serves.
     *
     * @return the newest of {@link #versions()}
     * @since 1.0.0
     */
    public @NonNull DataVersion latest() {
        return versions.getLast();
    }

    /**
     * The Bedrock Dedicated Server release builds that were run and found to send a data version's data: the build it was
     * captured from and the builds compared with it and found identical, oldest first. The list may hold the capture
     * build alone. A data version serves every protocol from its first one up to the next version's; a build or protocol
     * that is not in this list is served the same data, unverified.
     *
     * @param version a data version of this instance's data source
     * @return the builds, oldest first; empty when the data source records none (a preview, or data of another source)
     * @see PaletteDataSource#verifiedBuilds(DataVersion)
     * @since 1.0.0
     */
    public @NonNull List<VerifiedBuild> verifiedBuilds(@NonNull DataVersion version) {
        return source.verifiedBuilds(version);
    }

    /** Whether the data of a version is there for this instance: a preview's only with previews. */
    private boolean available(DataVersion version) {
        return previews || !version.isPreview();
    }

    /** What is loaded from the data source only depends on the data source: every Paletter over the bundled data shares it. */
    private boolean sharesBundledData() {
        return source == PaletteDataSource.bundled() && this != Loaders.BUNDLED;
    }

    /**
     * The canonical ids derive from the newest version, so they depend on the data source and on previews: every Paletter
     * over the bundled data and the same previews setting shares them. The generated constants are linked to the newest
     * release's; the canonical ids of the others are mapped from them.
     */
    private @Nullable Paletter sharedCanonicals() {
        Paletter shared = previews ? Loaders.WITH_PREVIEWS : Loaders.BUNDLED;
        return source == PaletteDataSource.bundled() && this != shared ? shared : null;
    }

    /** The newest version of the data, whether served or not: canonical ids are defined by it. */
    private DataVersion newest() {
        return source.versions().stream().filter(this::available).reduce((older, newer) -> newer).orElseThrow();
    }

    /**
     * Finds a data version by name.
     *
     * @param name a version name, e.g. {@code 26.60}
     * @return the data version of that name this instance serves, or null
     * @since 1.0.0
     */
    public @Nullable DataVersion version(@NonNull String name) {
        for (DataVersion version : versions) {
            if (version.name().equals(name)) {
                return version;
            }
        }
        return null;
    }

    /**
     * Finds the data version serving a protocol version.
     *
     * @param protocolVersion a Bedrock protocol version number
     * @return the data version for a protocol version: the newest one not newer than it
     * @throws IllegalArgumentException if the protocol version is older than all data
     * @since 1.0.0
     */
    public @NonNull DataVersion version(int protocolVersion) {
        DataVersion found = null;
        for (DataVersion version : versions()) {
            if (version.protocolVersion() <= protocolVersion) {
                found = version;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No Bedrock data for protocol " + protocolVersion + "; oldest is " + versions().getFirst());
        }
        return found;
    }

    /**
     * Internal: loads the bundled data of a version, interned across versions.
     *
     * @param version a data version of this instance's data source
     * @return the vanilla data
     * @throws java.io.UncheckedIOException if the data cannot be read
     */
    @Internal
    public @NonNull VanillaData vanillaData(@NonNull DataVersion version) {
        // A Paletter over the bundled data loads through the shared one, but only its own versions decide when interning ends
        Paletter loader = sharesBundledData() ? Loaders.BUNDLED : this;
        VanillaData loaded = loader.data.computeIfAbsent(version, v -> {
            try {
                return loader.intern(loader.source.load(v));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to load Bedrock data " + v, e);
            }
        });
        if (!loader.internedReleased && loader.data.keySet().containsAll(versions)) {
            loader.releaseInterned();
        }
        return loaded;
    }

    /**
     * Interning only matters while versions are still to be loaded: once all versions the asking Paletter serves are, the map
     * (about a megabyte) is not needed. For a Paletter over the bundled data that is its own versions, not every bundled one:
     * an application that serves a few versions releases it once those are loaded.
     * A version loaded after that (one outside the versions served, or one another Paletter over the bundled data serves)
     * gets a throwaway map instead, so it loses the sharing with the other versions: its states and components are separate
     * copies. The values stay the same, only the memory is not shared.
     */
    private void releaseInterned() {
        synchronized (internLock) {
            interned = null;
            internedStates = null;
            internedReleased = true;
        }
    }

    /** For tests: whether the intern map this instance loads through was dropped. */
    boolean internReleased() {
        Paletter loader = sharesBundledData() ? Loaders.BUNDLED : this;
        synchronized (loader.internLock) {
            return loader.interned == null;
        }
    }

    /**
     * Makes equal block states (those of the creative stacks too) and item components of different versions the same
     * instances, and the identifiers of items, creative stacks, item tags and block items shared strings.
     */
    private VanillaData intern(VanillaData loaded) {
        synchronized (internLock) {
            // Dropped once every version served was loaded: a load after that is of a version outside them, which shares nothing
            Map<NbtMap, NbtMap> interned = this.interned != null ? this.interned : new HashMap<>();
            Map<NbtMap, NbtMap> internedStates = this.internedStates != null ? this.internedStates
                : new Object2ObjectOpenCustomHashMap<>(BlockStates.STRATEGY);
            List<NbtMap> states = new ArrayList<>(loaded.blockStates().size());
            for (NbtMap state : loaded.blockStates()) {
                states.add(internedStates.computeIfAbsent(state, s -> s));
            }
            List<VanillaItem> items = new ArrayList<>(loaded.items().size());
            for (VanillaItem item : loaded.items()) {
                NbtMap components = item.componentData() == null ? null : interned.computeIfAbsent(item.componentData(), c -> c);
                items.add(new VanillaItem(item.identifier().intern(), item.runtimeId(), item.version(), item.componentBased(), components));
            }
            Map<String, NbtMap> defaults = new HashMap<>();
            loaded.blockDefaults().forEach((name, state) -> defaults.put(name, internedStates.computeIfAbsent(state, s -> s)));
            // Creative stacks name their block state: the same instance as the version's own, and identifiers shared with the items
            RawLayout creative = loaded.creative().mapStacks(stack -> new RawStack(stack.identifier().intern(), stack.damage(),
                stack.count(), stack.blockState() == null ? null : internedStates.computeIfAbsent(stack.blockState(), s -> s), stack.tag()));
            Map<String, List<String>> itemTags = new HashMap<>();
            loaded.itemTags().forEach((tag, identifiers) -> itemTags.put(tag.intern(), identifiers.stream().map(String::intern).toList()));
            Map<String, String> blockItems = new HashMap<>();
            loaded.blockItems().forEach((block, item) -> blockItems.put(block.intern(), item.intern()));
            return new VanillaData(loaded.version(), states, items, loaded.dataDrivenBlocks(), creative,
                itemTags, loaded.fallbacks(), defaults, loaded.experiments(), blockItems);
        }
    }

    // ---- Lookups ----

    /**
     * Internal: the canonical block ids.
     *
     * @return canonical block ids: every state of the newest version, in its vanilla palette order
     */
    @Internal
    public @NonNull CanonicalBlocks canonicalBlocks() {
        CanonicalBlocks blocks = canonicalBlocks;
        if (blocks == null) {
            synchronized (this) {
                blocks = canonicalBlocks;
                if (blocks == null) {
                    canonicalBlocks = blocks = sharedCanonicals() != null ? sharedCanonicals().canonicalBlocks()
                        : CanonicalBlocks.of(vanillaData(newest()), source.renames());
                }
            }
        }
        return blocks;
    }

    /**
     * Internal: the canonical item ids.
     *
     * @return canonical item ids: every item of the newest version, then its other creative variants
     */
    @Internal
    public @NonNull CanonicalItems canonicalItems() {
        CanonicalItems items = canonicalItems;
        if (items == null) {
            synchronized (this) {
                items = canonicalItems;
                if (items == null) {
                    canonicalItems = items = sharedCanonicals() != null ? sharedCanonicals().canonicalItems()
                        : CanonicalItems.of(vanillaData(newest()), source.renames());
                }
            }
        }
        return items;
    }

    /**
     * Internal: the block interner.
     *
     * @return what hashed palettes of this instance share between versions
     */
    @Internal
    public @NonNull BlockInterner blockInterner() {
        return blockInterner;
    }

    // ---- Palettes ----

    /**
     * Returns the vanilla palette of a version, compiled once and cached.
     *
     * @param version a data version of this instance's data source
     * @return the plain vanilla palette of a data version (with this instance's fallbacks and vanilla content, but
     *         without its layers)
     * @throws java.io.UncheckedIOException if the data of the version cannot be read
     * @since 1.0.0
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version) {
        return vanilla.computeIfAbsent(version,
            key -> PaletteCompiler.vanilla(vanillaData(key), canonicalBlocks(), canonicalItems(), blockInterner,
                fallbacks, vanillaContent, creativeOrdering, behaviour ? source::behaviour : Paletter::noBehaviour,
                source.voxelShapes()));
    }

    /** The behaviour data of a Paletter built with {@code behaviour(false)}: none, and asking is an error. */
    private static BehaviourStore noBehaviour() {
        throw new IllegalStateException("Behaviour values are switched off for this Paletter (Paletter.Builder#behaviour(false))");
    }

    /**
     * Finds the key of a vanilla item type in the newest data of this instance. The name an older version gave an item
     * finds it too (see {@link Lookup#RENAMED}). Content of this instance's layers is not found: use the {@code type()} of
     * the custom item.
     *
     * @param identifier an item identifier
     * @return the item type, or null if the newest data has no such item
     * @throws NullPointerException if the identifier is null
     * @throws java.io.UncheckedIOException if the data of the newest version cannot be read
     * @since 1.0.0
     */
    public @Nullable BedrockItemType itemType(@NonNull String identifier) {
        return vanilla(latest()).itemType(Objects.requireNonNull(identifier), Lookup.RENAMED);
    }

    /**
     * Finds the key of a vanilla block type in the newest data of this instance. The name an older version gave a block
     * finds it too (see {@link Lookup#RENAMED}). Content of this instance's layers is not found: use the {@code type()}
     * of the custom block.
     *
     * @param identifier a block identifier
     * @return the block type, or null if the newest data has no such block
     * @throws NullPointerException if the identifier is null
     * @throws java.io.UncheckedIOException if the data of the newest version cannot be read
     * @since 1.0.0
     */
    public @Nullable BedrockBlockType blockType(@NonNull String identifier) {
        return vanilla(latest()).blockType(Objects.requireNonNull(identifier), Lookup.RENAMED);
    }

    /**
     * The palette of a protocol version: vanilla content plus this instance's layers, compiled once per data version
     * and cached. Session content goes on top of it with {@link Palette#extend(PaletteLayer)}. A protocol newer than all
     * data gets the newest data, with an info entry in the report; content the client has that the data lacks is then
     * missing.
     *
     * @param protocolVersion a Bedrock protocol version number
     * @return the palette serving it, never null
     * @throws IllegalArgumentException if the protocol version is older than all data
     * @throws PaletteException if a layer of this instance cannot be compiled
     * @since 1.0.0
     */
    public @NonNull Palette palette(int protocolVersion) {
        DataVersion version = version(protocolVersion);
        Palette palette = compiled(version);
        if (protocolVersion > version.protocolVersion() && version.equals(latest())) {
            // Newer than all data: the latest data is used, and content the client has may be missing
            String message = "The requested protocol is newer than the newest bundled data (" + version + ", protocol "
                + version.protocolVersion() + "); its data is used, so content the client has that this version lacks is missing";
            boolean[] created = {false};
            Palette noted = newerThanData.computeIfAbsent(palette, p -> {
                created[0] = true;
                PaletteReportBuilder report = new PaletteReportBuilder();
                report.addAll(p.report());
                report.info("paletter", message);
                return p.withReport(report);
            });
            if (created[0]) {
                // Reported outside the computation, so the consumer may use this Paletter; only the note: the rest of
                // the report went to the consumer with the palette itself
                PaletteReportBuilder note = new PaletteReportBuilder();
                note.info("paletter", message);
                reports.accept(note.build(palette.version()));
            }
            return noted;
        }
        return palette;
    }

    /**
     * Compiles the palettes of all served versions, for {@link Builder#build()}. The versions compile at the same time, on
     * a few threads of their own (at most one per processor) that end with this call; the palettes are the same as
     * compiled one after another. Each is passed to the {@link Builder#reports reports} consumer on the calling thread,
     * oldest version first.
     *
     * @throws PaletteException if a global layer cannot be compiled: the first version's failure, after the palettes
     *                          that compiled were reported
     */
    private void compileAll(boolean sequential) {
        // The one file of the data source no palette reads: loaded here too, so verifiedBuilds(...) does not wait either
        if (!versions.isEmpty()) {
            source.verifiedBuilds(versions.getFirst());
        }
        List<DataVersion> pending = versions.stream().filter(version -> !compiled.containsKey(version)).toList();
        int threads = sequential ? 1 : Math.min(pending.size(), Runtime.getRuntime().availableProcessors());
        if (threads <= 1) {
            for (DataVersion version : pending) {
                compiled(version);
            }
            return;
        }
        // The versions share only what is thread safe: the maps of this instance, the interners (their maps are
        // concurrent or locked, and equal content gives the same instance whichever version comes first) and the
        // lazily built lookups of the data source and the canonical ids (built once, under a lock)
        AtomicInteger count = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "Paletter compile " + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Compiled>> futures = new ArrayList<>(pending.size());
            for (DataVersion version : pending) {
                futures.add(pool.submit(() -> compiledUnreported(version)));
            }
            RuntimeException failure = null;
            boolean interrupted = false;
            // In version order: every palette that compiled is reported, also after a version failed
            for (Future<Compiled> future : futures) {
                while (true) {
                    try {
                        Compiled result = future.get();
                        if (result.created()) {
                            reported(result.palette());
                        }
                        break;
                    } catch (InterruptedException e) {
                        // The compilations finish anyway: wait for them, and keep the interrupt for the caller
                        interrupted = true;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof Error error) {
                            throw error;
                        }
                        if (failure == null) {
                            failure = (RuntimeException) e.getCause();
                        }
                        break;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            pool.shutdown();
        }
    }

    /** A palette, and whether the call that returned it compiled it (and so reports it). */
    private record Compiled(Palette palette, boolean created) {
    }

    private Palette compiled(DataVersion version) {
        // Every served version is compiled by build(): a plain read, no allocation and no lock of a map bin
        Palette palette = compiled.get(version);
        return palette != null ? palette : compiledReported(version);
    }

    private Palette compiledReported(DataVersion version) {
        Compiled result = compiledUnreported(version);
        if (result.created()) {
            reported(result.palette());
        }
        return result.palette();
    }

    private Compiled compiledUnreported(DataVersion version) {
        boolean[] created = {false};
        Palette palette = compiled.computeIfAbsent(version, v -> {
            created[0] = true;
            Palette result = vanilla(v);
            for (PaletteLayer layer : globalLayers) {
                result = PaletteCompiler.extend(result, layer);
            }
            // Everything a lookup on it would otherwise build on first use, here on the compiling thread
            result.loadEagerly(behaviour);
            return result;
        });
        return new Compiled(palette, created[0]);
    }

    /** Outside computeIfAbsent: a consumer that asks for another palette would update the map recursively. */
    private void reported(Palette palette) {
        reports.accept(palette.report());
        if (!compileDataReleased && compiled.keySet().containsAll(versions)) {
            releaseCompileData();
        }
    }

    /**
     * Once every palette this instance serves is compiled, drops what only compiling them uses (about 2.8 MB with the
     * bundled data): the properties the data source put into the data of each version, the value sets that forward
     * and resolve states of other versions, and the maps that share blocks and pages between versions. What a palette
     * compiled later needs is built again: a version not served ({@link #vanilla(DataVersion)}) loads and compiles as
     * before, only its blocks and pages are copies of its own (see {@link BlockInterner#releaseCompileMaps()}).
     * {@link Palette#extend} does not use any of it. The data source and the canonical ids may be shared with other
     * Paletters over the bundled data: they read or build what they need again.
     */
    private void releaseCompileData() {
        compileDataReleased = true;
        blockInterner.releaseCompileMaps();
        CanonicalBlocks blocks = canonicalBlocks;
        if (blocks != null) {
            blocks.releaseSchemas();
        }
        // A compiled palette's block tables are over those of its vanilla palette: both are cleared
        for (Palette palette : compiled.values()) {
            palette.blocks().releaseSchemas();
        }
        for (Palette palette : vanilla.values()) {
            palette.blocks().releaseSchemas();
        }
        source.releaseProperties();
    }

    /**
     * Internal: the loader every Paletter over the bundled data shares its data and canonical ids through. It compiles no
     * palette, so the vanilla constants link to its data without compiling {@link #bundled()}.
     *
     * @return the loader of the bundled data, without previews
     */
    @Internal
    public static @NonNull Paletter bundledLoader() {
        return Loaders.BUNDLED;
    }

    /** For tests: whether what only compiling uses was dropped. */
    boolean compileDataReleased() {
        return compileDataReleased;
    }

    /**
     * Returns the palette of a codec's protocol version, as {@link #palette(int)}.
     *
     * @param codec the codec of a client's connection
     * @return the palette serving the codec's protocol version
     * @throws IllegalArgumentException if the codec's protocol version is older than all data
     * @throws PaletteException if a layer of this instance cannot be compiled
     * @since 1.0.0
     */
    public @NonNull Palette palette(@NonNull BedrockCodec codec) {
        return palette(codec.getProtocolVersion());
    }

    /**
     * Configures the global policy of a {@link Paletter}: everything that is the same for every palette it compiles.
     * <p>
     * Not thread safe: configure on one thread, then share the built {@link Paletter}. Every method throws
     * {@link NullPointerException} for a null argument and returns this builder.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private PaletteDataSource source = PaletteDataSource.bundled();
        private Predicate<DataVersion> versions = version -> true;
        private boolean previews;
        private boolean behaviour = true;
        /** For tests: compile the versions one after another, on the calling thread. */
        boolean sequential;
        /** For tests: false leaves every palette to compile on its first {@code palette(...)}, the way it was before build() compiled. */
        boolean compileOnBuild = true;
        private Fallbacks fallbacks = Fallbacks.UNKNOWN;
        private final VanillaContent vanillaContent = new VanillaContent();
        private CreativeOrdering creativeOrdering = CreativeOrdering.BDS;
        private Consumer<PaletteReport> reports = report -> {
        };
        private final List<PaletteLayer> layers = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets where the vanilla data comes from.
         *
         * @param source where the vanilla data comes from; default the data bundled in the library
         * @return this builder
         */
        public @NonNull Builder data(@NonNull PaletteDataSource source) {
            this.source = Objects.requireNonNull(source);
            return this;
        }

        /**
         * Restricts the data versions served.
         *
         * @param versions which data versions are served; default all
         * @return this builder
         */
        public @NonNull Builder versions(@NonNull Predicate<DataVersion> versions) {
            this.versions = Objects.requireNonNull(versions);
            return this;
        }

        /**
         * Serves the data of Bedrock preview builds as well, or not. The constants only a preview has
         * ({@link dev.opencollab.paletter.annotation.SincePreview}) exist either way, but are linked to the newest release;
         * with previews the item and block constants send the preview's item or block, without they resolve through
         * {@link #fallbacks(Fallbacks) fallbacks} (see {@link dev.opencollab.paletter.annotation.SincePreview}).
         *
         * @param previews whether preview data is served; default {@code false}
         * @return this builder
         */
        public @NonNull Builder previews(boolean previews) {
            this.previews = previews;
            return this;
        }

        /**
         * Sets what content a version lacks becomes.
         *
         * @param fallbacks the fallbacks; default {@link Fallbacks#UNKNOWN}
         * @return this builder
         */
        public @NonNull Builder fallbacks(@NonNull Fallbacks fallbacks) {
            this.fallbacks = Objects.requireNonNull(fallbacks);
            return this;
        }

        /**
         * Enables experiment toggles (e.g. {@code y_2026_drop_4}) in every version. By default none is enabled: the items
         * and blocks a version has only behind a toggle are missing in its palette, so the {@link Fallbacks} apply to
         * them like to content the version lacks altogether (and {@code force} is only for content that is really there).
         * The items stay in the item registry ({@link Palette#itemDefinitions()}) all the same: BDS registers them in a
         * world without the toggle too, it only leaves them out of everything else (the creative inventory, for one).
         * An enabled toggle makes its content present in the versions that gate it; those palettes list the toggle in
         * {@link Palette#experiments()} and send it with
         * {@link Palette#applyTo(org.cloudburstmc.protocol.bedrock.packet.StartGamePacket)}. A palette tells the toggle of a
         * piece of content with {@code Palette#experimentOf}, and its report has a line for every toggle left disabled.
         * Every call adds to the toggles of the earlier ones.
         *
         * @param toggles the toggle ids, as BDS names them
         * @return this builder
         */
        public @NonNull Builder experiments(@NonNull String... toggles) {
            vanillaContent.experiments(toggles);
            return this;
        }

        /**
         * Enables experiment toggles in one version only, in addition to those of {@link #experiments(String...)}.
         * The version must be one this instance serves, or {@link #build()} throws a {@link PaletteException}.
         * Every call adds to the toggles of the earlier ones. Toggles that gate nothing in a version are ignored there; for
         * a toggle enabled for one version the palette's report says so.
         *
         * @param version the data version
         * @param toggles the toggle ids, as BDS names them
         * @return this builder
         */
        public @NonNull Builder experiments(@NonNull DataVersion version, @NonNull String... toggles) {
            vanillaContent.experiments(Objects.requireNonNull(version), toggles);
            return this;
        }

        /**
         * Enables every experiment toggle each version gates content with, except the voxel shape experiment, which
         * changes how the client treats shapes instead of adding content. The content behind the toggles is present, as in
         * the raw data of the version; the palettes list the toggles in {@link Palette#experiments()}. Lookups with a
         * {@link Lookup} see that content then. Adds to {@link #experiments(String...)} (the union is enabled). The voxel
         * shape experiment is enabled only by naming it: {@code experiments("voxel_shapes")}.
         *
         * @return this builder
         */
        public @NonNull Builder allExperiments() {
            vanillaContent.allExperiments();
            return this;
        }

        /**
         * Does not register these vanilla items: they have no definition and no creative entry, and resolve through the
         * {@link Fallbacks}. Blocks are never removed, the client's block palette has to match.
         *
         * @param items the vanilla item types to remove
         * @return this builder
         */
        public @NonNull Builder removeItems(@NonNull BedrockItemType... items) {
            vanillaContent.remove(items);
            return this;
        }

        /**
         * Sets how custom content is ordered in the creative inventory.
         *
         * @param ordering how custom items and blocks without an anchored placement are ordered in the creative
         *                 inventory; {@link CreativeOrdering#BDS} (the default) puts them exactly where BDS puts
         *                 add-on content
         * @return this builder
         */
        public @NonNull Builder creativeOrdering(@NonNull CreativeOrdering ordering) {
            this.creativeOrdering = Objects.requireNonNull(ordering);
            return this;
        }

        /**
         * Sets where the compilation reports go.
         *
         * @param reports called once for each palette compiled (with this instance's layers applied), with its report, and
         *                once with a one entry report for a palette served for a protocol newer than the newest data (that
         *                note has no {@code unknownBlocks} or {@code unknownItems}).
         *                Called outside any internal lock: it may use this Paletter
         * @return this builder
         */
        public @NonNull Builder reports(@NonNull Consumer<PaletteReport> reports) {
            this.reports = Objects.requireNonNull(reports);
            return this;
        }

        /**
         * Adds content applied to every palette, after the layers added before it.
         *
         * @param layer the layer to add
         * @return this builder
         */
        public @NonNull Builder layer(@NonNull PaletteLayer layer) {
            layers.add(Objects.requireNonNull(layer));
            return this;
        }

        /**
         * Loads the behaviour values ({@link Palette#resolve(dev.opencollab.paletter.vanilla.BedrockBlockState)}{@code .behaviour()},
         * {@code ResolvedItem#behaviour()}) with the palettes, or not. Off, the behaviour data is never read or kept, and asking
         * for a behaviour value throws {@link IllegalStateException}; resolutions and everything else work as before. For
         * consumers that never read behaviour values (a proxy that only translates).
         *
         * @param behaviour whether behaviour values are loaded; default {@code true}
         * @return this builder
         */
        public @NonNull Builder behaviour(boolean behaviour) {
            this.behaviour = behaviour;
            return this;
        }

        /**
         * Creates the Paletter and compiles the palette of every served version now, so no later call waits for data:
         * each palette is built with everything its lookups use (behaviour values unless {@link #behaviour(boolean) off},
         * voxel shapes, creative content, lookup tables). The versions compile at the same time, on a few threads of their
         * own (at most one per processor) that end with this call; the palettes are the same as compiled one after another.
         * Each is passed to the {@link #reports reports} consumer on the calling thread, oldest version first. Narrow the
         * versions with {@link #versions(Predicate)} to compile fewer.
         *
         * @return the Paletter
         * @throws PaletteException if a layer cannot be compiled: the first version's failure, after the palettes that
         *                          compiled were reported
         * @throws java.io.UncheckedIOException if the data source cannot be read
         */
        public @NonNull Paletter build() {
            Paletter paletter = new Paletter(this);
            if (compileOnBuild) {
                paletter.compileAll(sequential);
            }
            return paletter;
        }
    }

    /** {@link #bundled()}: compiled on first use. */
    private static final class Holder {
        private static final Paletter BUNDLED = builder().build();
    }

    /**
     * The loaders every Paletter over the bundled data shares its data and canonical ids through (see
     * {@link #sharesBundledData}, {@link #sharedCanonicals}). Never compiled: they only load data, so they keep no palette.
     */
    private static final class Loaders {
        private static final Paletter BUNDLED = new Paletter(builder());
        private static final Paletter WITH_PREVIEWS = new Paletter(builder().previews(true));
    }

    // ---- Properties ----

    /**
     * Reads the properties of a vanilla item without compiling a palette.
     *
     * @param type the vanilla item type
     * @param version the data version
     * @return the properties of a vanilla item in a version, without compiling a palette; empty for custom items and for
     * items the version does not have. Equal to {@code palette.itemDefinition(type).properties()} of a palette of that version
     * @since 1.0.0
     */
    public @NonNull Properties properties(@NonNull BedrockItemType type, @NonNull DataVersion version) {
        return source.properties().item(type.identifier(), version);
    }

    /**
     * Reads the properties of a vanilla block without compiling a palette.
     *
     * @param type the vanilla block type
     * @param version the data version
     * @return the properties of a vanilla block in a version, without compiling a palette; empty for custom blocks and for
     * blocks the version does not have
     * @since 1.0.0
     */
    public @NonNull Properties properties(@NonNull BedrockBlockType type, @NonNull DataVersion version) {
        return source.properties().block(type.identifier(), version);
    }
}
