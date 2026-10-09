package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.data.ItemDefinition;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.item.VersionedBedrockItem;

record SimpleBedrockItemData(
        BedrockVersion version,
        BedrockItem item,
        ItemDefinition definition,
        BedrockComponents components,
        boolean isFallback
) implements VersionedBedrockItem {
    @Override
    public String identifier() {
        return item.identifier();
    }
}
