package org.geysermc.paletter.api.block;

import org.geysermc.paletter.api.component.BedrockComponents;

import java.util.Map;

/**
 * A record to identify and create new custom blocks within bedrock
 * @param identifier the ID of the new block
 * @param components the components of the new block
 * @param explicitStates TODO need to find a nicer way to do this, map is ugly
 */
public record CustomBedrockBlock(
        String identifier,
        BedrockComponents components,
        Map<String, Object> explicitStates
) {
}
