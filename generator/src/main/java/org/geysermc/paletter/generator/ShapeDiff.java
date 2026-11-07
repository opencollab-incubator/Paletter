package org.geysermc.paletter.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two inferred component specs (the {@code components} of {@code codegen/spec/inferred.json}) shape by shape.
 * An added, removed or retyped field, or a component that is no longer sent, is a <b>wire format change</b>; a field
 * that became optional or required, or a byte that is no longer only 0/1, is reported as a shape note.
 */
final class ShapeDiff {
    final List<String> wireChanges = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<String> newComponents = new ArrayList<>();

    static ShapeDiff of(JsonObject before, JsonObject after) {
        ShapeDiff diff = new ShapeDiff();
        Map<String, JsonObject> a = components(before);
        Map<String, JsonObject> b = components(after);
        Set<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String name : names) {
            JsonObject x = a.get(name);
            JsonObject y = b.get(name);
            if (x == null) {
                diff.newComponents.add(name);
            } else if (y == null) {
                diff.wireChanges.add(name + ": no longer sent");
            } else {
                diff.compare(name, "", x, y);
            }
        }
        return diff;
    }

    boolean identical() {
        return wireChanges.isEmpty() && notes.isEmpty() && newComponents.isEmpty();
    }

    private static Map<String, JsonObject> components(JsonObject spec) {
        Map<String, JsonObject> out = new TreeMap<>();
        if (spec != null && spec.has("components")) {
            spec.getAsJsonObject("components").entrySet()
                .forEach(entry -> out.put(entry.getKey(), entry.getValue().getAsJsonObject().getAsJsonObject("shape")));
        }
        return out;
    }

    private void compare(String component, String path, JsonObject x, JsonObject y) {
        String where = component + (path.isEmpty() ? "" : " " + path);
        String typeX = string(x, "type");
        String typeY = string(y, "type");
        if (!Objects.equals(typeX, typeY)) {
            wireChanges.add(where + ": type " + typeX + " -> " + typeY);
            return;
        }
        if (bool(x, "optional") != bool(y, "optional")) {
            notes.add(where + ": " + (bool(y, "optional") ? "now optional (absent in some observations)" : "now always present"));
        }
        if (bool(x, "bool") != bool(y, "bool")) {
            notes.add(where + ": " + (bool(y, "bool") ? "byte now only 0/1" : "byte no longer only 0/1"));
        }
        JsonObject fieldsX = x.has("fields") ? x.getAsJsonObject("fields") : new JsonObject();
        JsonObject fieldsY = y.has("fields") ? y.getAsJsonObject("fields") : new JsonObject();
        Set<String> keys = new TreeSet<>(fieldsX.keySet());
        keys.addAll(fieldsY.keySet());
        for (String key : keys) {
            String child = path.isEmpty() ? key : path + "." + key;
            if (!fieldsX.has(key)) {
                wireChanges.add(component + " " + child + ": new field (" + describe(fieldsY.getAsJsonObject(key)) + ")");
            } else if (!fieldsY.has(key)) {
                wireChanges.add(component + " " + child + ": field removed (was " + describe(fieldsX.getAsJsonObject(key)) + ")");
            } else {
                compare(component, child, fieldsX.getAsJsonObject(key), fieldsY.getAsJsonObject(key));
            }
        }
        if (x.has("element") && y.has("element")) {
            compare(component, path + "[]", x.getAsJsonObject("element"), y.getAsJsonObject("element"));
        } else if (x.has("element") != y.has("element")) {
            notes.add(where + "[]: list element shape " + (x.has("element") ? "no longer observed (lists empty)" : "now observed"));
        }
    }

    private static String describe(JsonObject shape) {
        return string(shape, "type") + (bool(shape, "optional") ? ", optional" : "");
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null ? null : element.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).getAsBoolean();
    }
}
