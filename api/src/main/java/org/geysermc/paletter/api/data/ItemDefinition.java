package org.geysermc.paletter.api.data;

/**
 * An item definition
 * @param runtimeId the runtime ID of the item
 * @param identifier the string ID of the item
 * @param componentBased if this item is component based
 */
public record ItemDefinition(int runtimeId, String identifier, boolean componentBased) {
}
