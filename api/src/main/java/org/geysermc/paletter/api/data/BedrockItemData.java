package org.geysermc.paletter.api.data;

import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.item.BedrockItem;

/**
 * Represents a greater in depth view of an item and it's components
 */
public interface BedrockItemData {
    BedrockItem item();

    ItemDefinition definition();

    default int runtimeId() {
        return definition().runtimeId();
    }

    BedrockComponents components();

    boolean isFallback();
}
