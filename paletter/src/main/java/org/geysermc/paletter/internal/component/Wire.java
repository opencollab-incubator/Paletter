package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtType;

import java.util.List;

/**
 * Value conversions the generated encoders use: add-on JSON values to the NBT type a wire leaf has.
 */
public final class Wire {
    /** An empty list without element type, as BDS sends empty lists ({@code TAG_End}). */
    public static final NbtList<?> EMPTY_LIST = new NbtList<>(NbtType.END, List.of());

    private Wire() {
    }

    public static byte bool(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    public static byte toByte(double value) {
        return (byte) (long) value;
    }

    public static short toShort(double value) {
        return (short) (long) value;
    }

    public static int toInt(double value) {
        return (int) (long) value;
    }

    public static float toFloat(double value) {
        return (float) value;
    }

    public static @NonNull NbtList<String> strings(@NonNull List<String> values) {
        return values.isEmpty() ? emptyList() : new NbtList<>(NbtType.STRING, values);
    }

    public static @NonNull NbtList<Integer> ints(@NonNull List<Integer> values) {
        return values.isEmpty() ? emptyList() : new NbtList<>(NbtType.INT, values);
    }

    public static @NonNull NbtList<Float> floats(@NonNull List<Float> values) {
        return values.isEmpty() ? emptyList() : new NbtList<>(NbtType.FLOAT, values);
    }

    /**
     * @return the identifier with the {@code minecraft:} namespace if it has none
     */
    public static @NonNull String namespaced(@NonNull String identifier) {
        return identifier.indexOf(':') < 0 ? "minecraft:" + identifier : identifier;
    }

    /**
     * @return a number as add-on JSON writes it (no {@code .0} for whole numbers): the key of a value table
     */
    public static @NonNull String key(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public static <T> @NonNull NbtList<T> emptyList() {
        return (NbtList<T>) EMPTY_LIST;
    }
}
