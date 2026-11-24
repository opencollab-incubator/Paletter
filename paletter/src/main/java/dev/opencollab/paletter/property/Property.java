package dev.opencollab.paletter.property;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * One named property of an item or block. Properties are equal when their kind, name and value are.
 *
 * @since 1.0.0
 */
public final class Property {
    private final PropertyKind kind;
    private final String name;
    private final Object value;

    private Property(PropertyKind kind, String name, Object value) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = Objects.requireNonNull(name, "name");
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a property.
     *
     * @param kind the kind of property
     * @param name the name within the kind, e.g. {@code minecraft:max_stack_size} for a {@link PropertyKind#COMPONENT}
     * @param value the value as an NBT tag value: an {@code NbtMap}, {@code NbtList}, string or number
     * @return the property
     * @throws NullPointerException if an argument is null
     * @since 1.0.0
     */
    public static @NonNull Property of(@NonNull PropertyKind kind, @NonNull String name, @NonNull Object value) {
        return new Property(kind, name, value);
    }

    /**
     * The kind of property.
     *
     * @return the kind
     * @since 1.0.0
     */
    public @NonNull PropertyKind kind() {
        return kind;
    }

    /**
     * The name within the kind.
     *
     * @return the name, e.g. {@code minecraft:max_stack_size} for a {@link PropertyKind#COMPONENT}
     * @since 1.0.0
     */
    public @NonNull String name() {
        return name;
    }

    /**
     * The value.
     *
     * @return the value as an NBT tag value: an {@code NbtMap}, {@code NbtList}, string or number
     * @since 1.0.0
     */
    public @NonNull Object value() {
        return value;
    }

    /**
     * The name qualified by the kind.
     *
     * @return the kind's prefix followed by the name, unique within one item or block
     * @since 1.0.0
     */
    public @NonNull String fullName() {
        return kind.prefix() + name;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Property other && kind == other.kind && name.equals(other.name) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, value);
    }

    @Override
    public String toString() {
        return "Property[kind=" + kind + ", name=" + name + ", value=" + value + "]";
    }
}
