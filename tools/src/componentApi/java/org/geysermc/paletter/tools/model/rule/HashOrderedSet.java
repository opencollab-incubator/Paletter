package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.palantir.javapoet.CodeBlock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A list of names BDS sends as a set ordered by the hash of each name ({@code uv_lock}'s bones; {@link #byNameHash}),
 * each name once. Unlike an {@link OrderedSet} of a closed set of values the order holds for any names. The rule is of every
 * element of the list leaf ({@code wire} ends in {@code []}).
 */
public final class HashOrderedSet extends Rule {
    static final String KIND = "hash-ordered-set";

    /**
     * Every list must be sent in the hash order of its distinct names. A hash order is no rule on little evidence: some list
     * must be sent in an order other than the one written (else the JSON's order explains the leaf), and the lists must
     * also rule out a plain reversal: one sent as written, or one sent in an order that is not the reverse.
     *
     * @return one rule per list leaf of {@code varying} that fits
     */
    public static List<HashOrderedSet> fit(List<Observation> observations, Set<String> varying) {
        List<Observation> lists = observations.stream().filter(p -> p.json() != null && p.json().isJsonArray()).toList();
        Set<String> leaves = new TreeSet<>();
        for (String wire : varying) {
            Matcher m = Pattern.compile("^(.*)\\[\\d+]$").matcher(wire);
            if (m.matches()) {
                leaves.add(m.group(1));
            }
        }
        List<HashOrderedSet> rules = new ArrayList<>();
        for (String list : leaves) {
            if (!lists.isEmpty() && fits(lists, list)) {
                HashOrderedSet rule = new HashOrderedSet();
                rule.nbt = "string";
                rule.tags = List.of();
                rule.wire = list.replaceAll("\\[\\d+]", "[]") + "[]";
                rules.add(rule);
            }
        }
        return rules;
    }

    private static boolean fits(List<Observation> lists, String list) {
        boolean differs = false;
        boolean notReversal = false;
        for (Observation p : lists) {
            List<String> json = new ArrayList<>();
            for (JsonElement e : p.json().getAsJsonArray()) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                    return false;
                }
                json.add(e.getAsString());
            }
            List<String> sent = new ArrayList<>();
            for (int k = 0; p.wire().get(list + "[" + k + "]") != null; k++) {
                sent.add(String.valueOf(p.wire().get(list + "[" + k + "]").value()));
            }
            if (!sent.equals(byNameHash(json))) {
                return false;
            }
            List<String> distinct = json.stream().distinct().toList();
            if (distinct.size() >= 2) {
                differs |= !sent.equals(distinct);
                notReversal |= !sent.equals(distinct.reversed());
            }
        }
        return differs && notReversal;
    }

    private static final long FNV1_64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1_64_PRIME = 0x100000001b3L;

    /**
     * The distinct names in the order BDS sends a set of them: ascending by the unsigned 64 bit hash of each name (the
     * runtime has the same, {@code Wire.byNameHash}). The hash is BDS's {@code HashedString}: FNV-1 64 bit over the UTF-8 bytes,
     * 0 for the empty string. Names with equal hashes keep their order.
     */
    public static List<String> byNameHash(List<String> names) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(names));
        ordered.sort((a, b) -> Long.compareUnsigned(hash(a), hash(b)));
        return ordered;
    }

    private static long hash(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return 0;
        }
        long hash = FNV1_64_OFFSET_BASIS;
        for (byte b : bytes) {
            hash = hash * FNV1_64_PRIME ^ (b & 0xff);
        }
        return hash;
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
    public void emit(Emitter e) {
        e.put(CodeBlock.of("$T.strings($T.byNameHash($L))", Emitter.WIRE, Emitter.WIRE, e.strings()));
    }
}
