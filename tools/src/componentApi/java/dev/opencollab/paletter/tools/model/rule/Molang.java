package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.palantir.javapoet.CodeBlock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A Molang expression BDS parses: a number is sent as the float in six decimals ({@code "1.0"} as {@code "1.000000"}),
 * {@code "true"} and {@code "false"} as 1 and 0, anything else as written ({@link #normalise}). The normalisation is the one of the
 * captured builds (the runtime has the same, {@code Wire.molang}); a window whose build sends other text fits no Molang rule
 * and stays unexplained.
 */
public final class Molang extends Rule {
    static final String KIND = "molang";

    /**
     * Every observation must be the {@link #normalise normalised} input, and at least two different numbers must have been sent
     * normalised, one of them with a fraction or an exponent: inputs that are all written as BDS sends them are an identity,
     * and whole numbers alone say nothing about how BDS prints the others (the six decimals).
     *
     * @return the rule, or null if the observations do not show it
     */
    public static Molang fit(List<JsonElement> xs, List<Leaf> ys) {
        Set<String> normalised = new HashSet<>();
        boolean fraction = false;
        for (int i = 0; i < xs.size(); i++) {
            JsonPrimitive x = xs.get(i).getAsJsonPrimitive();
            if (!x.isString() || ys.get(i) == null || !"string".equals(ys.get(i).type())) {
                return null;
            }
            String text = x.getAsString();
            String sent = normalise(text);
            if (!sent.equals(ys.get(i).value())) {
                return null;
            }
            if (!sent.equals(text) && number(text) != null) {
                normalised.add(text);
                fraction |= !text.trim().matches("-?\\d+");
            }
        }
        return normalised.size() >= 2 && fraction ? new Molang() : null;
    }

    /** The shapes of a Molang number the captures have: digits with or without a fraction or an exponent, spaces around. */
    private static final Pattern NUMBER = Pattern.compile("\\s*-?(\\d+\\.?\\d*|\\.\\d+)([eE]-?\\d+)?\\s*");

    /**
     * The expression as BDS sends it: {@code "true"} and {@code "false"} as 1 and 0, a number as {@link #number}, anything else
     * as written (BDS computes more expressions than that; Paletter does not).
     */
    public static String normalise(String expression) {
        if (expression.equals("true") || expression.equals("false")) {
            return expression.equals("true") ? "1.000000" : "0.000000";
        }
        String number = number(expression);
        return number != null ? number : expression;
    }

    /**
     * A number as BDS sends it: the float in six decimals of its exact value, a tie to the even digit ({@code 0.0078125} is
     * {@code "0.007812"}), the sign kept ({@code "-0.000000"}).
     *
     * @return the number, or null if the text is not a number of a captured shape or is beyond a float
     */
    static String number(String text) {
        if (!NUMBER.matcher(text).matches()) {
            return null;
        }
        float value = Float.parseFloat(text.trim());
        if (Float.isInfinite(value)) {
            return null;
        }
        String digits = new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        return (Float.floatToRawIntBits(value) < 0 && !digits.startsWith("-") ? "-" : "") + digits;
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
    public boolean mapValue() {
        return true;
    }

    @Override
    public void emit(Emitter e) {
        e.put(CodeBlock.of("$T.molang($L)", Emitter.WIRE, e.string()));
    }

    @Override
    String evaluate(JsonPrimitive p) {
        return "string".equals(nbt) ? normalise(p.getAsString()) + " (string)" : null;
    }
}
