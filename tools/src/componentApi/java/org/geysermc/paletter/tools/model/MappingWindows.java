package org.geysermc.paletter.tools.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reader and writer of a component's windows in {@code mappings.json}: the first window is the component's own
 * object, the windows after it hang inside it as {@code protocol<N>} members (N: the window's first protocol). Every window
 * lists the protocols of the captures it stands for as {@code protocols}.
 */
public final class MappingWindows {
    private static final Pattern KEY = Pattern.compile("protocol(\\d+)");

    private MappingWindows() {
    }

    /** @return the first protocol of a window's key ({@code protocol2193}), or -1 if the key is not a window */
    public static int protocolOfKey(String key) {
        Matcher m = KEY.matcher(key);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** @return the windows of a merged component, the first (the component's object) and then the nested ones */
    public static List<JsonObject> of(JsonObject component) {
        List<JsonObject> windows = new ArrayList<>();
        windows.add(component);
        for (Map.Entry<String, JsonElement> e : component.entrySet()) {
            if (protocolOfKey(e.getKey()) >= 0 && e.getValue().isJsonObject()) {
                windows.add(e.getValue().getAsJsonObject());
            }
        }
        return windows;
    }

    /** @return the window of the newest capture of a merged component */
    public static JsonObject newest(JsonObject component) {
        JsonObject newest = component;
        int newestProtocol = component.getAsJsonArray("protocols").get(0).getAsInt();
        for (Map.Entry<String, JsonElement> e : component.entrySet()) {
            int protocol = protocolOfKey(e.getKey());
            if (protocol > newestProtocol && e.getValue().isJsonObject()) {
                newestProtocol = protocol;
                newest = e.getValue().getAsJsonObject();
            }
        }
        return newest;
    }

    /** @return every protocol some window of the component stands for */
    public static TreeSet<Integer> protocols(JsonObject component) {
        TreeSet<Integer> protocols = new TreeSet<>();
        for (JsonObject window : of(component)) {
            if (window.has("protocols")) {
                window.getAsJsonArray("protocols").forEach(p -> protocols.add(p.getAsInt()));
            }
        }
        return protocols;
    }

    /**
     * One result per capture (oldest first) into the component's windows: a capture whose wire relevant part
     * ({@link #wireShape}) equals the previous window's joins that window ({@code protocols}); a different one starts a
     * new window, kept beside the first as {@code protocol<N>} (N: its first protocol). A window whose status is not
     * {@code sent} (rejected, server only) records that the component is not on the wire there.
     *
     * @return the first window with the others attached, or null without results
     */
    public static JsonObject merge(List<JsonObject> results, List<Integer> protocols) {
        JsonObject first = null;
        JsonObject current = null;
        JsonElement currentShape = null;
        JsonArray currentProtocols = null;
        for (int i = 0; i < results.size(); i++) {
            JsonObject result = results.get(i);
            JsonElement shape = wireShape(result);
            if (current != null && shape.equals(currentShape)) {
                currentProtocols.add(protocols.get(i));
                continue;
            }
            current = result;
            currentShape = shape;
            currentProtocols = new JsonArray();
            currentProtocols.add(protocols.get(i));
            current.add("protocols", currentProtocols);
            if (first == null) {
                first = current;
            } else {
                first.add("protocol" + protocols.get(i), current);
            }
        }
        return first;
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
