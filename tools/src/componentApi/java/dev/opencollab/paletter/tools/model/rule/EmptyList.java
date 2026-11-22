package dev.opencollab.paletter.tools.model.rule;

import java.util.List;
import java.util.function.Predicate;

/** An empty JSON list sent as an empty list leaf, a marker its presence switches on. */
public final class EmptyList extends Rule {
    static final String KIND = "empty-list";

    /**
     * @return the rule if empty and non empty lists were seen and exactly the empty ones send the empty list leaf, else
     *         null: without an empty list the rule would hold vacuously
     */
    public static EmptyList fit(List<Observation> set, String wire) {
        Predicate<Observation> empty = p -> p.json().getAsJsonArray().isEmpty();
        return set.stream().anyMatch(empty) && set.stream().anyMatch(empty.negate())
            && set.stream().allMatch(p -> empty.test(p) == (p.wire().get(wire) != null && "[]".equals(p.wire().get(wire).value())))
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
