package dev.opencollab.paletter.internal.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One block state property of the generated vanilla block definitions: its name and its values in the order Bedrock
 * enumerates them. The values are what the state NBT holds: {@code Byte} 0 and 1 for a boolean, {@code Integer} or
 * {@code String}. Not API.
 */
public final class StateValues {
    private final String name;
    private final Object[] values;

    private StateValues(String name, Object[] values) {
        this.name = Objects.requireNonNull(name);
        this.values = values;
        if (values.length == 0) {
            throw new IllegalArgumentException(name + " has no values");
        }
    }

    /** A boolean property: false, then true. */
    public static @NonNull StateValues bool(@NonNull String name) {
        return new StateValues(name, new Object[] {(byte) 0, (byte) 1});
    }

    /** An int property with the values {@code first} to {@code last}, ascending. */
    public static @NonNull StateValues ints(@NonNull String name, int first, int last) {
        if (last < first) {
            throw new IllegalArgumentException(name + ": " + first + " to " + last);
        }
        Object[] values = new Object[last - first + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = first + i;
        }
        return new StateValues(name, values);
    }

    /** A string property with these values, in this order. */
    public static @NonNull StateValues strings(@NonNull String name, @NonNull String @NonNull ... values) {
        return new StateValues(name, values.clone());
    }

    /** An int property whose values are not a run from the first to the last: these, in this order. */
    public static @NonNull StateValues intList(@NonNull String name, int @NonNull ... values) {
        Object[] boxed = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return new StateValues(name, boxed);
    }

    public @NonNull String name() {
        return name;
    }

    public int size() {
        return values.length;
    }

    /** @return the value at {@code index}, as the state NBT holds it */
    public @NonNull Object value(int index) {
        return values[index];
    }

    /** @return the values, as the state NBT holds them */
    public @NonNull List<Object> values() {
        return List.of(values);
    }

    /**
     * @param value a value as the state NBT holds it, or a {@code Boolean} for a boolean property
     * @return its index, or -1 if the property has no such value
     */
    public int indexOf(@NonNull Object value) {
        Object wanted = value instanceof Boolean bool ? (Object) (byte) (bool ? 1 : 0) : value;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateValues that && name.equals(that.name) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return name + Arrays.toString(values);
    }
}
