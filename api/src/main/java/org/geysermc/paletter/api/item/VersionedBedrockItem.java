package org.geysermc.paletter.api.item;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.data.BedrockItemData;

/**
 * A versioned view of a bedrock item, giving access to definition, components and more
 */
public interface VersionedBedrockItem extends BedrockItem, BedrockItemData {
    BedrockVersion version();

    @Override
    default BedrockItem item() {
        return this;
    }
}
