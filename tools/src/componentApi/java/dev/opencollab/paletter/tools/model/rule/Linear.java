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
 * is wrapped into {@code [0, modulo)} ({@code -90} degrees as 3 quarter turns). With {@code rounding} the line lands on an
 * integral leaf the way BDS computes it ({@link Rounding}): {@code use_duration} 0.35 seconds is 7 ticks, not the 6 the
 * double product 6.99999988 truncates to.
 */
public final class Linear extends Rule {
    static final String KIND = "linear";

    public final double scale;
    public final double offset;
    /** The modulus the line is wrapped into; null if it is not. */
    public final Long modulo;
    /**
     * How a value off the whole numbers lands on the integral leaf, as the captures show it; null if every observation
     * was a whole number of the line (the encoder then truncates the double product, unverified).
     */
    public final Rounding rounding;

    public Linear(double scale, double offset, Long modulo) {
        this(scale, offset, modulo, null);
    }

    public Linear(double scale, double offset, Long modulo, Rounding rounding) {
        this.scale = scale;
        this.offset = offset;
        this.modulo = modulo;
        this.rounding = rounding;
    }

    static Linear read(JsonObject json) {
        return new Linear(json.get("scale").getAsDouble(), json.get("offset").getAsDouble(),
            json.has("modulo") ? json.get("modulo").getAsLong() : null,
            json.has("rounding") ? Rounding.of(json.get("arithmetic").getAsString(), json.get("rounding").getAsString()) : null);
    }

    /**
     * How BDS turns the line into the whole number of an integral leaf: the arithmetic of the product (the value and the
     * scale as doubles, or as floats in float arithmetic: BDS's own fields are floats) and the rounding of the result.
     * {@code mappings.json} writes it as {@code "arithmetic"} and {@code "rounding"}. The candidates are tried in the order of
     * the constants; {@code floor} differs from {@code truncate} only below zero.
     */
    public enum Rounding {
        DOUBLE_TRUNCATE("double", "truncate"),
        DOUBLE_FLOOR("double", "floor"),
        DOUBLE_HALF_UP("double", "half-up"),
        DOUBLE_HALF_EVEN("double", "half-even"),
        DOUBLE_CEILING("double", "ceiling"),
        FLOAT_TRUNCATE("float32", "truncate"),
        FLOAT_FLOOR("float32", "floor"),
        FLOAT_HALF_UP("float32", "half-up"),
        FLOAT_HALF_EVEN("float32", "half-even"),
        FLOAT_CEILING("float32", "ceiling");

        public final String arithmetic;
        public final String mode;

        Rounding(String arithmetic, String mode) {
            this.arithmetic = arithmetic;
            this.mode = mode;
        }

