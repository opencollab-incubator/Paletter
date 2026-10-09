package org.geysermc.paletter.api.block;

import org.geysermc.paletter.api.BedrockVersion;

import java.util.Collection;
import java.util.Optional;

/**
 * A versioned view of a bedrock block, giving access to states and attributes
 */
public interface VersionedBedrockBlock extends BedrockBlock {
    BedrockVersion version();

    BedrockBlockState defaultState();

    Collection<BedrockBlockState> states();

    Optional<BedrockBlockAttributes> attributes();
}
