package dev.opencollab.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A state property of a custom block: an entry of the add-on JSON description {@code states}. The order properties are
 * declared in determines the order of the generated states; the order of the values is the order sent to clients.
 * <p>
 * A final class, not a record (docs/api.md, "Evolution"): made with {@link #bool}, {@link #bools}, {@link #ints},
 * {@link #range} or {@link #strings}, which check that the values match the {@link #type()}.
 *
 * @param <T> the value type: {@link Boolean}, {@link Integer} or {@link String}, as the keys hold them
 *            ({@code BedrockBlockState#values()}); booleans are the bytes 0 and 1 only on the wire
 * @since 1.0.0
 */
public final class BlockProperty<T> {
    private final String name;
    private final Type type;
    private final List<T> values;

    private BlockProperty(String name, Type type, List<T> values) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.values = List.copyOf(values);
        if (this.values.isEmpty()) {
            throw new IllegalArgumentException("Block property " + name + " has no values");
        }
        if (new HashSet<>(this.values).size() != this.values.size()) {
            throw new IllegalArgumentException("Block property " + name + " lists a value twice: " + this.values);
        }
        for (T value : this.values) {
            if (!type.javaType.isInstance(value)) {
                throw new IllegalArgumentException("Block property " + name + " is " + type + ", but has the "
                    + value.getClass().getSimpleName() + " value " + value);
            }
        }
    }

    /**
     * Internal: a property of values already in their stored types, as a registered block derives it.
     */
    static BlockProperty<?> of(String name, Type type, List<?> values) {
        return new BlockProperty<>(name, type, values);
    }

    /**
     * The value type of a {@link BlockProperty}.
     *
     * @since 1.0.0
     */
    public enum Type {
        /** Boolean values, sent as the bytes 0 and 1. */
        BOOLEAN(Boolean.class),
        /** Integer values. */
        INTEGER(Integer.class),
        /** String values. */
        STRING(String.class);

        private final Class<?> javaType;

        Type(Class<?> javaType) {
            this.javaType = javaType;
        }
    }

    /**
     * Returns the name.
     *
     * @return the property name, e.g. {@code example:lit}
     */
    public @NonNull String name() {
        return name;
    }

    /**
     * Returns the value type.
     *
     * @return the value type
     */
    public @NonNull Type type() {
        return type;
    }

    /**
     * Returns the values.
     *
     * @return the possible values in order, unmodifiable; the first one is the default
     */
    public @NonNull List<T> values() {
        return values;
    }

    /**
     * A boolean state with the values {@code false} (the default) and {@code true}.
     *
     * @param name the property name, e.g. {@code example:lit}
     * @return the property
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Boolean> bool(@NonNull String name) {
        return new BlockProperty<>(name, Type.BOOLEAN, List.of(false, true));
    }

    /**
     * A boolean state with its values in the given order, as add-on JSON lists them ({@code [true, false]}).
     *
     * @param name the property name
     * @param first the first value, the default
     * @param rest the other values in order
     * @return the property
     * @throws IllegalArgumentException if a value is given twice
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Boolean> bools(@NonNull String name, boolean first, boolean @NonNull ... rest) {
        List<Boolean> values = new ArrayList<>(rest.length + 1);
        values.add(first);
        for (boolean value : rest) {
            values.add(value);
        }
        return bools(name, values);
    }

    /**
     * A boolean state with its values in the given order.
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty or lists a value twice
     * @throws NullPointerException if a value is null
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Boolean> bools(@NonNull String name, @NonNull List<Boolean> values) {
        return new BlockProperty<>(name, Type.BOOLEAN, values);
    }

    /**
     * An integer state with the given values in order.
     *
     * @param name the property name
     * @param first the first value, the default
     * @param rest the other values in order
     * @return the property
     * @throws IllegalArgumentException if a value is given twice
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Integer> ints(@NonNull String name, int first, int @NonNull ... rest) {
        List<Integer> values = new ArrayList<>(rest.length + 1);
        values.add(first);
        for (int value : rest) {
            values.add(value);
        }
        return new BlockProperty<>(name, Type.INTEGER, values);
    }

    /**
     * An integer state with the given values in order.
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty or lists a value twice
     * @throws NullPointerException if a value is null
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Integer> ints(@NonNull String name, @NonNull List<Integer> values) {
        return new BlockProperty<>(name, Type.INTEGER, values);
    }

    /**
     * An integer state with every value from {@code fromInclusive} to {@code toInclusive}, ascending.
     *
     * @param name the property name
     * @param fromInclusive the first value, which is the default
     * @param toInclusive the last value
     * @return the property
     * @throws IllegalArgumentException if {@code toInclusive} is below {@code fromInclusive}
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Integer> range(@NonNull String name, int fromInclusive, int toInclusive) {
        if (toInclusive < fromInclusive) {
            throw new IllegalArgumentException("The range " + fromInclusive + ".." + toInclusive + " has no values");
        }
        List<Integer> values = new ArrayList<>(toInclusive - fromInclusive + 1);
        for (int value = fromInclusive; value <= toInclusive; value++) {
            values.add(value);
        }
        return ints(name, values);
    }

    /**
     * A string state with the given values in order.
     *
     * @param name the property name
     * @param first the first value, the default
     * @param rest the other values in order
     * @return the property
     * @throws IllegalArgumentException if a value is given twice
     * @throws NullPointerException if a value is null
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<String> strings(@NonNull String name, @NonNull String first, @NonNull String @NonNull ... rest) {
        List<String> values = new ArrayList<>(rest.length + 1);
        values.add(first);
        values.addAll(List.of(rest));
        return new BlockProperty<>(name, Type.STRING, values);
    }

    /**
     * A string state with the given values in order.
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty or lists a value twice
     * @throws NullPointerException if a value is null
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<String> strings(@NonNull String name, @NonNull List<String> values) {
        return new BlockProperty<>(name, Type.STRING, values);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof BlockProperty<?> other && name.equals(other.name) && type == other.type
            && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, values);
    }

    @Override
    public String toString() {
        return "BlockProperty[name=" + name + ", type=" + type + ", values=" + values + "]";
    }
}
