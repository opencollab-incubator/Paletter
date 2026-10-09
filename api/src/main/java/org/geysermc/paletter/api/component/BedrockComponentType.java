package org.geysermc.paletter.api.component;

/**
 * A type of bedrock component, a simple identifier
 * @param <T> the data that is stored under this component
 */
public interface BedrockComponentType<T> {
    String identifier();

    static <T> BedrockComponentType<T> of(String identifier) {
        return new Simple<>(identifier);
    }

    record Simple<T>(String identifier) implements BedrockComponentType<T> {
    }
}
