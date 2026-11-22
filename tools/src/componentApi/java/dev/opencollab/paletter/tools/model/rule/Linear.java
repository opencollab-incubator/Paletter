package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.tools.codegen.component.Docs;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code leaf = scale * value + offset} over numbers (booleans as 0 and 1): unit conversions. With {@code modulo} the line
 * is wrapped into {@code [0, modulo)} ({@code -90} degrees as 3 quarter turns).
 */
public final class Linear extends Rule {
    static final String KIND = "linear";

    public final double scale;
    public final double offset;
    /** The modulus the line is wrapped into; null if it is not. */
    public final Long modulo;

    public Linear(double scale, double offset, Long modulo) {
        this.scale = scale;
        this.offset = offset;
        this.modulo = modulo;
    }

    static Linear read(JsonObject json) {
        return new Linear(json.get("scale").getAsDouble(), json.get("offset").getAsDouble(),
            json.has("modulo") ? json.get("modulo").getAsLong() : null);
    }

    /**
     * The distinct inputs a line needs. Two points always fit a line, so a rule from two is a guess; the third point is
     * what shows it. With fewer the leaf stays unexplained.
     */
    static final int MIN_DISTINCT_POINTS = 3;

    /**
     * @return the line through numeric values and leaves (wrapped if a straight one does not fit), or null; null with fewer
     *         than {@link #MIN_DISTINCT_POINTS} different inputs
     */
    public static Linear fit(List<JsonElement> xs, List<Leaf> ys) {
        if (!xs.stream().allMatch(x -> x.getAsJsonPrimitive().isNumber()) || !ys.stream().allMatch(y -> y != null && y.numeric())) {
            return null;
        }
        Set<Double> distinct = new TreeSet<>();
        xs.forEach(x -> distinct.add(x.getAsDouble()));
        if (distinct.size() < MIN_DISTINCT_POINTS) {
            return null;
        }
        int i = 0;
        int j = -1;
        for (int k = 1; k < xs.size(); k++) {
            if (xs.get(k).getAsDouble() != xs.get(0).getAsDouble()) {
                j = k;
                break;
            }
        }
        double a = (ys.get(j).number() - ys.get(i).number()) / (xs.get(j).getAsDouble() - xs.get(i).getAsDouble());
        double b = ys.get(i).number() - a * xs.get(i).getAsDouble();
        boolean fits = true;
        for (int k = 0; k < xs.size(); k++) {
            if (!close(a * xs.get(k).getAsDouble() + b, ys.get(k).number())) {
                fits = false;
                break;
            }
        }
        if (fits && Math.abs(a) > 1e-9) { // scale 0 is a constant
            return new Linear(round(a), round(b), null);
        }
        return wrapped(xs, ys);
    }

    /**
     * A line wrapped into {@code [0, m)}: whole steps on an integer leaf that start again past the largest one BDS sends
     * ({@code -90 -> 3}, {@code 450 -> 1} quarter turns). The modulus is the largest leaf plus one, and an observation
     * outside the line's range must show the wrap; null otherwise.
     * <p>
     * With few observations some pair of them and a third agree modulo m by chance (about 3 in m), so the leaf must have
     * an integral NBT type and the scale must be whole steps, n or 1/n: {@code (3 * origin.x + 8) mod 12} onto a float
     * leaf is no rule.
     */
    static Linear wrapped(List<JsonElement> xs, List<Leaf> ys) {
        if (ys.stream().anyMatch(y -> y == null || !y.numeric() || !INTEGRAL.contains(y.type()) || y.number() < 0
            || y.number() != Math.rint(y.number()))) {
            return null;
        }
        long modulus = (long) ys.stream().mapToDouble(Leaf::number).max().orElse(0) + 1;
        if (modulus < 2) {
            return null;
        }
        for (int i = 0; i < xs.size(); i++) {
            for (int j = i + 1; j < xs.size(); j++) {
                double dx = xs.get(j).getAsDouble() - xs.get(i).getAsDouble();
                if (dx == 0) {
                    continue;
                }
                double a = (ys.get(j).number() - ys.get(i).number()) / dx;
                double b = ys.get(i).number() - a * xs.get(i).getAsDouble();
                if (Math.abs(a) < 1e-9 || !wholeSteps(a)) {
                    continue;
                }
                boolean fits = true;
                boolean wraps = false;
                for (int k = 0; k < xs.size() && fits; k++) {
                    double line = a * xs.get(k).getAsDouble() + b;
                    fits = close(line, Math.rint(line)) && Math.floorMod((long) Math.rint(line), modulus) == (long) ys.get(k).number();
                    wraps |= line < 0 || line >= modulus;
                }
                if (fits && wraps) {
                    return new Linear(round(a), round(b), modulus);
                }
            }
        }
        return null;
    }

    private static final Set<String> INTEGRAL = Set.of("byte", "short", "int", "long");

    /** @return whether a scale is a whole number of steps, n or 1/n */
    static boolean wholeSteps(double scale) {
        double s = Math.abs(scale);
        return s >= 1 ? Math.abs(s - Math.rint(s)) < 1e-9 : Math.abs(1 / s - Math.rint(1 / s)) < 1e-6;
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
    public List<String> kindTags() {
        return List.of("unit-conversion");
    }

    @Override
    void params(JsonObject out) {
        out.addProperty("scale", scale);
        out.addProperty("offset", offset);
        if (modulo != null) {
            out.addProperty("modulo", modulo);
        }
    }

    /**
     * The divisor of a scale of 1/n (degrees / 90 -> quarter turns), which divides by n: the rounded scale times a multiple
     * of n would fall just short of the integer an integral leaf truncates to. Null for a scale that multiplies.
     */
    private Double divisor() {
        double divisor = 1 / scale;
        return Math.abs(scale) < 1 && Math.abs(divisor - Math.rint(divisor)) < 1e-3 ? Math.rint(divisor) : null;
    }

    /**
     * The leaf of a value before its NBT type, computed as the encoder ({@link #emit}) computes it: the evaluation and the
     * encoder are one function.
     * <p>
     * TODO(recapture): an integral leaf truncates the result ({@code Wire.toInt}), so {@code 20 * 0.7f} sends 13 where BDS
     * may send 14; the captures only hold values exact in binary (docs/data-pipeline.md, "Recapture to-do").
     */
    double apply(double x) {
        Double divisor = divisor();
        double v = divisor != null ? x / divisor + offset : scale * x + offset;
        // wrapped into [0, modulo): BDS sends -90 degrees as 3 quarter turns
        return modulo == null ? v : Math.floorMod(Math.round(v), modulo);
    }

    @Override
    public void emit(Emitter e) {
        Double divisor = divisor();
        CodeBlock number = divisor != null
            ? CodeBlock.of("($L / $L + $L)", e.number(), decimal(divisor), decimal(offset))
            : CodeBlock.of("($L * $L + $L)", decimal(scale), e.number(), decimal(offset));
        if (modulo != null) {
            number = CodeBlock.of("$T.floorMod($T.round($L), $LL)", Math.class, Math.class, number, modulo);
        }
        e.put(e.fromNumber(nbt, number));
    }

    static String decimal(double d) {
        return Docs.number(d) + (d == Math.rint(d) ? ".0" : "");
    }

    @Override
    String evaluate(JsonPrimitive p) {
        if (!p.isNumber() && !p.isBoolean()) {
            return null;
        }
        double x = p.isBoolean() ? (p.getAsBoolean() ? 1 : 0) : p.getAsDouble();
        return typed(apply(x), nbt);
    }
}
