package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ModelFields.DocField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fields of a component as the model has them, with what the generator leaves out removed, and the path and type helpers
 * of the model's notation.
 */
final class ModelFields {
    /** A field of the model: what the docs (with the errata applied) say about it. */
    static final class DocField {
        final String path;
        final List<JsonObject> types;
        final boolean required;
        final JsonElement defaultValue;
        final List<JsonElement> enumValues;
        final JsonObject range;
        final String doc;
        final String minFormat;

        DocField(String path, List<JsonObject> types, boolean required, JsonElement defaultValue, List<JsonElement> enumValues,
                 JsonObject range, String doc, String minFormat) {
            this.path = path;
            this.types = types;
            this.required = required;
            this.defaultValue = defaultValue;
            this.enumValues = enumValues;
            this.range = range;
            this.doc = doc;
            this.minFormat = minFormat;
        }
    }

    private final Hand hand;

    ModelFields(Hand hand) {
        this.hand = hand;
    }

    /**
     * The fields of a component for the API. The model is already merged ({@code Fixtures#apply} is the only place errata are
     * applied); this decodes the markers that merge leaves (a type BDS refuses, the reason of an erratum as docs text) and
     * drops what the API leaves out ({@code probeOnly}, {@code skip}, {@code exclude}, fields BDS does not know).
     */
    List<DocField> read(Comp c, JsonObject doc, String standalone) {
        Map<String, DocField> fields = new LinkedHashMap<>();
        for (JsonElement e : doc.getAsJsonArray("fields")) {
            JsonObject f = e.getAsJsonObject();
            List<JsonObject> types = new ArrayList<>();
            // The API offers no type BDS refuses
            f.getAsJsonArray("types").forEach(t -> {
                if (!(t.getAsJsonObject().has("label") && t.getAsJsonObject().get("label").getAsString().equals(BdsErrata.REFUSED))) {
                    types.add(t.getAsJsonObject());
                }
            });
            List<JsonElement> enums = new ArrayList<>();
            f.getAsJsonArray("enumValues").forEach(enums::add);
            String path = f.get("path").getAsString();
            // The reason of an erratum is the model's note on the docs, not documentation of the field
            String text = text(f, "doc").startsWith("(erratum) ") ? "" : text(f, "doc");
            fields.put(path, new DocField(path, types, f.get("required").getAsBoolean(), f.get("defaultValue"), enums,
                f.has("range") ? f.getAsJsonObject("range") : null, text,
                f.has("minFormatVersion") ? f.get("minFormatVersion").getAsString() : null));
        }
        Set<String> drop = new TreeSet<>();
        if (hand.in.fixtures().has("probeOnly")) {
            hand.in.fixtures().getAsJsonArray("probeOnly").forEach(p -> drop.add(p.getAsString()));
        }
        JsonObject skip = hand.in.fixtures().has("skip") ? hand.in.fixtures().getAsJsonObject("skip") : new JsonObject();
        JsonObject feedback = hand.in.feedback().has(c.id) ? hand.in.feedback().getAsJsonObject(c.id) : new JsonObject();
        Set<String> excluded = new TreeSet<>();
        if (hand.in.api().has("exclude")) {
            hand.in.api().getAsJsonArray("exclude").forEach(e -> excluded.add(e.getAsString()));
        }
        List<DocField> out = new ArrayList<>();
        Set<String> removed = new TreeSet<>();
        for (DocField docField : fields.values()) {
            DocField f = docField;
            String key = c.id + ":" + f.path;
            String fieldPath = f.path;
            boolean unknown = feedback.has(fieldPath) && feedback.getAsJsonArray(fieldPath).asList().stream().anyMatch(x -> x.getAsString().equals("unknown"));
            if ((drop.contains(key) && !takenByBds(c, f.path)) || skip.has(key) || excluded.contains(key) || unknown
                || removed.stream().anyMatch(r -> fieldPath.startsWith(r + ".") || fieldPath.startsWith(r + "[") || fieldPath.startsWith(r + "{"))) {
                removed.add(f.path);
                continue;
            }
            if (standalone != null) {
                String prefix = hand.standalonePrefix(c.id);
                if (!f.path.startsWith(prefix + ".")) {
                    continue;
                }
                f = new DocField(f.path.substring(prefix.length() + 1), f.types, f.required, f.defaultValue, f.enumValues, f.range, f.doc, f.minFormat);
            }
            out.add(f);
        }
        return out;
    }

    static List<String> forms(Comp c, JsonObject doc) {
        List<String> forms = new ArrayList<>();
        for (JsonElement e : doc.getAsJsonArray("forms")) {
            forms.add(typeString(e.getAsJsonObject()));
        }
        if (c.standalone) {
            return List.of("object");
        }
        // Forms BDS rejected entirely are not offered
        Window w = c.latest();
        List<String> usable = new ArrayList<>();
        for (String form : forms) {
            String key = form.startsWith("array<object") ? "array<object>" : form;
            JsonObject m = w == null ? null : w.form(key);
            if (!form.equals("object") && m != null && m.get("status").getAsString().equals("rejected")) {
                continue;
            }
            usable.add(form.startsWith("array<object") ? "array<object>" : form);
        }
        return usable;
    }

    /**
     * Whether the newest capture with a result for a field ({@link Comp#evidence}) shows BDS taking it: a probe only field
     * it accepts is part of the API all the same.
     */
    static boolean takenByBds(Comp c, String path) {
        Window w = c.evidence(path);
        JsonObject m = w == null ? null : w.field(path);
        return m != null && !m.get("status").getAsString().equals("rejected");
    }

    static JsonObject parseType(String text) {
        JsonObject t = new JsonObject();
        Matcher m = Pattern.compile("(array|map)<(.+)>").matcher(text);
        if (m.matches()) {
            t.addProperty("type", m.group(1));
            t.add("items", parseType(m.group(2)));
        } else {
            t.addProperty("type", text);
        }
        return t;
    }

    static String typeString(JsonObject t) {
        String s = t.get("type").getAsString();
        if (t.has("items")) {
            s += "<" + typeString(t.getAsJsonObject("items")) + ">";
        }
        return s;
    }

    static String parent(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            return path.substring(0, dot);
        }
        return "";
    }

    static String token(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
