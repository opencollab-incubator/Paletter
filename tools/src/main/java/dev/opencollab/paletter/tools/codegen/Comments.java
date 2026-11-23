package dev.opencollab.paletter.tools.codegen;

import java.util.Locale;

/** Text from data in the comments of generated code: nothing in it may end the comment or read as code. */
public final class Comments {
    private Comments() {
    }

    /**
     * @return text that is safe in a {@code //} comment or a Javadoc: a backslash doubled (so no Unicode escape starts),
     * a character outside printable ASCII as {@code <U+000A>}, and no {@code *}{@code /}
     */
    public static String safe(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format(Locale.ROOT, "<U+%04X>", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString().replace("*/", "*<U+002F>");
    }
}
