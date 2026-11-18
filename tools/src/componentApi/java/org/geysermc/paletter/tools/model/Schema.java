package org.geysermc.paletter.tools.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.Model.JType;
import org.geysermc.paletter.tools.model.Model.Range;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What BDS's own JSON schemas say about the item and block components ({@code data/bds/<build>/schema.json}, written by
 * {@link SchemaExtract}), merged into the model by {@link Fixtures#apply}. The schemas are what BDS validates against, so
 * they come first: before what BDS told the generator in its content log ({@code bds-feedback.json}), the hand-written
 * errata and the creator docs.
 *
 * <p>Only the newest stable format of each component counts. Components and fields of the {@code beta} schema alone are
 * not merged (the add-on has no experiment to ask for them).
 */
public final class Schema {
    public static final String FILE = "schema.json";

    /** No schema: every merge is the identity. */
    public static final Schema NONE = new Schema("", new JsonObject(), null);

    private final String build;
    private final JsonObject json;
    /** The format version of the add-on this schema serves (the newest schema format at or below it counts), or null for the newest. */
    private final String format;

    private Schema(String build, JsonObject json, String format) {
        this.build = build;
        this.json = json;
        this.format = format;
    }

    public static Schema read(Path file) throws IOException {
        return of(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
    }

    /** The schema of an extract ({@link SchemaExtract#extract}). */
    public static Schema of(JsonObject json) {
        return new Schema(json.get("bdsVersion").getAsString(), json, null);
    }

    /**
     * The schema of the newest build in {@code bdsData} that has one and is not newer than {@code build} (any build if it is
     * null): a build without its own schema (or one whose docgen failed) uses the previous one. A schema file that cannot be
     * read is no schema, with a warning: the add-on is then made without one, as before the step existed.
     */
    public static Schema newest(Path bdsData, String build) {
        if (!Files.isDirectory(bdsData)) {
            return NONE;
        }
        try (Stream<Path> builds = Files.list(bdsData)) {
            Path file = builds.filter(b -> Files.isRegularFile(b.resolve(FILE)))
                .filter(b -> build == null || compareVersions(b.getFileName().toString(), build) <= 0)
                .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString()))
                .map(b -> b.resolve(FILE)).orElse(null);
            return file == null ? NONE : read(file);
        } catch (IOException | RuntimeException e) {
            System.err.println("WARNING: no BDS schema, a " + FILE + " in " + bdsData + " cannot be read: " + e);
            return NONE;
        }
    }

    /** This schema for an add-on of this format version. */
    public Schema forFormat(String formatVersion) {
        return this == NONE ? NONE : new Schema(build, json, formatVersion);
    }

    /** Components the extract could not read, and schema keywords it does not know: what to look at in the extract of a build. */
    public List<String> skipped() {
        return strings("skipped");
    }

    public List<String> unhandledKeywords() {
        return strings("unhandledKeywords");
    }

    private List<String> strings(String key) {
        return json.has(key) ? json.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList() : List.of();
    }

    /** Dotted versions number by number; a name that is not a version ({@code beta}) after all of them. */
    public static int compareVersions(String a, String b) {
        boolean x = a.matches("[0-9.]+");
        boolean y = b.matches("[0-9.]+");
        if (x != y) {
            return x ? -1 : 1;
        }
        if (!x) {
            return a.compareTo(b);
        }
        String[] p = a.split("[.]");
        String[] q = b.split("[.]");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int c = Integer.compare(i < p.length ? Integer.parseInt(p[i]) : 0, i < q.length ? Integer.parseInt(q[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The build the schema is of, or null for none. */
    public String build() {
        return build;
    }

    /** The marker of what this schema's facts are called in the model's docs text. */
    private String source() {
        return "(bds-schema " + build + ") ";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lookup

    /** {@code item:minecraft:x}, {@code block:minecraft:x} and {@code block:trait:minecraft:x} are schema components; {@code block:states} is not. */
    JsonObject definition(String id) {
        String kind = id.startsWith("block:trait:") ? "trait" : id.substring(0, id.indexOf(':'));
        String name = id.substring(id.startsWith("block:trait:") ? "block:trait:".length() : kind.length() + 1);
        if (!json.has(kind) || !json.getAsJsonObject(kind).has(name) || !json.getAsJsonObject("formats").has(kind)) {
            return null;
        }
        // The newest stable format that is not newer than the add-on's (all of them without one); beta is for experiments
        String chosen = null;
        for (JsonElement f : json.getAsJsonObject("formats").getAsJsonArray(kind)) {
            if (!f.getAsString().equals("beta") && (format == null || compareVersions(f.getAsString(), format) <= 0)) {
                chosen = f.getAsString();
            }
        }
        if (chosen == null) {
            return null;
        }
        for (JsonElement definition : json.getAsJsonObject(kind).getAsJsonArray(name)) {
            if (definition.getAsJsonObject().getAsJsonArray("formats").contains(new com.google.gson.JsonPrimitive(chosen))) {
                return definition.getAsJsonObject();
            }
        }
        return null;
    }

    /**
     * The key of a path in a definition's fields ({@code ""} is the component itself), or null. The model spells the members of
     * a map with free keys {@code *.x} (and one copy per documented key); the schema {@code {}.x}.
     */
    static String key(JsonObject definition, String path) {
        JsonObject fields = definition.getAsJsonObject("fields");
        int dot = path.indexOf('.');
        return path.isEmpty() || fields.has(path) ? path : dot > 0 && fields.has("{}" + path.substring(dot)) ? "{}" + path.substring(dot) : null;
    }

    private static JsonObject node(JsonObject definition, String path) {
        String key = key(definition, path);
        return key == null ? null : key.isEmpty() ? definition.getAsJsonObject("root") : definition.getAsJsonObject("fields").getAsJsonObject(key);
    }

    /**
     * The values of the string enum the schema lists at a path of a component: the field's own, else its list elements' ({@code a[]}).
     * Empty without a schema, a component or a path of it, or when there is no enum.
     */
    public List<String> enumValues(String component, String path) {
        JsonObject definition = definition(component);
        JsonObject node = definition == null ? null : node(definition, path);
        if (node == null) {
            return List.of();
        }
        if (!node.has("enum")) {
            String key = key(definition, path);
            JsonObject fields = definition.getAsJsonObject("fields");
            node = !path.isEmpty() && fields.has(key + "[]") ? fields.getAsJsonObject(key + "[]") : null;
            if (node == null || !node.has("enum")) {
                return List.of();
            }
        }
        return node.getAsJsonArray("enum").asList().stream().filter(v -> v.getAsJsonPrimitive().isString()).map(JsonElement::getAsString).toList();
    }

    /** A member of the object alternative of a map's element ({@link #element}): its name, JSON types and whether the schema requires it. */
    public record Member(String name, List<String> types, boolean required) {
    }

    /** The JSON types the schema lists for the elements of the map at a path ({@code bone_visibility{}}: string, object, number, boolean). */
    public List<String> elementTypes(String component, String mapPath) {
        JsonObject definition = definition(component);
        JsonObject node = definition == null ? null : node(definition, mapPath + "{}");
        return node == null || !node.has("types") ? List.of() : node.getAsJsonArray("types").asList().stream().map(JsonElement::getAsString).toList();
    }

    /** The members of the object alternative of the elements of the map at a path ({@code bone_visibility{}.expression}), in the schema's order. */
    public List<Member> elementMembers(String component, String mapPath) {
        JsonObject definition = definition(component);
        if (definition == null) {
            return List.of();
        }
        List<Member> members = new ArrayList<>();
        String prefix = mapPath + "{}.";
        JsonObject fields = definition.getAsJsonObject("fields");
        for (String path : fields.keySet()) {
            if (path.startsWith(prefix) && path.indexOf('.', prefix.length()) < 0) {
                JsonObject node = fields.getAsJsonObject(path);
                members.add(new Member(path.substring(prefix.length()), node.has("types")
                    ? node.getAsJsonArray("types").asList().stream().map(JsonElement::getAsString).toList() : List.of(), node.has("required")));
            }
        }
        return members;
    }

    /** The families of facts that say the same thing in different words: a schema fact of a family replaces the log's of it. */
    private static String family(String fact) {
        return switch (fact.split(" ")[0]) {
            case "range", "min", "max" -> "limits";
            case "step" -> "step";
            case "minItems", "maxItems" -> "items";
            case "values" -> "values";
            default -> null;
        };
    }

    /** The schema's facts of a field, in the notation of {@code bds-feedback.json}: limits, list lengths, values. */
    private static List<String> schemaFacts(JsonObject node) {
        List<String> facts = new ArrayList<>();
        Double min = bound(node, "minimum");
        Double max = bound(node, "maximum");
        if (min != null && max != null) {
            facts.add("range " + plain(min) + " " + plain(max));
        } else if (min != null) {
            facts.add("min " + plain(min));
        } else if (max != null) {
            facts.add("max " + plain(max));
        }
        for (String limit : List.of("minItems", "maxItems")) {
            if (node.has(limit)) {
                facts.add(limit + " " + node.get(limit).getAsInt());
            }
        }
        if (node.has("multipleOf")) {
            facts.add("step " + plain(node.get("multipleOf").getAsDouble()));
        }
        if (node.has("enum") && node.getAsJsonArray("enum").asList().stream().allMatch(v -> v.getAsJsonPrimitive().isString())) {
            facts.add("values " + String.join(", ", node.getAsJsonArray("enum").asList().stream().map(v -> "'" + v.getAsString() + "'").toList()));
        }
        return facts;
    }

    /**
     * What BDS says about a field: the schema's facts, then the content log's, except those of a family the schema covers
     * (the log's {@code range} next to the schema's {@code min} is the same kind of fact). The log's {@code required} stays: a field
     * is required when the schema, the log or the docs say so. Where the log disagrees with the schema, see {@link #disputed}.
     */
    public List<String> facts(String component, String path, List<String> feedback) {
        JsonObject definition = definition(component);
        JsonObject node = definition == null ? null : node(definition, path);
        if (node == null) {
            return feedback;
        }
        List<String> facts = schemaFacts(node);
        Set<String> covered = new LinkedHashSet<>(facts.stream().map(Schema::family).toList());
        feedback.stream().filter(f -> !covered.contains(family(f))).forEach(facts::add);
        return facts;
    }

    /**
     * The facts of the content log that contradict the schema (a different limit, a value the schema does not list). The
     * add-on keeps both: it tests the boundaries of the schema's and of the log's, so BDS decides.
     */
    public List<String> disputed(String component, String path, List<String> feedback) {
        JsonObject definition = definition(component);
        JsonObject node = definition == null ? null : node(definition, path);
        return node == null ? List.of() : feedback.stream().filter(f -> contradicts(f, node)).toList();
    }

    private static boolean contradicts(String fact, JsonObject node) {
        String[] parts = fact.split(" ");
        Double min = bound(node, "minimum");
        Double max = bound(node, "maximum");
        try {
            return switch (parts[0]) {
                case "range" -> (min != null && min != Double.parseDouble(parts[1])) || (max != null && max != Double.parseDouble(parts[2]));
                case "min" -> min != null && min != Double.parseDouble(parts[1]);
                case "max" -> max != null && max != Double.parseDouble(parts[1]);
                case "step" -> node.has("multipleOf") && node.get("multipleOf").getAsDouble() != Double.parseDouble(parts[1]);
                case "minItems", "maxItems" -> node.has(parts[0]) && node.get(parts[0]).getAsInt() != Integer.parseInt(parts[1]);
                case "values" -> node.has("enum")
                    && quoted(fact).stream().anyMatch(v -> !node.getAsJsonArray("enum").contains(new com.google.gson.JsonPrimitive(v)));
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<String> quoted(String fact) {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']*)'").matcher(fact);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    /** Every dispute between the schema and a {@code bds-feedback.json}, one line each. */
    public List<String> disputes(JsonObject feedback) {
        List<String> lines = new ArrayList<>();
        for (String component : feedback.keySet()) {
            if (component.equals(Model.GENERATED_KEY)) {
                continue;
            }
            for (String path : feedback.getAsJsonObject(component).keySet()) {
                List<String> facts = feedback.getAsJsonObject(component).getAsJsonArray(path).asList().stream().map(JsonElement::getAsString).toList();
                for (String fact : disputed(component, path, facts)) {
                    List<String> schema = schemaFacts(node(definition(component), path)).stream()
                        .filter(f -> family(f).equals(family(fact))).toList();
                    lines.add(component + " " + (path.isEmpty() ? "(itself)" : path) + ": the content log says `" + fact + "`, the schema `"
                        + String.join("`, `", schema) + "`");
                }
            }
        }
        return lines;
    }

    /**
     * A numeric bound, as the float it is in BDS ({@code 0.9}, not {@code 0.8999999761581421}): where the schema says float, or
     * where the number is exactly a float (the schema prints floats as doubles).
     */
    private static Double bound(JsonObject node, String key) {
        if (!node.has(key)) {
            return null;
        }
        double value = node.get(key).getAsDouble();
        boolean isFloat = node.has("underlyingType") && node.get("underlyingType").getAsString().equals("float");
        return isFloat || (double) (float) value == value ? Double.parseDouble(Float.toString((float) value)) : value;
    }

    private static String plain(double number) {
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Merge

    /**
     * The model with the schema's facts: the enum values, limits and required of every field the schema has (also those the
     * docs call something else), the schema's types where the docs lack them, the fields only the schema knows and the
     * components only the schema knows (not the deprecated ones). Types, forms and fields are only ever added.
     */
    public Model apply(Model model) {
        if (this == NONE) {
            return model;
        }
        List<Component> components = new ArrayList<>();
        for (Component c : model.components()) {
            JsonObject definition = definition(c.id());
            components.add(definition == null ? c : merge(c, definition));
        }
        for (String kind : List.of("item", "block", "trait")) {
            if (!json.has(kind)) {
                continue;
            }
            for (String name : json.getAsJsonObject(kind).keySet()) {
                String id = (kind.equals("trait") ? "block:trait:" : kind + ":") + name;
                JsonObject definition = definition(id);
                if (model.component(id) == null && definition != null && !definition.getAsJsonObject("root").has("deprecated")) {
                    components.add(created(id, kind, name, definition));
                }
            }
        }
        return new Model(model.source(), model.commit(), model.license(), model.lists(), components, model.formatNotes());
    }

    private Component created(String id, String kind, String name, JsonObject definition) {
        JsonObject root = definition.getAsJsonObject("root");
        List<Field> fields = new ArrayList<>();
        addMissing(fields, definition);
        return new Component(id, kind.equals("trait") ? "block" : kind, kind.equals("trait") ? "trait" : "component", name, "active",
            source() + "BDS has this component in its schema; the creator docs do not list it.", null, null, List.of(),
            types(root, "", definition.getAsJsonObject("fields")), fields, List.of());
    }

    private Component merge(Component c, JsonObject definition) {
        JsonObject fields = definition.getAsJsonObject("fields");
        List<Field> merged = new ArrayList<>();
        for (Field f : c.fields()) {
            String key = key(definition, f.path());
            merged.add(key == null ? f : merge(f, node(definition, f.path()), fields, key));
        }
        addMissing(merged, definition);
        return new Component(c.id(), c.kind(), c.category(), c.name(), c.status(), c.doc(), c.page(), c.minFormatVersion(), c.experiments(),
            c.forms(), merged, c.notes());
    }

    private Field merge(Field f, JsonObject node, JsonObject fields, String path) {
        List<JType> schemaTypes = types(node, path, fields);
        List<JType> types = new ArrayList<>(f.types());
        List<JType> added = schemaTypes.stream().filter(t -> types.stream().noneMatch(e -> kindOf(e).equals(kindOf(t)))).toList();
        // BDS takes none of the documented types: what it takes comes first
        boolean refused = !f.types().isEmpty() && !schemaTypes.isEmpty() && schemaTypes.stream().noneMatch(t -> kindOf(t).equals(kindOf(f.types().get(0))));
        types.addAll(refused ? 0 : types.size(), added);
        List<JsonElement> enums = enumValues(node, path, fields);
        Range range = node.has("minimum") || node.has("maximum")
            ? new Range(node.has("minimum") ? bound(node, "minimum") : f.range() == null ? null : f.range().min(),
                node.has("maximum") ? bound(node, "maximum") : f.range() == null ? null : f.range().max(), source() + "limits")
            : f.range();
        // Required when the schema, the log (a fact, read by the generator) or the docs say so
        return new Field(f.path(), types, f.defaultValue(), f.required() || node.has("required"), enums.isEmpty() ? f.enumValues() : enums, range,
            f.minFormatVersion(), f.examples(), f.doc());
    }

    /**
     * Adds the members the schema has and the model lacks, below a field the model has (or one just added). Not the elements of
     * lists and maps ({@code a[]}, {@code a{}}: the model keeps their enum values and element type on the list or map itself,
     * and spells the members of free keys its own way), and not deprecated members.
     */
    private void addMissing(List<Field> into, JsonObject definition) {
        JsonObject fields = definition.getAsJsonObject("fields");
        Set<String> known = new LinkedHashSet<>(into.stream().map(Field::path).toList());
        for (String path : fields.keySet()) {
            JsonObject node = fields.getAsJsonObject(path);
            int dot = path.lastIndexOf('.');
            String parent = dot < 0 ? "" : path.substring(0, dot);
            boolean parentKnown = parent.isEmpty() || known.contains(parent) || (parent.endsWith("[]") && known.contains(parent.substring(0, parent.length() - 2)));
            if (known.contains(path) || path.endsWith("[]") || path.contains("{}") || node.has("deprecated") || !parentKnown) {
                continue;
            }
            Range range = node.has("minimum") || node.has("maximum")
                ? new Range(bound(node, "minimum"), bound(node, "maximum"), source() + "limits") : null;
            into.add(new Field(path, types(node, path, fields), node.has("default") ? node.get("default") : null, node.has("required"),
                enumValues(node, path, fields), range, null, List.of(), source() + "BDS accepts this key; the creator docs do not list it."));
            known.add(path);
        }
    }

    private static List<JsonElement> enumValues(JsonObject node, String path, JsonObject fields) {
        if (node.has("enum")) {
            return node.getAsJsonArray("enum").asList();
        }
        JsonObject element = fields.has(path + "[]") ? fields.getAsJsonObject(path + "[]") : null;
        return element != null && element.has("enum") ? element.getAsJsonArray("enum").asList() : List.of();
    }

    /** The model types of a schema node: its JSON types, a list with its element type, an object with free keys as a map. */
    private static List<JType> types(JsonObject node, String path, JsonObject fields) {
        List<JType> types = new ArrayList<>();
        if (!node.has("types")) {
            return types;
        }
        String prefix = path.isEmpty() ? "" : path;
        for (JsonElement type : node.getAsJsonArray("types")) {
            switch (type.getAsString()) {
                case "array" -> types.add(new JType("array", element(prefix + "[]", fields), null, null));
                case "object" -> {
                    boolean members = fields.keySet().stream().anyMatch(k -> path.isEmpty() ? !k.startsWith("[") && !k.startsWith("{") : k.startsWith(path + "."));
                    types.add(fields.has(prefix + "{}") && !members ? new JType("map", element(prefix + "{}", fields), null, null) : JType.of("object"));
                }
                default -> types.add(JType.of(type.getAsString()));
            }
        }
        return types;
    }

    private static JType element(String path, JsonObject fields) {
        if (!fields.has(path)) {
            return null;
        }
        List<JType> types = types(fields.getAsJsonObject(path), path, fields);
        return types.isEmpty() ? (fields.keySet().stream().anyMatch(k -> k.startsWith(path + ".")) ? JType.of("object") : null) : types.get(0);
    }

    /** What two types have to share to be the same alternative: an integer is a number, a map an object, a vector a list. */
    private static String kindOf(JType type) {
        return switch (type.type()) {
            case "integer", "number" -> "number";
            case "map" -> "object";
            case "vec3" -> "array";
            default -> type.type();
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reports

    /** The fields of the model the schema's component does not have: stale docs (or older formats the schema no longer describes). */
    public List<String> stale(Model model) {
        List<String> stale = new ArrayList<>();
        for (Component c : model.components()) {
            JsonObject definition = definition(c.id());
            if (definition != null) {
                c.fields().stream().filter(f -> node(definition, f.path()) == null && !f.doc().startsWith(source()))
                    .forEach(f -> stale.add(c.id() + " " + f.path()));
            }
        }
        return stale;
    }

    /** What changed from one schema to the next (components and fields added, removed or with other facts, formats), one line each. */
    public static List<String> diff(Schema before, Schema after) {
        List<String> lines = new ArrayList<>();
        for (String kind : List.of("item", "block", "trait")) {
            JsonObject b = before.json.has(kind) ? before.json.getAsJsonObject(kind) : new JsonObject();
            JsonObject a = after.json.has(kind) ? after.json.getAsJsonObject(kind) : new JsonObject();
            JsonElement formatsBefore = before.json.has("formats") ? before.json.getAsJsonObject("formats").get(kind) : null;
            JsonElement formatsAfter = after.json.has("formats") ? after.json.getAsJsonObject("formats").get(kind) : null;
            if (formatsBefore != null && !formatsBefore.equals(formatsAfter)) {
                lines.add("~ " + kind + " formats " + formatsBefore + " -> " + formatsAfter);
            }
            Set<String> names = new LinkedHashSet<>(b.keySet());
            names.addAll(a.keySet());
            for (String name : names) {
                String id = kind.equals("trait") ? "block:trait:" + name : kind + ":" + name;
                JsonObject was = b.has(name) ? before.definition(id) : null;
                JsonObject is = a.has(name) ? after.definition(id) : null;
                if (was == null || is == null) {
                    if (was != is) {
                        lines.add((is == null ? "- " : "+ ") + kind + " " + name);
                    }
                    continue;
                }
                JsonObject wasFields = was.getAsJsonObject("fields");
                JsonObject isFields = is.getAsJsonObject("fields");
                for (String path : isFields.keySet()) {
                    if (!wasFields.has(path)) {
                        lines.add("+ " + kind + " " + name + " " + path);
                    } else if (!wasFields.get(path).equals(isFields.get(path))) {
                        lines.add("~ " + kind + " " + name + " " + path + ": " + wasFields.get(path) + " -> " + isFields.get(path));
                    }
                }
                wasFields.keySet().stream().filter(p -> !isFields.has(p)).forEach(p -> lines.add("- " + kind + " " + name + " " + p));
            }
        }
        return lines;
    }

    /** The number of components and fields of the newest stable formats, and the formats, for a report row. */
    public String summary() {
        int components = 0;
        int fields = 0;
        List<String> formats = new ArrayList<>();
        for (String kind : List.of("item", "block", "trait")) {
            if (json.has(kind)) {
                formats.add(kind + " " + json.getAsJsonObject("formats").get(kind));
                for (String name : json.getAsJsonObject(kind).keySet()) {
                    JsonObject definition = definition(kind.equals("trait") ? "block:trait:" + name : kind + ":" + name);
                    if (definition != null) {
                        components++;
                        fields += definition.getAsJsonObject("fields").size();
                    }
                }
            }
        }
        return components + " components, " + fields + " fields; formats " + String.join(", ", formats);
    }
}
