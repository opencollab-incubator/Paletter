package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates the generated constants without loading any data: the constants classes call it from their static
 * initializers. {@link VanillaIndex} registers the constant fields by identifier and links them to the bundled data
 * on first use; types created later (absent from the data) link themselves to their fallback.
 */
final class VanillaTypes {
    static final String UNKNOWN = "minecraft:unknown";

    static final Map<String, BedrockState<?>> STATES = new ConcurrentHashMap<>();

    private VanillaTypes() {
    }

    static @NonNull BedrockBlockType block(@NonNull String identifier) {
        return block(identifier, null);
    }

    /**
     * @param fallback what the type resolves to if the bundled data does not have it (a removed or preview block);
     *                 null for {@code minecraft:unknown}
     */
    static @NonNull BedrockBlockType block(@NonNull String identifier, @Nullable String fallback) {
        // Runs for every constant in the (interpreted) static initializers: no map, VanillaIndex registers the fields
        return new BedrockBlockType(identifier, fallback == null ? UNKNOWN : fallback);
    }

    static @NonNull BedrockBlockType educationBlock(@NonNull String identifier) {
        return educationBlock(identifier, null);
    }

    static @NonNull BedrockBlockType educationBlock(@NonNull String identifier, @Nullable String fallback) {
        return new BedrockBlockType(identifier, fallback == null ? UNKNOWN : fallback, true);
    }

    static @NonNull BedrockItemType educationItem(@NonNull String identifier) {
        return educationItem(identifier, null);
    }

    static @NonNull BedrockItemType educationItem(@NonNull String identifier, @Nullable String fallback) {
        return new BedrockItemType(identifier, fallback == null ? UNKNOWN : fallback, true);
    }

    static @NonNull BedrockItemType item(@NonNull String identifier) {
        return item(identifier, null);
    }

    static @NonNull BedrockItemType item(@NonNull String identifier, @Nullable String fallback) {
        return new BedrockItemType(identifier, fallback == null ? UNKNOWN : fallback);
    }

    static <S extends BedrockState<?>> @NonNull S state(@NonNull S state) {
        BedrockState<?> existing = STATES.putIfAbsent(state.name(), state);
        if (existing != null) {
            throw new IllegalStateException("Two state constants named " + state.name());
        }
        return state;
    }
}
