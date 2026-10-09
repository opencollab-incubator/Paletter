package org.geysermc.paletter.api.item;

/**
 * Represents a specific item in bedrock edition, whether it be custom or not
 */
public interface BedrockItem {
    String identifier();

    static BedrockItem of(String identifier) {
        return BedrockItemRegistry.intern(identifier);
    }
}
