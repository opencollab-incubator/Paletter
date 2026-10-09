package org.geysermc.paletter.api.palette;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.item.CustomBedrockItem;
import org.geysermc.paletter.api.item.VersionedBedrockItem;

import java.util.Collection;
import java.util.Optional;

/**
 * A palette representing all the items within bedrock edition
 */
public interface ItemPalette {
    /**
     * The bedrock version of this palette
     * @return the bedrock version
     */
    BedrockVersion version();

    /**
     * Gets a version-specific instance of an item, throws if no such item exists in the palette
     * @param item the item
     * @return the version-specific data
     */
    VersionedBedrockItem get(BedrockItem item);

    /**
     * Get an item by its runtime ID
     * @param runtimeId the ID
     * @return an item, if any
     */
    Optional<VersionedBedrockItem> byRuntimeId(int runtimeId);

    /**
     * Get an item by ID
     * @param identifier the ID
     * @return an item, if any
     */
    Optional<VersionedBedrockItem> byIdentifier(String identifier);

    /**
     * All items in the palette
     * @return the items
     */
    Collection<VersionedBedrockItem> items();

    /**
     * Makes a builder out of this item palette
     * @return builder
     */
    Builder toBuilder();

    interface Builder {
        Builder register(CustomBedrockItem custom);

        Builder fallback(BedrockItem unresolved, BedrockItem fallback);

        ItemPalette build();
    }
}
