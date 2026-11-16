package org.geysermc.paletter.tools;

import java.util.Comparator;

/** The order of dotted versions, such as BDS builds ({@code 1.26.63.1}) and the names of {@code data/bds} directories. */
public final class Versions {
    /**
     * Number by number ({@code 1.26.9.0} before {@code 1.26.63.1}); a version before the longer ones it starts
     * ({@code 1.26.70} before {@code 1.26.70.23}). Parts are split at dots and dashes, and a part that is not a number
     * counts as 0.
     */
    public static final Comparator<String> ORDER = Versions::compare;

    private Versions() {
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
