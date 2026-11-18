package org.geysermc.paletter.tools.model.rule;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.CodeBlock;

import java.util.List;

/**
 * A boolean that is one bit of an integral leaf several fields share ({@code face_dimming}, {@code isotropic} and
 * {@code alpha_masked_tint} in {@code packed_bools}). The encoder sets or clears the bit in what the leaf holds, so the
 * fields can be written in any order; a leaf nothing wrote yet holds {@link #base}, the value with every field unset.
 */
public final class PackedBits extends Rule {
    static final String KIND = "packed-bits";

    /** The bit of the field. */
    public final long mask;
    /** The leaf when no field of it is set. */
    public final long base;
    /** Whether {@code false} sets the bit and {@code true} clears it. */
    public final boolean inverted;

    public PackedBits(long mask, long base, boolean inverted) {
        this.mask = mask;
        this.base = base;
        this.inverted = inverted;
    }

    static PackedBits read(JsonObject json) {
        return new PackedBits(json.get("mask").getAsLong(), json.get("base").getAsLong(), json.has("inverted") && json.get("inverted").getAsBoolean());
    }

    /**
     * A boolean packs into the leaf when {@code true} and {@code false} each gave one value, the two differ in one bit, and
     * both have other bits set: bits that are not the field's, so the leaf is shared (a leaf of the field alone would be a
     * table or identity). The value without the field ({@code json == null}) is the {@link #base}; it must be one of the two.
     *
     * @return the rule, or null if the observations do not show it
     */
    public static PackedBits fit(List<Observation> observations, String wire) {
        Long base = null;
        Long whenTrue = null;
        Long whenFalse = null;
        for (Observation p : observations) {
            Leaf leaf = p.wire().get(wire);
            if (leaf == null || !(leaf.value() instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 0
                || !List.of("byte", "short", "int", "long").contains(leaf.type())) {
                return null;
            }
            long value = n.longValue();
            if (p.json() == null) {
                if (base != null && base != value) {
                    return null;
                }
                base = value;
            } else if (p.json().isJsonPrimitive() && p.json().getAsJsonPrimitive().isBoolean()) {
                boolean on = p.json().getAsBoolean();
                Long seen = on ? whenTrue : whenFalse;
                if (seen != null && seen != value) {
                    return null;
                }
                if (on) {
                    whenTrue = value;
                } else {
                    whenFalse = value;
                }
            } else {
                return null;
            }
        }
        if (base == null || whenTrue == null || whenFalse == null) {
            return null;
        }
        long mask = whenTrue ^ whenFalse;
        if (Long.bitCount(mask) != 1 || (whenTrue & whenFalse) == 0 || base != whenTrue && base != whenFalse) {
            return null;
        }
        return new PackedBits(mask, base, (whenTrue & mask) == 0);
    }

    /** @return the leaf with the field {@code false} and {@code true}, as a table of the field would list them */
    public long[] integral() {
        return new long[] {with(false), with(true)};
    }

    private long with(boolean value) {
        return value != inverted ? base | mask : base & ~mask;
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
    public boolean sharesLeaf() {
        return true;
    }

    @Override
    public List<String> kindTags() {
        return List.of("packed-bits");
    }

    @Override
    void params(JsonObject out) {
        out.addProperty("mask", mask);
        out.addProperty("base", base);
        if (inverted) {
            out.addProperty("inverted", true);
        }
    }

    @Override
    public void emit(Emitter e) {
        String bits = e.local("bits");
        String written = e.local("n");
        e.code().addStatement("int $N = $L instanceof $T $N ? $N.intValue() : $L", bits, e.leaf(), Number.class, written, written, base);
        e.code().addStatement(inverted ? "$N = !$L ? $N | $L : $N & ~$L" : "$N = $L ? $N | $L : $N & ~$L", bits, e.value(), bits, mask, bits, mask);
        e.put(e.fromNumber(nbt, CodeBlock.of("$N", bits)));
    }

    @Override
    String evaluate(JsonPrimitive p) {
        return p.isBoolean() ? typed(with(p.getAsBoolean()), nbt) : null;
    }
}
