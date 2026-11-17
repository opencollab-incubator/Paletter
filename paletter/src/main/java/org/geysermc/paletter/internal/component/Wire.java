package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.List;
import java.util.Map;

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

    /**
     * A block descriptor as item components send it: the full {@code {name, states, tags}} triple at {@code path} ("" for the
     * node itself), "" for what is not set, the states typed like the named block's states in the palette's registry
     * ({@link #states}).
     */
    public static void blockDescriptor(@NonNull WireContext ctx, @NonNull WireNode out, @NonNull String path, @Nullable String name,
                                       @NonNull Map<String, Object> states, @Nullable String tags) {
        String prefix = path.isEmpty() ? "" : path + ".";
        out.put(prefix + "name", name == null ? "" : name);
        out.put(prefix + "states", states(ctx, name, states));
        out.put(prefix + "tags", tags == null ? "" : tags);
    }

    /**
     * An item descriptor at {@code path}: {@code {name}} for an item the registry knows, {@code {}} for any other, which is
     * reported for the field at {@code fieldPath} of the component. No item is the empty descriptor too.
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
     * The states of a block descriptor: a compound of state values, typed like the named block's states in the registry
     * (booleans as bytes, ints as the registry's type). BDS 1.26.63.1 sends an enum state as its index, which the registry
     * does not give, so a string state is sent as given and reported; a state the block lacks is reported too.
     */
    public static @NonNull NbtMap states(WireContext ctx, @Nullable String block, Map<String, Object> states) {
        NbtMap known = block == null ? null : ctx.registry().blockStates(block);
        if (!states.isEmpty() && block != null && known == null && (block.indexOf(':') < 0 || block.startsWith("minecraft:"))) {
            ctx.changed("block descriptor", "block " + block + " is not a block of this version; BDS drops a descriptor it cannot resolve");
        }
        NbtMapBuilder out = NbtMap.builder();
        states.forEach((name, value) -> {
            Object type = known == null ? null : known.get(name);
            if (known != null && type == null) {
                // the capture's evidence is a vanilla block without states (stone) and invented state names: BDS dropped them
                ctx.changed("block descriptor", known.isEmpty()
                    ? "block " + block + " has no states, so state " + name + " names none; BDS dropped such descriptors of a stateless block"
                    : "block " + block + " has no state " + name + "; unverified for a block with states, BDS dropped the descriptors of a stateless one");
            } else if (value instanceof String) {
                ctx.changed("block descriptor", "state " + name + " is sent as the string " + value + "; BDS sends an enum state as its index (unverified beyond pillar_axis y = 0)");
            }
            Object converted;
            if (value instanceof Boolean b) {
                converted = Wire.bool(b);
            } else if (value instanceof Integer i) {
                converted = type instanceof Byte ? (Object) i.byteValue() : i;
            } else {
                converted = value;
            }
            out.put(name, converted);
        });
        return out.build();
    }

    @SuppressWarnings("unchecked")
    public static <T> @NonNull NbtList<T> emptyList() {
        return (NbtList<T>) EMPTY_LIST;
    }
}
