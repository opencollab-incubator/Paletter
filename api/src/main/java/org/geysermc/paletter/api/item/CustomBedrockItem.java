package org.geysermc.paletter.api.item;

import org.geysermc.paletter.api.component.BedrockComponents;

/**
 * A record to identify and create new custom items within bedrock
 * @param identifier the ID of the new items
 * @param components the components of the new items
 */
public record CustomBedrockItem(String identifier, BedrockComponents components) {
}
