package org.geysermc.paletter.api.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link BedrockComponents} backed by a Map, honestly Claude should have just made BedrockComponents a class...
 */
final class MapBedrockComponents implements BedrockComponents {
    private final Map<BedrockComponentType<?>, Object> values;

    private MapBedrockComponents(Map<BedrockComponentType<?>, Object> values) {
        this.values = values;
    }

    @Override
    @SuppressWarnings("unchecked") // mmmm, warnings.
    public <T> Optional<T> get(BedrockComponentType<T> type) {
        return Optional.ofNullable((T) values.get(type));
    }

    @Override
    public boolean has(BedrockComponentType<?> type) {
        return values.containsKey(type);
    }

    @Override
    public Set<BedrockComponentType<?>> types() {
        return values.keySet();
    }

    static final class Builder implements BedrockComponents.Builder {
        private final Map<BedrockComponentType<?>, Object> values = new LinkedHashMap<>();

        @Override
        public <T> BedrockComponents.Builder set(BedrockComponentType<T> type, T value) {
            values.put(type, value);
            return this;
        }

        @Override
        public BedrockComponents build() {
            return new MapBedrockComponents(Map.copyOf(values));
        }
    }
}
