package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** Paths into a component's JSON: {@code a.b}, {@code a[]} (the first element) and {@code a{}} (the first value of a map). */
public final class JsonPath {
    private JsonPath() {
    }

    public static String parentOf(String path) {
        if (path.endsWith("[]") || path.endsWith("{}")) {
            return path.substring(0, path.length() - 2);
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    /** Path tokens: keys, "[]" and "{}". */
    public static List<String> tokens(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if ((ch == '[' || ch == '{') && i + 1 < path.length() && path.charAt(i + 1) == (ch == '[' ? ']' : '}')) {
                if (!key.isEmpty()) {
                    tokens.add(key.toString());
                    key.setLength(0);
                }
                tokens.add(ch == '[' ? "[]" : "{}");
                i++;
            } else if (ch == '.') {
                if (!key.isEmpty()) {
                    tokens.add(key.toString());
                    key.setLength(0);
                }
            } else {
                key.append(ch);
            }
        }
        if (!key.isEmpty()) {
            tokens.add(key.toString());
        }
        return tokens;
    }

    public static JsonElement get(JsonElement root, String path) {
        JsonElement current = root;
        for (String token : tokens(path)) {
            if (current == null) {
                return null;
            }
            if (token.equals("[]")) {
                current = current.isJsonArray() && !current.getAsJsonArray().isEmpty() ? current.getAsJsonArray().get(0) : null;
            } else if (token.equals("{}")) {
                current = current.isJsonObject() && !current.getAsJsonObject().isEmpty()
                    ? current.getAsJsonObject().entrySet().iterator().next().getValue() : null;
            } else {
                current = current.isJsonObject() ? current.getAsJsonObject().get(token) : null;
            }
        }
        return current;
    }

    public static boolean remove(JsonElement root, String path) {
        List<String> tokens = tokens(path);
        JsonElement parent = get(root, String.join(".", tokens.subList(0, tokens.size() - 1)).replace(".[]", "[]").replace(".{}", "{}"));
        String last = tokens.get(tokens.size() - 1);
        if (parent != null && parent.isJsonObject() && parent.getAsJsonObject().has(last)) {
            parent.getAsJsonObject().remove(last);
            return true;
        }
        return false;
    }

    /** Sets a value in an existing object of the JSON; false if the object is not there. */
    public static boolean set(JsonElement root, String path, JsonElement value) {
        String parent = parentOf(path);
        JsonElement object = parent.isEmpty() ? root : get(root, parent);
        if (object == null || !object.isJsonObject() || path.endsWith("[]") || path.endsWith("{}")) {
            return false;
        }
        object.getAsJsonObject().add(tokens(path).getLast(), value.deepCopy());
        return true;
    }
}
