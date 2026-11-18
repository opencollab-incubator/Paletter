package dev.opencollab.paletter.tools.model.rule;

import com.palantir.javapoet.CodeBlock;

import java.util.List;

/** The length of a JSON list as a number. */
public final class Count extends Rule {
    static final String KIND = "count";

    /** @return the rule if every leaf is the length of its list, else null */
    public static Count fit(List<Observation> set, String wire) {
        return set.stream().allMatch(p -> p.wire().get(wire) != null && p.wire().get(wire).numeric()
            && (long) p.wire().get(wire).number() == p.json().getAsJsonArray().size()) ? new Count() : null;
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
    public void emit(Emitter e) {
        e.put(e.fromNumber(nbt, CodeBlock.of("$L.size()", e.value())));
    }
}
