package org.geysermc.paletter.tools.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reader of a component's windows in {@code mappings.json}: the first window is the component's own object, the
 * windows after it hang inside it as {@code protocol<N>} members (N: the window's first protocol). Every window lists the
 * protocols of the captures it stands for as {@code protocols}.
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
}
