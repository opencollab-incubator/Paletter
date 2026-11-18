package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonObject;

/**
 * A leaf that is the same whatever the value: the field's presence switches it on (a sibling default BDS writes with
 * it), or {@code <not sent>} removes it.
 */
public final class Constant extends Rule {
    static final String KIND = "constant";

    /** The leaf's value, or {@link #NOT_SENT}. */
    public final String value;

    public Constant(String value) {
        this.value = value;
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

    @Override
    void params(JsonObject out) {
        out.addProperty("value", value);
    }

    /** Writes the constant: the encoder calls it where the field is set and no other field owns the leaf. */
    @Override
    public void emit(Emitter e) {
        if (value.equals(NOT_SENT)) {
            e.code().addStatement("$L", e.write(NOT_SENT));
        } else if (nbt != null) {
            e.code().addStatement("$L", e.write(value + " (" + nbt + ")"));
        }
    }
}
