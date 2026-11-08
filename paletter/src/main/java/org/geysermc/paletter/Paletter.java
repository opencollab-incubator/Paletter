package org.geysermc.paletter;

import org.geysermc.paletter.internal.Canonicals;
import org.geysermc.paletter.internal.Forwarding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.block.BlockInterner;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.data.VanillaItem;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<DataVersion, VanillaData> data = new ConcurrentHashMap<>();
    private final Map<VanillaKey, Palette> vanilla = new ConcurrentHashMap<>();
    private final Map<Key, Palette> palettes = new ConcurrentHashMap<>();
    private final Map<Palette, Palette> newerThanData = new ConcurrentHashMap<>();
    private final Map<NbtMap, NbtMap> interned = new HashMap<>();
    private final BlockInterner blockInterner = new BlockInterner();
    private volatile CanonicalBlocks canonicalBlocks;
    private volatile CanonicalItems canonicalItems;

    private Paletter(PaletteDataSource source) {
        this.source = source;
    }

    /**
     * @return a Paletter reading the data bundled in the library
     */
    public static @NonNull Paletter bundled() {
        return Holder.BUNDLED;
    }

    public static @NonNull Paletter create(@NonNull PaletteDataSource source) {
        return new Paletter(source);
    }

    /**
     * @return all data versions, oldest first
     */
    public @NonNull List<DataVersion> versions() {
        return source.versions();
    }

    public @NonNull DataVersion latest() {
        return source.versions().getLast();
    }

    /**
     * @return the data version for a protocol version: the newest one not newer than it
     * @throws IllegalArgumentException if the protocol version is older than all data
     */
    public @NonNull DataVersion version(int protocolVersion) {
        DataVersion found = null;
        for (DataVersion version : source.versions()) {
            if (version.protocolVersion() <= protocolVersion) {
                found = version;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No Bedrock data for protocol " + protocolVersion + "; oldest is " + source.versions().getFirst());
        }
        return found;
    }

    public @NonNull VanillaData vanillaData(@NonNull DataVersion version) {
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
                    canonicalBlocks = blocks = CanonicalBlocks.of(vanillaData(latest()), source.renames());
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
                    canonicalItems = items = CanonicalItems.of(vanillaData(latest()), source.renames());
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
     * @param variant an item variant of <em>any</em> bundled version; one the newest version lacks is mapped forward
     * @return its handle, or null if the newest version has no equivalent (see {@link #explainUnknown(ItemVariant)})
     * @since 1.0.0
     */
    public @Nullable BedrockItemVariant item(@NonNull ItemVariant variant) {
        return canonicalItems().handle(Canonicals.item(canonicalItems(), canonicalBlocks(), variant), canonicalBlocks());
    }

    /**
     * The block state an item places, version independent: the block the newest version sends the item with
     * ({@link Palette#blockOf}). Accepts variants of any bundled version like {@link #item(ItemVariant)}.
     *
     * @return the block state, or null if the item is not a block item or has no handle
     * @since 1.0.0
     */
    public @Nullable BedrockBlockState blockOf(@NonNull ItemVariant variant) {
        int canonical = Canonicals.item(canonicalItems(), canonicalBlocks(), variant);
        if (canonical == -1) {
            return null;
        }
        var block = vanilla(latest()).blockOf(canonicalItems().variant(canonical));
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
     * @return why an item variant has no handle (the item was removed, it has no such damage value or block
     *         state), or null if it has one
     * @since 1.0.0
     */
    public @Nullable String explainUnknown(@NonNull ItemVariant variant) {
        Forwarding forwarding = Canonicals.forwardItem(canonicalItems(), canonicalBlocks(), variant);
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
     * @return the plain vanilla palette of a data version, with sequential block network ids
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version) {
        return vanilla(version, BlockNetworkIds.SEQUENTIAL);
    }

    /**
     * @return the plain vanilla palette of a data version
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version, @NonNull BlockNetworkIds mode) {
        return vanilla.computeIfAbsent(new VanillaKey(version, mode),
            key -> PaletteCompiler.vanilla(vanillaData(key.version()), key.mode(), canonicalBlocks(), canonicalItems(), blockInterner));
    }

    /**
     * Compiles (or returns the cached) palette of a protocol version with the given layers, in order.
     * Every prefix of the layer list is cached too, so palettes sharing their first layers share those results.
     * Meant for long lived layers; use {@link Palette#extend} for short lived ones like sessions.
     *
     * @param mode how block runtime ids are assigned. {@link BlockNetworkIds#HASHED} gives a state the same id in
     *             every version; {@link Palette#applyTo(org.cloudburstmc.protocol.bedrock.packet.StartGamePacket)}
     *             tells the client which mode is used
     */
    public @NonNull Palette palette(int protocolVersion, @NonNull BlockNetworkIds mode, @NonNull List<PaletteLayer> layers) {
        DataVersion version = version(protocolVersion);
        Palette palette = vanilla(version, mode);
        for (int i = 0; i < layers.size(); i++) {
            Key key = new Key(version, mode, List.copyOf(layers.subList(0, i + 1)));
            Palette parent = palette;
            PaletteLayer layer = layers.get(i);
            palette = palettes.computeIfAbsent(key, k -> PaletteCompiler.extend(parent, layer));
        }
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
     * Like {@link #palette(int, BlockNetworkIds, List)} with {@link BlockNetworkIds#SEQUENTIAL} ids.
     */
    public @NonNull Palette palette(int protocolVersion, @NonNull List<PaletteLayer> layers) {
        return palette(protocolVersion, BlockNetworkIds.SEQUENTIAL, layers);
    }

    public @NonNull Palette palette(int protocolVersion, @NonNull PaletteLayer... layers) {
        return palette(protocolVersion, BlockNetworkIds.SEQUENTIAL, List.of(layers));
    }

    public @NonNull Palette palette(int protocolVersion, @NonNull BlockNetworkIds mode, @NonNull PaletteLayer... layers) {
        return palette(protocolVersion, mode, List.of(layers));
    }

    public @NonNull Palette palette(@NonNull BedrockCodec codec, @NonNull PaletteLayer... layers) {
        return palette(codec.getProtocolVersion(), BlockNetworkIds.SEQUENTIAL, List.of(layers));
    }

    public @NonNull Palette palette(@NonNull BedrockCodec codec, @NonNull BlockNetworkIds mode, @NonNull PaletteLayer... layers) {
        return palette(codec.getProtocolVersion(), mode, List.of(layers));
    }

    /**
     * Drops all cached palettes, e.g. after the layers of a reload replaced the old ones. Vanilla data stays loaded.
     */
    public void clearPalettes() {
        palettes.clear();
    }

    private record VanillaKey(DataVersion version, BlockNetworkIds mode) {
    }

    /**
     * Layers are compared by identity, so a rebuilt layer is a new cache entry.
     */
    private record Key(DataVersion version, BlockNetworkIds mode, List<PaletteLayer> layers) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other) || !version.equals(other.version) || mode != other.mode || layers.size() != other.layers.size()) {
                return false;
            }
            for (int i = 0; i < layers.size(); i++) {
                if (layers.get(i) != other.layers.get(i)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 31 * version.hashCode() + mode.hashCode();
            for (PaletteLayer layer : layers) {
                hash = 31 * hash + System.identityHashCode(layer);
            }
            return hash;
        }
    }

    private static final class Holder {
        private static final Paletter BUNDLED = new Paletter(PaletteDataSource.bundled());
    }
}
