package org.geysermc.paletter.test;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.geysermc.paletter.api.BedrockData;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.geysermc.paletter.cloudburst.CloudburstPalettes;
import org.geysermc.paletter.palette.PaletteVersion;
import org.geysermc.paletter.runtime.SupportedVersions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class CloudburstBridgeTest {
    private static final BedrockVersion VERSION = BedrockVersion.of("26.50");

    @Test
    public void toItemDefinitionsCoversWholePalette() {
        ItemPalette palette = BedrockData.itemPalette(VERSION);
        List<ItemDefinition> definitions = CloudburstPalettes.toItemDefinitions(palette);

        assertEquals(palette.items().size(), definitions.size());
        assertTrue(definitions.stream().anyMatch(d -> d.getIdentifier().equals("minecraft:apple")));
    }

    @Test
    public void toBlockDefinitionsCoversWholePalette() {
        BlockPalette palette = BedrockData.blockPalette(VERSION);
        List<BlockDefinition> definitions = CloudburstPalettes.toBlockDefinitions(palette);

        assertEquals(palette.allStates().size(), definitions.size());
    }

    @Test
    public void paletteVersionResolvesBackToBedrockVersion() {
        Optional<BedrockVersion> resolved = SupportedVersions.byProtocolVersion(PaletteVersion.v26_50.protocolVersion());
        assertEquals(Optional.of(VERSION), resolved);
        assertTrue(SupportedVersions.byProtocolVersion(-1).isEmpty());
    }
}
