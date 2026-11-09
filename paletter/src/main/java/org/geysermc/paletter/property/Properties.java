package org.geysermc.paletter.property;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The properties of one item or block in one version. Immutable.
 *
 * @since 1.0.0
 */
public final class Properties {
    /** No properties. */
    public static final Properties EMPTY = new Properties(Map.of());

    private final Map<String, Property> byName;

    private Properties(Map<String, Property> byName) {
        this.byName = byName;
    }

    /**
     * @param properties the properties, in the order {@link #all()} returns them
     * @return the properties
     * @throws IllegalArgumentException if two properties have the same {@link Property#fullName()}
     */
    public static @NonNull Properties of(@NonNull Collection<Property> properties) {
        if (properties.isEmpty()) {
            return EMPTY;
        }
        Map<String, Property> map = new LinkedHashMap<>();
        for (Property property : properties) {
            if (map.put(property.fullName(), property) != null) {
                throw new IllegalArgumentException("Duplicate property " + property.fullName());
            }
        }
        return new Properties(map);
    }

    /**
     * @return the property, or null if there is none
     */
    public @Nullable Property get(@NonNull PropertyKind kind, @NonNull String name) {
        return byName.get(kind.prefix() + name);
    }

    /**
     * @param name the component name, e.g. {@code minecraft:max_stack_size}
     * @return the value of a component property if it is a compound, or null
     */
    public @Nullable NbtMap component(@NonNull String name) {
        Property property = get(PropertyKind.COMPONENT, name);
        return property != null && property.value() instanceof NbtMap map ? map : null;
    }

    /**
     * @return all properties
     */
    public @NonNull List<Property> all() {
        return List.copyOf(byName.values());
    }

    /**
     * @return the properties of one kind
     */
    public @NonNull List<Property> of(@NonNull PropertyKind kind) {
        List<Property> result = new ArrayList<>();
        for (Property property : byName.values()) {
            if (property.kind() == kind) {
                result.add(property);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Properties properties && byName.equals(properties.byName);
    }

    @Override
    public int hashCode() {
        return byName.hashCode();
    }

    @Override
    public String toString() {
        return "Properties" + byName.keySet();
    }
}
