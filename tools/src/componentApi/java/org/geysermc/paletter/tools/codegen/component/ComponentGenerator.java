package org.geysermc.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Comp;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.EnumType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Field;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Kind;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ListOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.MapOf;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjRef;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ObjType;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Scalar;
import org.geysermc.paletter.tools.codegen.component.ApiModel.ScalarKind;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Shorthand;
import org.geysermc.paletter.tools.codegen.component.ApiModel.States;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Type;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Vec3;
import org.geysermc.paletter.tools.codegen.component.ApiModel.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates Paletter's custom item and block component API from the component model in {@code data/model}:
 * <ul>
 *     <li>{@code components.json}: every component and field as the creator docs describe it (types, defaults, enums,
 *     ranges, docs text, format versions, experiments, status), plus the {@code errata} of {@code fixtures.json};</li>
 *     <li>{@code bds-feedback.json}: required fields and ranges BDS enforces, fields BDS does not know;</li>
 *     <li>{@code mappings.json}: per component and protocol window, what reaches the wire and how (inferred from BDS);</li>
 *     <li>{@code transforms.json}: fields no inferred rule explains, by named hand transform;</li>
 *     <li>{@code api.json}: Java naming and shaping decisions (names, unions, descriptors, exclusions).</li>
 * </ul>
 * The output is one immutable record per component (nested records and enums for its object and enum types, builders
 * with defaults and validation) in {@code org.geysermc.paletter.item.component} / {@code block.component}, and the
 * internal encoders in {@code org.geysermc.paletter.internal.component}. Anything the inputs do not settle fails generation
 * with a message naming the component and field; nothing is guessed silently.
 */
public final class ComponentGenerator {
    /** A model problem the inputs have to settle. */
    public static final class GenerationException extends RuntimeException {
        public GenerationException(String message) {
            super(message);
        }
    }

    /**
     * @param transforms names of the transforms the runtime implements ({@code Transforms} methods, snake_case)
     * @param since the Paletter version for {@code @since}
     */
    public record Inputs(JsonObject components, JsonObject fixtures, JsonObject feedback, JsonObject mappings, JsonObject transforms,
                         JsonObject api, Set<String> knownTransforms, String since) {
    }

