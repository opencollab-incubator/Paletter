package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockNetworkIds;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Palettes of the bundled data with layers, the way a consumer builds them: a {@link Paletter} per layer list and mode
 * ({@code Paletter.builder().layer(...).build().palette(protocol)}). Kept per layer identity, so asking twice gives
 * the same palette, as it does from one Paletter.
 */
public final class Palettes {
    private static final Map<Key, Paletter> PALETTERS = new ConcurrentHashMap<>();

    private Palettes() {
    }

    public static Paletter paletter(BlockNetworkIds mode, PaletteLayer... layers) {
        return PALETTERS.computeIfAbsent(new Key(mode, List.of(layers)), key -> {
            Paletter.Builder builder = Paletter.builder().blockNetworkIds(mode);
            key.layers.forEach(builder::layer);
            return builder.build();
        });
    }

    public static Palette palette(int protocol, PaletteLayer... layers) {
        return paletter(BlockNetworkIds.SEQUENTIAL, layers).palette(protocol);
    }

    public static Palette palette(int protocol, BlockNetworkIds mode, PaletteLayer... layers) {
        return paletter(mode, layers).palette(protocol);
    }

    private record Key(BlockNetworkIds mode, List<PaletteLayer> layers) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other) || mode != other.mode || layers.size() != other.layers.size()) {
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
            int hash = mode.hashCode();
            for (PaletteLayer layer : layers) {
                hash = 31 * hash + System.identityHashCode(layer);
            }
            return hash;
        }
    }
}
