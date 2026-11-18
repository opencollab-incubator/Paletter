package dev.opencollab.paletter.tools.behaviour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Pretty printing for captures: indented, but an element whose compact form is short stays on one line, so a list of
 * hundreds of small entries stays readable and diffs by entry.
 */
final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int INLINE = 150;

    private Json() {
    }

    static String pretty(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(out, element, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, JsonElement element, int depth) {
        String compact = GSON.toJson(element);
        if (compact.length() <= INLINE || element.isJsonPrimitive() || element.isJsonNull()) {
            out.append(compact);
            return;
        }
        String indent = "  ".repeat(depth + 1);
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            out.append("[\n");
            for (int i = 0; i < array.size(); i++) {
                out.append(indent);
                write(out, array.get(i), depth + 1);
                out.append(i + 1 < array.size() ? ",\n" : "\n");
            }
            out.append("  ".repeat(depth)).append(']');
        } else {
            JsonObject object = element.getAsJsonObject();
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                out.append(indent).append(GSON.toJson(entry.getKey())).append(": ");
                write(out, entry.getValue(), depth + 1);
                out.append(++i < object.size() ? ",\n" : "\n");
            }
            out.append("  ".repeat(depth)).append('}');
        }
    }
}