    public static final String API_FILE = "api.json";
    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "record", "var", "yield", "sealed", "permits");
    private static final Set<String> JAVA_LANG = Set.of("Record", "Throwable", "Object", "String", "Class", "Enum", "Exception", "Error",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "Number", "Void", "Math", "System", "Thread",
        "Runtime", "Process", "Module", "Package", "Iterable", "Comparable", "Override", "Deprecated");
    static final Set<String> VALUE_KINDS = Set.of("identity", "linear", "table", "affix", "bitset", "count");

    private final Inputs in;
    private final ApiModel model = new ApiModel();

    private ComponentGenerator(Inputs in) {
        this.in = in;
    }

    public static Inputs read(Path modelDir, Set<String> knownTransforms, String since) throws IOException {
        return new Inputs(json(modelDir.resolve("components.json")), json(modelDir.resolve("fixtures.json")),
            json(modelDir.resolve("bds-feedback.json")), json(modelDir.resolve("mappings.json")), json(modelDir.resolve("transforms.json")),
            json(modelDir.resolve(API_FILE)), knownTransforms, since);
    }

    private static JsonObject json(Path file) throws IOException {
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
    }

    /**
     * @return generated sources by path relative to the source root ({@code org/geysermc/...java}), sorted
     */
    public static Map<String, String> generate(Inputs in) {
        ComponentGenerator generator = new ComponentGenerator(in);
        generator.build();
        Map<String, String> out = new TreeMap<>();
        // api.json "since" overrides the build version (the Paletter version the generated API first shipped in)
        String since = in.api().has("since") ? in.api().get("since").getAsString() : in.since();
        ApiSource.write(generator.model, since, out);
        WireSource.write(generator.model, out);
        return out;
    }

    static ApiModel model(Inputs in) {
        ComponentGenerator generator = new ComponentGenerator(in);
        generator.build();
        return generator.model;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Model

    private record DocField(String path, List<JsonObject> types, boolean required, JsonElement defaultValue, List<JsonElement> enumValues,
                            JsonObject range, String doc, String minFormat) {
    }

    private JsonObject api(String section) {
        return in.api().has(section) ? in.api().getAsJsonObject(section) : new JsonObject();
    }

    private String apiString(String section, String key) {
        JsonObject s = api(section);
        return s.has(key) ? s.get(key).getAsString() : null;
    }

    private void build() {
        List<JsonObject> docs = new ArrayList<>();
        in.components().getAsJsonArray("components").forEach(e -> docs.add(e.getAsJsonObject()));
        docs.sort(Comparator.comparing(o -> o.get("id").getAsString()));
        JsonObject mappings = in.mappings().has("components") ? in.mappings().getAsJsonObject("components") : new JsonObject();
        JsonObject skip = in.fixtures().has("skip") ? in.fixtures().getAsJsonObject("skip") : new JsonObject();
        Set<String> excluded = new TreeSet<>();
        if (in.api().has("exclude")) {
            in.api().getAsJsonArray("exclude").forEach(e -> excluded.add(e.getAsString()));
        }
        for (String key : in.transforms().keySet()) {
            if (key.startsWith("_")) {
                continue;
            }
            String name = in.transforms().getAsJsonObject(key).get("transform").getAsString();
            if (!in.knownTransforms().contains(name)) {
                throw new GenerationException("transforms.json: " + key + " names the transform '" + name + "', which the runtime does not implement");
            }
        }
        for (JsonObject doc : docs) {
            String id = doc.get("id").getAsString();
            String category = doc.get("category").getAsString();
            JsonObject mapping = mappings.has(id) ? mappings.getAsJsonObject(id) : null;
            String status = mapping == null ? "not captured" : mapping.get("status").getAsString();
            String standalone = apiString("standalone", id);
            if (skip.has(id)) {
                model.omitted.put(id, "not modelled: " + skip.get(id).getAsString());
                continue;
            }
            if (excluded.contains(id)) {
                model.omitted.put(id, "excluded in api.json");
                continue;
            }
            if (standalone == null && !category.equals("component") && !category.equals("trait")) {
                model.omitted.put(id, "part of the definition core (CustomItem / CustomBlock)");
                continue;
            }
            if (status.equals("server-only")) {
                model.omitted.put(id, "server only: BDS accepts it but sends nothing to clients");
                continue;
            }
            if (!status.equals("sent") && !status.equals("untestable")) {
                model.omitted.put(id, status.equals("rejected") ? "rejected by BDS " + bdsVersion() : status);
                continue;
            }
            model.components.add(component(doc, mapping, standalone));
        }
        Map<String, String> names = new TreeMap<>();
        for (Comp c : model.components) {
            if (JAVA_LANG.contains(c.className)) {
                throw new GenerationException("name collision: " + c.id + " would be " + c.className + ", which shadows java.lang."
                    + c.className + "; set a class name in api.json classes");
            }
            String key = c.kind.pkg() + "." + c.className;
            String other = names.put(key, c.id);
            if (other != null) {
                throw new GenerationException("name collision: " + other + " and " + c.id + " are both " + key + "; set one in api.json classes");
            }
        }
    }

    private String bdsVersion() {
        if (in.mappings().has("captures") && !in.mappings().getAsJsonArray("captures").isEmpty()) {
            return in.mappings().getAsJsonArray("captures").get(0).getAsJsonObject().get("bdsVersion").getAsString();
        }
        return "";
    }

    private Comp component(JsonObject doc, JsonObject mapping, String standalone) {
        Comp c = new Comp();
        c.id = doc.get("id").getAsString();
        c.name = doc.get("name").getAsString();
        String kind = doc.get("kind").getAsString();
        c.kind = doc.get("category").getAsString().equals("trait") ? Kind.TRAIT : kind.equals("item") ? Kind.ITEM : Kind.BLOCK;
        c.status = doc.get("status").getAsString();
        c.doc = text(doc, "doc");
        c.page = text(doc, "page");
        c.minFormat = doc.has("minFormatVersion") ? doc.get("minFormatVersion").getAsString() : null;
        List<String> experiments = new ArrayList<>();
        doc.getAsJsonArray("experiments").forEach(e -> experiments.add(e.getAsString()));
        c.experiments = experiments;
        String className = apiString("classes", c.id);
        c.className = className != null ? className : standalone != null ? standalone : pascal(c.name.replaceFirst("^[a-z_]+:", ""));
        javaName(c.className, c.id);
        c.windows.addAll(windows(c.id, mapping));
        c.transform = transform(c.id, "");
        c.standalone = standalone != null;

        // Fields after errata, BDS feedback and exclusions
        List<DocField> fields = docFields(c, doc, standalone);
        List<String> forms = forms(c, doc);
        c.root = new ObjType();
        c.root.name = c.className;
        c.root.path = "";
        c.root.doc = c.doc;
        boolean objectForm = forms.contains("object") && (!fields.isEmpty() || forms.size() == 1);
        List<String> scalarForms = forms.stream().filter(f -> !f.equals("object")).toList();
        for (String form : scalarForms) {
            if (form.startsWith("array<object")) {
                // The value is a list of objects: one list field holding them
                Field list = synthetic(c, form, fields);
                c.root.fields.add(list);
                continue;
            }
            JsonObject declared = api("forms").has(c.id) && api("forms").getAsJsonObject(c.id).has(form)
                ? api("forms").getAsJsonObject(c.id).getAsJsonObject(form) : null;
            if (declared != null && declared.has("field")) {
                c.pendingForms.put(form, declared);
            } else if (!objectForm) {
                c.pendingForms.put(form, null);
            } else {
                c.pendingForms.put(form, null);
                c.autoShorthandForms.add(form);
            }
        }
        // Object fields
        for (Field f : children(c, "", fields, c.root)) {
            c.root.fields.add(f);
        }
        // Forms: synthetic fields, or shorthands of a field
        for (Map.Entry<String, JsonObject> form : c.pendingForms.entrySet()) {
            String type = form.getKey();
            JsonObject declared = form.getValue();
            if (c.autoShorthandForms.contains(type)) {
                Field target = shorthandTarget(c, type);
                c.shorthands.add(new Shorthand(type, target));
                continue;
            }
            Field field = new Field();
            field.form = type;
            field.json = null;
            field.path = "<" + type + ">";
            field.java = declared != null ? declared.get("field").getAsString() : scalarForms.size() == 1 ? "value" : null;
            if (field.java == null) {
                throw new GenerationException(c.id + " has the value forms " + scalarForms + " but no object fields; name a field per form in api.json forms");
            }
            if (declared != null && declared.has("shorthandOf")) {
                String of = declared.get("shorthandOf").getAsString();
                Field target = c.root.fields.stream().filter(f -> f.java.equals(of)).findFirst()
                    .orElseThrow(() -> new GenerationException("api.json forms: " + c.id + " " + type + " is a shorthand of '" + of + "', which is no field"));
                c.shorthands.add(new Shorthand(type, target));
                continue;
            }
            field.type = scalarType(c, type, field.java, List.of());
            if (declared != null && declared.has("type")) {
                field.type = scalarType(c, declared.get("type").getAsString(), field.java, List.of());
            }
            field.mappingKey = "form:" + type;
            field.doc = declared != null && declared.has("doc") ? declared.get("doc").getAsString()
                : "The " + type + " form of the component (add-on JSON {@code \"" + c.name + "\": <" + type + ">}).";
            field.transform = transform(c.id, field.path);
            boolean only = scalarForms.size() == 1 && c.root.fields.isEmpty();
            field.required = only;
            c.root.fields.add(field);
            c.shorthands.add(new Shorthand(type, field));
        }
        for (Field f : c.root.fields) {
            javaName(f.java, c.id + " " + (f.path == null ? "" : f.path));
        }
        checkFieldNames(c, c.root);
        propagateDefaults(c.root);
        dedupeTypes(c);
        checkConflicts(c);
        return c;
    }

    /** The field a scalar component form fills: the one whose value rules move the same leaves. */
    private Field shorthandTarget(Comp c, String form) {
        JsonObject declared = api("forms").has(c.id) ? api("forms").getAsJsonObject(c.id) : null;
        if (declared != null && declared.has(form) && declared.getAsJsonObject(form).has("shorthandOf")) {
            String of = declared.getAsJsonObject(form).get("shorthandOf").getAsString();
            return c.root.fields.stream().filter(f -> of.equals(f.java)).findFirst()
                .orElseThrow(() -> new GenerationException("api.json forms: " + c.id + " " + form + " is a shorthand of '" + of + "', which is no field"));
        }
        // The newest window that observed the form
        Window w = null;
        for (Window candidate : c.windows) {
            if (candidate.form(form) != null) {
                w = candidate;
            }
        }
        Set<String> leaves = w == null ? Set.of() : valueLeaves(w.form(form));
        if (!leaves.isEmpty()) {
            for (Field f : c.root.fields) {
                JsonObject fm = w.field(f.path);
                if (fm != null && valueLeaves(fm).equals(leaves)) {
                    return f;
                }
            }
        }
        if (c.root.fields.size() == 1) {
            return c.root.fields.getFirst();
        }
        throw new GenerationException(c.id + ": the " + form + " form moves " + leaves + ", which no single field does; "
            + "declare it in api.json forms");
    }

    static Set<String> valueLeaves(JsonObject fieldMapping) {
        Set<String> leaves = new TreeSet<>();
        if (fieldMapping.has("rules")) {
            for (JsonElement r : fieldMapping.getAsJsonArray("rules")) {
                if (VALUE_KINDS.contains(r.getAsJsonObject().get("kind").getAsString())) {
                    leaves.add(r.getAsJsonObject().get("wire").getAsString());
                }
            }
        }
        return leaves;
    }

    private Field synthetic(Comp c, String form, List<DocField> fields) {
        String name = apiString("rootLists", c.id);
        if (name == null) {
            throw new GenerationException(c.id + " is a list of objects (" + form + "); name its list field in api.json rootLists");
        }
        Field list = new Field();
        list.path = "";
        list.json = null;
        list.form = form;
        list.java = name;
        list.mappingKey = "form:" + form;
        list.transform = transform(c.id, "[]");
        list.doc = "The entries of the component (add-on JSON: the component's value is this list).";
        ObjType element = new ObjType();
        element.path = "[]";
        element.name = typeName(c, "[]", null);
        element.doc = "An entry of {@code " + c.name + "}.";
        element.fields.addAll(children(c, "[]", fields, element));
        list.type = new ListOf(new ObjRef(element));
        list.defaultValue = new JsonArray();
        register(c, element);
        return list;
    }

    // ---- Windows -----------------------------------------------------------------------------------------------------

    private static List<Window> windows(String id, JsonObject mapping) {
        if (mapping == null) {
            return List.of();
        }
        TreeMap<Integer, JsonObject> byProtocol = new TreeMap<>();
        if (mapping.has("protocols")) {
            for (JsonElement p : mapping.getAsJsonArray("protocols")) {
                byProtocol.put(p.getAsInt(), mapping);
            }
        }
        for (String key : mapping.keySet()) {
            Matcher m = Pattern.compile("protocol(\\d+)").matcher(key);
            if (m.matches()) {
                int protocol = Integer.parseInt(m.group(1));
                JsonObject other = byProtocol.put(protocol, mapping.getAsJsonObject(key));
                if (other != null) {
                    throw new GenerationException("conflicting windows: " + id + " has two mappings for protocol " + protocol);
                }
            }
        }
        if (byProtocol.isEmpty()) {
            return List.of(new Window(Integer.MIN_VALUE, Integer.MAX_VALUE, mapping));
        }
        // Each observed mapping holds from its protocol until the next protocol with a different mapping; the oldest
        // also holds for every older protocol (nothing contradicts it there)
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

    // ---- Docs fields -------------------------------------------------------------------------------------------------

    private List<DocField> docFields(Comp c, JsonObject doc, String standalone) {
        Map<String, DocField> fields = new LinkedHashMap<>();
        for (JsonElement e : doc.getAsJsonArray("fields")) {
            JsonObject f = e.getAsJsonObject();
            List<JsonObject> types = new ArrayList<>();
            f.getAsJsonArray("types").forEach(t -> types.add(t.getAsJsonObject()));
            List<JsonElement> enums = new ArrayList<>();
            f.getAsJsonArray("enumValues").forEach(enums::add);
            String path = f.get("path").getAsString();
            fields.put(path, new DocField(path, types, f.get("required").getAsBoolean(), f.get("defaultValue"), enums,
                f.has("range") ? f.getAsJsonObject("range") : null, text(f, "doc"),
                f.has("minFormatVersion") ? f.get("minFormatVersion").getAsString() : null));
        }
        if (in.fixtures().has("errata")) {
            for (JsonElement e : in.fixtures().getAsJsonArray("errata")) {
                JsonObject erratum = e.getAsJsonObject();
                if (!erratum.get("component").getAsString().equals(c.id) || !erratum.has("path")) {
                    continue;
                }
                String path = erratum.get("path").getAsString();
                List<JsonObject> added = new ArrayList<>();
                erratum.getAsJsonArray("types").forEach(t -> added.add(parseType(t.getAsString())));
                List<JsonObject> types = added;
                DocField old = fields.get(path);
                if (old != null) {
                    // Errata add types the docs omit; the documented ones stay (BDS confirms or rejects each)
                    List<JsonObject> merged = new ArrayList<>(types);
                    for (JsonObject t : old.types) {
                        if (merged.stream().noneMatch(m -> typeString(m).equals(typeString(t)))) {
                            merged.add(t);
                        }
                    }
                    types = merged;
                }
                fields.put(path, old == null
                    ? new DocField(path, types, false, null, List.of(), null, "", null)
                    : new DocField(path, types, old.required, old.defaultValue, old.enumValues, old.range, old.doc, old.minFormat));
            }
        }
        Set<String> drop = new TreeSet<>();
        if (in.fixtures().has("probeOnly")) {
            in.fixtures().getAsJsonArray("probeOnly").forEach(p -> drop.add(p.getAsString()));
        }
        JsonObject skip = in.fixtures().has("skip") ? in.fixtures().getAsJsonObject("skip") : new JsonObject();
        JsonObject feedback = in.feedback().has(c.id) ? in.feedback().getAsJsonObject(c.id) : new JsonObject();
        Set<String> excluded = new TreeSet<>();
        if (in.api().has("exclude")) {
            in.api().getAsJsonArray("exclude").forEach(e -> excluded.add(e.getAsString()));
        }
        List<DocField> out = new ArrayList<>();
        Set<String> removed = new TreeSet<>();
        for (DocField docField : fields.values()) {
            DocField f = docField;
            String key = c.id + ":" + f.path;
            String fieldPath = f.path;
            boolean unknown = feedback.has(fieldPath) && feedback.getAsJsonArray(fieldPath).asList().stream().anyMatch(x -> x.getAsString().equals("unknown"));
            if (drop.contains(key) || skip.has(key) || excluded.contains(key) || unknown
                || removed.stream().anyMatch(r -> fieldPath.startsWith(r + ".") || fieldPath.startsWith(r + "[") || fieldPath.startsWith(r + "{"))) {
                removed.add(f.path);
                continue;
            }
            if (standalone != null) {
                String prefix = standalonePrefix(c.id);
                if (!f.path.startsWith(prefix + ".")) {
                    continue;
                }
                f = new DocField(f.path.substring(prefix.length() + 1), f.types, f.required, f.defaultValue, f.enumValues, f.range, f.doc, f.minFormat);
            }
            out.add(f);
        }
        return out;
    }

    private String standalonePrefix(String id) {
        JsonObject prefixes = api("standalonePaths");
        if (!prefixes.has(id)) {
            throw new GenerationException("api.json standalone: " + id + " needs its object path in standalonePaths");
        }
        return prefixes.get(id).getAsString();
    }

    private List<String> forms(Comp c, JsonObject doc) {
        List<String> forms = new ArrayList<>();
        for (JsonElement e : doc.getAsJsonArray("forms")) {
            forms.add(typeString(e.getAsJsonObject()));
        }
        if (in.fixtures().has("errata")) {
            for (JsonElement e : in.fixtures().getAsJsonArray("errata")) {
                JsonObject erratum = e.getAsJsonObject();
                if (erratum.get("component").getAsString().equals(c.id) && erratum.has("forms")) {
                    erratum.getAsJsonArray("forms").forEach(f -> {
                        if (!forms.contains(f.getAsString())) {
                            forms.add(f.getAsString());
                        }
                    });
                }
            }
        }
        if (c.standalone) {
            return List.of("object");
        }
        // Forms BDS rejected entirely are not offered
        Window w = c.windows.isEmpty() ? null : c.windows.getLast();
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

    private static JsonObject parseType(String text) {
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

    // ---- Object trees ------------------------------------------------------------------------------------------------

    static String parent(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            return path.substring(0, dot);
        }
        return path.equals("[]") || path.equals("{}") ? "" : "";
    }

    static String token(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private List<Field> children(Comp c, String prefix, List<DocField> fields, ObjType owner) {
        List<Field> out = new ArrayList<>();
        for (DocField f : fields) {
            if (f.path.equals("[]") || f.path.equals("{}") || f.path.equals(prefix)) {
                continue;
            }
            String parent = parent(f.path);
            if (!parent.equals(prefix) || (prefix.isEmpty() && f.path.startsWith("[]"))) {
                continue;
            }
            out.add(field(c, f, fields, owner));
        }
        return out;
    }

    private Field field(Comp c, DocField d, List<DocField> fields, ObjType owner) {
        Field f = new Field();
        f.path = d.path;
        f.json = token(d.path);
        String renamed = apiString("fields", c.id + ":" + d.path);
        f.java = renamed != null ? renamed : camel(f.json);
        javaName(f.java, c.id + " " + d.path);
        f.doc = d.doc;
        f.minFormat = d.minFormat;
        f.mappingKey = d.path;
        f.transform = transform(c.id, d.path);
        f.descriptor = apiString("descriptors", c.id + ":" + d.path);
        f.type = type(c, d, fields);
        f.docsDefault = d.defaultValue;
        JsonObject feedback = in.feedback().has(c.id) ? in.feedback().getAsJsonObject(c.id) : new JsonObject();
        List<String> notes = new ArrayList<>();
        String feedbackPath = c.standalone ? standalonePrefix(c.id) + "." + d.path : d.path;
        if (feedback.has(feedbackPath)) {
            feedback.getAsJsonArray(feedbackPath).forEach(n -> notes.add(n.getAsString()));
        }
        // Required unless BDS accepted the field's omission (it then knows what to send)
        Window latest = c.windows.isEmpty() ? null : c.windows.getLast();
        JsonObject observed = latest == null ? null : latest.field(d.path);
        boolean omissionAccepted = observed != null && observed.has("whenAbsent");
        boolean optional = in.api().has("optional") && in.api().getAsJsonArray("optional").asList().stream()
            .anyMatch(e -> e.getAsString().equals(c.id + ":" + d.path));
        f.required = !optional && (d.required || (notes.contains("required") && !omissionAccepted));
        // Ranges: what BDS enforces wins over the docs
        for (String note : notes) {
            Matcher range = Pattern.compile("range (-?[\\d.]+) (-?[\\d.]+)").matcher(note);
            Matcher min = Pattern.compile("min (-?[\\d.]+)").matcher(note);
            if (range.matches()) {
                f.min = Double.parseDouble(range.group(1));
                f.max = Double.parseDouble(range.group(2));
                f.rangeSource = "BDS " + bdsVersion();
            } else if (min.matches()) {
                f.min = Double.parseDouble(min.group(1));
                f.rangeSource = "BDS " + bdsVersion();
            }
        }
        if (f.rangeSource == null && d.range != null && (d.range.has("min") || d.range.has("max"))) {
            f.min = d.range.has("min") ? d.range.get("min").getAsDouble() : null;
            f.max = d.range.has("max") ? d.range.get("max").getAsDouble() : null;
            f.rangeSource = "docs";
        }
        if (f.type instanceof Scalar s && s.kind() == ScalarKind.INT && !d.enumValues.isEmpty()) {
            f.allowed = d.enumValues.stream().map(JsonElement::getAsDouble).toList();
        }
        if (f.min != null && f.max != null && !(f.type instanceof Scalar s && (s.kind() == ScalarKind.INT || s.kind() == ScalarKind.FLOAT))) {
            f.min = null;
            f.max = null;
        }
        if (!(f.type instanceof Scalar s && (s.kind() == ScalarKind.INT || s.kind() == ScalarKind.FLOAT))) {
            f.min = null;
            f.max = null;
            f.rangeSource = null;
        }
        // The alternative of a union kept as a field of its own
        JsonObject union = api("unions").has(c.id + ":" + d.path) ? api("unions").getAsJsonObject(c.id + ":" + d.path) : null;
        if (union != null && union.has("alternative")) {
            JsonObject alt = union.getAsJsonObject("alternative");
            for (String type : alt.keySet()) {
                Field a = new Field();
                a.path = d.path;
                a.json = f.json;
                a.java = f.java + pascal(alt.get(type).getAsString());
                a.type = scalarType(c, type, a.java, List.of());
                a.mappingKey = d.path;
                a.alternativeOf = f;
                a.transform = f.transform;
                a.doc = "The " + type + " form of {@code " + f.json + "} (" + alt.get(type).getAsString() + "); excludes {@link #" + f.java + "()}.";
                f.alternatives.add(a);
            }
        }
        return f;
    }

    private Type type(Comp c, DocField d, List<DocField> fields) {
        String override = apiString("types", c.id + ":" + d.path);
        List<JsonObject> types = d.types;
        if (override != null) {
            if (override.equals("states")) {
                return new States();
            }
            if (override.startsWith("component:")) {
                return new ApiModel.ComponentRef(override.substring("component:".length()));
            }
            types = List.of(parseType(override));
        }
        JsonObject union = api("unions").has(c.id + ":" + d.path) ? api("unions").getAsJsonObject(c.id + ":" + d.path) : null;
        if (union != null && union.has("use")) {
            types = List.of(parseType(union.get("use").getAsString()));
        }
        if (union != null && union.has("alternative")) {
            Set<String> alternatives = union.getAsJsonObject("alternative").keySet();
            types = types.stream().filter(t -> !alternatives.contains(typeString(t))).toList();
        }
        if (override != null && override.startsWith("component:")) {
            return new ApiModel.ComponentRef(override.substring("component:".length()));
        }
        boolean hasChildren = fields.stream().anyMatch(f -> parent(f.path).equals(d.path) || parent(f.path).equals(d.path + "[]"));
        if (types.size() > 1) {
            List<String> kinds = types.stream().map(ComponentGenerator::typeString).toList();
            boolean vecChildren = fields.stream().filter(f -> parent(f.path).equals(d.path)).map(f -> token(f.path)).toList().equals(List.of("x", "y", "z"));
            if (vecChildren && kinds.stream().anyMatch(k -> k.equals("vec3") || k.equals("array<number>"))) {
                c.usesVec3 = true;
                return new Vec3();
            }
            JsonObject object = types.stream().filter(t -> t.get("type").getAsString().equals("object")).findFirst().orElse(null);
            JsonObject array = types.stream().filter(t -> t.get("type").getAsString().equals("array") && t.has("items")
                && t.getAsJsonObject("items").get("type").getAsString().equals("object")).findFirst().orElse(null);
            List<JsonObject> scalars = types.stream().filter(t -> isScalar(t.get("type").getAsString())).toList();
            if (array != null && fields.stream().anyMatch(f -> parent(f.path).equals(d.path + "[]"))) {
                // [{...}] next to shorthand forms of one entry: the list is the type
                return single(c, d, array, fields);
            }
            if (object != null && hasChildren && types.stream().allMatch(x -> isScalar(x.get("type").getAsString())
                || x.get("type").getAsString().equals("object") || x.get("type").getAsString().equals("map"))) {
                ObjRef ref = (ObjRef) single(c, d, object, fields);
                if (!scalars.isEmpty()) {
                    // The scalar alternative is a shorthand of one child
                    ref.type().shorthand = unionShorthand(c, d, ref.type(), union);
                    ref.type().shorthandType = scalars.getFirst().get("type").getAsString();
                }
                return ref;
            }
            JsonObject map = types.stream().filter(t -> t.get("type").getAsString().equals("map")).findFirst().orElse(null);
            if (map != null && !hasChildren && kinds.stream().allMatch(k -> k.equals("object") || k.startsWith("map<"))) {
                // An object without documented fields next to a typed map: the map
                return single(c, d, map, fields);
            }
            if (kinds.contains("string") && kinds.stream().allMatch(k -> k.equals("string") || k.startsWith("map<string"))) {
                return scalarType(c, "string", camel(token(d.path)), d.enumValues);
            }
            throw new GenerationException("ambiguous union: " + c.id + " " + d.path + " is " + String.join(" | ", kinds)
                + "; settle it in api.json unions");
        }
        return single(c, d, types.getFirst(), fields);
    }

    private static int countMaps(List<JsonObject> types) {
        return (int) types.stream().filter(t -> t.get("type").getAsString().equals("map")).count();
    }

    private Field unionShorthand(Comp c, DocField d, ObjType object, JsonObject union) {
        if (union != null && union.has("shorthand")) {
            String name = union.get("shorthand").getAsString();
            return object.fields.stream().filter(f -> f.json.equals(name)).findFirst()
                .orElseThrow(() -> new GenerationException("api.json unions: " + c.id + " " + d.path + " shorthand '" + name + "' is no field of it"));
        }
        Window w = c.windows.isEmpty() ? null : c.windows.getLast();
        JsonObject own = w == null ? null : w.field(d.path);
        Set<String> leaves = own == null ? Set.of() : valueLeaves(own);
        if (!leaves.isEmpty()) {
            for (Field child : object.fields) {
                JsonObject m = w.field(child.path);
                if (m != null && !valueLeaves(m).isEmpty() && leaves.containsAll(valueLeaves(m))) {
                    return child;
                }
            }
        }
        for (String name : List.of("name", "identifier", "value")) {
            for (Field child : object.fields) {
                if (child.json.equals(name)) {
                    return child;
                }
            }
        }
        throw new GenerationException("ambiguous union: " + c.id + " " + d.path + " has a scalar form that fills no single field of "
            + object.name + "; settle it in api.json unions");
    }

    private static boolean isScalar(String type) {
        return switch (type) {
            case "string", "boolean", "integer", "number" -> true;
            default -> false;
        };
    }

    private Type single(Comp c, DocField d, JsonObject t, List<DocField> fields) {
        String type = t.get("type").getAsString();
        String name = camel(token(d.path));
        return switch (type) {
            case "boolean", "integer", "number", "string" -> scalarType(c, type, name, enumValuesOf(c, d));
            case "vec3" -> {
                c.usesVec3 = true;
                yield new Vec3();
            }
            case "object" -> {
                boolean children = fields.stream().anyMatch(f -> parent(f.path).equals(d.path));
                if (!children) {
                    throw new GenerationException(c.id + " " + d.path + " is an object without documented fields; type it in api.json types");
                }
                ObjType o = new ObjType();
                o.path = d.path;
                o.name = typeName(c, d.path, t.has("ref") ? t.get("ref").getAsString() : null);
                o.doc = d.doc;
                o.fields.addAll(children(c, d.path, fields, o));
                o.blockDescriptor = blockDescriptor(c.id + ":" + d.path);
                register(c, o);
                yield new ObjRef(o);
            }
            case "array" -> {
                JsonObject items = t.getAsJsonObject("items");
                String itemType = items.get("type").getAsString();
                if (itemType.equals("object")) {
                    ObjType o = new ObjType();
                    o.path = d.path + "[]";
                    o.name = typeName(c, d.path + "[]", items.has("ref") ? items.get("ref").getAsString() : null);
                    o.doc = d.doc;
                    o.fields.addAll(children(c, d.path + "[]", fields, o));
                    if (o.fields.isEmpty()) {
                        throw new GenerationException(c.id + " " + d.path + " is a list of objects without documented fields; type it in api.json types");
                    }
                    o.blockDescriptor = blockDescriptor(c.id + ":" + d.path + "[]");
                    register(c, o);
                    yield new ListOf(new ObjRef(o));
                }
                if (itemType.equals("any") || itemType.equals("array")) {
                    throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + typeString(t) + "; exclude or type it in api.json");
                }
                yield new ListOf(scalarType(c, itemType, name, enumValuesOf(c, d)));
            }
            case "map" -> {
                JsonObject items = t.getAsJsonObject("items");
                String itemType = items.get("type").getAsString();
                if (!isScalar(itemType)) {
                    throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + typeString(t) + "; exclude or type it in api.json");
                }
                yield new MapOf(scalarType(c, itemType, name, List.of()));
            }
            default -> throw new GenerationException("unsupported type: " + c.id + " " + d.path + " is " + typeString(t) + "; exclude or type it in api.json");
        };
    }

    private boolean blockDescriptor(String key) {
        return in.api().has("blockDescriptors") && in.api().getAsJsonArray("blockDescriptors").asList().stream().anyMatch(e -> e.getAsString().equals(key));
    }

    private List<JsonElement> enumValuesOf(Comp c, DocField d) {
        boolean open = in.api().has("openEnums") && in.api().getAsJsonArray("openEnums").asList().stream()
            .anyMatch(e -> e.getAsString().equals(c.id + ":" + d.path));
        return open ? List.of() : d.enumValues;
    }

    private Type scalarType(Comp c, String type, String fieldName, List<JsonElement> enumValues) {
        return switch (type) {
            case "boolean" -> new Scalar(ScalarKind.BOOL);
            case "integer" -> new Scalar(ScalarKind.INT);
            case "number" -> new Scalar(ScalarKind.FLOAT);
            case "string" -> {
                if (enumValues.isEmpty()) {
                    yield new Scalar(ScalarKind.STRING);
                }
                yield new EnumRef(enumType(c, pascal(fieldName), enumValues));
            }
            default -> throw new GenerationException("unsupported type: " + c.id + " " + fieldName + " is " + type);
        };
    }

    private EnumType enumType(Comp c, String name, List<JsonElement> values) {
        String renamed = apiString("enums", c.id + ":" + name);
        if (renamed != null) {
            name = renamed;
        }
        List<String> strings = values.stream().map(JsonElement::getAsString).toList();
        EnumType existing = c.enums.get(name);
        if (existing != null) {
            if (existing.values.equals(strings)) {
                return existing;
            }
            throw new GenerationException("name collision: " + c.id + " has two enums named " + name + " with different values; rename one in api.json enums");
        }
        EnumType e = new EnumType();
        e.name = name;
        Set<String> constants = new LinkedHashSet<>();
        for (String value : strings) {
            String constant = constant(value);
            if (!constants.add(constant)) {
                throw new GenerationException("name collision: " + c.id + " enum " + name + " has two values named " + constant);
            }
            e.values.add(value);
            e.constants.add(constant);
        }
        c.enums.put(name, e);
        return e;
    }

    private String typeName(Comp c, String path, String ref) {
        String renamed = apiString("types", c.id + ":" + path + "#name");
        if (renamed == null && in.api().has("typeNames") && in.api().getAsJsonObject("typeNames").has(c.id + ":" + path)) {
            renamed = in.api().getAsJsonObject("typeNames").get(c.id + ":" + path).getAsString();
        }
        if (renamed != null) {
            return renamed;
        }
        if (ref != null) {
            String cleaned = ref.replaceAll("\\s*\\(.*\\)", "").trim();
            if (!cleaned.isEmpty() && cleaned.matches("[A-Za-z][A-Za-z0-9 ]*")) {
                return pascal(cleaned.replace(' ', '_'));
            }
        }
        String t = path.endsWith("[]") ? token(path.substring(0, path.length() - 2)) : token(path);
        if (t.isEmpty() || t.equals("[]")) {
            t = "entry";
        }
        if (!t.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new GenerationException(c.id + " " + path + " has no usable type name; set one in api.json typeNames");
        }
        return pascal(t);
    }

    private void register(Comp c, ObjType o) {
        // Same name: allowed only for the same structure (merged later)
        c.allTypes.add(o);
    }

    /** Object types with the same fields share one record; types with the same name must be the same. */
    private void dedupeTypes(Comp c) {
        Map<String, ObjType> bySignature = new LinkedHashMap<>();
        Map<ObjType, ObjType> replace = new LinkedHashMap<>();
        for (ObjType o : c.allTypes) {
            String signature = signature(o);
            ObjType first = bySignature.putIfAbsent(signature, o);
            if (first != null) {
                replace.put(o, first);
            }
        }
        for (ObjType o : c.allTypes) {
            if (replace.containsKey(o)) {
                continue;
            }
            ObjType other = c.types.put(o.name, o);
            if (other != null) {
                throw new GenerationException("name collision: " + c.id + " has two different types named " + o.name + " ("
                    + other.path + ", " + o.path + "); name one in api.json typeNames");
            }
            if (o.name.equals(c.className) || c.enums.containsKey(o.name) || o.name.equals("Builder") || (c.usesVec3 && o.name.equals("Vec3"))) {
                throw new GenerationException("name collision: " + c.id + " type " + o.name + " (" + o.path + ") clashes with another nested name; name it in api.json typeNames");
            }
        }
        rewrite(c.root, replace);
        for (ObjType o : c.types.values()) {
            rewrite(o, replace);
        }
    }

    private static void rewrite(ObjType o, Map<ObjType, ObjType> replace) {
        for (Field f : o.fields) {
            f.type = rewrite(f.type, replace);
        }
    }

    private static Type rewrite(Type t, Map<ObjType, ObjType> replace) {
        return switch (t) {
            case ObjRef r -> replace.containsKey(r.type()) ? new ObjRef(replace.get(r.type()), r.wire()) : r;
            case ListOf l -> new ListOf(rewrite(l.element(), replace));
            case MapOf m -> new MapOf(rewrite(m.value(), replace));
            default -> t;
        };
    }

    private static String signature(ObjType o) {
        StringBuilder s = new StringBuilder("{");
        for (Field f : o.fields) {
            s.append(f.json).append(':').append(typeSignature(f.type)).append('=').append(f.docsDefault).append(f.required).append(';');
        }
        return s.append('}').toString();
    }

    private static String typeSignature(Type t) {
        return switch (t) {
            case ObjRef r -> signature(r.type());
            case ListOf l -> "list<" + typeSignature(l.element()) + ">";
            case MapOf m -> "map<" + typeSignature(m.value()) + ">";
            case EnumRef e -> "enum" + e.type().values;
            default -> t.toString();
        };
    }

    private void checkFieldNames(Comp c, ObjType o) {
        Set<String> names = new TreeSet<>();
        for (Field f : o.fields) {
            if (!names.add(f.java)) {
                throw new GenerationException("name collision: " + c.id + " " + (o.path.isEmpty() ? "" : o.path + " ") + "has two fields named " + f.java);
            }
            for (Field a : f.alternatives) {
                if (!names.add(a.java)) {
                    throw new GenerationException("name collision: " + c.id + " has two fields named " + a.java);
                }
            }
            if (f.type instanceof ObjRef r) {
                checkFieldNames(c, r.type());
            } else if (f.type instanceof ListOf l && l.element() instanceof ObjRef r) {
                checkFieldNames(c, r.type());
            }
        }
    }

    // ---- Wire rules: conflicts and defaults ----------------------------------------------------------------------------

    /**
     * Every wire leaf must be moved by at most one field (shorthands and union alternatives aside): two fields deciding
     * the same leaf need a hand transform. Then each default is checked against what BDS sends when the field is omitted.
     */
    private void checkConflicts(Comp c) {
        for (Window w : c.windows) {
            Map<String, List<String>> owners = new TreeMap<>();
            collectOwners(c, w, c.root, owners, false);
            for (Map.Entry<String, List<String>> e : owners.entrySet()) {
                if (new LinkedHashSet<>(e.getValue()).size() > 1) {
                    throw new GenerationException("conflicting rules: " + c.id + " " + e.getValue() + " all decide the wire leaf "
                        + e.getKey() + "; name a transform for one of them in transforms.json");
                }
            }
        }
        applyDefaults(c, c.root);
    }

    private void collectOwners(Comp c, Window w, ObjType o, Map<String, List<String>> owners, boolean transformed) {
        for (Field f : o.fields) {
            boolean t = transformed || f.transform != null || c.transform != null;
            if (!t) {
                for (String leaf : rulesOf(w, f).stream().filter(r -> VALUE_KINDS.contains(r.get("kind").getAsString()))
                    .map(r -> r.get("wire").getAsString()).toList()) {
                    owners.computeIfAbsent(leaf, k -> new ArrayList<>()).add(f.alternativeOf != null ? f.alternativeOf.path : f.path);
                }
                for (Field a : f.alternatives) {
                    rulesOf(w, a).stream().filter(r -> VALUE_KINDS.contains(r.get("kind").getAsString()))
                        .forEach(r -> owners.computeIfAbsent(r.get("wire").getAsString(), k -> new ArrayList<>()).add(f.path));
                }
            }
            ObjType child = objectOf(f.type);
            if (child != null) {
                collectOwners(c, w, child, owners, t);
            }
        }
    }

    /** The object a field holds, as it occurs at the field (its own JSON paths and rules). */
    static ObjType objectOf(Type t) {
        if (t instanceof ObjRef r) {
            return r.wire();
        }
        if (t instanceof ListOf l && l.element() instanceof ObjRef r) {
            return r.wire();
        }
        return null;
    }

    /** The rules of a field in a window, without the rules a union's object takes over from its children. */
    static List<JsonObject> rulesOf(Window w, Field f) {
        JsonObject m = mappingOf(w, f);
        List<JsonObject> rules = new ArrayList<>();
        if (m == null || !m.has("rules")) {
            return rules;
        }
        m.getAsJsonArray("rules").forEach(r -> rules.add(r.getAsJsonObject()));
        if (f.type instanceof ObjRef || (f.type instanceof ListOf l && l.element() instanceof ObjRef)) {
            // An object's own value rules come from its scalar shorthand, which its children explain
            rules.removeIf(r -> VALUE_KINDS.contains(r.get("kind").getAsString()));
        }
        if (!f.alternatives.isEmpty()) {
            rules.removeIf(r -> VALUE_KINDS.contains(r.get("kind").getAsString()));
        }
        return rules;
    }

    static JsonObject mappingOf(Window w, Field f) {
        if (f.mappingKey == null) {
            return null;
        }
        if (f.mappingKey.startsWith("form:")) {
            return w.form(f.mappingKey.substring(5));
        }
        return w.field(f.mappingKey);
    }

    static Map<String, String> whenAbsent(Window w, Field f) {
        JsonObject m = mappingOf(w, f);
        Map<String, String> out = new TreeMap<>();
        if (m != null && m.has("whenAbsent")) {
            m.getAsJsonObject("whenAbsent").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return out;
    }

    /**
     * Applies the docs default unless BDS sends something else when the field is omitted; then the field stays unset
     * (nullable) and the encoder writes what BDS sends.
     */
    private void applyDefaults(Comp c, ObjType o) {
        Window w = c.windows.isEmpty() ? null : c.windows.getLast();
        for (Field f : o.fields) {
            ObjType child = objectOf(f.type);
            if (child != null) {
                applyDefaults(c, child);
            }
            if (f.required || f.form != null && f.docsDefault == null) {
                if (f.form != null && f.type instanceof ListOf) {
                    f.defaultValue = new JsonArray();
                }
                continue;
            }
            JsonElement d = f.docsDefault;
            if (d == null || d.isJsonNull()) {
                continue;
            }
            if (in.api().has("noDefaults") && in.api().getAsJsonArray("noDefaults").asList().stream()
                .anyMatch(e -> e.getAsString().equals(c.id + ":" + f.path))) {
                f.defaultNote = "The docs give the default " + render(d) + "; unset, BDS behaves differently (see api.json noDefaults).";
                continue;
            }
            if (!fits(f.type, d)) {
                f.defaultNote = "The docs give the default " + d + ", which is not a value of this type; unset, BDS's behaviour applies.";
                continue;
            }
            String mismatch = w == null || f.transform != null || c.transform != null ? null : defaultMismatch(w, f, d);
            if (mismatch != null) {
                f.defaultNote = "The docs give the default " + render(d) + ", but " + mismatch + "; so it is unset by default, and the "
                    + "encoder sends what BDS sends then.";
                continue;
            }
            f.defaultValue = d;
        }
    }

    /** An object's documented default names its fields' values; they win over the fields' own defaults. */
    private static void propagateDefaults(ObjType o) {
        for (Field f : o.fields) {
            ObjType child = objectOf(f.type);
            if (child == null) {
                continue;
            }
            if (f.type instanceof ObjRef && f.docsDefault != null && f.docsDefault.isJsonObject()) {
                for (Field cf : child.fields) {
                    JsonElement value = f.docsDefault.getAsJsonObject().get(cf.json);
                    if (value != null) {
                        cf.docsDefault = value;
                    }
                }
            }
            propagateDefaults(child);
        }
    }

    private static String render(JsonElement d) {
        return Docs.literal(d);
    }

    private static boolean fits(Type t, JsonElement d) {
        return switch (t) {
            case Scalar s -> d.isJsonPrimitive() && switch (s.kind()) {
                case BOOL -> d.getAsJsonPrimitive().isBoolean();
                case INT, FLOAT -> d.getAsJsonPrimitive().isNumber();
                case STRING -> d.getAsJsonPrimitive().isString();
            };
            case EnumRef e -> d.isJsonPrimitive() && e.type().values.contains(d.getAsString());
            case Vec3 v -> d.isJsonArray() && d.getAsJsonArray().size() == 3 || d.isJsonObject();
            case ListOf l -> d.isJsonArray() && d.getAsJsonArray().asList().stream().allMatch(e -> fits(l.element(), e));
            case MapOf m -> d.isJsonObject() && d.getAsJsonObject().entrySet().stream().allMatch(e -> fits(m.value(), e.getValue()));
            case ObjRef r -> d.isJsonObject() && d.getAsJsonObject().keySet().stream().allMatch(k -> r.type().fields.stream().anyMatch(f -> k.equals(f.json)));
            case States s -> d.isJsonObject();
            case ApiModel.ComponentRef r -> false;
        };
    }

    /** @return why the default contradicts BDS, or null if it is consistent (or nothing shows otherwise) */
    private static String defaultMismatch(Window w, Field f, JsonElement d) {
        Map<String, String> absent = whenAbsent(w, f);
        if (absent.isEmpty()) {
            return null;
        }
        if (f.type instanceof ObjRef r && d.isJsonObject()) {
            for (Field child : r.type().fields) {
                JsonElement v = d.getAsJsonObject().get(child.json);
                if (v == null) {
                    continue;
                }
                for (JsonObject rule : rulesOf(w, child)) {
                    String leaf = rule.get("wire").getAsString();
                    String expected = absent.get(leaf);
                    String actual = WireSource.evaluate(rule, v);
                    if (expected != null && actual != null && !sameLiteral(expected, actual)) {
                        return "BDS sends " + leaf + " = " + expected + " when it is omitted";
                    }
                }
            }
            return null;
        }
        for (JsonObject rule : rulesOf(w, f)) {
            String kind = rule.get("kind").getAsString();
            String leaf = rule.get("wire").getAsString();
            String expected = absent.get(leaf);
            if (expected == null || (expected.equals("omitted") && f.path != null && f.path.contains("[]"))) {
                continue;
            }
            if (kind.equals("constant") || kind.equals("empty-list") || kind.equals("structure") || kind.equals("cross-field")) {
                continue;
            }
            String actual = WireSource.evaluate(rule, d);
            if (actual == null) {
                continue;
            }
            if (!sameLiteral(expected, actual)) {
                return "BDS " + "sends " + leaf + " = " + (expected.equals("omitted") ? "nothing" : expected) + " when it is omitted";
            }
        }
        return null;
    }

    static boolean sameLiteral(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int ia = a.lastIndexOf(" (");
        int ib = b.lastIndexOf(" (");
        if (ia < 0 || ib < 0 || !a.substring(ia).equals(b.substring(ib))) {
            return false;
        }
        try {
            return Double.parseDouble(a.substring(0, ia)) == Double.parseDouble(b.substring(0, ib));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String transform(String id, String path) {
        String key = id + ":" + path;
        if (!in.transforms().has(key)) {
            return null;
        }
        return in.transforms().getAsJsonObject(key).get("transform").getAsString();
    }

    // ---- Names ---------------------------------------------------------------------------------------------------------

    static String pascal(String s) {
        StringBuilder out = new StringBuilder();
        for (String part : s.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }

    static String camel(String s) {
        String p = pascal(s);
        return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    static String constant(String value) {
        String c = value.replaceFirst("^minecraft:", "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (c.isEmpty() || Character.isDigit(c.charAt(0))) {
            c = "_" + c;
        }
        return c;
    }

    private static void javaName(String name, String where) {
        if (name == null || name.isEmpty() || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || KEYWORDS.contains(name)) {
            throw new GenerationException("not a Java identifier: '" + name + "' for " + where + "; rename it in api.json");
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    static JsonPrimitive primitive(Object o) {
        return o instanceof Boolean b ? new JsonPrimitive(b) : o instanceof Number n ? new JsonPrimitive(n) : new JsonPrimitive(Objects.toString(o));
    }
}
