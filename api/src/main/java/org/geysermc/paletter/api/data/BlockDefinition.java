package org.geysermc.paletter.api.data;

/**
 * A state definition
 * @param runtimeId the runtime ID of the state
 * @param identifier the string ID of the state
 */
public record BlockDefinition(int runtimeId, String identifier) {
}
