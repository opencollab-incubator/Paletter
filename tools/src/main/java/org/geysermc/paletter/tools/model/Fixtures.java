package org.geysermc.paletter.tools.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.Model.JType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code codegen/model/fixtures.json}: the hand-written inputs of the add-on generator (errata, content samples,
 * companions). See the file's comments.
 */
public final class Fixtures {
    public final JsonObject json;
    public final String formatVersion;
    public final JsonObject filler;

    /** {@code bds-feedback.json} (generated from the last capture), or empty. */
    public final JsonObject feedback;

    private Fixtures(JsonObject json, JsonObject feedback) {
        this.json = json;
        this.feedback = feedback;
        this.formatVersion = json.get("formatVersion").getAsString();
        this.filler = json.getAsJsonObject("filler");
    }

    public static Fixtures read(Path model) throws IOException {
        Path feedback = model.resolve("bds-feedback.json");
        return new Fixtures(JsonParser.parseString(Files.readString(model.resolve("fixtures.json"), StandardCharsets.UTF_8)).getAsJsonObject(),
            Files.exists(feedback) ? JsonParser.parseString(Files.readString(feedback, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject());
    }

    /** What BDS said about a field in the last capture: unknown, required, array, object, range a b, max n, min n, minItems n, ... */
    public List<String> facts(String component, String path) {
        List<String> facts = new ArrayList<>();
        if (feedback.has(component) && feedback.getAsJsonObject(component).has(path)) {
            feedback.getAsJsonObject(component).getAsJsonArray(path).forEach(f -> facts.add(f.getAsString()));
        }
        return facts;
    }

    public boolean fact(String component, String path, String fact) {
        return facts(component, path).stream().anyMatch(f -> f.equals(fact) || f.startsWith(fact + " "));
    }

    /** Groups of sibling fields of which an object may hold only one (block descriptors: name or tags). */
    public List<List<String>> exclusive() {
        List<List<String>> groups = new ArrayList<>();
        if (json.has("exclusive")) {
            json.getAsJsonArray("exclusive").forEach(g -> groups.add(g.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList()));
        }
        return groups;
    }

    /** Sibling values a field needs when it is set ("blocks_to_corner_with" needs corner_and_cardinal_direction). */
    public JsonObject together(String component, String path) {
        JsonObject together = json.has("together") ? json.getAsJsonObject("together") : new JsonObject();
        return together.has(component + ":" + path) ? together.getAsJsonObject(component + ":" + path) : new JsonObject();
    }

    public List<JsonObject> errata() {
        List<JsonObject> errata = new ArrayList<>();
        json.getAsJsonArray("errata").forEach(e -> errata.add(e.getAsJsonObject()));
        return errata;
    }

    /** Companion components a component needs, as {@code minecraft:x -> value}. */
    public JsonObject requires(String component) {
        JsonObject requires = json.getAsJsonObject("requires");
        return requires.has(component) ? requires.getAsJsonObject(component) : new JsonObject();
    }

    /** Extra description entries (e.g. states) for the variants of a component. */
    public JsonObject context(String component) {
        JsonObject context = json.getAsJsonObject("context");
        return context.has(component) ? context.getAsJsonObject(component) : new JsonObject();
    }

    public String skip(String component, String path) {
        JsonObject skip = json.getAsJsonObject("skip");
        String key = path == null ? component : component + ":" + path;
        return skip.has(key) ? skip.get(key).getAsString() : null;
    }

    /** Fields only set in their own variants, never in a base (a docs name BDS does not take would reject every variant). */
    public boolean probeOnly(String component, String path) {
        return json.has("probeOnly") && json.getAsJsonArray("probeOnly").contains(new com.google.gson.JsonPrimitive(component + ":" + path));
    }

    /** A sample value: by {@code <component>:<path>}, then by field name; null if none. */
    public JsonElement sample(String component, String path, String name) {
        JsonObject samples = json.getAsJsonObject("samples");
        if (samples.has(component + ":" + path)) {
            return samples.get(component + ":" + path);
        }
        return name != null && samples.has(name) ? samples.get(name) : null;
    }

    /** A second valid value of a reference field (by '<component>:<path>', then field name), or null. */
    public JsonElement alternative(String component, String path, String name) {
        JsonObject alternatives = json.has("alternatives") ? json.getAsJsonObject("alternatives") : new JsonObject();
        if (alternatives.has(component + ":" + path)) {
            return alternatives.get(component + ":" + path);
        }
        return name != null && alternatives.has(name) ? alternatives.get(name) : null;
    }

    public String mapKey(String component, String path, String name) {
        JsonObject keys = json.getAsJsonObject("mapKeys");
        if (keys.has(component + ":" + path)) {
            return keys.get(component + ":" + path).getAsString();
        }
        if (name != null && keys.has(name)) {
            return keys.get(name).getAsString();
        }
        return keys.get("default").getAsString();
    }

    /** The model with the errata applied: types, forms and fields are only ever added. */
    public Model apply(Model model) {
        Map<String, Component> components = new LinkedHashMap<>();
        model.components().forEach(c -> components.put(c.id(), c));
        for (JsonObject erratum : errata()) {
            String id = erratum.get("component").getAsString();
            Component c = components.get(id);
            if (c == null) {
                throw new IllegalStateException("Erratum for unknown component " + id);
            }
            List<JType> forms = new ArrayList<>(c.forms());
            List<Field> fields = new ArrayList<>(c.fields());
            if (erratum.has("forms")) {
                erratum.getAsJsonArray("forms").forEach(f -> {
                    JType type = parseType(f.getAsString());
                    if (!forms.contains(type)) {
                        forms.add(type);
                    }
                });
            }
            if (erratum.has("path")) {
                String path = erratum.get("path").getAsString();
                List<JType> types = new ArrayList<>();
                erratum.getAsJsonArray("types").forEach(t -> types.add(parseType(t.getAsString())));
                Field existing = c.field(path);
                if (existing == null) {
                    fields.add(new Field(path, types, null, false, List.of(), null, null, List.of(), "(erratum) " + erratum.get("why").getAsString()));
                } else {
                    List<JType> merged = new ArrayList<>(existing.types());
                    types.stream().filter(t -> !merged.contains(t)).forEach(merged::add);
                    List<JsonElement> values = existing.enumValues();
                    if (values.isEmpty() && types.stream().anyMatch(t -> t.type().equals("string"))) {
                        values = org.geysermc.paletter.tools.model.docs.DocsParser.valuesIn(existing.doc());
                    }
                    fields.set(fields.indexOf(existing), new Field(path, merged, existing.defaultValue(), existing.required(), values,
                        existing.range(), existing.minFormatVersion(), existing.examples(), existing.doc()));
                }
            }
            components.put(id, new Component(c.id(), c.kind(), c.category(), c.name(), c.status(), c.doc(), c.page(), c.minFormatVersion(),
                c.experiments(), forms, fields, c.notes()));
        }
        return new Model(model.source(), model.commit(), model.license(), new ArrayList<>(components.values()), model.formatNotes());
    }

    /** {@code integer}, {@code array<object>}, {@code map<string>}, ... */
    public static JType parseType(String text) {
        int open = text.indexOf('<');
        if (open < 0) {
            return JType.of(text);
        }
        return new JType(text.substring(0, open), parseType(text.substring(open + 1, text.length() - 1)), null, null);
    }
}
