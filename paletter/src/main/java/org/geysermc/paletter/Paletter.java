package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.block.BlockInterner;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.VerifiedBuild;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
 * Everything is loaded lazily and cached; instances are thread safe. Block states and item components that are equal
 * across versions are the same instances.
 * <p>
 * Canonical ids ({@link #canonicalBlocks()}, {@link #canonicalItems()}) are defined by the newest
 * version, and valid in every palette of this instance.
 *
 * @since 1.0.0
 */
public final class Paletter {
    private final PaletteDataSource source;
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
    private final Map<NbtMap, NbtMap> interned = new HashMap<>();
    private final BlockInterner blockInterner = new BlockInterner();
    private volatile CanonicalBlocks canonicalBlocks;
    private volatile CanonicalItems canonicalItems;

    private Paletter(Builder builder) {
        this.source = builder.source;
        this.versions = source.versions().stream().filter(builder.versions).toList();
        this.fallbacks = builder.fallbacks;
        this.vanillaContent = builder.vanillaContent.copy();
        this.creativeOrdering = builder.creativeOrdering;
        this.reports = builder.reports;
        this.globalLayers = List.copyOf(builder.layers);
    }

    /**
     * @return a builder with the defaults: bundled data, all versions,
     *         {@link Fallbacks#UNKNOWN}, all vanilla content, {@link CreativeOrdering#BDS}, no layers
     * @since 1.0.0
     */
    public static @NonNull Builder builder() {
        return new Builder();
    }

    /**
     * @return a Paletter reading the data bundled in the library
     */
    public static @NonNull Paletter bundled() {
        return Holder.BUNDLED;
    }

    /**
     * @return the data versions this instance serves (those the builder's predicate accepts), oldest first
     */
    public @NonNull List<DataVersion> versions() {
        return versions;
    }

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

    /**
     * What is loaded from the data source, and the canonical ids derived from it, only depends on the data source: every
     * Paletter over the bundled data shares those of {@link #bundled()}, which the generated constants are linked to.
     */
    private boolean sharesBundledCanonicals() {
        return source == PaletteDataSource.bundled() && this != Holder.BUNDLED;
    }

    /** The newest version of the data, whether served or not: canonical ids are defined by it. */
    private DataVersion newest() {
        return source.versions().getLast();
    }

    /**
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
     * @return the data version for a protocol version: the newest one not newer than it
     * @throws IllegalArgumentException if the protocol version is older than all data
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

    @Internal
    public @NonNull VanillaData vanillaData(@NonNull DataVersion version) {
        if (sharesBundledCanonicals()) {
            return Holder.BUNDLED.vanillaData(version);
        }
        return data.computeIfAbsent(version, v -> {
            try {
                return intern(source.load(v));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to load Bedrock data " + v, e);
            }
        });
    }

    /**
     * Makes equal block states and item components of different versions the same instances.
     */
    private VanillaData intern(VanillaData loaded) {
        synchronized (interned) {
            List<NbtMap> states = new ArrayList<>(loaded.blockStates().size());
            for (NbtMap state : loaded.blockStates()) {
                states.add(interned.computeIfAbsent(state, s -> s));
            }
            List<VanillaItem> items = new ArrayList<>(loaded.items().size());
            for (VanillaItem item : loaded.items()) {
                NbtMap components = item.componentData() == null ? null : interned.computeIfAbsent(item.componentData(), c -> c);
                items.add(new VanillaItem(item.identifier().intern(), item.runtimeId(), item.version(), item.componentBased(), components));
            }
            Map<String, NbtMap> defaults = new HashMap<>();
            loaded.blockDefaults().forEach((name, state) -> defaults.put(name, interned.computeIfAbsent(state, s -> s)));
            return new VanillaData(loaded.version(), states, items, loaded.dataDrivenBlocks(), loaded.creative(),
                loaded.itemTags(), loaded.fallbacks(), defaults, loaded.experiments(), loaded.blockItems());
        }
    }

    // ---- Lookups ----

    /**
     * @return canonical block ids: every state of the newest version, in its vanilla palette order
     */
    @Internal
    public @NonNull CanonicalBlocks canonicalBlocks() {
        CanonicalBlocks blocks = canonicalBlocks;
        if (blocks == null) {
            synchronized (this) {
                blocks = canonicalBlocks;
                if (blocks == null) {
                    canonicalBlocks = blocks = sharesBundledCanonicals() ? Holder.BUNDLED.canonicalBlocks()
                        : CanonicalBlocks.of(vanillaData(newest()), source.renames());
                }
            }
        }
        return blocks;
    }

    /**
     * @return canonical item ids: every item of the newest version, then its other creative variants
     */
    @Internal
    public @NonNull CanonicalItems canonicalItems() {
        CanonicalItems items = canonicalItems;
        if (items == null) {
            synchronized (this) {
                items = canonicalItems;
                if (items == null) {
                    canonicalItems = items = sharesBundledCanonicals() ? Holder.BUNDLED.canonicalItems()
                        : CanonicalItems.of(vanillaData(newest()), source.renames());
                }
            }
        }
        return items;
    }

    /**
     * @return what hashed palettes of this instance share between versions
     */
    @Internal
    public @NonNull BlockInterner blockInterner() {
        return blockInterner;
    }

    // ---- Palettes ----

    /**
     * @return the plain vanilla palette of a data version (with this instance's fallbacks and vanilla content, but
     *         without its layers)
     * @since 1.0.0
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version) {
        return vanilla.computeIfAbsent(version,
            key -> PaletteCompiler.vanilla(vanillaData(key), canonicalBlocks(), canonicalItems(), blockInterner,
                fallbacks, vanillaContent, creativeOrdering, source::behaviour, source.voxelShapes()));
    }

    /**
     * The palette of a protocol version: vanilla content plus this instance's layers, compiled once per data version
     * and cached. Session content goes on top of it with {@link Palette#extend(PaletteLayer)}.
     *
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
                PaletteReport report = new PaletteReport();
                report.addAll(p.report());
                report.info("paletter", message);
                return p.withReport(report);
            });
            if (created[0]) {
                // Reported outside the computation, so the consumer may use this Paletter; only the note: the rest of
                // the report went to the consumer with the palette itself
                PaletteReport note = new PaletteReport();
                note.info("paletter", message);
                reports.accept(note);
            }
            return noted;
        }
        return palette;
    }

    /**
     * Compiles the palettes of all served versions now, instead of on first use, so no session pays for it. Each is
     * passed to the {@link Builder#reports reports} consumer as usual.
     *
     * @return this instance
     * @throws PaletteException if a global layer cannot be compiled
     * @since 1.0.0
     */
    public @NonNull Paletter warmUp() {
        for (DataVersion version : versions()) {
            compiled(version);
        }
        return this;
    }

    private Palette compiled(DataVersion version) {
        boolean[] created = {false};
        Palette palette = compiled.computeIfAbsent(version, v -> {
            created[0] = true;
            Palette result = vanilla(v);
            for (PaletteLayer layer : globalLayers) {
                result = PaletteCompiler.extend(result, layer, false);
            }
            return result;
        });
        if (created[0]) {
            // Outside computeIfAbsent: a consumer that asks for another palette would update the map recursively
            reports.accept(palette.report());
        }
        return palette;
    }

    /**
     * @since 1.0.0
     */
    public @NonNull Palette palette(@NonNull BedrockCodec codec) {
        return palette(codec.getProtocolVersion());
    }

    /**
     * Configures the global policy of a {@link Paletter}: everything that is the same for every palette it compiles.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private PaletteDataSource source = PaletteDataSource.bundled();
        private Predicate<DataVersion> versions = version -> true;
        private Fallbacks fallbacks = Fallbacks.UNKNOWN;
        private final VanillaContent vanillaContent = new VanillaContent();
        private CreativeOrdering creativeOrdering = CreativeOrdering.BDS;
        private Consumer<PaletteReport> reports = report -> {
        };
        private final List<PaletteLayer> layers = new ArrayList<>();

        private Builder() {
        }

        /**
         * @param source where the vanilla data comes from; default the data bundled in the library
         */
        public @NonNull Builder data(@NonNull PaletteDataSource source) {
            this.source = Objects.requireNonNull(source);
            return this;
        }

        /**
         * @param versions which data versions are served; default all
         */
        public @NonNull Builder versions(@NonNull Predicate<DataVersion> versions) {
            this.versions = Objects.requireNonNull(versions);
            return this;
        }

        /**
         * @param fallbacks what content a version lacks becomes; default {@link Fallbacks#UNKNOWN}
         */
        public @NonNull Builder fallbacks(@NonNull Fallbacks fallbacks) {
            this.fallbacks = Objects.requireNonNull(fallbacks);
            return this;
        }

        /**
         * Selects which vanilla content exists. The content is only valid inside the consumer.
         */
        public @NonNull Builder vanilla(@NonNull Consumer<VanillaContent> content) {
            Objects.requireNonNull(content).accept(vanillaContent);
            return this;
        }

        /**
         * @param ordering how custom items and blocks without an anchored placement are ordered in the creative
         *                 inventory; {@link CreativeOrdering#BDS} (the default) puts them exactly where BDS puts
         *                 add-on content
         */
        public @NonNull Builder creativeOrdering(@NonNull CreativeOrdering ordering) {
            this.creativeOrdering = Objects.requireNonNull(ordering);
            return this;
        }

        /**
         * @param reports called once for each palette compiled (with this instance's layers applied), with its report, and
         *                once with a one entry report for a palette served for a protocol newer than the newest data.
         *                Called outside any internal lock: it may use this Paletter
         */
        public @NonNull Builder reports(@NonNull Consumer<PaletteReport> reports) {
            this.reports = Objects.requireNonNull(reports);
            return this;
        }

        /**
         * Adds content applied to every palette, after the layers added before it.
         */
        public @NonNull Builder layer(@NonNull PaletteLayer layer) {
            layers.add(Objects.requireNonNull(layer));
            return this;
        }

        public @NonNull Paletter build() {
            return new Paletter(this);
        }
    }

    private static final class Holder {
        private static final Paletter BUNDLED = builder().build();
    }

    // ---- Properties ----

    /**
     * @return the properties of a vanilla item in a version, without compiling a palette; empty for custom items and for
     * items the version does not have. Equal to {@code palette.itemDefinition(type).properties()} of a palette of that version
     * @since 1.0.0
     */
    public @NonNull Properties properties(@NonNull BedrockItemType type, @NonNull DataVersion version) {
        return source.properties().item(type.identifier(), version);
    }

    /**
     * @return the properties of a vanilla block in a version, without compiling a palette; empty for custom blocks and for
     * blocks the version does not have
     * @since 1.0.0
     */
    public @NonNull Properties properties(@NonNull BedrockBlockType type, @NonNull DataVersion version) {
        return source.properties().block(type.identifier(), version);
    }
}
