package dev.opencollab.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.block.BlockRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /*
     * The integral conversions truncate toward zero and refuse a value whose whole part does not fit the leaf's bits. A
     * rule that rounds otherwise, or computes in float as BDS does (use_duration: the float product truncated, BDS
     * 1.26.36.1 to 1.26.70.24), rounds before it calls them (the linear rule's rounding); a value past a limit the
     * builder does not check would otherwise wrap silently (300 as a byte is 44). An unsigned bit pattern (255 as a byte)
     * fits.
     */

    public static byte toByte(double value) {
        return (byte) checked(value, Byte.MIN_VALUE, 0xFF, "byte");
    }

    public static short toShort(double value) {
        return (short) checked(value, Short.MIN_VALUE, 0xFFFF, "short");
    }

    public static int toInt(double value) {
        return (int) checked(value, Integer.MIN_VALUE, 0xFFFFFFFFL, "int");
    }

    private static long checked(double value, long min, long max, String type) {
        long whole = (long) value;
        if (whole < min || whole > max || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(value + " does not fit an NBT " + type + " leaf: a range check of the builder is missing");
        }
        return whole;
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

    /**
     * Compares two dotted versions number by number ({@code 1.19.40} before {@code 1.26.60}); a missing number is 0.
     *
     * @throws IllegalArgumentException if a version is not dotted numbers
     */
    public static int compareVersions(@NonNull String a, @NonNull String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        try {
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
                if (c != 0) {
                    return c;
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a version of dotted numbers: " + a + " or " + b, e);
        }
        return 0;
    }

    /**
     * A block descriptor as item components send it: the full {@code {name, states, tags}} triple at {@code path} ("" for the
     * node itself), "" for what is not set, the states as ints ({@link #states}).
     */
    public static void blockDescriptor(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull String path, @Nullable String name,
                                       @NonNull Map<String, Object> states, @Nullable String tags) {
        String prefix = path.isEmpty() ? "" : path + ".";
        out.put(prefix + "name", name == null ? "" : name);
        out.put(prefix + "states", states(ctx, states));
        out.put(prefix + "tags", tags == null ? "" : tags);
    }

    /**
     * An item descriptor at {@code path}: {@code {name}} for an item the registry knows, {@code {}} for any other, which is
     * reported for the field at {@code fieldPath} of the component. No item is the empty descriptor too (unverified: no capture
     * has an entry without item).
     */
    public static void itemDescriptor(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull String path, @NonNull String component,
                                      @NonNull String fieldPath, @Nullable String item) {
        NbtMap descriptor = item != null && ctx.registry().itemExists(item)
            ? NbtMap.builder().putString("name", namespaced(item)).build() : NbtMap.EMPTY;
        if (item != null && descriptor.isEmpty()) {
            ctx.drop(component, fieldPath + " " + item + " is not an item");
        }
        out.put(path, descriptor);
    }

    /**
     * The states of an item component's block descriptor, as BDS 1.26.36.1 to 1.26.70.24 send them (the placers' {@code use_on}
     * and {@code dispense_on}, digger's {@code block}): every value an int, whatever the named block's states are. An int is
     * sent as given, a boolean as 1 or 0, and a string as 0 ({@code pillar_axis} {@code "x"}, {@code "y"}, {@code "z"} and
     * {@code "w"}, {@code candles} {@code "2"}: all 0), which loses the value, so a string is reported. Names the block does
     * not have, and blocks the registry lacks, are sent as given (oak_log {@code paletter_unknown}, stone {@code paletter:k}).
     */
    public static @NonNull NbtMap states(WireContext ctx, Map<String, Object> states) {
        NbtMapBuilder out = NbtMap.builder();
        states.forEach((name, value) -> {
            int converted;
            if (value instanceof Boolean b) {
                converted = b ? 1 : 0;
            } else if (value instanceof Integer i) {
                converted = i;
            } else {
                converted = 0;
                ctx.changed("block descriptor", "state " + name + " = " + value + " is sent as 0: BDS sends every state of an item's block descriptor as an int, a string as 0");
            }
            out.putInt(name, converted);
        });
        return out.build();
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
