package dev.opencollab.paletter.internal.behaviour;

import dev.opencollab.paletter.annotation.Internal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The state properties of a block as one string, e.g. {@code facing_direction=0..5,open_bit=false|true,wood='oak'|'spruce'}:
 * the properties by name, each with its values in order ({@link #ORDER}). Two blocks of the same identifier with the same
 * schema have the same states (the states of a vanilla block are every combination of its property values, which the
 * behaviour generator checks), so a palette's block can use the behaviour values of another data version exactly when
 * their schemas are equal. The generated behaviour data names each block's schema; {@link VanillaVersion} compares it.
 * <p>
 * Property values are read as {@link Boolean} (the bytes Bedrock stores for bits), {@link Integer} or {@link String}
 * ({@link #value}); strings are quoted, so a string never reads like a number or a bit.
 */
@Internal
public final class BlockSchema {
    /** Booleans false first, ints ascending, strings by {@link String#compareTo}; a type before the next one. */
    public static final Comparator<Object> ORDER = Comparator.comparingInt(BlockSchema::rank).thenComparing((a, b) -> compare(a, b));

    private BlockSchema() {
    }

    /** @return a property value of a state as the generated data writes it: a byte as a boolean, else unchanged */
    public static @Nullable Object value(@Nullable Object nbt) {
        return nbt instanceof Byte bit ? bit != 0 : nbt;
    }

    /** @return the schema of a block whose states have these {@code states} compounds */
    public static @NonNull String of(@NonNull Collection<NbtMap> states) {
        Map<String, TreeSet<Object>> domains = new TreeMap<>();
        for (NbtMap state : states) {
            state.forEach((name, value) -> domains.computeIfAbsent(name, n -> new TreeSet<>(ORDER)).add(value(value)));
        }
        StringBuilder out = new StringBuilder();
        domains.forEach((name, values) -> {
            out.append(out.isEmpty() ? "" : ",").append(name).append('=');
            List<Object> list = new ArrayList<>(values);
            boolean range = list.size() > 2 && list.getFirst() instanceof Integer first
                && list.getLast() instanceof Integer last && last - first == list.size() - 1;
            if (range) {
                out.append(list.getFirst()).append("..").append(list.getLast());
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                Object v = list.get(i);
                out.append(i == 0 ? "" : "|").append(v instanceof String s ? "'" + s + "'" : v);
            }
        });
        return out.toString();
    }

    /** A schema read back: the property names in order and the values of each. */
    public record Parsed(@NonNull String @NonNull [] properties, @NonNull Object @NonNull [] @NonNull [] domains) {
    }

    /** @return the properties and values {@link #of} wrote */
    public static @NonNull Parsed parse(@NonNull String schema) {
        List<String> names = new ArrayList<>();
        List<Object[]> domains = new ArrayList<>();
        int at = 0;
        while (at < schema.length()) {
            int equals = schema.indexOf('=', at);
            names.add(schema.substring(at, equals));
            List<Object> values = new ArrayList<>();
            at = equals + 1;
            while (true) {
                int end;
                if (schema.charAt(at) == '\'') {
                    end = schema.indexOf('\'', at + 1) + 1;
                    values.add(schema.substring(at + 1, end - 1));
                } else {
                    end = at;
                    while (end < schema.length() && schema.charAt(end) != '|' && schema.charAt(end) != ',') {
                        end++;
                    }
                    String text = schema.substring(at, end);
                    int range = text.indexOf("..");
                    if (range > 0) {
                        for (int i = Integer.parseInt(text.substring(0, range)); i <= Integer.parseInt(text.substring(range + 2)); i++) {
                            values.add(i);
                        }
                    } else {
                        values.add(text.equals("true") || text.equals("false") ? (Object) Boolean.parseBoolean(text) : (Object) Integer.parseInt(text));
                    }
                }
                at = end;
                if (at >= schema.length() || schema.charAt(at) == ',') {
                    at++;
                    break;
                }
                at++; // '|'
            }
            domains.add(values.toArray());
        }
        return new Parsed(names.toArray(String[]::new), domains.toArray(Object[][]::new));
    }

    private static int rank(Object value) {
        return value instanceof Boolean ? 0 : value instanceof Integer ? 1 : 2;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        return a instanceof Comparable c && a.getClass() == b.getClass() ? c.compareTo(b) : a.toString().compareTo(b.toString());
    }
}
