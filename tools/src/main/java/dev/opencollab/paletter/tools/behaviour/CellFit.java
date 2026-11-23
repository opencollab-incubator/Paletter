package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.internal.behaviour.BlockSchema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A function of a block's states written as simply as it is: one value for every state, a map over the one property it
 * depends on, or a table over the properties it depends on, keyed by property values (the generated code's {@code Cell}).
 * A state may be {@link #ANY}: its value does not matter (the capture of a state without a value).
 */
final class CellFit {
    /** A value that may be anything. */
    static final Object ANY = new Object() {
        @Override
        public String toString() {
            return "ANY";
        }
    };

    private CellFit() {
    }

    /** A function of a block's state properties (by name, values as {@link BlockSchema#value} reads them). */
    sealed interface Fit permits Constant, ByProperty, Table {
        @Nullable Object get(Map<String, Object> properties);
    }

    record Constant(@Nullable Object value) implements Fit {
        @Override
        public @Nullable Object get(Map<String, Object> properties) {
            return value;
        }
    }

    /** @param keys the property's values in {@link BlockSchema#ORDER}; {@code values} the value for each */
    record ByProperty(String property, List<Object> keys, List<@Nullable Object> values) implements Fit {
        @Override
        public @Nullable Object get(Map<String, Object> properties) {
            int at = keys.indexOf(properties.get(property));
            return at < 0 ? null : values.get(at);
        }
    }

    /** @param values the value of each combination of the domains, the last property fastest */
    record Table(List<String> properties, List<List<Object>> domains, List<@Nullable Object> values) implements Fit {
        @Override
        public @Nullable Object get(Map<String, Object> properties) {
            int index = 0;
            for (int i = 0; i < this.properties.size(); i++) {
                int at = domains.get(i).indexOf(properties.get(this.properties.get(i)));
                if (at < 0) {
                    return null;
                }
                index = index * domains.get(i).size() + at;
            }
            return values.get(index);
        }
    }

    /**
     * @param states the properties of each state of the block
     * @param values the value of each state (null: unknown), or {@link #ANY}
     * @return the simplest function that gives every value that is not {@link #ANY}; {@link #ANY} states read null in a map or table
     */
    static Fit fit(List<Map<String, Object>> states, List<Object> values) {
        Set<Object> distinct = new HashSet<>();
        for (Object value : values) {
            if (value != ANY) {
                distinct.add(value == null ? Constant.class : value);
            }
        }
        if (distinct.size() <= 1) {
            return new Constant(distinct.isEmpty() || distinct.contains(Constant.class) ? null : distinct.iterator().next());
        }
        Map<String, List<Object>> domains = domains(states);
        List<String> relevant = new ArrayList<>();
        for (String property : domains.keySet()) {
            if (dependsOn(states, values, property)) {
                relevant.add(property);
            }
        }
        if (relevant.isEmpty() || !functionOf(states, values, relevant)) {
            // Not every combination is a state: the values depend on all properties together
            relevant = new ArrayList<>(domains.keySet());
        }
        if (relevant.size() == 1) {
            String property = relevant.getFirst();
            List<Object> keys = domains.get(property);
            List<Object> byKey = new ArrayList<>(Collections.nCopies(keys.size(), null));
            for (int i = 0; i < states.size(); i++) {
                if (values.get(i) != ANY) {
                    byKey.set(keys.indexOf(states.get(i).get(property)), values.get(i));
                }
            }
            return new ByProperty(property, keys, byKey);
        }
        List<List<Object>> tableDomains = relevant.stream().map(domains::get).toList();
        int size = tableDomains.stream().mapToInt(List::size).reduce(1, (a, b) -> a * b);
        Table empty = new Table(relevant, tableDomains, new ArrayList<>(Collections.nCopies(size, null)));
        for (int i = 0; i < states.size(); i++) {
            if (values.get(i) != ANY) {
                empty.values().set(index(empty, states.get(i)), values.get(i));
            }
        }
        return empty;
    }

    private static int index(Table table, Map<String, Object> state) {
        int index = 0;
        for (int i = 0; i < table.properties().size(); i++) {
            index = index * table.domains().get(i).size() + table.domains().get(i).indexOf(state.get(table.properties().get(i)));
        }
        return index;
    }

    /** @return each property's values over the states, properties by name, values in {@link BlockSchema#ORDER} */
    static Map<String, List<Object>> domains(List<Map<String, Object>> states) {
        Map<String, Set<Object>> domains = new TreeMap<>();
        for (Map<String, Object> state : states) {
            state.forEach((name, value) -> domains.computeIfAbsent(name, n -> new TreeSet<>(BlockSchema.ORDER)).add(value));
        }
        Map<String, List<Object>> result = new TreeMap<>();
        domains.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return result;
    }

    /** @return true if two states that differ only in {@code property} have different values (neither {@link #ANY}) */
    private static boolean dependsOn(List<Map<String, Object>> states, List<Object> values, String property) {
        Map<Map<String, Object>, Object> seen = new HashMap<>();
        for (int i = 0; i < states.size(); i++) {
            if (values.get(i) == ANY) {
                continue;
            }
            Map<String, Object> others = new TreeMap<>(states.get(i));
            others.remove(property);
            Object value = values.get(i) == null ? Constant.class : values.get(i);
            Object before = seen.putIfAbsent(others, value);
            if (before != null && !before.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if the values (but {@link #ANY}) are a function of these properties */
    private static boolean functionOf(List<Map<String, Object>> states, List<Object> values, List<String> properties) {
        Map<List<Object>, Object> seen = new HashMap<>();
        for (int i = 0; i < states.size(); i++) {
            if (values.get(i) == ANY) {
                continue;
            }
            List<Object> key = new ArrayList<>();
            for (String property : properties) {
                key.add(states.get(i).get(property));
            }
            Object value = values.get(i) == null ? Constant.class : values.get(i);
            Object before = seen.putIfAbsent(key, value);
            if (before != null && !Objects.equals(before, value)) {
                return false;
            }
        }
        return true;
    }
}
