package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A field whose JSON type decides the rule: one leaf, a rule for each type the values have ({@code repair_amount}: a
 * number is a float, a string is Molang). Each type is fitted on its own observations, so a leaf the types write with
 * different NBT types or by different rules is explained instead of left to a hand transform. The generated API holds such
 * a field as one sealed type of the union ({@link Emitter#holds}), never as {@code Object}.
 */
public final class Union extends Rule {
    static final String KIND = "union";

    /** JSON type ({@code boolean}, {@code number}, {@code string}, {@code array}, {@code object}) -> the rule of its values. */
    public final Map<String, Rule> by;

    public Union(Map<String, Rule> by) {
        this.by = by;
    }

    static Union read(JsonObject json) {
        Map<String, Rule> by = new TreeMap<>();
        json.getAsJsonObject("by").entrySet().forEach(e -> by.put(e.getKey(), Rule.of(e.getValue().getAsJsonObject())));
        return new Union(by);
    }

    /**
     * @param fit fits the rule of observations that all have one JSON type; null if none fits
     * @return the rules of the types of {@code set}, or null if it has one type only or a type has no rule
     */
    public static Union fit(List<Observation> set, String wire, Function<List<Observation>, Rule> fit) {
        Map<String, List<Observation>> types = set.stream().collect(Collectors.groupingBy(p -> type(p.json()), TreeMap::new, Collectors.toList()));
        if (types.size() < 2) {
            return null;
        }
        Map<String, Rule> by = new TreeMap<>();
        for (Map.Entry<String, List<Observation>> type : types.entrySet()) {
            Rule rule = fit.apply(type.getValue());
            if (rule == null) {
                return null;
            }
            type.getValue().stream().map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().ifPresent(leaf -> rule.nbt = leaf.type());
            by.put(type.getKey(), rule);
        }
        return new Union(by);
    }

    /** @return the name of the JSON type of a value: {@code boolean}, {@code number}, {@code string}, {@code array} or {@code object} */
    public static String type(JsonElement json) {
        if (json.isJsonArray()) {
            return "array";
        }
        if (json.isJsonObject()) {
            return "object";
        }
        JsonPrimitive p = json.getAsJsonPrimitive();
        return p.isBoolean() ? "boolean" : p.isNumber() ? "number" : "string";
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
    void params(JsonObject out) {
        JsonObject types = new JsonObject();
        by.forEach((type, rule) -> types.add(type, rule.toJson()));
        out.add("by", types);
    }

    @Override
    public void emit(Emitter e) {
        by.forEach((type, rule) -> {
            e.code().beginControlFlow("if ($L)", e.holds(type));
            rule.emit(e.as(type));
            e.code().endControlFlow();
        });
    }

    @Override
    String evaluate(JsonPrimitive p) {
        Rule rule = by.get(type(p));
        return rule == null ? null : rule.evaluate(p);
    }

    @Override
    String evaluateStructured(JsonElement json) {
        Rule rule = by.get(type(json));
        return rule == null ? null : rule.evaluate(json);
    }
}
