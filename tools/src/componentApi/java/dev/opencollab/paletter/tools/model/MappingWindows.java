package dev.opencollab.paletter.tools.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The one reader and writer of a component's windows in {@code mappings.json}: the component is
 * {@code {"windows": [{"protocols": [...], "status": ..., "fields": ..., ...}, ...]}}, oldest window first. Every window
 * lists the protocols of the captures it stands for as {@code protocols}; inference, coverage and the generator read them
 * here.
 */
public final class MappingWindows {
    /** The key of a component's window list. */
    public static final String WINDOWS = "windows";

    /**
     * The status of a window whose builds do not have the component: their own schema lacks it, so their add-on had no
     * variant of it. Not a missing capture; nothing is sent there.
     */
    public static final String ABSENT = "absent";

    private MappingWindows() {
    }

    /**
     * @return the windows of a component, oldest first
     * @throws IllegalArgumentException if it has none, or a window lists no protocol
     */
    public static List<JsonObject> of(JsonObject component) {
        if (!component.has(WINDOWS) || component.getAsJsonArray(WINDOWS).isEmpty()) {
            throw new IllegalArgumentException("a component of mappings.json without \"" + WINDOWS + "\": " + component.keySet());
        }
        List<JsonObject> windows = new ArrayList<>();
        for (JsonElement e : component.getAsJsonArray(WINDOWS)) {
            JsonObject window = e.getAsJsonObject();
            if (!window.has("protocols") || window.getAsJsonArray("protocols").isEmpty()) {
                throw new IllegalArgumentException("a window of mappings.json lists no protocols: " + window.keySet());
            }
            windows.add(window);
        }
        return windows;
    }

    /** @return the window of the oldest capture of a component */
    public static JsonObject oldest(JsonObject component) {
        return of(component).getFirst();
    }

    /** @return the window of the newest capture of a component */
    public static JsonObject newest(JsonObject component) {
        return of(component).getLast();
    }

    /** @return every protocol some window of the component stands for */
    public static TreeSet<Integer> protocols(JsonObject component) {
        TreeSet<Integer> protocols = new TreeSet<>();
        for (JsonObject window : of(component)) {
            window.getAsJsonArray("protocols").forEach(p -> protocols.add(p.getAsInt()));
        }
        return protocols;
    }

    /**
     * One result per capture (oldest first) into the component's windows: a capture whose wire relevant part
     * ({@link #wireShape}) equals the previous window's joins that window ({@code protocols}); a different one starts a
     * new window. A window whose status is not {@code sent} (rejected, server only, absent) records that the component is
     * not on the wire there.
     *
     * @return the component with its windows, or null without results
     */
    public static JsonObject merge(List<JsonObject> results, List<Integer> protocols) {
        if (results.isEmpty()) {
            return null;
        }
        JsonArray windows = new JsonArray();
        JsonObject current = null;
        JsonElement currentShape = null;
        for (int i = 0; i < results.size(); i++) {
            JsonObject result = results.get(i);
            JsonElement shape = wireShape(result);
            if (current != null && shape.equals(currentShape)) {
                current.getAsJsonArray("protocols").add(protocols.get(i));
                continue;
            }
            // the protocols first: what a reader of the file looks for
            JsonObject window = new JsonObject();
            JsonArray own = new JsonArray();
            own.add(protocols.get(i));
            window.add("protocols", own);
            result.entrySet().stream().filter(e -> !e.getKey().equals("protocols")).forEach(e -> window.add(e.getKey(), e.getValue()));
            current = window;
            currentShape = shape;
            windows.add(current);
        }
        JsonObject component = new JsonObject();
        component.add(WINDOWS, windows);
        return component;
    }

    /**
     * What of a result decides the bytes on the wire: the status, the wire keys, the constants and per form and field
     * its status, rules, transform and absent values. What BDS said about the JSON (errors, ranges, accepted and
     * rejected counts) is not part of it: another build with other messages or a different variant count is no new window.
     */
    public static JsonElement wireShape(JsonObject result) {
        JsonObject shape = new JsonObject();
        for (String key : List.of("status", "wire", "constants")) {
            if (result.has(key)) {
                shape.add(key, result.get(key));
            }
        }
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = new JsonObject();
                for (String key : List.of("status", "rules", "transform", "whenAbsent")) {
                    if (e.getValue().getAsJsonObject().has(key)) {
                        field.add(key, e.getValue().getAsJsonObject().get(key));
                    }
                }
                kept.add(e.getKey(), field);
            }
            shape.add(group, kept);
        }
        return shape;
    }
}
