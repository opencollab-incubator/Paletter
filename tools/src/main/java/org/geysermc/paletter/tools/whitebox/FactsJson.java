package org.geysermc.paletter.tools.whitebox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.whitebox.ComponentDiscovery.Component;
import org.geysermc.paletter.tools.whitebox.ComponentDiscovery.Layout;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The JSON of the facts: components, classes without a name, and the layout they were read with. */
final class FactsJson {
    private final List<String> notes;

    FactsJson(List<String> notes) {
        this.notes = notes;
    }

    static JsonObject layout(Layout layout) {
        JsonObject json = new JsonObject();
        if (layout.nameSlot() >= 0) {
            json.addProperty("nameSlot", layout.nameSlot());
        }
        json.addProperty("networkSlot", layout.networkSlot());
        json.addProperty("writerSlot", layout.writerSlot());
        json.addProperty("readerSlot", layout.readerSlot());
        return json;
    }

    JsonObject components(List<Component> components) {
        Map<String, JsonObject> sorted = new TreeMap<>();
        for (Component component : components) {
            if (component.name != null && sorted.putIfAbsent(component.name, json(component)) != null) {
                notes.add("more than one class is named " + component.name + ": only the first is listed");
            }
        }
        JsonObject json = new JsonObject();
        sorted.forEach(json::add);
        return json;
    }

    /**
     * The classes of the family that have no component name: the ones that are sent in full (sorted by what they
     * write, as a build without type names has nothing else to tell them apart), the others by class name or count.
     */
    JsonObject withoutName(List<Component> components) {
        Map<String, JsonObject> sent = new TreeMap<>();
        Map<String, Integer> alike = new HashMap<>();
        Set<String> notSent = new TreeSet<>();
        int notSentCount = 0;
        for (Component component : components) {
            if (component.name != null) {
                continue;
            }
            if (Boolean.TRUE.equals(component.network)) {
                JsonObject json = json(component);
                sent.put(json.toString(), json);
                alike.merge(json.toString(), 1, Integer::sum);
            } else {
                notSentCount++;
                if (component.table.className() != null) {
                    notSent.add(component.table.className());
                }
            }
        }
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        sent.forEach((text, entry) -> {
            if (alike.get(text) > 1) {
                entry.addProperty("count", alike.get(text));
            }
            array.add(entry);
        });
        json.add("sent", array);
        json.addProperty("notSent", notSentCount);
        if (!notSent.isEmpty()) {
            json.add("notSentClasses", array(notSent));
        }
        return json;
    }

    private JsonObject json(Component component) {
        JsonObject json = new JsonObject();
        if (component.table == null) {
            json.addProperty("kind", "unknown");
            return json;
        }
        if (component.table.className() != null) {
            json.addProperty("class", component.table.className());
        }
        json.addProperty("network", Boolean.TRUE.equals(component.network));
        json.addProperty("kind", component.kind);
        if (Boolean.TRUE.equals(component.network)) {
            json.add("writes", array(new TreeSet<>(component.writer.keys)));
            if (!component.extraKeys.isEmpty()) {
                json.add("writesExtra", array(component.extraKeys));
            }
            if (!component.writer.nestedKeys.isEmpty()) {
                json.add("writesNested", array(new TreeSet<>(component.writer.nestedKeys)));
            }
            if (!component.writer.types.isEmpty()) {
                json.add("types", object(new TreeMap<>(component.writer.types)));
            }
            if (!component.reader.keys.isEmpty()) {
                json.add("reads", array(new TreeSet<>(component.reader.keys)));
            }
            if (component.schema != null) {
                json.add("schema", array(component.schema));
            }
            json.addProperty("code", component.writer.code);
        }
        if (!component.overrides.isEmpty()) {
            JsonArray overrides = new JsonArray();
            component.overrides.forEach(overrides::add);
            json.add("hooks", overrides);
        }
        return json;
    }

    static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static JsonObject object(Map<String, String> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }
}
