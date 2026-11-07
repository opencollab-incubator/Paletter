package org.geysermc.paletter;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.data.VanillaData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point: vanilla Bedrock data for every supported version, and the palettes compiled from it.
 * <pre>{@code
 * Paletter paletter = Paletter.bundled();
 * PaletteLayer geyser = PaletteLayer.builder("geyser")...build();
 *
 * // Once per client version, shared by all sessions on it
 * Palette palette = paletter.palette(codec, geyser);
 * // Optionally per session, sharing everything it does not change
 * Palette sessionPalette = palette.extend(sessionLayer);
 * }</pre>
 * Everything is loaded lazily and cached; instances are thread safe.
 */
public final class Paletter {
    private final PaletteDataSource source;
    private final Map<DataVersion, VanillaData> data = new ConcurrentHashMap<>();
    private final Map<DataVersion, Palette> vanilla = new ConcurrentHashMap<>();
    private final Map<Key, Palette> palettes = new ConcurrentHashMap<>();

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
                return source.load(v);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to load Bedrock data " + v, e);
            }
        });
    }

    /**
     * @return the plain vanilla palette of a data version
     */
    public @NonNull Palette vanilla(@NonNull DataVersion version) {
        return vanilla.computeIfAbsent(version, v -> PaletteCompiler.vanilla(vanillaData(v)));
    }

    /**
     * Compiles (or returns the cached) palette of a protocol version with the given layers, in order.
     * Every prefix of the layer list is cached too, so palettes sharing their first layers share those results.
     * Meant for long lived layers; use {@link Palette#extend} for short lived ones like sessions.
     */
    public @NonNull Palette palette(int protocolVersion, @NonNull List<PaletteLayer> layers) {
        DataVersion version = version(protocolVersion);
        Palette palette = vanilla(version);
        for (int i = 0; i < layers.size(); i++) {
            Key key = new Key(version, List.copyOf(layers.subList(0, i + 1)));
            Palette parent = palette;
            PaletteLayer layer = layers.get(i);
            palette = palettes.computeIfAbsent(key, k -> PaletteCompiler.extend(parent, layer));
        }
        return palette;
    }

    public @NonNull Palette palette(int protocolVersion, @NonNull PaletteLayer... layers) {
        return palette(protocolVersion, List.of(layers));
    }

    public @NonNull Palette palette(@NonNull BedrockCodec codec, @NonNull PaletteLayer... layers) {
        return palette(codec.getProtocolVersion(), List.of(layers));
    }

    /**
     * Drops all cached palettes, e.g. after the layers of a reload replaced the old ones. Vanilla data stays loaded.
     */
    public void clearPalettes() {
        palettes.clear();
    }

    /**
     * Layers are compared by identity, so a rebuilt layer is a new cache entry.
     */
    private record Key(DataVersion version, List<PaletteLayer> layers) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other) || !version.equals(other.version) || layers.size() != other.layers.size()) {
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
            int hash = version.hashCode();
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
