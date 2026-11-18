package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * What BDS said about the JSON, applied to the docs model ({@code components.json}) once, in {@code Fixtures#apply}: the
 * add-on generator, the mapping inference and the component API generator see the same model.
 * <ul>
 *     <li><b>Types from the content log</b> (the {@code object} facts of {@code bds-feedback.json}): "expected an object" at
 *     a field the docs give no object type (a string in the docs' {@code n_way_visual_rotation}) adds the object type and
 *     puts it first; "expected a key/value object" at an element of a list ({@code material_instances[0]}) of a field that
 *     also has an object type says the list is the form BDS does not take, so the object comes first. Where the docs had
 *     no object, their types stay after it marked {@link #REFUSED}: the add-on still varies them, so every capture asks
 *     BDS again (and the fact stays in the feedback), and the API leaves them out. "expected an array" (the {@code array} facts) at a field the docs give no list
 *     type ({@code connected_faces}, a string; {@code allowed_faces}, an object) adds a list and puts it first: of the
 *     field's scalar type, else of the type of its documented default list's elements, else (an empty default list) of
 *     any type.</li>
 *     <li><b>Keys from the key probe</b> ({@code bds-keys.json}): each key BDS accepted becomes a field with the types it
 *     accepted, the values it accepted as examples and the format version its own texts name, and the BDS build in its doc
 *     text.</li>
 * </ul>
 */
public final class BdsErrata {
    private BdsErrata() {
    }

    /** The label of a documented type BDS refuses in favour of an object it demands (the content log said "expected an object"). */
    public static final String REFUSED = "refused by BDS, which expects an object";

    /**
     * @param model the root of {@code components.json}; its components are changed in place
     */
    public static void apply(JsonObject model, JsonObject feedback, JsonObject keys) {
        for (JsonElement e : model.getAsJsonArray("components")) {
            JsonObject component = e.getAsJsonObject();
            String id = component.get("id").getAsString();
            if (feedback.has(id)) {
                types(component, feedback.getAsJsonObject(id));
            }
            if (keys.has("components") && keys.getAsJsonObject("components").has(id)) {
                keys(component, keys.getAsJsonObject("components").getAsJsonObject(id), keys.get("bdsVersion").getAsString());
            }
        }
    }

    private static void types(JsonObject component, JsonObject facts) {
        for (String path : facts.keySet()) {
            if (facts.getAsJsonArray(path).asList().stream().anyMatch(f -> f.getAsString().equals("array"))) {
                list(field(component, path));
            }
            boolean object = facts.getAsJsonArray(path).asList().stream().anyMatch(f -> f.getAsString().equals("object"));
            boolean element = path.endsWith("[]");
            JsonObject field = object ? field(component, element ? path.substring(0, path.length() - 2) : path) : null;
            if (field == null) {
                continue;
            }
            List<JsonElement> types = new ArrayList<>(field.getAsJsonArray("types").asList());
            JsonElement first = types.stream().filter(t -> isObject(t.getAsJsonObject())).findFirst().orElse(null);
            if (element && first == null) {
                continue;
            }
            if (first == null) {
                // BDS wants an object where the docs say something else: what the docs say is refused
                types.replaceAll(t -> {
                    JsonObject refused = t.deepCopy().getAsJsonObject();
                    refused.addProperty("label", REFUSED);
                    return refused;
                });
                first = ModelFields.parseType("object");
            } else {
                types.remove(first);
            }
            types.add(0, first);
            JsonArray ordered = new JsonArray();
            types.forEach(ordered::add);
            field.add("types", ordered);
        }
    }

    /** Adds a list type in front of a field that has none (BDS: "expected an array"). */
    private static void list(JsonObject field) {
        if (field == null) {
            return;
        }
        List<JsonElement> types = new ArrayList<>(field.getAsJsonArray("types").asList());
        if (types.stream().anyMatch(t -> List.of("array", "vec3").contains(t.getAsJsonObject().get("type").getAsString()))) {
            return;
        }
        String element = types.stream().map(t -> t.getAsJsonObject().get("type").getAsString())
            .filter(t -> List.of("string", "number", "integer", "boolean").contains(t)).findFirst().orElse(null);
        JsonElement defaults = field.get("defaultValue");
        if (element == null && defaults != null && defaults.isJsonArray() && !defaults.getAsJsonArray().isEmpty()
            && defaults.getAsJsonArray().get(0).isJsonPrimitive()) {
            JsonPrimitive first = defaults.getAsJsonArray().get(0).getAsJsonPrimitive();
            element = first.isString() ? "string" : first.isBoolean() ? "boolean" : "number";
        }
        if (element == null && !(defaults != null && defaults.isJsonArray())) {
            return; // nothing says the field is a list of anything (uv_lock: false or a list of bones)
        }
        types.add(0, ModelFields.parseType("array<" + (element == null ? "any" : element) + ">"));
        JsonArray ordered = new JsonArray();
        types.forEach(ordered::add);
        field.add("types", ordered);
    }

    private static boolean isObject(JsonObject type) {
        return type.get("type").getAsString().equals("object") || type.get("type").getAsString().equals("map");
    }

    private static void keys(JsonObject component, JsonObject found, String bdsVersion) {
        for (var key : found.getAsJsonObject("accepted").entrySet()) {
            if (field(component, key.getKey()) != null) {
                continue;
            }
            JsonObject accepted = key.getValue().getAsJsonObject();
            JsonObject field = new JsonObject();
            field.addProperty("path", key.getKey());
            JsonArray types = new JsonArray();
            accepted.getAsJsonArray("types").forEach(t -> types.add(ModelFields.parseType(t.getAsString())));
            field.add("types", types);
            field.addProperty("required", false);
            field.add("enumValues", new JsonArray());
            if (accepted.has("minFormatVersion")) {
                field.addProperty("minFormatVersion", accepted.get("minFormatVersion").getAsString());
            }
            field.add("examples", accepted.has("examples") ? accepted.get("examples") : new JsonArray());
            field.addProperty("doc", "(bds-schema " + bdsVersion + ") BDS accepts this key; the creator docs do not list it."
                + (accepted.has("minFormatVersion") ? " BDS names the format version " + accepted.get("minFormatVersion").getAsString() + " for it." : ""));
            component.getAsJsonArray("fields").add(field);
        }
    }

    private static JsonObject field(JsonObject component, String path) {
        for (JsonElement f : component.getAsJsonArray("fields")) {
            if (f.getAsJsonObject().get("path").getAsString().equals(path)) {
                return f.getAsJsonObject();
            }
        }
        return null;
    }
}
