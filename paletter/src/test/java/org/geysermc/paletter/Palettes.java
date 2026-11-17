package org.geysermc.paletter;

import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.item.ResolvedItem;
import org.geysermc.paletter.vanilla.BedrockItemVariant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Palettes of the bundled data with layers, the way a consumer builds them: a {@link Paletter} per layer list
 * ({@code Paletter.builder().layer(...).build().palette(protocol)}). Kept per layer identity, so asking twice gives
 * the same palette, as it does from one Paletter.
 */
public final class Palettes {
    public static final String[] EXPERIMENTS = {"y_2026_drop_3", "y_2026_drop_4"};
    private static final Map<Key, Paletter> PALETTERS = new ConcurrentHashMap<>();

    private Palettes() {
    }

    public static Paletter paletter(PaletteLayer... layers) {
        return PALETTERS.computeIfAbsent(new Key(List.of(layers)), key -> {
            // Every experiment on: the tests of the palettes are about the content of a version, not about its gating
            Paletter.Builder builder = Paletter.builder().vanilla(vanilla -> vanilla.experiments(EXPERIMENTS));
            key.layers.forEach(builder::layer);
            return builder.build();
        });
    }

    public static Palette palette(int protocol, PaletteLayer... layers) {
        return paletter(layers).palette(protocol);
    }

    /** The block a palette sends an item variant with, or null if it is not a block item here. */
    public static RegisteredBlockState blockDefinitionOf(Palette palette, BedrockItemVariant variant) {
        ResolvedItem resolved = palette.resolve(variant);
        return resolved == null ? null : resolved.block();
    }

    /** Whether a palette sends a variant as another item: a resolution that is a fallback. */
    public static boolean isFallback(Palette palette, BedrockItemVariant variant) {
        ResolvedItem resolved = palette.resolve(variant);
        return resolved != null && resolved.fallback();
    }

    /** The variant a palette sends in place of a variant, or null if it sends it as itself or not at all. */
    public static BedrockItemVariant fallbackOf(Palette palette, BedrockItemVariant variant) {
        ResolvedItem resolved = palette.resolve(variant);
        return resolved == null ? null : resolved.sent();
    }

    private record Key(List<PaletteLayer> layers) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other) || layers.size() != other.layers.size()) {
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
            int hash = 1;
            for (PaletteLayer layer : layers) {
                hash = 31 * hash + System.identityHashCode(layer);
            }
            return hash;
        }
    }
}
