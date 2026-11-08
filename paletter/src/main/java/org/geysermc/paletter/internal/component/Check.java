package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.vanilla.BedrockBlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validation the generated component records run in their constructors.
 */
public final class Check {
    private Check() {
    }

    public static void range(@NonNull String field, double value, @Nullable Double min, @Nullable Double max) {
        if ((min != null && value < min) || (max != null && value > max)) {
            throw new IllegalArgumentException(field + " must be in [" + format(min, "-inf") + ", " + format(max, "inf") + "], was "
                + format(value, ""));
        }
    }

    public static void oneOf(@NonNull String field, double value, double... allowed) {
        for (double a : allowed) {
            if (a == value) {
                return;
            }
        }
        StringBuilder list = new StringBuilder();
        for (double a : allowed) {
            list.append(list.isEmpty() ? "" : ", ").append(format(a, ""));
        }
        throw new IllegalArgumentException(field + " must be one of [" + list + "], was " + format(value, ""));
    }

    private static String format(@Nullable Double d, String absent) {
        if (d == null) {
            return absent;
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    public static <V> @NonNull Map<String, V> copy(@NonNull Map<String, V> map, @NonNull String field) {
        Objects.requireNonNull(map, field);
        Map<String, V> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(Objects.requireNonNull(key, field), Objects.requireNonNull(value, field)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Block descriptor state values are ints, strings or booleans.
     */
    public static void stateValues(@Nullable Map<String, Object> states, @NonNull String field) {
        if (states == null) {
            return;
        }
        states.forEach((name, value) -> {
            if (!(value instanceof Integer || value instanceof String || value instanceof Boolean)) {
                throw new IllegalArgumentException(field + ": the value of " + name + " must be an Integer, String or Boolean, was " + value);
            }
        });
    }

    /**
     * @return the states of a block state as descriptor values (byte states as booleans)
     */
    public static @NonNull Map<String, Object> states(@NonNull BedrockBlockState state) {
        Map<String, Object> states = new LinkedHashMap<>();
        NbtMap nbt = state.toNbt().getCompound("states");
        nbt.forEach((name, value) -> states.put(name, value instanceof Byte b ? (Object) (b != 0) : value));
        return Collections.unmodifiableMap(states);
    }
}
