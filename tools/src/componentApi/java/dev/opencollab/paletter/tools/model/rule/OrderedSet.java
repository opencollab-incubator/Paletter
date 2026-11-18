package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A list of strings BDS sends as the set of its values: in an order of its own ({@code connected_faces ["north", "east",
 * "up"]} -> {@code ["up", "north", "east"]}), or in the JSON's order without the duplicates ({@code tags ["a", "a"]} ->
 * {@code item_tags ["a"]}; no {@code order}). The rule is of every element of the list leaf ({@code wire} ends in {@code []}).
 */
public final class OrderedSet extends Rule {
    static final String KIND = "ordered-set";

    /** BDS's order of the values; null for the JSON's order. */
    public final List<String> order;

    public OrderedSet(List<String> order) {
        this.order = order;
    }

    static OrderedSet read(JsonObject json) {
        return new OrderedSet(json.has("order") ? json.getAsJsonArray("order").asList().stream().map(JsonElement::getAsString).toList() : null);
    }

    /**
     * Every observed list leaf must hold the distinct values of the JSON list in the rule's order, and the order must
     * differ from the JSON's somewhere or a duplicate must be dropped (else element identity explains it). The {@code order}
     * is the one of the observation that holds every value seen.
     * <p>
     * An order of BDS's own is a rule only for a closed set of values, all of them seen: for a list of any strings
     * ({@code uv_lock}'s bones, sent in the order of their name hashes) the order of the few captured values says nothing
     * about the others, and an encoder made from it would drop them. Such a list stays unexplained (a hand transform).
     *
     * @param closed the values the list's elements can have, empty if any string goes
     * @return one rule per list leaf of {@code varying} that fits
     */
    public static List<OrderedSet> fit(List<Observation> observations, Set<String> varying, Set<String> closed) {
        List<Observation> set = observations.stream().filter(p -> p.json() != null && p.json().isJsonArray()).toList();
        if (set.isEmpty() || set.stream().anyMatch(p -> p.json().getAsJsonArray().asList().stream()
            .anyMatch(e -> !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()))) {
            return List.of();
        }
        Set<String> lists = new TreeSet<>();
        for (String wire : varying) {
            Matcher m = Pattern.compile("^(.*)\\[\\d+]$").matcher(wire);
            if (m.matches()) {
                lists.add(m.group(1));
            }
        }
        List<OrderedSet> rules = new ArrayList<>();
        for (String list : lists) {
            OrderedSet rule = fit(set, list, closed);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static OrderedSet fit(List<Observation> set, String list, Set<String> closed) {
        Function<Observation, List<Object>> sent = p -> {
            List<Object> out = new ArrayList<>();
            for (int k = 0; p.wire().get(list + "[" + k + "]") != null; k++) {
                out.add(p.wire().get(list + "[" + k + "]").value());
            }
            return out;
        };
        Function<Observation, List<String>> distinct = p -> p.json().getAsJsonArray().asList().stream().map(JsonElement::getAsString).distinct().toList();
        if (set.stream().allMatch(p -> distinct.apply(p).equals(sent.apply(p)))) {
            // the JSON's order: a set only if BDS dropped a duplicate
            if (set.stream().noneMatch(p -> p.json().getAsJsonArray().size() != distinct.apply(p).size())) {
                return null;
            }
            return rule(list, null);
        }
        Set<String> all = new TreeSet<>();
        set.forEach(p -> p.json().getAsJsonArray().forEach(e -> all.add(e.getAsString())));
        List<Object> order = set.stream().map(sent).filter(l -> l.size() == all.size() && all.containsAll(l)).findFirst().orElse(null);
        if (order == null || all.size() < 2 || closed.isEmpty() || !all.containsAll(closed)) {
            return null;
        }
        for (Observation p : set) {
            List<String> json = distinct.apply(p);
            if (!order.stream().filter(json::contains).toList().equals(sent.apply(p))) {
                return null;
            }
        }
        return rule(list, order.stream().map(String::valueOf).toList());
    }

    private static OrderedSet rule(String list, List<String> order) {
        OrderedSet rule = new OrderedSet(order);
        rule.nbt = "string";
        rule.tags = List.of();
        rule.wire = list.replaceAll("\\[\\d+]", "[]") + "[]";
        return rule;
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
    public boolean wholeList() {
        return true;
    }

    @Override
    void params(JsonObject out) {
        if (order != null) {
            JsonArray values = new JsonArray();
            order.forEach(values::add);
            out.add("order", values);
        }
    }

    @Override
    public void emit(Emitter e) {
        if (order == null) { // the JSON's order without duplicates
            e.put(CodeBlock.of("$T.strings($L.stream().distinct().toList())", Emitter.WIRE, e.strings()));
            return;
        }
        CodeBlock values = CodeBlock.join(order.stream().map(v -> CodeBlock.of("$S", v)).toList(), ", ");
        e.put(CodeBlock.of("$T.strings($T.of($L).stream().filter($L::contains).toList())", Emitter.WIRE, List.class, values, e.strings()));
    }
}
