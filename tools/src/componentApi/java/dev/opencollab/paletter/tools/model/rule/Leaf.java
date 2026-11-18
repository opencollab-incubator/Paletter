package dev.opencollab.paletter.tools.model.rule;

/** One leaf of wire NBT flattened to paths ({@code a.b[0].c}): its NBT type and value. */
public record Leaf(String type, Object value) {
    public boolean numeric() {
        return value instanceof Number;
    }

    public double number() {
        return ((Number) value).doubleValue();
    }

    @Override
    public String toString() {
        return value + " (" + type + ")";
    }
}
