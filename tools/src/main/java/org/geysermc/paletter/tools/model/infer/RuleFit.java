package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.JsonPath;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Obs;
import org.geysermc.paletter.tools.model.rule.Affix;
import org.geysermc.paletter.tools.model.rule.Bitset;
import org.geysermc.paletter.tools.model.rule.Constant;
import org.geysermc.paletter.tools.model.rule.Count;
import org.geysermc.paletter.tools.model.rule.EmptyList;
import org.geysermc.paletter.tools.model.rule.EmptyMap;
import org.geysermc.paletter.tools.model.rule.EnumTable;
import org.geysermc.paletter.tools.model.rule.Identity;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.geysermc.paletter.tools.model.rule.Linear;
import org.geysermc.paletter.tools.model.rule.Observation;
import org.geysermc.paletter.tools.model.rule.Rule;
import org.geysermc.paletter.tools.model.rule.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fits the rule of one wire leaf to what a capture shows of a field: the JSON values BDS was given and the leaves it sent
 * ({@link Observation}). The shape of the JSON decides which kinds of rule are tried, in order; each kind fits itself
 * ({@link Rule}). A new kind of rule is tried here.
 */
final class RuleFit {
    private final Feedback feedback;

    RuleFit(Feedback feedback) {
        this.feedback = feedback;
    }

    /** @return the rule of the leaf the field's JSON value decides, with its NBT type and tags; null if no kind fits */
    Rule fit(Component component, String path, String wire, List<Observation> observations) {
        List<Observation> set = observations.stream().filter(p -> p.json() != null && !p.json().isJsonNull()).toList();
        if (set.isEmpty()) {
            return null;
        }
        String jsonName = path.isEmpty() ? "" : JsonPath.tokens(path).getLast();
        boolean scalars = set.stream().allMatch(p -> p.json().isJsonPrimitive());
        Rule rule;
        Field field = component.field(path);
        // an enum of the docs, or one BDS lists in its content log ("expects one of the following strings")
        boolean enumerated = field != null && !field.enumValues().isEmpty() || feedback.has(component.id(), path, "values");
        if (scalars) {
            rule = scalar(set, Observation::json, wire);
            if (rule instanceof Table && !tableAllowed(set, enumerated)) {
                rule = Affix.fit(set, wire);
            }
            EnumTable complete = EnumTable.fit(rule, closedValues(component, path));
            rule = complete != null ? complete : rule;
        } else if (set.stream().allMatch(p -> p.json().isJsonArray())) {
            rule = array(set, wire, enumerated || feedback.has(component.id(), path + "[]", "values"));
        } else if (set.stream().allMatch(p -> p.json().isJsonObject())) {
            rule = object(set, wire, enumerated || feedback.has(component.id(), path + "{}", "values"));
        } else {
            rule = mixed(set, wire, enumerated, enumerated || feedback.has(component.id(), path + "[]", "values"));
        }
        if (rule == null) {
            return null;
        }
        Leaf sample = set.stream().map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (sample != null) {
            rule.nbt = sample.type();
        }
        rule.tags = tags(component, path, jsonName, wire, rule, set);
        return rule;
    }

    /**
     * Mixed forms (shorthand and object, or a vector as a list and as an object): each JSON type is fitted separately, the
     * scalar part first, else the list part. A leaf only the list form sends is the list part's to explain: that the
     * scalar form does not send it says nothing about what a list puts there ({@code uv_lock}: a byte for the boolean, the
     * bones for the list).
     *
     * @param enumerated     whether the scalar values are of a closed set (an enum)
     * @param closedElements whether the list's elements are
     */
    static Rule mixed(List<Observation> set, String wire, boolean enumerated, boolean closedElements) {
        List<Observation> primitive = set.stream().filter(p -> p.json().isJsonPrimitive()).toList();
        List<Observation> lists = set.stream().filter(p -> p.json().isJsonArray()).toList();
        Rule rule = primitive.isEmpty() ? null : scalar(primitive, Observation::json, wire);
        if (rule instanceof Table && !tableAllowed(primitive, enumerated)) {
            rule = Affix.fit(primitive, wire);
        }
        boolean scalarSilent = rule instanceof Constant
            && primitive.stream().allMatch(p -> p.wire().get(wire) == null) && lists.stream().anyMatch(p -> p.wire().get(wire) != null);
        if ((rule == null || scalarSilent) && !lists.isEmpty()) {
            rule = array(lists, wire, closedElements);
        }
        return rule;
    }

    /** A value table generalizes only for closed sets: booleans and documented enums. */
    static boolean tableAllowed(List<Observation> set, boolean enumerated) {
        return set.stream().allMatch(p -> p.json().getAsJsonPrimitive().isBoolean())
            || (enumerated && set.stream().allMatch(p -> p.json().getAsJsonPrimitive().isString()));
    }

