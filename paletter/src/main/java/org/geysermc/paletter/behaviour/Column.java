package org.geysermc.paletter.behaviour;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;

/**
 * The values of one key by dense position, in primitive arrays for numbers and booleans: reading one does not allocate
 * and touches one array. Only {@link #get} boxes.
 */
abstract class Column {
    /** @return a column of no values for a key of this type */
    static Column empty(Class<?> type) {
        return type == Float.class ? new Floats(new float[0])
            : type == Integer.class ? new Ints(new int[0], new boolean[0])
            : type == Boolean.class ? new Booleans(new byte[0]) : new Objects(new Object[0]);
    }

    /** @return the column for the values of a key of this type, a missing value being null */
    static Column of(Class<?> type, @Nullable Object[] values) {
        return empty(type).extend(values);
    }

    abstract @Nullable Object get(int index);

    /** @return the number of values */
    abstract int size();

    /** @return a column of this one's values followed by {@code added} */
    abstract Column extend(@Nullable Object[] added);

    float getFloat(int index, float orElse) {
        throw new ClassCastException("not a float column");
    }

    int getInt(int index, int orElse) {
        throw new ClassCastException("not an int column");
    }

    boolean getBoolean(int index, boolean orElse) {
        throw new ClassCastException("not a boolean column");
    }

    /** Floats, NaN where nothing says. */
    private static final class Floats extends Column {
        private final float[] values;

        Floats(float[] values) {
            this.values = values;
        }

        @Override
        @Nullable Object get(int index) {
            float value = values[index];
            return value != value ? null : value;
        }

        @Override
        int size() {
            return values.length;
        }

        @Override
        Column extend(@Nullable Object[] added) {
            float[] all = Arrays.copyOf(values, values.length + added.length);
            for (int i = 0; i < added.length; i++) {
                all[values.length + i] = added[i] == null ? Float.NaN : (Float) added[i];
            }
            return new Floats(all);
        }

        @Override
        float getFloat(int index, float orElse) {
            float value = values[index];
            return value != value ? orElse : value;
        }
    }

    /** Ints and a flag per value for where something says. */
    private static final class Ints extends Column {
        private final int[] values;
        private final boolean[] known;

        Ints(int[] values, boolean[] known) {
            this.values = values;
            this.known = known;
        }

        @Override
        @Nullable Object get(int index) {
            return known[index] ? values[index] : null;
        }

        @Override
        int size() {
            return values.length;
        }

        @Override
        Column extend(@Nullable Object[] added) {
            int[] all = Arrays.copyOf(values, values.length + added.length);
            boolean[] allKnown = Arrays.copyOf(known, all.length);
            for (int i = 0; i < added.length; i++) {
                allKnown[values.length + i] = added[i] != null;
                all[values.length + i] = added[i] == null ? 0 : (Integer) added[i];
            }
            return new Ints(all, allKnown);
        }

        @Override
        int getInt(int index, int orElse) {
            return known[index] ? values[index] : orElse;
        }
    }

    /** Booleans, one byte each: 0 where nothing says, 1 false, 2 true. */
    private static final class Booleans extends Column {
        private final byte[] values;

        Booleans(byte[] values) {
            this.values = values;
        }

        @Override
        @Nullable Object get(int index) {
            return values[index] == 0 ? null : values[index] == 2;
        }

        @Override
        int size() {
            return values.length;
        }

        @Override
        Column extend(@Nullable Object[] added) {
            byte[] all = Arrays.copyOf(values, values.length + added.length);
            for (int i = 0; i < added.length; i++) {
                all[values.length + i] = added[i] == null ? 0 : (byte) ((Boolean) added[i] ? 2 : 1);
            }
            return new Booleans(all);
        }

        @Override
        boolean getBoolean(int index, boolean orElse) {
            byte value = values[index];
            return value == 0 ? orElse : value == 2;
        }
    }

    /** Strings and shapes: shared instances. */
    private static final class Objects extends Column {
        private final Object[] values;

        Objects(Object[] values) {
            this.values = values;
        }

        @Override
        @Nullable Object get(int index) {
            return values[index];
        }

        @Override
        int size() {
            return values.length;
        }

        @Override
        Column extend(@Nullable Object[] added) {
            Object[] all = Arrays.copyOf(values, values.length + added.length);
            System.arraycopy(added, 0, all, values.length, added.length);
            return new Objects(all);
        }
    }
}
