package org.geysermc.paletter.api.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry containing all items, caching them so instances are not recreated
 */
public final class BedrockItemRegistry {
    private BedrockItemRegistry() {
    }

    private static final Map<String, BedrockItem> INTERNED = new ConcurrentHashMap<>();

    public static BedrockItem intern(String identifier) {
        return INTERNED.computeIfAbsent(identifier, Interned::new);
    }

    private record Interned(String identifier) implements BedrockItem {
        @Override
        public String toString() {
            return identifier;
        }
    }
}
