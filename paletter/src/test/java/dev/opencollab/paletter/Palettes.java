package dev.opencollab.paletter;

import dev.opencollab.paletter.data.DataVersion;

import java.lang.ref.SoftReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Palettes of the bundled data with layers, the way a consumer builds them: a {@link Paletter} per layer list
 * ({@code Paletter.builder().layer(...).build().palette(protocol)}). Kept per layer identity, so asking twice gives
 * the same palette, as it does from one Paletter.
 */
public final class Palettes {
    public static final String[] EXPERIMENTS = {"y_2026_drop_3", "y_2026_drop_4"};
    private static final Map<Key, SoftReference<Paletter>> PALETTERS = new ConcurrentHashMap<>();

    private Palettes() {
    }

    public static Paletter paletter(PaletteLayer... layers) {
        return paletter(null, layers);
    }

    /** {@code version} null: every version; else only that one, which is all build() then compiles and keeps. */
    private static Paletter paletter(DataVersion version, PaletteLayer... layers) {
        Key key = new Key(List.of(layers), version);
        // Held softly: a test that keeps its palette gets the same one again, the many one-off layers of other tests do
        // not fill the test JVM's heap
        SoftReference<Paletter> cached = PALETTERS.get(key);
        Paletter paletter = cached == null ? null : cached.get();
        if (paletter == null) {
            // Every experiment on: the tests of the palettes are about the content of a version, not about its gating
            Paletter.Builder builder = Paletter.builder().experiments(EXPERIMENTS);
            if (version != null) {
                builder.versions(version::equals);
            }
            key.layers.forEach(builder::layer);
            paletter = builder.build();
            PALETTERS.put(key, new SoftReference<>(paletter));
        }
        return paletter;
    }

    public static Palette palette(int protocol, PaletteLayer... layers) {
        return paletter(Paletter.bundledLoader().version(protocol), layers).palette(protocol);
    }

    private record Key(List<PaletteLayer> layers, DataVersion version) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other) || !Objects.equals(version, other.version) || layers.size() != other.layers.size()) {
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
            int hash = Objects.hashCode(version);
            for (PaletteLayer layer : layers) {
                hash = 31 * hash + System.identityHashCode(layer);
            }
            return hash;
        }
    }
}
