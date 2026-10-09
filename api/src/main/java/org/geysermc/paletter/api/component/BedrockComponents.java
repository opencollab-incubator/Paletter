package org.geysermc.paletter.api.component;

import java.util.Optional;
import java.util.Set;

/**
 * A class which holds bedrock components, view-only
 */
public interface BedrockComponents {
    <T> Optional<T> get(BedrockComponentType<T> type);

    boolean has(BedrockComponentType<?> type);

    Set<BedrockComponentType<?>> types();

    static Builder builder() {
        return new MapBedrockComponents.Builder();
    }

    interface Builder {
        <T> Builder set(BedrockComponentType<T> type, T value);

        BedrockComponents build();
    }
}