    /** identity, linear, table (or constant) between a scalar JSON value and a leaf, in that order. */
    static Rule scalar(List<Observation> set, Function<Observation, JsonElement> getter, String wire) {
        List<JsonElement> xs = new ArrayList<>();
        List<Leaf> ys = new ArrayList<>();
        for (Observation p : set) {
            JsonElement x = getter.apply(p);
            if (x == null || !x.isJsonPrimitive()) {
                return null;
            }
            xs.add(x);
            ys.add(p.wire().get(wire));
        }
        Rule rule = Identity.fit(xs, ys);
        if (rule == null) {
            rule = Linear.fit(xs, ys);
        }
        return rule != null ? rule : Table.fit(xs, ys);
    }

    /**
     * A linear rule onto whole steps of its input ({@code degrees / 90}) gets {@code multipleOf}: when every accepted value
     * is a multiple of the step and BDS refused a variant whose value is not. The API refuses such values too.
     */
    static void multipleOf(Rule rule, String path, List<Obs> accepted, List<Obs> rejected) {
        if (!(rule instanceof Linear linear) || !"int".equals(rule.nbt)) {
            return;
        }
        double step = 1 / linear.scale;
        if (Math.abs(step) < 2 || Math.abs(step - Math.rint(step)) > 1e-3) {
            return;
        }
        long n = Math.abs((long) Math.rint(step));
        Integer element = rule.element;
        Function<Obs, List<Double>> numbers = o -> {
            JsonElement value = o.variant().role().equals("omit") ? null : SpecCaptures.jsonAt(o.variant().json(), path);
            List<Double> out = new ArrayList<>();
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.add(value.getAsDouble());
            } else if (value != null && value.isJsonArray()) {
                List<JsonElement> list = value.getAsJsonArray().asList();
                for (int k = 0; k < list.size(); k++) {
                    if ((element == null || element == k) && list.get(k).isJsonPrimitive() && list.get(k).getAsJsonPrimitive().isNumber()) {
                        out.add(list.get(k).getAsDouble());
                    }
                }
            }
            return out;
        };
        java.util.function.DoublePredicate multiple = x -> Math.abs(x / n - Math.rint(x / n)) < 1e-9;
        boolean acceptedOnSteps = accepted.stream().flatMap(o -> numbers.apply(o).stream()).allMatch(multiple::test);
        boolean refusedOffSteps = rejected.stream().anyMatch(o -> numbers.apply(o).stream().anyMatch(x -> !multiple.test(x)));
        if (acceptedOnSteps && refusedOffSteps) {
            rule.multipleOf = n;
        }
    }

    /** The strings the docs list as the values of a field (of its elements, for a list); empty if they list none. */
    static Set<String> closedValues(Component component, String path) {
        Field field = component.field(path);
        Set<String> values = new TreeSet<>();
        if (field != null) {
            field.enumValues().stream().filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()).forEach(e -> values.add(e.getAsString()));
        }
        return values;
    }

    /**
     * A constant, bit set, element k -> leaf, count or empty list.
     *
     * @param closed whether the elements are of a closed set of values (an enum): only then a table of the strings seen
     *               is a rule, as for a scalar ({@link #tableAllowed})
     */
    static Rule array(List<Observation> set, String wire, boolean closed) {
        // the same leaf whatever the list (a structure the presence of the field adds)
        if (set.stream().map(p -> String.valueOf(p.wire().get(wire) == null ? null : p.wire().get(wire).value())).distinct().count() == 1) {
            return new Constant(set.get(0).wire().get(wire) == null ? Rule.NOT_SENT : String.valueOf(set.get(0).wire().get(wire).value()));
        }
        Rule bitset = Bitset.fit(set, wire);
        if (bitset != null) {
            return bitset;
        }
        // element k of the JSON list -> the leaf (index of the leaf first)
        Matcher index = Pattern.compile("\\[(\\d+)]$").matcher(wire);
        List<Integer> candidates = new ArrayList<>();
        if (index.find()) {
            candidates.add(Integer.parseInt(index.group(1)));
        }
        for (int k = 0; k < 4; k++) {
            if (!candidates.contains(k)) {
                candidates.add(k);
            }
        }
        Rule best = null;
        for (int k : candidates) {
            final int at = k;
            List<Observation> having = set.stream().filter(p -> p.json().getAsJsonArray().size() > at).toList();
            boolean shorterUnsent = set.stream().filter(p -> p.json().getAsJsonArray().size() <= at).allMatch(p -> p.wire().get(wire) == null);
            if (having.isEmpty() || !shorterUnsent || having.stream().anyMatch(p -> !p.json().getAsJsonArray().get(at).isJsonPrimitive())) {
                continue;
            }
            Rule rule = scalar(having, p -> p.json().getAsJsonArray().get(at), wire);
            if (rule != null && having.size() < 2 && !(rule instanceof Identity)) {
                rule = null;
            }
            if (rule instanceof Table table && table.numericKeys()) {
                rule = null;
            }
            if (rule instanceof Table && !closed) {
                rule = null;
            }
            if (rule != null && !(rule instanceof Constant)) {
                rule.element = k;
                if (best == null || offset(rule) < offset(best)) {
                    best = rule;
                }
            }
        }
        if (best != null) {
            return best;
        }
        Rule count = Count.fit(set, wire);
        return count != null ? count : EmptyList.fit(set, wire);
    }

    /**
     * How far a list element rule is from passing the value through: 0 for identity and for a pure scale. Two observations
     * of a vector fit a line on every element ({@code [90, 180, 270]} and {@code [180, 270, 0]} put RY on element 0 with
     * offset 1 as well as on element 1 with offset 0), so the element whose rule needs no offset explains the leaf.
     */
    private static double offset(Rule rule) {
        return rule instanceof Linear linear ? Math.abs(linear.offset) : 0;
    }

    /**
     * A constant, an empty map, or a map entry: the JSON key appears in the leaf path and its value maps.
     * <p>
     * An entry's rule needs the evidence a scalar's does: a leaf that is the same for the one value the entry ever had
     * is no constant (another value was never tried), and a table of the strings seen is a rule only for a closed set of
     * values ({@link #tableAllowed}).
     *
     * @param closed whether the values of the entries are of a closed set (an enum)
     */
    static Rule object(List<Observation> set, String wire, boolean closed) {
        // presence: the leaf exists iff the object is set
        boolean presence = set.stream().allMatch(p -> p.wire().get(wire) != null);
        Set<String> values = new TreeSet<>();
        set.forEach(p -> values.add(String.valueOf(p.wire().get(wire) == null ? null : p.wire().get(wire).value())));
        if (presence && values.size() == 1) {
            return new Constant(values.iterator().next());
        }
        Rule emptyMap = EmptyMap.fit(set, wire);
        if (emptyMap != null) {
            return emptyMap;
        }
        // map entry: JSON {key: v} -> leaf ...key... = f(v)
        List<String> segments = Wire.segments(wire);
        for (Observation p : set) {
            for (Map.Entry<String, JsonElement> entry : p.json().getAsJsonObject().entrySet()) {
                if (segments.contains(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    List<Observation> having = set.stream().filter(q -> q.json().getAsJsonObject().has(entry.getKey())
                        && q.json().getAsJsonObject().get(entry.getKey()).isJsonPrimitive()).toList();
                    Function<Observation, JsonElement> value = q -> q.json().getAsJsonObject().get(entry.getKey());
                    Rule rule = scalar(having, value, wire);
                    boolean oneValue = rule instanceof Constant && having.stream().map(q -> value.apply(q).toString()).distinct().count() < 2;
                    boolean openTable = rule instanceof Table && !having.stream().allMatch(q -> value.apply(q).getAsJsonPrimitive().isBoolean()
                        || (closed && value.apply(q).getAsJsonPrimitive().isString()));
                    if (rule != null && !(rule instanceof Table table && table.numericKeys()) && !oneValue && !openTable) {
                        rule.mapEntry = true;
                        rule.key = entry.getKey();
                        return rule;
                    }
                }
            }
        }
        return null;
    }

    private List<String> tags(Component component, String path, String jsonName, String wire, Rule rule, List<Observation> set) {
        Set<String> tags = new LinkedHashSet<>();
        List<String> segments = Wire.segments(Wire.generic(wire));
        String leafName = segments.getLast().replace("[]", "");
        if (rule.presence()) {
            // a leaf the field's presence switches on (a sibling default BDS writes with it), not the field's value
            return List.of(!jsonName.isEmpty() && leafName.equals(jsonName) ? "presence" : "injected-default");
        }
        if (path.isEmpty() && wire.endsWith(".value")) {
            tags.add("wrapped-in-value");
        }
        if (!jsonName.isEmpty() && !leafName.equals(jsonName) && !jsonName.equals("[]") && !jsonName.equals("{}")) {
            tags.add("renamed");
        }
        if (wire.startsWith("item_properties")) {
            tags.add("item_properties");
        } else if (!segments.get(0).replace("[]", "").equals(component.name()) && component.category().equals("component")) {
            tags.add("outside-component:" + segments.get(0).replace("[]", ""));
        }
        int jsonDepth = path.isEmpty() ? 0 : JsonPath.tokens(path).stream().filter(t -> !t.equals("[]") && !t.equals("{}")).toList().size();
        int wireDepth = segments.size() - (component.category().equals("component") ? 1 : 0);
        if (!path.isEmpty() && wireDepth != jsonDepth && !wire.startsWith("item_properties")) {
            tags.add(wireDepth > jsonDepth ? "nested" : "flattened");
        }
        JsonElement x = set.getFirst().json();
        Leaf y = set.stream().map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (y != null && x.isJsonPrimitive()) {
            JsonPrimitive p = x.getAsJsonPrimitive();
            String jsonType = p.isBoolean() ? "boolean" : p.isNumber() ? (isInteger(p) ? "integer" : "number") : "string";
            String nbt = y.type();
            if (!(jsonType.equals("integer") && nbt.equals("int")) && !(jsonType.equals("number") && nbt.equals("float"))
                && !(jsonType.equals("string") && nbt.equals("string"))) {
                tags.add("type:" + jsonType + "->" + nbt);
            }
        }
        tags.addAll(rule.kindTags());
        if (rule.element != null) {
            tags.add("list-element");
        }
        return List.copyOf(tags);
    }

    private static boolean isInteger(JsonPrimitive p) {
        String s = p.getAsString();
        return s.matches("-?\\d+");
    }
}
