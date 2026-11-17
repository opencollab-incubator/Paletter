package org.geysermc.paletter.tools.model.rule;

import java.util.List;

/** A leaf only variants without the field moved: another field of those variants decides it. */
public final class CrossField extends Rule {
    static final String KIND = "cross-field";

    public CrossField() {
        tags = List.of("depends-on-other-fields");
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
