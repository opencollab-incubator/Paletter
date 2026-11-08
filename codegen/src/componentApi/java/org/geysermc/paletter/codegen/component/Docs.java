package org.geysermc.paletter.codegen.component;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Javadoc text and Java literals.
 */
final class Docs {
    private static final int WIDTH = 110;

    private Docs() {
    }

    /** Markdown docs text to javadoc: HTML escaped, code spans and links kept readable, no stray javadoc tags. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("@", "&#64;")
            .replace("*/", "*&#47;").replace("\\", "&#92;");
        s = s.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");
        StringBuilder out = new StringBuilder();
        boolean code = false;
        for (char ch : s.toCharArray()) {
            if (ch == '`') {
                out.append(code ? "</code>" : "<code>");
                code = !code;
            } else if (ch == '{' ) {
                out.append("&#123;");
            } else if (ch == '}') {
                out.append("&#125;");
            } else {
                out.append(ch);
            }
        }
        if (code) {
            out.append("</code>");
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** Escaped text, except javadoc inline tags this generator writes ({@code ...}, {@link ...}). */
    static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + word.length() + 1 > WIDTH) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    static String literal(JsonElement value) {
        return "<code>" + value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("{", "&#123;")
            .replace("}", "&#125;").replace("@", "&#64;") + "</code>";
    }

    static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    static String floatLiteral(double d) {
        float f = (float) d;
        String s = Float.toString(f);
        return s.toLowerCase(Locale.ROOT) + "f";
    }

    static String string(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (ch < 0x20 || ch > 0x7e) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
