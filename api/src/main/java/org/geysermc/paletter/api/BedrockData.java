package org.geysermc.paletter.api;

import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.geysermc.paletter.api.spi.BedrockDataProvider;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

public final class BedrockData {
    private BedrockData() {
    }

    private static final BedrockDataProvider PROVIDER = ServiceLoader.load(BedrockDataProvider.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No BedrockDataProvider on the classpath. Add the runtime module."));

    public static ItemPalette itemPalette(BedrockVersion version) {
        return PROVIDER.itemPalette(version);
    }

    public static BlockPalette blockPalette(BedrockVersion version) {
        return PROVIDER.blockPalette(version);
    }

    public static ItemPalette.Builder itemPaletteBuilder(BedrockVersion version) {
        return itemPalette(version).toBuilder();
    }

    public static BlockPalette.Builder blockPaletteBuilder(BedrockVersion version) {
        return blockPalette(version).toBuilder();
    }

    public static Set<BedrockVersion> supportedVersions() {
        return PROVIDER.supportedVersions();
    }

    public static Optional<BedrockItem> convert(BedrockItem item, BedrockVersion from, BedrockVersion to) {
        return PROVIDER.convert(item, from, to);
    }

    public static Optional<BedrockBlockState> convert(BedrockBlockState state, BedrockVersion from, BedrockVersion to) {
        return PROVIDER.convert(state, from, to);
    }

    public static void preload(BedrockVersion... versions) {
        PROVIDER.preload(versions);
    }

    public static void evict(BedrockVersion version) {
        PROVIDER.evict(version);
    }
}
