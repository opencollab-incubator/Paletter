package org.geysermc.paletter.cloudburst;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.data.BedrockBlockData;
import org.geysermc.paletter.api.item.VersionedBedrockItem;
import org.geysermc.paletter.api.palette.BlockPalette;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.geysermc.paletter.runtime.SupportedVersions;

import java.util.ArrayList;
import java.util.List;

// Converts this library's own definitions to cloudburstmc.protocol's wire types, keeping the
// api/runtime modules dependency-free of the protocol library (see BedrockItemData/BedrockBlockData).
//
// Two deliberate deviations from the original sketch, found by checking the real library
// (org.cloudburstmc.protocol 3.0.0.Beta13) instead of assuming it matched:
//  - there's no StartGamePacket.ItemEntry type; StartGamePacket just holds List<ItemDefinition>
//    directly, which is what toItemDefinitions already produces.
//  - palette state lives on BedrockCodecHelper (setItemDefinitions/setBlockDefinitions) - BedrockCodec
//    and BedrockCodec.Builder only know about packet (de)serialization and have no palette hooks at
//    all, so applyPalettes takes the per-connection BedrockCodecHelper instead of a BedrockCodec.
public final class CloudburstPalettes {
    private CloudburstPalettes() {
    }

    public static List<ItemDefinition> toItemDefinitions(ItemPalette palette) {
        List<ItemDefinition> definitions = new ArrayList<>();
        for (VersionedBedrockItem item : palette.items()) {
            definitions.add(new SimpleItemDefinition(item.definition().identifier(), item.definition().runtimeId(), item.definition().componentBased()));
        }
        return definitions;
    }

    public static List<BlockDefinition> toBlockDefinitions(BlockPalette palette) {
        List<BlockDefinition> definitions = new ArrayList<>();
        for (BedrockBlockState state : palette.allStates()) {
            BedrockBlockData data = palette.dataFromState(state);
            definitions.add(new SimpleBlockDefinition(data.definition().identifier(), data.definition().runtimeId(), data.blockStateTag()));
        }
        return definitions;
    }

    public static void applyPalettes(BedrockCodecHelper helper, ItemPalette items, BlockPalette blocks) {
        helper.setItemDefinitions(SimpleDefinitionRegistry.<ItemDefinition>builder()
                .addAll(toItemDefinitions(items))
                .build());
        helper.setBlockDefinitions(SimpleDefinitionRegistry.<BlockDefinition>builder()
                .addAll(toBlockDefinitions(blocks))
                .build());
    }

    public static BedrockVersion paletteVersion(BedrockCodec codec) {
        return SupportedVersions.byProtocolVersion(codec.getProtocolVersion())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported protocol version: " + codec.getProtocolVersion()));
    }
}
