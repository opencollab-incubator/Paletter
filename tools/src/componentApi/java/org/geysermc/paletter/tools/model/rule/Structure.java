package org.geysermc.paletter.tools.model.rule;

import java.util.List;

/** A leaf of a list, object or map field that its child fields explain. */
public final class Structure extends Rule {
    static final String KIND = "structure";

    public Structure() {
        tags = List.of("explained-by-child-fields");
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
