package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.vanilla.StateValues;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The names of the shared value list constants ({@code BlockValues}): the property name without {@code minecraft:} in
 * UPPER_SNAKE ({@code CARDINAL_DIRECTION}), with it where two properties would otherwise share a name
 * ({@code MINECRAFT_FACING_DIRECTION} beside {@code facing_direction}); an int property adds its range ({@code AGE_0_15}),
 * a string property with several value lists the hash of its values.
 */
final class BlockNames {
    private BlockNames() {
    }

    /** @return the constant of each value list, sorted by name */
    static Map<StateValues, String> of(Collection<StateValues> lists) {
        Set<StateValues> distinct = new LinkedHashSet<>(lists);
        Map<String, Set<String>> byStripped = new HashMap<>();
        Map<String, Integer> stringLists = new HashMap<>();
        for (StateValues values : distinct) {
            byStripped.computeIfAbsent(strip(values.name()), k -> new java.util.TreeSet<>()).add(values.name());
            if (values.value(0) instanceof String) {
                stringLists.merge(values.name(), 1, Integer::sum);
            }
        }
        Map<String, StateValues> named = new TreeMap<>();
        for (StateValues values : distinct) {
            String base = constant(byStripped.get(strip(values.name())).size() > 1 ? values.name() : strip(values.name()));
            String name = base + suffix(values, stringLists.getOrDefault(values.name(), 0) > 1);
            StateValues other = named.put(name, values);
            if (other != null) {
                throw new IllegalStateException("The value lists " + other + " and " + values + " would both be " + name);
            }
        }
        Map<StateValues, String> result = new java.util.LinkedHashMap<>();
        named.forEach((name, values) -> result.put(values, name));
        return result;
    }

    private static String suffix(StateValues values, boolean severalStringLists) {
        Object first = values.value(0);
        if (first instanceof Byte) {
            return "";
        }
        if (first instanceof Integer) {
            List<Object> ints = values.values();
            boolean run = true;
            for (int i = 1; i < ints.size(); i++) {
                run &= (Integer) ints.get(i) == (Integer) first + i;
            }
            List<Object> shown = run ? List.of(first, ints.getLast()) : ints;
            StringBuilder suffix = new StringBuilder();
            shown.forEach(v -> suffix.append('_').append(v.toString().replace("-", "M")));
            return suffix.toString();
        }
        return severalStringLists ? String.format(Locale.ROOT, "_%06X", values.values().toString().hashCode() & 0xFFFFFF) : "";
    }

    private static String strip(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    /** UPPER_SNAKE of a name: every run of other characters than letters and digits is one underscore. */
    static String constant(String name) {
        String constant = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return Character.isDigit(constant.charAt(0)) ? "_" + constant : constant;
    }
}
