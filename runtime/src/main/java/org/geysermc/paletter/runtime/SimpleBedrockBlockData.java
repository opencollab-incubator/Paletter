package org.geysermc.paletter.runtime;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.api.block.BedrockBlockAttributes;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.data.BedrockBlockData;
import org.geysermc.paletter.api.data.BlockDefinition;

import java.util.Optional;

record SimpleBedrockBlockData(
        BedrockBlockState state,
        BlockDefinition definition,
        BedrockComponents components,
        NbtMap blockStateTag,
        Optional<BedrockBlockAttributes> attributes,
        boolean isFallback
) implements BedrockBlockData {
}
