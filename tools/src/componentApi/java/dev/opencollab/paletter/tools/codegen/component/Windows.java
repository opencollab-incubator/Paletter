package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.MappingWindows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The protocol windows of a component: the ranges of protocols over which one mapping of BDS holds, and what they say about
 * the component (server only, rejected, since which preview).
 */
final class Windows {
    /** The status of a field none of whose variants the capture of a window has: no result, not a finding about BDS. */
    static final String NOT_GENERATED = "not-generated";

    private Windows() {
    }

    /**
     * The fields with no result in the newest window that sends the component ({@link #NOT_GENERATED}) while an older one
     * has one: the newest capture lacks their variants (it was made with an older add-on). The newest window is the
     * encoder of every later protocol, so such a field is left out there with a report entry, and its API facts come
     * from the older window ({@link Comp#evidence}). Reported, not failed: the gap is the capture's, and the fix is a
     * capture with the current add-on, which the generator cannot make; failing would stop every generation until then.
     *
     * @return one line per such field, for the generation output
     */
    static List<String> gaps(Comp c) {
        Window latest = c.latest();
        List<String> out = new ArrayList<>();
        if (latest == null || !latest.mapping().has("fields")) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : latest.mapping().getAsJsonObject("fields").entrySet()) {
            if (!NOT_GENERATED.equals(e.getValue().getAsJsonObject().get("status").getAsString())) {
                continue;
            }
            Window evidence = c.evidence(e.getKey());
            if (evidence != latest) {
                out.add(c.id + " " + e.getKey() + " has no result in the newest capture window (from protocol " + latest.since()
                    + ") but has one from protocol " + evidence.since() + " (" + evidence.field(e.getKey()).get("status").getAsString()
                    + "): its API follows that window, newer protocols leave it out with a report entry; capture again with the"
                    + " current add-on");
            }
        }
        return out;
    }

    /** @return the window in which BDS only accepts the component, or null; one is all the annotation can state */
    static Window serverOnly(Comp c) {
        List<Window> serverOnly = c.windows.stream().filter(w -> w.status().equals("server-only")).toList();
        if (serverOnly.size() > 1) {
            throw new GenerationException(c.id + " is server only in " + serverOnly.size() + " protocol windows; @ServerOnly states one range");
        }
        return serverOnly.isEmpty() ? null : serverOnly.getFirst();
    }

    /** @return the newest window when BDS rejects the component there (the trailing run of rejecting windows), or null */
    static Window rejected(Comp c) {
        if (c.windows.isEmpty() || !c.windows.getLast().status().equals("rejected")) {
            return null;
        }
        int i = c.windows.size() - 1;
        while (i > 0 && c.windows.get(i - 1).status().equals("rejected")) {
            i--;
        }
        return new Window(c.windows.get(i).since(), Integer.MAX_VALUE, c.windows.getLast().mapping());
    }

    /**
     * The bundled preview that alone has something: the window of every release capture rejects it (or does not have it),
     * the window of a preview capture does not. Null if there is no such preview.
     *
     * @param status what a window says about it ({@code rejected}, ...), null if the window does not have it
     */
    static String sincePreview(ApiModel model, Comp c, java.util.function.Function<Window, String> status) {
        for (int protocol : model.releaseProtocols) {
            String s = statusIn(c, protocol, status);
            if (s != null && !s.equals("rejected")) {
                return null;
            }
        }
        for (Map.Entry<Integer, String> preview : model.previewProtocols.entrySet()) {
            String s = statusIn(c, preview.getKey(), status);
            if (s != null && !s.equals("rejected")) {
                return preview.getValue();
            }
        }
        return null;
    }

    /** What a window says about it, null where the build does not have the component ({@link MappingWindows#ABSENT}). */
    private static String statusIn(Comp c, int protocol, java.util.function.Function<Window, String> status) {
        Window w = window(c, protocol);
        return w.absent() ? null : status.apply(w);
    }

    /**
     * The first bundled release with the component, when the oldest window is one of builds that do not have it
     * ({@link MappingWindows#ABSENT}); null if the oldest builds have it, or only a preview does ({@code @SincePreview}).
     */
    static String bedrockSince(ApiModel model, Comp c) {
        if (c.windows.isEmpty() || !c.windows.getFirst().absent()) {
            return null;
        }
        Window first = c.windows.stream().filter(w -> !w.absent()).findFirst()
            .orElseThrow(() -> new GenerationException(c.id + " is absent in every window"));
        if (model.previewProtocols.containsKey(first.since())) {
            return null;
        }
        String name = model.dataVersions.get(first.since());
        if (name == null) {
            throw new GenerationException(c.id + ": no data version for protocol " + first.since()
                + " in the captures of mappings.json (dataVersion), so @BedrockSince cannot name the first one with it");
        }
        return name;
    }

    static Window window(Comp c, int protocol) {
        return c.windows.stream().filter(w -> w.since() <= protocol && protocol < w.until()).findFirst().orElseThrow();
    }

    static List<Window> windows(String id, JsonObject mapping) {
        if (mapping == null) {
            return List.of();
        }
        TreeMap<Integer, JsonObject> byProtocol = new TreeMap<>();
        List<JsonObject> listed;
        try {
            listed = MappingWindows.of(mapping);
        } catch (IllegalArgumentException e) {
            throw new GenerationException(id + ": " + e.getMessage());
        }
        for (JsonObject window : listed) {
            for (JsonElement p : window.getAsJsonArray("protocols")) {
                if (byProtocol.put(p.getAsInt(), window) != null) {
                    throw new GenerationException("conflicting windows: " + id + " has two mappings for protocol " + p.getAsInt());
                }
            }
        }
        // Each observed mapping holds from its protocol until the next protocol with a different mapping. A capture without
        // the component fails generation (ComponentGenerator), so every window protocol is listed and none is absorbed by a
        // neighbour. The oldest window also holds below the oldest capture: no bundled data version is older than it
        // (ComponentIndex.OLDEST_CAPTURED_PROTOCOL)
        List<Window> windows = new ArrayList<>();
        List<Map.Entry<Integer, JsonObject>> entries = new ArrayList<>(byProtocol.entrySet());
        int i = 0;
        while (i < entries.size()) {
            JsonObject current = entries.get(i).getValue();
            int j = i;
            while (j + 1 < entries.size() && entries.get(j + 1).getValue() == current) {
                j++;
            }
            int since = windows.isEmpty() ? Integer.MIN_VALUE : entries.get(i).getKey();
            int until = j + 1 < entries.size() ? entries.get(j + 1).getKey() : Integer.MAX_VALUE;
            windows.add(new Window(since, until, current));
            i = j + 1;
        }
        return windows;
    }
}
