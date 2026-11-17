package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** A list of enum strings as one integer: the bits of its values ORed. */
public final class Bitset extends Rule {
    static final String KIND = "bitset";

    /** Value -> its bits. */
    public final Map<String, Long> bits;

    public Bitset(Map<String, Long> bits) {
        this.bits = bits;
    }

    static Bitset read(JsonObject json) {
        Map<String, Long> bits = new LinkedHashMap<>();
        json.getAsJsonObject("bits").entrySet().forEach(e -> bits.put(e.getKey(), e.getValue().getAsLong()));
        return new Bitset(bits);
    }

    /**
     * @return the bits of each value (seen alone in a list) if every list of strings sends their OR as a number, and at
     *         least two values were seen alone; else null
     */
    public static Bitset fit(List<Observation> set, String wire) {
        if (!set.stream().allMatch(p -> p.json().getAsJsonArray().asList().stream().allMatch(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()))
            || !set.stream().allMatch(p -> p.wire().get(wire) != null && p.wire().get(wire).numeric())) {
            return null;
        }
        Map<String, Long> bits = new TreeMap<>();
        for (Observation p : set) {
            if (p.json().getAsJsonArray().size() == 1) {
                bits.put(p.json().getAsJsonArray().get(0).getAsString(), (long) p.wire().get(wire).number());
            }
        }
        if (bits.size() < 2) {
            return null;
        }
        for (Observation p : set) {
            long expected = 0;
            for (JsonElement e : p.json().getAsJsonArray()) {
                Long bit = bits.get(e.getAsString());
                if (bit == null) {
                    return null;
                }
                expected |= bit;
            }
            if (expected != (long) p.wire().get(wire).number()) {
                return null;
            }
        }
        return new Bitset(bits);
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
        JsonObject table = new JsonObject();
        bits.forEach(table::addProperty);
        out.add("bits", table);
    }

    @Override
    public void emit(Emitter e) {
        TypeName element = e.elementType();
        if (element == null) {
            throw e.error("a bitset rule needs a list field");
        }
        String bits = e.local("bits");
        String x = e.local("x");
        e.code().addStatement("int $N = 0", bits);
        e.code().beginControlFlow("for ($T $N : $L)", element, x, e.value());
        CodeBlock.Builder sw = CodeBlock.builder().add("$N |= switch ($L) {\n", bits, e.elementString(CodeBlock.of("$N", x))).indent();
        for (String k : new TreeSet<>(this.bits.keySet())) {
            sw.add("case $S -> $L;\n", k, this.bits.get(k));
        }
        sw.add("default -> 0;\n").unindent().add("}");
        e.code().addStatement("$L", sw.build());
        e.code().endControlFlow();
        e.put(e.fromNumber(nbt, CodeBlock.of("$N", bits)));
    }
}
