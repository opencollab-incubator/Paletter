package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.CodeBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A value table: the leaf for each JSON value ({@code enum -> int}, {@code string -> string}; {@code <not sent>} for a
 * value BDS sends no leaf for). A rule only for closed sets of values: booleans and enums.
 */
public final class Table extends Rule {
    static final String KIND = "table";

    /** JSON value (a string as it is, others as JSON text) -> the leaf's value, by JSON value. */
    public final Map<String, String> table;

    public Table(Map<String, String> table) {
        this.table = table;
    }

    static Table read(JsonObject json) {
        Map<String, String> table = new LinkedHashMap<>();
        json.getAsJsonObject("table").entrySet().forEach(e -> table.put(e.getKey(), e.getValue().getAsString()));
        return new Table(table);
    }

    /**
     * @return the table of the observations, a {@link Constant} if every value gives the same leaf, or null if one value
     *         gives two leaves (no function of the value)
     */
    public static Rule fit(List<JsonElement> xs, List<Leaf> ys) {
        Map<String, String> table = new TreeMap<>();
        for (int i = 0; i < xs.size(); i++) {
            String key = xs.get(i).getAsJsonPrimitive().isString() ? xs.get(i).getAsString() : xs.get(i).toString();
            String value = ys.get(i) == null ? NOT_SENT : String.valueOf(ys.get(i).value());
            String existing = table.putIfAbsent(key, value);
            if (existing != null && !existing.equals(value)) {
                return null;
            }
        }
        if (table.values().stream().distinct().count() == 1) {
            // every tested value gives the same leaf: a constant the field's presence switches on
            return new Constant(table.values().iterator().next());
        }
        return new Table(table);
    }

    /** @return whether a JSON value of the table is a number: a table of numbers fits any numbers, it is no rule */
    public boolean numericKeys() {
        return table.keySet().stream().anyMatch(k -> k.matches("-?[\\d.]+"));
    }

    /** @return the values of a table onto an integral leaf, when every one is a non negative integer; else null */
    public long[] integral() {
        if (nbt == null || !List.of("byte", "short", "int", "long").contains(nbt)) {
            return null;
        }
        return table.values().stream().allMatch(v -> v.matches("\\d{1,18}")) ? table.values().stream().mapToLong(Long::parseLong).toArray() : null;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean writesValue() {
        return true;
    }

    @Override
    public List<String> kindTags() {
        return List.of(table.values().stream().allMatch(v -> v.matches("-?\\d+")) ? "enum->int" : "value-map");
    }

    @Override
    void params(JsonObject out) {
        JsonObject entries = new JsonObject();
        table.forEach(entries::addProperty);
        out.add("table", entries);
    }

    @Override
    public void emit(Emitter e) {
        CodeBlock key = e.key();
        e.code().beginControlFlow("switch ($L)", key);
        String fallback = openFallback(e.documentedValues());
        for (String k : new TreeSet<>(table.keySet())) {
            String v = table.get(k);
            if (fallback != null && !e.documentedValues().contains(k) && v.equals(fallback)) {
                continue; // an undocumented value: the default below
            }
            e.code().addStatement("case $S -> $L", k, e.write(typed(v)));
        }
        if (fallback != null) {
            e.code().addStatement("default -> $L", e.write(typed(fallback)));
        } else {
            e.code().addStatement("default -> ctx.drop($L, $S + $L + $S)", e.component(), e.path() + " = ", key, " has no known wire value");
        }
        e.code().endControlFlow();
    }

    private String typed(String value) {
        return value.equals(NOT_SENT) ? value : value + " (" + nbt + ")";
    }

    /**
     * What BDS sends for every string an open enum does not document: the one output of the table entries observed for
     * undocumented values (the capture's own samples), or null if there are none or they disagree.
     */
    private String openFallback(List<String> documented) {
        if (documented == null) {
            return null;
        }
        Set<String> outputs = new TreeSet<>();
        for (String k : table.keySet()) {
            if (!documented.contains(k)) {
                outputs.add(table.get(k));
            }
        }
        return outputs.size() == 1 ? outputs.iterator().next() : null;
    }

    @Override
    String evaluate(JsonPrimitive p) {
        String k = p.isString() ? p.getAsString() : p.toString();
        return table.containsKey(k) ? table.get(k) + " (" + nbt + ")" : null;
    }
}
