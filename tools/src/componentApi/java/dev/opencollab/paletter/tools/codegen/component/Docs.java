package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Javadoc text and Java literals.
 */
public final class Docs {
    private static final int WIDTH = 110;
    private static final String LEARN = "https://learn.microsoft.com/en-us/minecraft/creator/";
    private static final String CONTENT_VIEW = "?view=minecraft-bedrock-stable";

    private Docs() {
    }

    /**
     * The Microsoft Learn page of a docs page of the MicrosoftDocs/minecraft-creator repository: the path below
     * {@code creator/} lowercased and without {@code .md}, which is how Learn publishes it.
     *
     * @param page a path of the repository, e.g. {@code creator/Reference/Content/ItemReference/Examples/ItemComponents/minecraft_glint.md}
     * @return the URL, or null if the path is empty or not a page below {@code creator/}
     */
    static String learnUrl(String page) {
        if (page == null || !page.startsWith("creator/") || !page.endsWith(".md")) {
            return null;
        }
        return LEARN + page.substring("creator/".length(), page.length() - ".md".length()).toLowerCase(Locale.ROOT) + CONTENT_VIEW;
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

    /** "BDS 1.26.63.1", also wrapped over two lines. */
    private static final Pattern BDS_BUILD = Pattern.compile("(?:BDS|Bedrock Dedicated Server)\\s+(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern BUILD = Pattern.compile("(?<![\\w.])\\d+\\.\\d+\\.\\d+\\.\\d+(?![\\w.])");
    private static final Pattern DATA_BDS_PARENS = Pattern.compile("\\s*\\((?:\\{@code )?data/bds/[^)]*\\)");
    private static final Pattern DATA_BDS = Pattern.compile(",?\\s*(?:\\{@code )?data/bds/[^\\s)}]*}?");

    /**
     * Public Javadoc names Bedrock versions, not BDS builds or {@code data/bds} paths (those are the evidence of the model,
     * not API): "BDS 1.26.63.1" becomes "Bedrock 26.60", the data version whose capture the build is, a lone build its data
     * version, and a {@code data/bds} path goes. A build of no capture keeps only "BDS".
     */
    static String bedrock(ApiModel model, String text) {
        Map<String, String> versions = versions(model);
        String out = DATA_BDS.matcher(DATA_BDS_PARENS.matcher(text).replaceAll("")).replaceAll("");
        out = BDS_BUILD.matcher(out).replaceAll(m -> Matcher.quoteReplacement(versions.containsKey(m.group(1)) ? "Bedrock " + versions.get(m.group(1)) : "BDS"));
        return BUILD.matcher(out).replaceAll(m -> Matcher.quoteReplacement(versions.getOrDefault(m.group(), m.group())));
    }

    /** The data version of every captured BDS build, by build. */
    private static Map<String, String> versions(ApiModel model) {
        Map<String, String> versions = new HashMap<>();
        model.windowProtocols.forEach((bds, window) -> {
            if (model.dataVersions.containsKey(window)) {
                versions.put(bds, model.dataVersions.get(window));
            }
        });
        return versions;
    }

    /**
     * A capture in public Javadoc: "Bedrock 26.60 (protocol 2225)", or "protocol 2225" if the build is of no known data version.
     *
     * @param capture the protocol of a capture and its BDS build
     */
    static String release(ApiModel model, Map.Entry<Integer, String> capture) {
        String version = versions(model).get(capture.getValue());
        return version == null ? "protocol " + capture.getKey() : "Bedrock " + version + " (protocol " + capture.getKey() + ")";
    }

    /** @return the data versions of captured BDS builds, "Bedrock 26.30, 26.40"; the builds of no known data version left out ("" if none is left) */
    static String releases(ApiModel model, List<String> builds) {
        Map<String, String> versions = versions(model);
        List<String> known = builds.stream().filter(versions::containsKey).map(versions::get).toList();
        return known.isEmpty() ? "" : "Bedrock " + String.join(", ", known);
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

    public static String number(double d) {
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
