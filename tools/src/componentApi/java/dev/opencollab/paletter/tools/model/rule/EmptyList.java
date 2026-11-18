package dev.opencollab.paletter.tools.model.rule;

import java.util.List;

/** An empty JSON list sent as an empty list leaf, a marker its presence switches on. */
public final class EmptyList extends Rule {
    static final String KIND = "empty-list";

    /** @return the rule if exactly the empty lists send the empty list leaf, else null */
    public static EmptyList fit(List<Observation> set, String wire) {
        return set.stream().allMatch(p -> p.json().getAsJsonArray().isEmpty() == (p.wire().get(wire) != null && "[]".equals(p.wire().get(wire).value())))
            ? new EmptyList() : null;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean writesValue() {
        return false;
    }

    @Override
    public boolean presence() {
        return true;
    }
}
