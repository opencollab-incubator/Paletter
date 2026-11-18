package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code leaf = constant + sum of ±input}: a leaf that several numbers of one JSON list or object decide together, which no
 * rule over one field can fit ({@code collision_box}: {@code minX = 8 - origin.x - size.x}). The inputs are named by
 * their path in the value ({@code origin[0]}, {@code size[0]}); every coefficient is 1 or -1, as the unit
 * conversions and mirrors of the wire are. A single input is a {@link Linear} rule.
 * <p>
 * For a leaf of a list of objects ({@code boxes[].minX}) the rule is of every element: the inputs are the numbers of one
 * element ({@code origin[0]}), and the encoder writes the leaf of each element from its own ({@link #fitElements}).
 */
public final class Affine extends Rule {
    static final String KIND = "affine";
    /** More inputs than this make the search for the coefficients too large to be unique. */
    private static final int MAX_INPUTS = 8;
    /** A leaf directly below an element of a list of objects: the part up to the list, then the leaf name. */
    private static final Pattern ELEMENT_LEAF = Pattern.compile("(.*)\\[\\d+](\\.[^.\\[\\]]+)");

    /** Input path -> its coefficient (1 or -1), sorted by path. */
    public final Map<String, Integer> terms;
    public final double constant;
    /** Whether the rule is of every element of a list of objects: the inputs are those of one element, the leaf its own. */
    public final boolean elements;

    public Affine(Map<String, Integer> terms, double constant, boolean elements) {
        this.terms = terms;
        this.constant = constant;
        this.elements = elements;
    }

    static Affine read(JsonObject json) {
        Map<String, Integer> terms = new TreeMap<>();
        json.getAsJsonObject("terms").entrySet().forEach(e -> terms.put(e.getKey(), e.getValue().getAsInt()));
        return new Affine(terms, json.get("constant").getAsDouble(), json.has("elements") && json.get("elements").getAsBoolean());
    }

    /**
     * @return the one combination of two or more of the numbers the JSON values have in common that gives every observed
     *         leaf, or null if none or several do (the observations do not tell which inputs the leaf follows)
     */
    public static Affine fit(List<Observation> set, String wire) {
        if (set.stream().anyMatch(p -> p.wire().get(wire) == null || !p.wire().get(wire).numeric())) {
            return null;
        }
        List<Map<String, Double>> inputs = set.stream().map(p -> numbers(p.json())).toList();
        Set<String> common = new TreeSet<>(inputs.get(0).keySet());
        inputs.forEach(i -> common.retainAll(i.keySet()));
        List<String> varying = common.stream().filter(path -> inputs.stream().map(i -> i.get(path)).distinct().count() > 1).toList();
        if (varying.size() < 2 || varying.size() > MAX_INPUTS) {
            return null;
        }
        Affine best = null;
        int fewest = Integer.MAX_VALUE;
        boolean unique = false;
        int[] coefficient = new int[varying.size()];
        for (int combination = 0; combination < Math.pow(3, varying.size()); combination++) {
            int rest = combination;
            int used = 0;
            for (int i = 0; i < coefficient.length; i++) {
                coefficient[i] = rest % 3 - 1;
                rest /= 3;
                used += Math.abs(coefficient[i]);
            }
            if (used < 2 || used > fewest) {
                continue;
            }
            Affine candidate = through(varying, coefficient, inputs, set, wire);
            if (candidate != null) {
                unique = used < fewest;
                fewest = used;
                best = candidate;
            }
        }
        return unique ? best : null;
    }

    /**
     * Fits a leaf of a list of objects ({@code a.boxes[0].minX}) over the elements: each element of each observation's JSON
     * list is a row, its numbers the inputs and the leaf of its index the output, so the terms are relative to the element.
     *
     * @return the rule, of every element, if {@link #fit} gives one over those rows; null if the leaf is no element's or no
     *         observation has a list
     */
    public static Affine fitElements(List<Observation> set, String wire) {
        Matcher element = ELEMENT_LEAF.matcher(wire);
        if (!element.matches() || set.stream().anyMatch(p -> !p.json().isJsonArray())) {
            return null;
        }
        List<Observation> rows = new ArrayList<>();
        for (Observation p : set) {
            JsonArray list = p.json().getAsJsonArray();
            for (int i = 0; i < list.size(); i++) {
                Leaf leaf = p.wire().get(element.group(1) + "[" + i + "]" + element.group(2));
                if (leaf != null) {
                    rows.add(new Observation(list.get(i), Map.of(wire, leaf), p.label()));
                }
            }
        }
        Affine rule = rows.isEmpty() ? null : fit(rows, wire);
        return rule == null ? null : new Affine(rule.terms, rule.constant, true);
    }

    /** @return the combination of the coefficients on the inputs if it gives every leaf, else null */
    private static Affine through(List<String> paths, int[] coefficient, List<Map<String, Double>> inputs, List<Observation> set, String wire) {
        double constant = 0;
        for (int row = 0; row < set.size(); row++) {
            double sum = 0;
            for (int i = 0; i < paths.size(); i++) {
                sum += coefficient[i] * inputs.get(row).get(paths.get(i));
            }
            double leaf = set.get(row).wire().get(wire).number() - sum;
            if (row == 0) {
                constant = leaf;
            } else if (!close(constant, leaf)) {
                return null;
            }
        }
        Map<String, Integer> terms = new TreeMap<>();
        for (int i = 0; i < paths.size(); i++) {
            if (coefficient[i] != 0) {
                terms.put(paths.get(i), coefficient[i]);
            }
        }
        return new Affine(terms, round(constant), false);
    }

    /** @return every number of a JSON list or object by its path ({@code origin[0]}, {@code [1].size[2]}) */
    private static Map<String, Double> numbers(JsonElement json) {
        Map<String, Double> out = new LinkedHashMap<>();
        collect(json, "", out);
        return out;
    }

    private static void collect(JsonElement json, String path, Map<String, Double> out) {
        if (json == null) {
            return;
        }
        if (json.isJsonObject()) {
            json.getAsJsonObject().entrySet().forEach(e -> collect(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out));
        } else if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                collect(array.get(i), path + "[" + i + "]", out);
            }
        } else if (json.getAsJsonPrimitive().isNumber()) {
            out.put(path, json.getAsDouble());
        }
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
        return List.of("unit-conversion");
    }

    @Override
    void params(JsonObject out) {
        JsonObject entries = new JsonObject();
        terms.forEach(entries::addProperty);
        out.add("terms", entries);
        out.addProperty("constant", constant);
        if (elements) {
            out.addProperty("elements", true);
        }
    }

    @Override
    public void emit(Emitter e) {
        List<CodeBlock> parts = new ArrayList<>();
        parts.add(CodeBlock.of("$L", Linear.decimal(constant)));
        terms.forEach((path, coefficient) -> parts.add(CodeBlock.of("$L $L", coefficient > 0 ? "+" : "-", e.input(path))));
        e.put(e.fromNumber(nbt, CodeBlock.of("($L)", CodeBlock.join(parts, " "))));
    }

    @Override
    public boolean eachElement() {
        return elements;
    }

    @Override
    String evaluateStructured(JsonElement json) {
        if (elements) {
            return null;
        }
        Map<String, Double> inputs = numbers(json);
        double sum = constant;
        for (Map.Entry<String, Integer> term : terms.entrySet()) {
            Double x = inputs.get(term.getKey());
            if (x == null) {
                return null;
            }
            sum += term.getValue() * x;
        }
        return typed(sum, nbt);
    }
}
