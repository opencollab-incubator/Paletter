package org.geysermc.paletter.test;

import org.geysermc.paletter.LogListener;
import org.geysermc.paletter.api.BedrockData;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.VersionedBedrockBlock;
import org.geysermc.paletter.api.item.VersionedBedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.junit.jupiter.api.Test;

public class APITest {
    private static final BedrockVersion VERSION = BedrockVersion.v26_50;

    @Test
    public void test() {
        ItemPalette itemPalette = BedrockData.itemPalette(VERSION);
        BlockPalette blockPalette = BedrockData.blockPalette(VERSION);

        LogListener log = new LogListener.Default();

        log.info("----- Items -----");
        for (VersionedBedrockItem item : itemPalette.items()) {
            log.info(item.identifier());
        }

        log.info("----- Blocks -----");
        for (VersionedBedrockBlock block : blockPalette.blocks()) {
            log.info(block.identifier());
        }
    }
}
