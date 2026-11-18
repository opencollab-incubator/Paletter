package org.geysermc.paletter.block;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * A state property of a custom block: an entry of the add-on JSON description {@code states}. The order properties are
 * declared in determines the order of the generated states; the order of the values is the order sent to clients.
 *
 * @param name the property name, e.g. {@code geyser:lit}
 * @param type the value type
 * @param values the possible values; the first one is the default
 * @param <T> the value type: {@link Byte} for booleans (0 and 1, as Bedrock stores them), {@link Integer} or {@link String}
 * @since 1.0.0
 */
public record BlockProperty<T>(@NonNull String name, @NonNull Type type, @NonNull List<T> values) {

    /**
     * The value type of a {@link BlockProperty}.
     */
    public enum Type {
        /** Boolean values, stored as the bytes 0 and 1. */
        BOOLEAN,
        /** Integer values. */
        INTEGER,
        /** String values. */
        STRING
    }

    /**
     * Creates a property; the values are copied.
     *
     * @throws IllegalArgumentException if {@code values} is empty
     */
    public BlockProperty {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Block property " + name + " has no values");
        }
    }

    /**
     * A boolean state with the values {@code false} (the default) and {@code true}.
     *
     * @param name the property name, e.g. {@code geyser:lit}
     * @return the property
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Byte> bool(@NonNull String name) {
        return new BlockProperty<>(name, Type.BOOLEAN, List.of((byte) 0, (byte) 1));
    }

    /**
     * A boolean state with its values in the given order, as add-on JSON lists them ({@code [true, false]}).
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Byte> bools(@NonNull String name, boolean @NonNull ... values) {
        List<Byte> bytes = new java.util.ArrayList<>(values.length);
        for (boolean value : values) {
            bytes.add((byte) (value ? 1 : 0));
        }
        return new BlockProperty<>(name, Type.BOOLEAN, bytes);
    }

    /**
     * An integer state with the given values in order.
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Integer> ints(@NonNull String name, @NonNull Integer... values) {
        return new BlockProperty<>(name, Type.INTEGER, List.of(values));
    }

    /**
     * An integer state with every value from {@code fromInclusive} to {@code toInclusive}, ascending.
     *
     * @param name the property name
     * @param fromInclusive the first value, which is the default
     * @param toInclusive the last value
     * @return the property
     * @throws IllegalArgumentException if the range has no values; a {@code toInclusive} more than one below
     *         {@code fromInclusive} fails with a {@link NegativeArraySizeException}
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<Integer> range(@NonNull String name, int fromInclusive, int toInclusive) {
        Integer[] values = new Integer[toInclusive - fromInclusive + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = fromInclusive + i;
        }
        return ints(name, values);
    }

    /**
     * A string state with the given values in order.
     *
     * @param name the property name
     * @param values the values in order; the first one is the default
     * @return the property
     * @throws IllegalArgumentException if {@code values} is empty
     * @since 1.0.0
     */
    public static @NonNull BlockProperty<String> strings(@NonNull String name, @NonNull String... values) {
        return new BlockProperty<>(name, Type.STRING, List.of(values));
    }
}
