package org.geysermc.paletter;

import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.internal.Forwarding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.internal.block.BlockInterner;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

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
 * Palette palette = paletter.palette(codec, BlockNetworkIds.HASHED, layer);
 * // Optionally per session, sharing everything it does not change
 * Palette sessionPalette = palette.extend(sessionLayer);
 * }</pre>
 * Everything is loaded lazily and cached; instances are thread safe. Block states and item components that are equal
 * across versions are the same instances.
 * <p>
 * Canonical ids ({@link #canonicalBlock(NbtMap)}, {@link #canonicalItem(ItemVariant)}) are defined by the newest
 * version, and valid in every palette of this instance.
 *
 * @since 1.0.0
 */
public final class Paletter {
    private final PaletteDataSource source;
    private final Predicate<DataVersion> versionFilter;
    private final BlockNetworkIds networkIds;
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
        this.versionFilter = builder.versions;
        this.networkIds = builder.networkIds;
        this.fallbacks = builder.fallbacks;
        this.vanillaContent = builder.vanillaContent;
        this.creativeOrdering = builder.creativeOrdering;
        this.reports = builder.reports;
        this.globalLayers = List.copyOf(builder.layers);
    }

    /**
     * @return a builder with the defaults: bundled data, all versions, {@link BlockNetworkIds#SEQUENTIAL},
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
        return source.versions().stream().filter(versionFilter).toList();
    }

    public @NonNull DataVersion latest() {
        return versions().getLast();
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
     * @param state a block state of <em>any</em> bundled version as {@code {name, states}}; extra keys are ignored.
     *              A state the newest version lacks is mapped forward to its newest equivalent (renamed identifiers, state
     *              keys added, removed or reset)
     * @return its handle, or null if the newest version has no equivalent (see {@link #explainUnknown(NbtMap)}).
     *         Custom blocks have handles of their own ({@code BedrockBlockType.of(customBlock)}), valid in palettes with
     *         their layer, see {@link Palette#block(NbtMap)}
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState block(@NonNull NbtMap state) {
        CanonicalBlocks blocks = canonicalBlocks();
        return blocks.handle(Canonicals.block(blocks, state));
    }

        /**
     * @param identifier an item identifier of <em>any</em> bundled version; an item the newest version lacks is mapped
     *                   forward (renames and splits)
     * @param aux the aux (damage) value
     * @return its handle, or null if the newest version has no equivalent (see {@link #explainUnknown(String, int)})
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant item(@NonNull String identifier, int aux) {
        return canonicalItems().handle(Canonicals.item(canonicalItems(), ItemVariant.of(identifier, aux)));
    }

    /**
     * The block state an item places, version independent: the block the newest version sends the item with
     * ({@link Palette#blockOf}). Accepts variants of any bundled version like {@link #item(String, int)}.
     * Vanilla variants of the newest data only; for a palette's view (fallbacks, custom content) use
     * {@link Palette#blockKeyOf}.
     *
     * @return the block state, or null if the item is not a block item or has no handle
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockOf(@NonNull BedrockItemVariant variant) {
        int canonical = Canonicals.item(canonicalItems(), variant.toItemVariant());
        if (canonical == -1) {
            return null;
        }
        var block = vanilla(newest()).blockOf(variant);
        return block == null ? null : block(block.getState());
    }

    /**
     * @return why a block state has no handle (the block was removed, a rename leads nowhere), or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull NbtMap state) {
        Forwarding forwarding = Canonicals.forwardBlock(canonicalBlocks(), state);
        return forwarding.known() ? null : forwarding.detail();
    }

    /**
     * @return why an item has no handle (the item was removed, it has no such aux value), or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull String identifier, int aux) {
        Forwarding forwarding = Canonicals.forwardItem(canonicalItems(), ItemVariant.of(identifier, aux));
        return forwarding.known() ? null : forwarding.detail();
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
     *         without its layers), with this instance's block network ids
     * @since 1.0.0
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version) {
        return vanilla.computeIfAbsent(version,
            key -> PaletteCompiler.vanilla(vanillaData(key), networkIds, canonicalBlocks(), canonicalItems(), blockInterner,
                fallbacks, vanillaContent, creativeOrdering));
    }

    /**
     * The palette of a protocol version: vanilla content plus this instance's layers, compiled once per data version
     * and cached. Session content goes on top of it with {@link Palette#extend(PaletteLayer)}.
     *
     * @since 1.0.0
     */
    public @NonNull Palette palette(int protocolVersion) {
        DataVersion version = version(protocolVersion);
        Palette palette = compiled.computeIfAbsent(version, v -> {
            Palette result = vanilla(v);
            for (PaletteLayer layer : globalLayers) {
                result = PaletteCompiler.extend(result, layer, false);
            }
            reports.accept(result.report());
            return result;
        });
        if (protocolVersion > version.protocolVersion() && version.equals(latest())) {
            // Newer than all data: the latest data is used, and block ids may shift against what the client has
            return newerThanData.computeIfAbsent(palette, p -> {
                PaletteReport report = new PaletteReport();
                report.addAll(p.report());
                report.warn("paletter", "The requested protocol is newer than the newest bundled data ("
                    + version + ", protocol " + version.protocolVersion() + "); its data is used, so content the client has"
                    + " that this version lacks is missing and sequential block ids may differ");
                return p.withReport(report);
            });
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
        private BlockNetworkIds networkIds = BlockNetworkIds.SEQUENTIAL;
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
         * @param ids how block runtime ids are assigned; default {@link BlockNetworkIds#SEQUENTIAL}
         */
        public @NonNull Builder blockNetworkIds(@NonNull BlockNetworkIds ids) {
            this.networkIds = Objects.requireNonNull(ids);
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
         * @param reports called once for each palette compiled (with this instance's layers applied), with its report
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
     * items the version does not have. Equal to {@code palette.items().item(...).properties()} of a palette of that version
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
