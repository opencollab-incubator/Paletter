package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.CodeBlock;

import java.util.List;

/** Free strings: {@code leaf = prefix + value + suffix}, the default namespace added to a bare value ({@code <>} event suffix). */
public final class Affix extends Rule {
    static final String KIND = "affix";

    public final String prefix;
    public final String suffix;

    public Affix(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    static Affix read(JsonObject json) {
        return new Affix(json.get("prefix").getAsString(), json.get("suffix").getAsString());
    }

    /** @return the one prefix and suffix every string leaf puts around its JSON string, or null */
    public static Affix fit(List<Observation> set, String wire) {
        String prefix = null;
        String suffix = null;
        for (Observation p : set) {
            if (!p.json().getAsJsonPrimitive().isString() || p.wire().get(wire) == null || !(p.wire().get(wire).value() instanceof String leaf)) {
                return null;
            }
            String x = p.json().getAsString();
            if (!x.contains(":") && leaf.contains("minecraft:" + x)) {
                x = "minecraft:" + x; // the default namespace is added
            }
            int at = leaf.indexOf(x);
            if (at < 0) {
                // a namespace the leaf adds to a bare value
                return null;
            }
            String pre = leaf.substring(0, at);
            String suf = leaf.substring(at + x.length());
            if ((prefix != null && !prefix.equals(pre)) || (suffix != null && !suffix.equals(suf))) {
                return null;
            }
            prefix = pre;
            suffix = suf;
        }
        return new Affix(prefix, suffix);
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
        out.addProperty("prefix", prefix);
        out.addProperty("suffix", suffix);
    }

    @Override
    public void emit(Emitter e) {
        e.put(CodeBlock.of("$S + $T.namespaced($L) + $S", prefix, Emitter.WIRE, e.string(), suffix));
    }

    @Override
    String evaluate(JsonPrimitive p) {
        String v = p.getAsString();
        return prefix + (v.contains(":") ? v : "minecraft:" + v) + suffix + " (string)";
    }
}
