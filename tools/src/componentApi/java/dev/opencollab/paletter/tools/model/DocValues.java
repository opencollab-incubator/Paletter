package dev.opencollab.paletter.tools.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values a sentence of the creator docs lists for a field: what the docs parser reads into {@code enumValues}, and what
 * {@link Fixtures#apply} reads for a field an erratum makes a string (the parser skips the list where the docs call the
 * field a boolean).
 */
public final class DocValues {
    private static final Pattern VALUE_LIST = Pattern.compile(
        "(?i)(?:valid (?:\\(and required\\) )?values? (?:for this property )?(?:is|are)|options (?:are|include)|supported [a-z ]+? are|must be(?= \\[)|"
            + "allowed [a-z ]+? are\\s*:?|one of|list of (?:any of )?the following [a-z]+)\\s*:?\\s*(.*?)(?:\\. |\\.$|$)");

    private DocValues() {
    }

    /**
     * "Valid values are `a`, `b`", "Options are "a", "b"", "Must be [0.0, 90.0]", "Can be a list of the following
     * directions: "up", ...": the listed values.
     */
    public static List<JsonElement> valuesIn(String text) {
        List<JsonElement> values = new ArrayList<>();
        Matcher list = VALUE_LIST.matcher(text);
        while (list.find()) {
            String part = list.group(1);
            Matcher code = Pattern.compile("`([^`]+)`").matcher(part);
            boolean any = false;
            while (code.find()) {
                any = true;
                for (String token : splitList(code.group(1))) {
                    values.add(literal(token));
                }
            }
            if (!any) {
                Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(part);
                while (quoted.find()) {
                    any = true;
                    values.add(new JsonPrimitive(quoted.group(1)));
                }
            }
            if (!any) {
                Matcher bracket = Pattern.compile("\\[([^\\]]+)]").matcher(part);
                if (bracket.find()) {
                    for (String token : splitList(bracket.group(1))) {
                        values.add(literal(token));
                    }
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static List<String> splitList(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split(",")) {
            String t = token.trim().replaceAll("^\"|\"$", "");
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** A docs value as JSON: a whole number, a decimal, a boolean, else a string without its quotes. */
    public static JsonElement literal(String token) {
        String t = token.trim();
        if (t.matches("-?\\d+")) {
            return new JsonPrimitive(Long.parseLong(t));
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            return new JsonPrimitive(Double.parseDouble(t));
        }
        if (t.equals("true") || t.equals("false")) {
            return new JsonPrimitive(Boolean.parseBoolean(t));
        }
        return new JsonPrimitive(t.replaceAll("^\"|\"$", ""));
    }
}
