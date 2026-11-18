package org.geysermc.paletter.vanilla;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An integer block state, e.g. {@link BedrockStates#AGE}. Its values are a range ({@link #range}) or a list.
 *
 * @since 1.0.0
 */
public final class IntState extends BedrockState<Integer> {
    private final List<Integer> values;

    private IntState(String name, List<Integer> values) {
        super(name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Int state " + name + " has no values");
        }
        this.values = List.copyOf(values);
    }

    /**
     * Creates a state with a range of values.
     *
     * @param name the state name as in the block state NBT
     * @param min the lowest value, inclusive
     * @param max the highest value, inclusive
     * @return a state with the values {@code min} to {@code max}, both inclusive
     * @throws IllegalArgumentException if {@code max} is below {@code min}
     */
    public static @NonNull IntState range(@NonNull String name, int min, int max) {
        if (max < min) {
            throw new IllegalArgumentException("Empty range " + min + ".." + max + " of " + name);
        }
        List<Integer> values = new ArrayList<>(max - min + 1);
        for (int i = min; i <= max; i++) {
            values.add(i);
        }
        return new IntState(name, values);
    }

    /**
     * Creates a state with a list of values.
     *
     * @param name the state name as in the block state NBT
     * @param values the values the state can have; at least one
     * @return a new state
     * @throws IllegalArgumentException if there are no values
     */
    public static @NonNull IntState of(@NonNull String name, int @NonNull ... values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return new IntState(name, list);
    }

    @Override
    public @NonNull List<Integer> values() {
        return values;
    }

    /**
     * The lowest value.
     *
     * @return the lowest value of the state
     */
    public int min() {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    /**
     * The highest value.
     *
     * @return the highest value of the state
     */
    public int max() {
        int max = Integer.MIN_VALUE;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    @Override
    @NonNull Object toNbt(@NonNull Integer value) {
        return value;
    }

    @Override
    @Nullable Integer fromNbt(@NonNull Object value) {
        return value instanceof Integer integer ? integer : value instanceof Number number ? number.intValue() : null;
    }
}
