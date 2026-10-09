package org.geysermc.paletter.api.block;

/**
 * Represents a specific block in bedrock edition, whether it be custom or not
 */
public interface BedrockBlock {
    String identifier();

    static BedrockBlock of(String identifier) {
        return BedrockBlockRegistry.intern(identifier);
    }
}
