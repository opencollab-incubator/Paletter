package org.geysermc.paletter.api.spi;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;

import java.util.Optional;
import java.util.Set;

public interface BedrockDataProvider {
    ItemPalette itemPalette(BedrockVersion version);

    BlockPalette blockPalette(BedrockVersion version);

    Set<BedrockVersion> supportedVersions();

    Optional<BedrockItem> convert(BedrockItem item, BedrockVersion from, BedrockVersion to);

    Optional<BedrockBlockState> convert(BedrockBlockState state, BedrockVersion from, BedrockVersion to);

    void preload(BedrockVersion... versions);

    void evict(BedrockVersion version);
}
