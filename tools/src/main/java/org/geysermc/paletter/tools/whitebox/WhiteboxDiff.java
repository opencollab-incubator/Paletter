package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What changed between the {@code whitebox.json} of two builds. A line that starts with {@code !} is a change of the
 * serialisation itself (a component, its network flag, its kind, a key or a tag type); a line that starts with
 * {@code ~} only says that the code of a writer changed while its keys did not, which is a reason to compare the
 * captures of the two builds, not a change by itself. The same goes for a reflection schema that was found in one build
 * and not in the other: the list is read where a function registers one component alone, which compilers decide.
 */
public final class WhiteboxDiff {
    private static final List<String> KEY_LISTS = List.of("writes", "writesExtra", "writesNested", "reads", "schema");

    private WhiteboxDiff() {
    }

    /** @return the changes from {@code before} to {@code after}, one per line; empty when nothing changed */
    public static List<String> diff(JsonObject before, JsonObject after) {
        List<String> lines = new ArrayList<>();
        if (!before.get("platform").equals(after.get("platform"))) {
            lines.add("! compared across platforms: " + before.get("platform").getAsString() + " and " + after.get("platform").getAsString()
                + " (keys built in place and tag types are only read on Linux)");
        }
        JsonObject layoutBefore = before.getAsJsonObject("layout");
        JsonObject layoutAfter = after.getAsJsonObject("layout");
        for (String family : List.of("item", "block")) {
            if (!layoutBefore.get(family).equals(layoutAfter.get(family))) {
                lines.add("! " + family + " component classes: the virtual function slots moved from " + layoutBefore.get(family) + " to "
                    + layoutAfter.get(family));
            }
        }
        for (String group : List.of("itemComponents", "blockComponents", "traits")) {
            String label = group.equals("traits") ? "trait" : group.replace("Components", " component");
            entries(lines, label, before.getAsJsonObject(group), after.getAsJsonObject(group));
        }
        // Classes without a name are told apart by what they write: class names and code differ between builds
        List<String> unnamedBefore = unnamed(before);
        List<String> unnamedAfter = unnamed(after);
        for (String written : unnamedAfter) {
            if (!unnamedBefore.remove(written)) {
                lines.add("! new item class without a component name: " + written);
            }
        }
        unnamedBefore.forEach(written -> lines.add("! removed item class without a component name: " + written));
        for (String family : List.of("item", "block")) {
            entry(lines, family + " envelope", before.getAsJsonObject("envelopes").getAsJsonObject(family),
                after.getAsJsonObject("envelopes").getAsJsonObject(family));
        }
        return lines;
    }

    private static void entries(List<String> lines, String label, JsonObject before, JsonObject after) {
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            if (!before.has(name)) {
                lines.add("! new " + label + " " + name + ": " + after.get(name));
            } else if (!after.has(name)) {
                lines.add("! removed " + label + " " + name);
            } else {
                entry(lines, label + " " + name, before.getAsJsonObject(name), after.getAsJsonObject(name));
            }
        }
    }

    private static void entry(List<String> lines, String label, JsonObject before, JsonObject after) {
        int size = lines.size();
        for (String field : List.of("network", "kind")) {
            if (before.has(field) && after.has(field) && !before.get(field).equals(after.get(field))) {
                lines.add("! " + label + ": " + field + " " + before.get(field).getAsString() + " -> " + after.get(field).getAsString());
            }
        }
        for (String list : KEY_LISTS) {
            if (list.equals("schema") && before.has(list) != after.has(list)) {
                lines.add("~ " + label + ": the reflection schema's keys were " + (after.has(list) ? "found: " + after.get(list) : "not found"));
                continue;
            }
            Set<String> old = strings(before, list);
            Set<String> now = strings(after, list);
            for (String key : now) {
                if (!old.contains(key)) {
                    lines.add("! " + label + ": " + list + " + " + key);
                }
            }
            for (String key : old) {
                if (!now.contains(key)) {
                    lines.add("! " + label + ": " + list + " - " + key);
                }
            }
        }
        if (before.has("types") && after.has("types")) {
            for (Map.Entry<String, JsonElement> type : after.getAsJsonObject("types").entrySet()) {
                JsonElement old = before.getAsJsonObject("types").get(type.getKey());
                if (old != null && !old.equals(type.getValue())) {
                    lines.add("! " + label + ": type of " + type.getKey() + " " + old.getAsString() + " -> " + type.getValue().getAsString());
                }
            }
        }
        if (before.has("hooks") != after.has("hooks") || before.has("hooks") && !before.get("hooks").equals(after.get("hooks"))) {
            lines.add("! " + label + ": hooks " + before.get("hooks") + " -> " + after.get("hooks"));
        }
        long changes = lines.subList(size, lines.size()).stream().filter(line -> line.startsWith("!")).count();
        if (changes == 0 && before.has("code") && after.has("code") && !before.get("code").equals(after.get("code"))) {
            lines.add("~ " + label + ": the writer's code changed, its keys did not");
        }
    }

    /** The classes without a name that are sent, each as its kind and what it writes; one entry per class. */
    private static List<String> unnamed(JsonObject facts) {
        List<String> classes = new ArrayList<>();
        for (JsonElement element : facts.getAsJsonObject("itemClassesWithoutName").getAsJsonArray("sent")) {
            JsonObject entry = element.getAsJsonObject();
            for (int i = 0; i < (entry.has("count") ? entry.get("count").getAsInt() : 1); i++) {
                classes.add(entry.get("kind").getAsString() + " " + strings(entry, "writes"));
            }
        }
        return classes;
    }

    private static Set<String> strings(JsonObject object, String list) {
        Set<String> strings = new TreeSet<>();
        if (object.has(list)) {
            object.getAsJsonArray(list).forEach(element -> strings.add(element.getAsString()));
        }
        return strings;
    }
}
