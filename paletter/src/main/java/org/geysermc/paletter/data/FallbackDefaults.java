package org.geysermc.paletter.data;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.item.ItemFallback;

/**
 * What canonical content a version lacks becomes when no layer has a fallback for it.
 * <p>
 * Paletter bundles no fallbacks: it maps every block of the newest version that an older version does not have to
 * {@code minecraft:unknown}, and every such item to the {@code minecraft:unknown} item ({@link #UNKNOWN}). Consumers
 * define better replacements with layer fallbacks ({@code PaletteLayer.Builder#blockFallback}, {@code #itemFallback}).
 * A layer can change the default for itself and every layer above it with
 * {@code PaletteLayer.Builder#fallbackDefaults}.
 *
 * @param block the fallback for blocks, or null to send air
 * @param item the fallback for items, or null to leave the item out ({@code Palette#item(int)} is null)
 * @since 1.0.0
 */
public record FallbackDefaults(@Nullable BlockFallback block, @Nullable ItemFallback item) {
    /**
     * The default: {@link BlockFallback#UNKNOWN} and {@link ItemFallback#UNKNOWN}.
     */
    public static final FallbackDefaults UNKNOWN = new FallbackDefaults(BlockFallback.UNKNOWN, ItemFallback.UNKNOWN);

    /**
     * No default: missing blocks are sent as air and missing items have no definition.
     */
    public static final FallbackDefaults NONE = new FallbackDefaults(null, null);
}
