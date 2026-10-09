package org.geysermc.paletter.api.block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry containing all blocks, caching them so instances are not recreated
 */
public final class BedrockBlockRegistry {
    private BedrockBlockRegistry() {
    }

    private static final Map<String, BedrockBlock> INTERNED = new ConcurrentHashMap<>();

    public static BedrockBlock intern(String identifier) {
        return INTERNED.computeIfAbsent(identifier, Interned::new);
    }

    private record Interned(String identifier) implements BedrockBlock {
        @Override
        public String toString() {
            return identifier;
        }
    }
}
