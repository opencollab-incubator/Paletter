package dev.opencollab.paletter.tools;

import java.util.Comparator;
import java.util.regex.Pattern;

/** The order of dotted versions, such as BDS builds ({@code 1.26.63.1}) and the names of {@code data/bds} directories. */
public final class Versions {
    /**
     * Number by number ({@code 1.26.9.0} before {@code 1.26.63.1}); a version before the longer ones it starts
     * ({@code 1.26.70} before {@code 1.26.70.23}). Parts are split at dots and dashes, and a part that is not a number
     * counts as 0.
     */
    public static final Comparator<String> ORDER = Versions::compare;
    /** A build or a line ({@code 1.26}, {@code 1.26.30}, {@code 1.26.30.5}), as a bound of a range of builds; builds are {@link BuildNumber}s. */
    private static final Pattern BOUND = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}");

    private Versions() {
    }

    /** A bound of a range of builds, checked: one to four dotted numbers. */
    public static String requireBound(String bound) {
        if (bound == null || !BOUND.matcher(bound).matches()) {
            throw new IllegalArgumentException("Not a BDS build or line (one to four dotted numbers, e.g. 1.26.30): " + bound);
        }
        return bound;
    }

    public static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Long.compare(number(x[i]), number(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static long number(String part) {
        return !part.isEmpty() && part.chars().allMatch(Character::isDigit) ? Long.parseLong(part) : 0;
    }
}
