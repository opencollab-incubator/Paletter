package org.geysermc.paletter.tools.whitebox;

/**
 * Reads class names out of the type names that compilers store for run-time type information. Only plain and nested
 * names are turned into {@code A::B}; anything else (templates, lambdas) is returned as stored.
 */
final class Names {
    private Names() {
    }

    /** Linux: {@code 24BlockFrictionDescription}, or nested {@code N10BlockTrait10ConnectionE}. */
    static String itanium(String mangled) {
        if (mangled == null || mangled.isEmpty()) {
            return null;
        }
        boolean nested = mangled.startsWith("N") && mangled.endsWith("E");
        String rest = nested ? mangled.substring(1, mangled.length() - 1) : mangled;
        StringBuilder name = new StringBuilder();
        int at = 0;
        while (at < rest.length()) {
            int digits = at;
            while (digits < rest.length() && Character.isDigit(rest.charAt(digits))) {
                digits++;
            }
            if (digits == at || digits - at > 3) {
                return mangled;
            }
            int length = Integer.parseInt(rest.substring(at, digits));
            if (digits + length > rest.length()) {
                return mangled;
            }
            String part = rest.substring(digits, digits + length);
            if (!part.matches("\\w+")) {
                return mangled;
            }
            name.append(name.isEmpty() ? "" : "::").append(part);
            at = digits + length;
        }
        return name.isEmpty() || (!nested && name.indexOf("::") >= 0) ? mangled : name.toString();
    }

    /** Windows: {@code .?AVBlockFrictionDescription@@}, or nested {@code .?AVConnection@BlockTrait@@}. */
    static String msvc(String decorated) {
        if (decorated == null || !decorated.matches("\\.\\?A[VU][\\w@]+@@")) {
            return decorated;
        }
        String[] parts = decorated.substring(4, decorated.length() - 2).split("@");
        StringBuilder name = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            name.append(name.isEmpty() ? "" : "::").append(parts[i]);
        }
        return name.toString();
    }
}
