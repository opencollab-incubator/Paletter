package org.geysermc.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.paletter.annotation.Internal;

/**
 * The values of one key by dense position, in primitive arrays for numbers and booleans: reading one does not allocate
 * and touches one or two arrays. Immutable: its fields are final, so it can be published without a lock.
 */
@Internal
public abstract class Column {
    /** @return the column for the values of a key of this type, a missing value being null */
    static Column of(Class<?> type, @Nullable Object[] values) {
        if (type == Float.class) {
            float[] floats = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                floats[i] = values[i] == null ? Float.NaN : (Float) values[i];
            }
            return new Floats(floats);
        }
        if (type == Integer.class) {
            int[] ints = new int[values.length];
            boolean[] known = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                known[i] = values[i] != null;
                ints[i] = values[i] == null ? 0 : (Integer) values[i];
            }
            return new Ints(ints, known);
        }
        if (type == Boolean.class) {
            byte[] booleans = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                booleans[i] = values[i] == null ? 0 : (byte) ((Boolean) values[i] ? 2 : 1);
            }
            return new Booleans(booleans);
        }
        return new Objects(values.clone());
    }

    /** @return true if something says the value at this position */
    public abstract boolean known(int index);

    /** @return the value, boxed, or null if nothing says */
    public abstract @Nullable Object get(int index);

    /** @return the value of a float column; meaningless where {@link #known} is false */
    public float getFloat(int index) {
        throw new ClassCastException("not a float column");
    }

    /** @return the value of an int column; meaningless where {@link #known} is false */
    public int getInt(int index) {
        throw new ClassCastException("not an int column");
    }

    /** @return the value of a boolean column; meaningless where {@link #known} is false */
    public boolean getBoolean(int index) {
        throw new ClassCastException("not a boolean column");
    }

    /** Floats, NaN where nothing says. */
    private static final class Floats extends Column {
        private final float[] values;

        Floats(float[] values) {
            this.values = values;
        }

        @Override
        public boolean known(int index) {
            float value = values[index];
            return value == value;
        }

        @Override
        public @Nullable Object get(int index) {
            return known(index) ? values[index] : null;
        }

        @Override
        public float getFloat(int index) {
            return values[index];
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
        public boolean known(int index) {
            return known[index];
        }

        @Override
        public @Nullable Object get(int index) {
            return known[index] ? values[index] : null;
        }

        @Override
        public int getInt(int index) {
            return values[index];
        }
    }

    /** Booleans, one byte each: 0 where nothing says, 1 false, 2 true. */
    private static final class Booleans extends Column {
        private final byte[] values;

        Booleans(byte[] values) {
            this.values = values;
        }

        @Override
        public boolean known(int index) {
            return values[index] != 0;
        }

        @Override
        public @Nullable Object get(int index) {
            return values[index] == 0 ? null : values[index] == 2;
        }

        @Override
        public boolean getBoolean(int index) {
            return values[index] == 2;
        }
    }

    /** Strings and shapes: shared instances. */
    private static final class Objects extends Column {
        private final @Nullable Object[] values;

        Objects(@Nullable Object[] values) {
            this.values = values;
        }

        @Override
        public boolean known(int index) {
            return values[index] != null;
        }

        @Override
        public @Nullable Object get(int index) {
            return values[index];
        }
    }
}
