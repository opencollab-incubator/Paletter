package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.block.BedrockBlock;
import org.geysermc.paletter.api.block.BedrockBlockAttributes;
import org.geysermc.paletter.api.block.BedrockBlockState;
import org.geysermc.paletter.api.block.VersionedBedrockBlock;

import java.util.Collection;
import java.util.Optional;

record SimpleVersionedBedrockBlock(
        BedrockVersion version,
        BedrockBlock block,
        BedrockBlockState defaultState,
        Collection<BedrockBlockState> states,
        Optional<BedrockBlockAttributes> attributes
) implements VersionedBedrockBlock {
    @Override
    public String identifier() {
        return block.identifier();
    }
}
