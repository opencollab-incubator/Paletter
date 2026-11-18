package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** The leaf is the value as it is (renamed, moved, re-typed: a boolean as a byte, a number as a float). */
public final class Identity extends Rule {
    static final String KIND = "identity";

    /** @return the rule if every leaf is its JSON value, else null */
    public static Identity fit(List<JsonElement> xs, List<Leaf> ys) {
        for (int i = 0; i < xs.size(); i++) {
            if (ys.get(i) == null || !same(xs.get(i), ys.get(i))) {
                return null;
            }
        }
        return new Identity();
    }

    private static boolean same(JsonElement x, Leaf y) {
        JsonPrimitive p = x.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return y.numeric() && y.number() == (p.getAsBoolean() ? 1 : 0);
        }
        if (p.isNumber()) {
            return y.numeric() && close(p.getAsDouble(), y.number());
        }
        return y.value() instanceof String s && s.equals(p.getAsString());
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
    public boolean mapValue() {
        return true;
    }

    @Override
    public boolean wholeList() {
        return true;
    }

    @Override
    public void emit(Emitter e) {
        e.put(e.convert(nbt));
    }

    @Override
    String evaluate(JsonPrimitive p) {
        if (p.isBoolean()) {
            return typed(p.getAsBoolean() ? 1 : 0, nbt);
        }
        if (p.isNumber()) {
            return typed(p.getAsDouble(), nbt);
        }
        return nbt.equals("string") ? p.getAsString() + " (string)" : null;
    }
}
