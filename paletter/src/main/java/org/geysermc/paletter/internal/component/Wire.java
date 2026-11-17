package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

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

    /** The shapes of a Molang number the captures have: digits with or without a fraction or an exponent, spaces around. */
    private static final Pattern MOLANG_NUMBER = Pattern.compile("\\s*-?(\\d+\\.?\\d*|\\.\\d+)([eE]-?\\d+)?\\s*");

    /**
     * A Molang number as BDS sends it where it computes an expression (bone visibility, BDS 1.26.36.1 to 1.26.70.24): the
     * float in six decimals of its exact value, a tie to the even digit ({@code 0.0078125} is {@code "0.007812"}), the
     * sign kept ({@code "-0.000000"}).
     *
     * @return the number, or null if the text is not a number of a captured shape or is beyond a float
     */
    public static @Nullable String molangNumber(@NonNull String text) {
        if (!MOLANG_NUMBER.matcher(text).matches()) {
            return null;
        }
        float value = Float.parseFloat(text.trim());
        if (Float.isInfinite(value)) {
            return null;
        }
        String digits = new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        return (Float.floatToRawIntBits(value) < 0 && !digits.startsWith("-") ? "-" : "") + digits;
    }

    /**
     * A Molang expression as BDS sends it: {@code "true"} and {@code "false"} as the numbers 1 and 0, a number as
     * {@link #molangNumber}, anything else as written (BDS computes more expressions than that; Paletter does not).
     */
    public static @NonNull String molang(@NonNull String expression) {
        if (expression.equals("true") || expression.equals("false")) {
            return expression.equals("true") ? "1.000000" : "0.000000";
        }
        String number = molangNumber(expression);
        return number != null ? number : expression;
    }

    /**
     * The distinct names in the order BDS sends a set of them: ascending by the unsigned 64 bit hash of each name (BDS keeps
     * them in a {@code std::set<HashedString>}). Names with equal hashes keep their order; BDS's order for them is not known.
     */
    public static @NonNull List<String> byNameHash(@NonNull List<String> names) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(names));
        ordered.sort((a, b) -> Long.compareUnsigned(BlockRegistry.nameHash(a), BlockRegistry.nameHash(b)));
        return ordered;
    }

    @SuppressWarnings("unchecked")
    public static <T> @NonNull NbtList<T> emptyList() {
        return (NbtList<T>) EMPTY_LIST;
    }
}
