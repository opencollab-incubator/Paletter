package org.geysermc.paletter.tools.model.rule;

import java.util.List;
import java.util.function.Predicate;

/** An empty JSON map sent as an empty compound (its entries are leaves of their own). */
public final class EmptyMap extends Rule {
    static final String KIND = "empty-map";

    /** @return the rule if empty and non empty maps were seen and exactly the empty ones send an empty compound, else null */
    public static EmptyMap fit(List<Observation> set, String wire) {
        Predicate<Observation> empty = p -> p.json().getAsJsonObject().isEmpty();
        return set.stream().anyMatch(empty) && set.stream().anyMatch(empty.negate()) && set.stream()
            .allMatch(p -> empty.test(p) == (p.wire().get(wire) != null && p.wire().get(wire).toString().equals("{} (compound)"))) ? new EmptyMap() : null;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean writesValue() {
        return false;
    }
}