        static Rounding of(String arithmetic, String mode) {
            for (Rounding r : values()) {
                if (r.arithmetic.equals(arithmetic) && r.mode.equals(mode)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("unknown rounding " + arithmetic + " " + mode);
        }

        boolean float32() {
            return arithmetic.equals("float32");
        }

        /**
         * The line at x in this arithmetic, before rounding. The input is the float the API holds for a JSON number (BDS
         * keeps it as a float too: it sends {@code use_modifiers.use_duration} 0.35 as 0.3499999940395355), so the double
         * arithmetic is that of the float: 0.35 times 20 is 6.99999988 there, 7 in float arithmetic.
         */
        double line(double scale, double offset, double x) {
            float value = (float) x;
            return float32() ? (double) (value * (float) scale + (float) offset) : scale * value + offset;
        }

        /** The whole number the leaf gets for the line's value v. */
        double round(double v) {
            return switch (mode) {
                case "truncate" -> v < 0 ? Math.ceil(v) : Math.floor(v);
                case "floor" -> Math.floor(v);
                case "half-up" -> Math.floor(v + 0.5);
                case "half-even" -> Math.rint(v);
                case "ceiling" -> Math.ceil(v);
                default -> throw new IllegalStateException(mode);
            };
        }

        /** The code of {@link #round} around a double expression; truncation is the integral conversion's own ({@code Wire.toInt}). */
        CodeBlock emit(CodeBlock v) {
            return switch (mode) {
                case "truncate" -> v;
                case "floor" -> CodeBlock.of("$T.floor($L)", Math.class, v);
                case "half-up" -> CodeBlock.of("$T.floor($L + 0.5)", Math.class, v);
                case "half-even" -> CodeBlock.of("$T.rint($L)", Math.class, v);
                case "ceiling" -> CodeBlock.of("$T.ceil($L)", Math.class, v);
                default -> throw new IllegalStateException(mode);
            };
        }
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
        Linear wrapped = wrapped(xs, ys);
        return wrapped != null ? wrapped : rounded(xs, ys);
    }

    /**
     * A line onto an integral leaf that some inputs leave off the whole numbers, rounded as BDS rounds ({@link Rounding}).
     * The line itself must be shown by at least {@link #MIN_DISTINCT_POINTS} inputs it maps onto whole numbers exactly, in
     * double arithmetic; the other inputs decide the rounding. Of the lines through two of those points, the one nearest
     * to passing through 0 wins ({@code 20x} over {@code 20x - 0.5}, which rounded half up is the same function), and of
     * the roundings the first in {@link Rounding}'s order that reproduces every observation.
     *
     * @return the rounded line, or null if no line and rounding reproduce every observation
     */
    static Linear rounded(List<JsonElement> xs, List<Leaf> ys) {
        if (ys.stream().anyMatch(y -> !INTEGRAL.contains(y.type()))) {
            return null;
        }
        List<Linear> lines = new java.util.ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            for (int j = i + 1; j < xs.size(); j++) {
                double dx = xs.get(j).getAsDouble() - xs.get(i).getAsDouble();
                if (dx == 0) {
                    continue;
                }
                double a = round((ys.get(j).number() - ys.get(i).number()) / dx);
                double b = round(ys.get(i).number() - a * xs.get(i).getAsDouble());
                if (Math.abs(a) > 1e-9 && lines.stream().noneMatch(l -> l.scale == a && l.offset == b)) {
                    lines.add(new Linear(a, b, null));
                }
            }
        }
        lines.sort(java.util.Comparator.comparingDouble((Linear l) -> Math.abs(l.offset)).thenComparingDouble(l -> Math.abs(l.scale)));
        for (Linear line : lines) {
            Set<Double> exact = new TreeSet<>();
            for (int k = 0; k < xs.size(); k++) {
                double v = line.scale * xs.get(k).getAsDouble() + line.offset;
                if (close(v, ys.get(k).number()) && close(v, Math.rint(v))) {
                    exact.add(xs.get(k).getAsDouble());
                }
            }
            if (exact.size() < MIN_DISTINCT_POINTS) {
                continue;
            }
            for (Rounding r : Rounding.values()) {
                boolean all = true;
                for (int k = 0; k < xs.size() && all; k++) {
                    all = r.round(r.line(line.scale, line.offset, xs.get(k).getAsDouble())) == ys.get(k).number();
                }
                if (all) {
                    return new Linear(line.scale, line.offset, null, r);
                }
            }
        }
        return null;
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
        if (rounding != null) {
            out.addProperty("arithmetic", rounding.arithmetic);
            out.addProperty("rounding", rounding.mode);
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
     * Without a {@link #rounding} an integral leaf truncates the double result ({@code Wire.toInt}); that is unverified
     * for such a rule, whose captures only hold inputs the line maps onto whole numbers.
     */
    double apply(double x) {
        if (rounding != null) {
            return rounding.round(rounding.line(scale, offset, x));
        }
        Double divisor = divisor();
        double v = divisor != null ? x / divisor + offset : scale * x + offset;
        // wrapped into [0, modulo): BDS sends -90 degrees as 3 quarter turns
        return modulo == null ? v : Math.floorMod(Math.round(v), modulo);
    }

    @Override
    public void emit(Emitter e) {
        if (rounding != null) {
            CodeBlock line = rounding.float32()
                ? CodeBlock.of("(double) ((float) $L * $Lf + $Lf)", e.number(), decimal(scale), decimal(offset))
                : CodeBlock.of("($L * $L + $L)", decimal(scale), e.number(), decimal(offset));
            e.put(e.fromNumber(nbt, rounding.emit(line)));
            return;
        }
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
