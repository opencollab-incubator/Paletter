package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The table of a closed enum BDS's schema lists, with a leaf observed for every one of its values. Unlike a {@link Table},
 * which holds the values a capture happened to try, this one is complete: the encoder has a case for each value and no
 * fallback, so a value the schema lacks is a compile error of the generated API, not a guess. A table that misses a value
 * stays a {@link Table} (or a hand transform) until a capture shows it.
 */
public final class EnumTable extends Rule {
    static final String KIND = "enum-table";

    /** Enum value -> the leaf's value, by enum value. */
    public final Map<String, String> table;

    public EnumTable(Map<String, String> table) {
        this.table = table;
    }

    static EnumTable read(JsonObject json) {
        Map<String, String> table = new LinkedHashMap<>();
        json.getAsJsonObject("table").entrySet().forEach(e -> table.put(e.getKey(), e.getValue().getAsString()));
        return new EnumTable(table);
    }

    /**
     * @param fitted the rule the observations gave
     * @param schema the values of the enum in BDS's schema
     * @return the complete table if {@code fitted} is a table of exactly the values of the schema, else null
     */
    public static EnumTable fit(Rule fitted, Set<String> schema) {
        return fitted instanceof Table seen && !schema.isEmpty() && seen.table.keySet().equals(new TreeSet<>(schema))
            ? new EnumTable(seen.table) : null;
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
        e.code().beginControlFlow("switch ($L)", e.key());
        for (String k : new TreeSet<>(table.keySet())) {
            String v = table.get(k);
            e.code().addStatement("case $S -> $L", k, e.write(v.equals(NOT_SENT) ? v : v + " (" + nbt + ")"));
        }
        e.code().addStatement("default -> throw new $T($S + $L)", IllegalStateException.class, e.path() + " is not a value of its enum: ", e.key());
        e.code().endControlFlow();
    }

    @Override
    String evaluate(JsonPrimitive p) {
        String k = p.isString() ? p.getAsString() : p.toString();
        return table.containsKey(k) ? table.get(k) + " (" + nbt + ")" : null;
    }
}
