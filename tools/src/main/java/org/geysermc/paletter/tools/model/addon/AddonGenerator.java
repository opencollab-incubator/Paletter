package org.geysermc.paletter.tools.model.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.Model.JType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Generates the spec capture add-on from the component model: per component a control (companions only), a base
 * (every field without a documented default set to a sample, so the definition is valid), and per field and per
 * documented form a set of variants that change only that field: omitted, true/false, numbers around the documented
 * range and default, every enum value, lists of length 0/1/2 (and every enum value alone, for bit sets), maps and
 * objects empty and filled. Each variant is its own item or block, listed in {@code variants.json} so the mapping
 * inference can tell which JSON value produced which wire NBT.
 */
public final class AddonGenerator {
    public static final String NAMESPACE = "paletter";
    public static final String BP_UUID = "a1e5c0de-0000-4000-8000-00000000d001";
    public static final String RP_UUID = "a1e5c0de-0000-4000-8000-00000000e001";
    private static final String GEO = "geometry.paletter_cube";

    private final Model model;
    private final Fixtures fixtures;
    private final String formatVersion;
    private final List<Variant> variants = new ArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    /**
     * @param identifier the item or block identifier
     * @param role       {@code control} (companions only), {@code base}, {@code form} (a whole value of another JSON
     *                   type), {@code field} (one field changed), {@code omit} (one base field removed)
     * @param path       the field path ("" for the whole value)
     * @param value      the JSON value at the path (null for control and omit)
     * @param component  the component JSON value as written (null for the control)
     */
    public record Variant(String identifier, String kind, String file, String componentId, String role, String path, String label,
                          JsonElement value, JsonElement component, JsonObject definition) {
    }

    public AddonGenerator(Model model, Fixtures fixtures) {
        this(model, fixtures, fixtures.formatVersion);
    }

    /**
     * @param formatVersion the {@code format_version} of the item and block files and the {@code min_engine_version} of
     *                      the packs: BDS refuses packs that ask for a newer engine than it is, so a capture of an older
     *                      build passes {@link #formatVersionFor}
     */
    public AddonGenerator(Model model, Fixtures fixtures, String formatVersion) {
        this.model = fixtures.apply(model);
        this.fixtures = fixtures;
        this.formatVersion = formatVersion;
        generate();
    }

    /**
     * @return the older of the model's format version and the BDS build ({@code 1.26.45.1} -> {@code 1.26.45}), the
     *         format version a capture of that build can load
     */
    public static String formatVersionFor(String modelFormatVersion, String bdsVersion) {
        int[] model = parse(modelFormatVersion);
        int[] bds = parse(bdsVersion);
        for (int i = 0; i < 3; i++) {
            if (bds[i] != model[i]) {
                return bds[i] < model[i] ? bds[0] + "." + bds[1] + "." + bds[2] : modelFormatVersion;
            }
        }
        return modelFormatVersion;
    }

    private static int[] parse(String version) {
        String[] parts = version.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    /** @return {@code [1, 26, 45]} for the format version {@code 1.26.45}: the {@code min_engine_version} of the packs */
    static String minEngine(String formatVersion) {
        int[] v = parse(formatVersion);
        return "[" + v[0] + ", " + v[1] + ", " + v[2] + "]";
    }

    public List<Variant> variants() {
        return variants;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Variants

    private void generate() {
        for (Component c : model.components()) {
            if (fixtures.skip(c.id(), null) != null) {
                continue;
            }
            variant(c, "control", "", "control", null, null);
            JsonElement base = base(c);
            variant(c, "base", "", "base", null, base);
            // Whole value forms (shorthands, alternative representations)
            for (JType form : c.forms()) {
                if (form == c.forms().get(0) && objectBase(c)) {
                    continue;
                }
                for (Labeled value : values(c, "", form, null)) {
                    variant(c, "form", "", form + ":" + value.label, value.value, value.value);
                }
            }
            // Fields
            for (Field field : c.fields()) {
                if (fixtures.skip(c.id(), field.path()) != null || skippedParent(c, field.path())) {
                    continue;
                }
                if (get(base, field.path()) != null && !field.path().endsWith("[]") && !field.path().endsWith("{}")) {
                    JsonElement omitted = base.deepCopy();
                    if (remove(omitted, field.path())) {
                        variant(c, "omit", field.path(), "omitted", null, omitted);
                    }
                }
                for (JType type : field.types()) {
                    for (Labeled value : values(c, field.path(), type, field)) {
                        JsonElement json = with(c, base, field.path(), value.value);
                        if (json != null) {
                            variant(c, "field", field.path(), type + ":" + value.label, value.value, json);
                        }
                    }
                }
            }
        }
    }

    private boolean skippedParent(Component c, String path) {
        String p = path;
        while (true) {
            int cut = Math.max(p.lastIndexOf('.'), Math.max(p.lastIndexOf("[]"), p.lastIndexOf("{}")));
            if (cut <= 0) {
                return false;
            }
            p = p.substring(0, cut);
            if (fixtures.skip(c.id(), p) != null) {
                return true;
            }
        }
    }

    private void variant(Component c, String role, String path, String label, JsonElement value, JsonElement json) {
        String shortName = c.id().replaceFirst("^(item|block):", "").replace("minecraft:", "").replace("trait:", "trait_").replace(':', '_');
        int n = counters.merge(c.id(), 1, Integer::sum);
        String identifier = NAMESPACE + ":" + (c.kind().equals("block") ? "b_" : "") + shortName + "_" + n;
        JsonObject definition = definition(c, identifier, json);
        String file = (c.kind().equals("block") ? "blocks/" : "items/") + identifier.substring(NAMESPACE.length() + 1) + ".json";
        variants.add(new Variant(identifier, c.kind(), file, c.id(), role, path, label, value, json, definition));
    }

    /** The whole item or block file of a variant. */
    private JsonObject definition(Component c, String identifier, JsonElement json) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject menu = new JsonObject();
        menu.addProperty("category", c.kind().equals("item") ? "items" : "construction");
        description.add("menu_category", menu);
        JsonObject components = new JsonObject();
        fixtures.requires(c.id()).entrySet().forEach(e -> components.add(e.getKey(), e.getValue().deepCopy()));
        fixtures.context(c.id()).entrySet().forEach(e -> description.add(e.getKey(), e.getValue().deepCopy()));
        JsonArray permutations = null;
        if (json != null) {
            switch (c.category()) {
                case "component" -> components.add(c.name(), json);
                case "description" -> {
                    if (json.isJsonObject()) {
                        description.remove("menu_category");
                        json.getAsJsonObject().entrySet().forEach(e -> description.add(e.getKey(), e.getValue()));
                    }
                }
                case "states" -> description.add("states", json);
                case "trait" -> {
                    JsonObject traits = new JsonObject();
                    traits.add(c.name(), json);
                    description.add("traits", traits);
                }
                case "permutations" -> permutations = json.isJsonArray() ? json.getAsJsonArray() : null;
                default -> throw new IllegalStateException(c.category());
            }
            if (c.category().equals("permutations") && permutations == null) {
                JsonObject body = body(c, description, components);
                body.add("permutations", json);
                return wrap(c, body);
            }
        }
        if (c.kind().equals("item") && components.isEmpty()) {
            fixtures.filler.entrySet().forEach(e -> components.add(e.getKey(), e.getValue().deepCopy()));
        }
        JsonObject body = body(c, description, components);
        if (permutations != null) {
            body.add("permutations", permutations);
        }
        return wrap(c, body);
    }

    private static JsonObject body(Component c, JsonObject description, JsonObject components) {
        JsonObject body = new JsonObject();
        body.add("description", description);
        body.add("components", components);
        return body;
    }

    private JsonObject wrap(Component c, JsonObject body) {
        JsonObject file = new JsonObject();
        file.addProperty("format_version", formatVersion);
        file.add("minecraft:" + c.kind(), body);
        return file;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Values

    record Labeled(String label, JsonElement value) {
    }

    /** The base value: every field without a documented default set to a sample (the object form), else a sample of the first form. */
    private JsonElement base(Component c) {
        JsonElement exact = fixtures.sample(c.id(), "", null);
        if (!objectBase(c)) {
            JType form = c.forms().isEmpty() ? JType.of("object") : c.forms().get(0);
            if (form.type().equals("array") && form.items() != null && form.items().type().equals("object")) {
                JsonArray array = new JsonArray();
                array.add(minimal(c, "[]"));
                return array;
            }
            if (form.type().equals("map")) {
                JsonObject map = new JsonObject();
                map.add(fixtures.mapKey(c.id(), "{}", null), sample(c, "{}", primaryType(c, c.field("{}")), c.field("{}")));
                return map;
            }
            return exact != null ? exact.deepCopy() : sample(c, "", form, null);
        }
        return minimal(c, "");
    }

    /** Whether the base is the object form (the docs' first form). */
    private static boolean objectBase(Component c) {
        return c.forms().isEmpty() || (c.forms().get(0).type().equals("object") && c.forms().get(0).label() == null);
    }

    /** An object at a path: its direct fields without a documented default (or required), with samples. */
    private JsonObject minimal(Component c, String prefix) {
        JsonElement exact = prefix.isEmpty() ? null : fixtures.sample(c.id(), prefix, null);
        if (exact != null && exact.isJsonObject()) {
            return exact.getAsJsonObject().deepCopy();
        }
        JsonObject object = new JsonObject();
        for (Field field : c.fields()) {
            if (!parentOf(field.path()).equals(prefix) || fixtures.skip(c.id(), field.path()) != null
                || fixtures.probeOnly(c.id(), field.path())) {
                continue;
            }
            if (fixtures.fact(c.id(), field.path(), "unknown")) {
                continue; // BDS: "not present in the Schema"
            }
            boolean required = field.required() || fixtures.fact(c.id(), field.path(), "required");
            if (field.defaultValue() != null && !required) {
                continue;
            }
            if (field.defaultValue() != null && matches(primaryType(c, field), field.defaultValue())) {
                object.add(field.name(), field.defaultValue().deepCopy());
                continue;
            }
            if (field.has("any") && field.types().size() == 1 && fixtures.sample(c.id(), field.path(), field.name()) == null) {
                continue;
            }
            JsonElement value = sample(c, field.path(), primaryType(c, field), field);
            if (value != null) {
                object.add(field.name(), value);
            }
        }
        return object;
    }

    private JType primaryType(Component c, Field field) {
        if (field == null) {
            return JType.of("object");
        }
        String path = field.path();
        for (String expected : List.of("array", "object")) {
            if (fixtures.fact(c.id(), path, expected)) {
                for (JType type : field.types()) {
                    if (type.type().equals(expected) || (expected.equals("array") && type.type().equals("vec3"))) {
                        return type;
                    }
                }
                if (expected.equals("array")) {
                    JType items = field.types().get(0).type().equals("string") ? JType.of("string") : JType.of("any");
                    return new JType("array", items, null, null);
                }
            }
        }
        boolean hasChildren = c.fields().stream().anyMatch(f -> f.path().startsWith(path + ".") || f.path().startsWith(path + "[]")
            || f.path().startsWith(path + "{}"));
        if (hasChildren) {
            for (JType type : field.types()) {
                if (type.type().equals("object") || (type.items() != null && type.items().type().equals("object"))
                    || type.type().equals("map") || type.type().equals("array")) {
                    return type;
                }
            }
        }
        if (field.defaultValue() != null) {
            for (JType type : field.types()) {
                if (matches(type, field.defaultValue())) {
                    return type;
                }
            }
        }
        return field.types().get(0);
    }

    private static boolean matches(JType type, JsonElement value) {
        return switch (type.type()) {
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "integer", "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "array", "vec3" -> value.isJsonArray();
            case "object", "map" -> value.isJsonObject();
            default -> false;
        };
    }

    /** One valid looking value of a type at a path. */
    private JsonElement sample(Component c, String path, JType type, Field field) {
        field = withFeedback(c, field);
        String name = field == null ? null : field.name();
        JsonElement exact = fixtures.sample(c.id(), path, null);
        if (exact != null && (matches(type, exact) || type.type().equals("any"))) {
            return exact.deepCopy();
        }
        JsonElement byName = fixtures.sample("", "", name);
        if (byName != null && (matches(type, byName) || type.type().equals("any"))) {
            return byName.deepCopy();
        }
        List<JsonElement> enums = field == null ? List.of() : field.enumValues();
        return switch (type.type()) {
            case "boolean" -> new JsonPrimitive(true);
            case "integer" -> new JsonPrimitive(clamp(field, 1));
            case "number" -> field != null && !enums.isEmpty() && enums.get(0).getAsJsonPrimitive().isNumber() ? enums.get(0)
                : new JsonPrimitive(clampDouble(field, 1.5));
            case "string" -> !enums.isEmpty() ? enums.get(0) : field != null && !field.examples().isEmpty()
                && field.examples().get(0).isJsonPrimitive() ? field.examples().get(0) : new JsonPrimitive("paletter_a");
            case "vec3" -> array(1, 2, 3);
            case "array" -> {
                JsonArray array = new JsonArray();
                JType items = type.items() == null ? JType.of("any") : type.items();
                if (items.type().equals("object")) {
                    array.add(minimal(c, path + "[]"));
                } else if (!items.type().equals("any")) {
                    int count = fixtures.facts(c.id(), path).stream().filter(f -> f.startsWith("minItems ")).mapToInt(f -> Integer.parseInt(f.substring(9))).max().orElse(1);
                    List<JsonElement> listed = field == null ? List.of() : field.enumValues();
                    for (int i = 0; i < count; i++) {
                        JsonElement element = i < listed.size() ? listed.get(i) : sample(c, path + "[]", items, enumField(field));
                        if (element == null) {
                            break;
                        }
                        if (i > 0 && element.equals(array.get(i - 1))) {
                            element = element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() ? new JsonPrimitive(!element.getAsBoolean())
                                : element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() ? new JsonPrimitive(element.getAsLong() + i)
                                : new JsonPrimitive(element.getAsString() + "_" + i);
                        }
                        array.add(element);
                    }
                }
                yield array;
            }
            case "map" -> {
                JsonObject map = new JsonObject();
                JType items = type.items() == null ? JType.of("string") : type.items();
                JsonElement element = items.type().equals("object") ? minimal(c, path + "{}") : sample(c, path + "{}", items, null);
                map.add(fixtures.mapKey(c.id(), path + "{}", name), element);
                yield map;
            }
            case "object" -> minimal(c, path);
            default -> null;
        };
    }

    /** A field carrying only the enum values (for list elements). */
    private static Field enumField(Field field) {
        return field == null ? null : new Field(field.path() + "[]", List.of(), null, false, field.enumValues(), null, null, List.of(), "");
    }

    /** The values a field (or the whole value, path "") takes in its variants, for one JSON type. */
    private List<Labeled> values(Component c, String path, JType type, Field field) {
        field = withFeedback(c, field);
        List<Labeled> values = new ArrayList<>();
        List<JsonElement> enums = field == null ? List.of() : field.enumValues();
        JsonElement exact = fixtures.sample(c.id(), path, null);
        switch (type.type()) {
            case "boolean" -> {
                values.add(new Labeled("true", new JsonPrimitive(true)));
                values.add(new Labeled("false", new JsonPrimitive(false)));
            }
            case "integer", "number" -> {
                boolean integer = type.type().equals("integer");
                Set<Double> numbers = new LinkedHashSet<>();
                enums.stream().filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()).forEach(e -> numbers.add(e.getAsDouble()));
                if (field != null && field.defaultValue() != null && field.defaultValue().isJsonPrimitive()
                    && field.defaultValue().getAsJsonPrimitive().isNumber()) {
                    numbers.add(field.defaultValue().getAsDouble());
                }
                if (enums.isEmpty()) {
                    for (double d : integer ? new double[]{0, 1, 2, 7, 100, -1} : new double[]{0, 0.5, 1, 2.25, 30, -1}) {
                        numbers.add(d);
                    }
                }
                if (field != null && field.range() != null) {
                    if (field.range().min() != null) {
                        numbers.add(field.range().min());
                        numbers.add(field.range().min() - 1);
                    }
                    if (field.range().max() != null) {
                        numbers.add(field.range().max());
                        numbers.add(field.range().max() + 1);
                    }
                }
                for (double d : numbers) {
                    String label = (d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
                    values.add(new Labeled(label, integer || d == Math.rint(d) && integer ? new JsonPrimitive((long) d) : number(d)));
                }
            }
            case "string" -> {
                List<JsonElement> strings = new ArrayList<>();
                if (exact != null && exact.isJsonPrimitive()) {
                    strings.add(exact);
                }
                strings.addAll(enums.stream().limit(16).toList());
                if (enums.isEmpty()) {
                    if (field != null) {
                        field.examples().stream().filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()).limit(2).forEach(strings::add);
                    }
                    JsonElement sample = sample(c, path, type, field);
                    if (sample != null && sample.isJsonPrimitive()) {
                        strings.add(sample);
                    }
                    // a second valid value for references (blocks, items, entities, textures), else a free string
                    JsonElement alternative = fixtures.alternative(c.id(), path, field == null ? null : field.name());
                    strings.add(alternative != null ? alternative : new JsonPrimitive("paletter_b"));
                }
                new LinkedHashSet<>(strings).forEach(s -> values.add(new Labeled(s.getAsString(), s)));
            }
            case "vec3" -> {
                values.add(new Labeled("1,2,3", array(1, 2, 3)));
                values.add(new Labeled("-4,5.5,0", arrayOf(number(-4), number(5.5), number(0))));
                values.add(new Labeled("0,0,0", array(0, 0, 0)));
                values.add(new Labeled("16,16,16", array(16, 16, 16)));
            }
            case "array" -> {
                JType items = type.items() == null ? JType.of("any") : type.items();
                values.add(new Labeled("[]", new JsonArray()));
                switch (items.type()) {
                    case "object" -> {
                        JsonArray one = new JsonArray();
                        one.add(minimal(c, path + "[]"));
                        values.add(new Labeled("[1]", one));
                        JsonArray two = new JsonArray();
                        two.add(minimal(c, path + "[]"));
                        two.add(minimal(c, path + "[]"));
                        values.add(new Labeled("[2]", two));
                    }
                    case "number", "integer" -> {
                        values.add(new Labeled("[3]", array(3)));
                        values.add(new Labeled("[3,7]", array(3, 7)));
                        values.add(new Labeled("[90,180,270]", array(90, 180, 270)));
                        values.add(new Labeled("[180,270,0]", array(180, 270, 0)));
                        values.add(new Labeled("[1,2,3]", array(1, 2, 3)));
                        values.add(new Labeled("[0.5,0.25,2]", arrayOf(number(0.5), number(0.25), number(2))));
                    }
                    case "boolean" -> {
                        values.add(new Labeled("[true]", arrayOf(new JsonPrimitive(true))));
                        values.add(new Labeled("[false,true]", arrayOf(new JsonPrimitive(false), new JsonPrimitive(true))));
                    }
                    case "string" -> {
                        if (!enums.isEmpty()) {
                            for (JsonElement e : enums.stream().limit(12).toList()) {
                                values.add(new Labeled("[" + e.getAsString() + "]", arrayOf(e)));
                            }
                            if (enums.size() > 1) {
                                values.add(new Labeled("[" + enums.get(0).getAsString() + "," + enums.get(1).getAsString() + "]",
                                    arrayOf(enums.get(0), enums.get(1))));
                                JsonArray all = new JsonArray();
                                enums.forEach(all::add);
                                values.add(new Labeled("[all]", all));
                            }
                        } else {
                            JsonElement sample = sample(c, path, type, field);
                            JsonElement first = sample != null && sample.isJsonArray() && !sample.getAsJsonArray().isEmpty()
                                ? sample.getAsJsonArray().get(0) : new JsonPrimitive("paletter_a");
                            values.add(new Labeled("[1]", arrayOf(first)));
                            values.add(new Labeled("[2]", arrayOf(first, new JsonPrimitive("paletter_b"))));
                        }
                    }
                    default -> {
                        if (exact != null) {
                            values.add(new Labeled("sample", exact));
                        }
                    }
                }
            }
            case "map" -> {
                JType items = type.items() == null ? JType.of("string") : type.items();
                String key = fixtures.mapKey(c.id(), path + "{}", field == null ? null : field.name());
                values.add(new Labeled("{}", new JsonObject()));
                JsonElement element = items.type().equals("object") ? minimal(c, path + "{}") : sample(c, path + "{}", items, field);
                if (element != null) {
                    JsonObject one = new JsonObject();
                    one.add(key, element);
                    values.add(new Labeled("{1}", one));
                    JsonObject two = one.deepCopy();
                    two.add(key + "_2", items.type().equals("boolean") ? new JsonPrimitive(false) : element.deepCopy());
                    values.add(new Labeled("{2}", two));
                }
            }
            case "object" -> {
                values.add(new Labeled("{}", new JsonObject()));
                if (!path.isEmpty() || !objectBase(c)) {
                    JsonObject minimal = minimal(c, path);
                    if (!minimal.isEmpty()) {
                        values.add(new Labeled("minimal", minimal));
                    }
                }
            }
            default -> {
                if (exact != null) {
                    values.add(new Labeled("sample", exact));
                }
            }
        }
        return values;
    }

    /** The field with the numeric range BDS enforces (content log) when the docs state none. */
    private Field withFeedback(Component c, Field field) {
        if (field == null) {
            return null;
        }
        Double min = field.range() == null ? null : field.range().min();
        Double max = field.range() == null ? null : field.range().max();
        boolean changed = false;
        for (String fact : fixtures.facts(c.id(), field.path())) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("range")) {
                min = Double.parseDouble(parts[1]);
                max = Double.parseDouble(parts[2]);
                changed = true;
            } else if (parts[0].equals("max") && max == null) {
                max = Double.parseDouble(parts[1]);
                changed = true;
            } else if (parts[0].equals("min") && min == null) {
                min = Double.parseDouble(parts[1]);
                changed = true;
            }
        }
        if (!changed) {
            return field;
        }
        return new Field(field.path(), field.types(), field.defaultValue(), field.required(), field.enumValues(),
            new Model.Range(min, max, "BDS content log"), field.minFormatVersion(), field.examples(), field.doc());
    }

    private long clamp(Field field, long value) {
        if (field != null && field.range() != null) {
            if (field.range().min() != null && value < field.range().min()) {
                return (long) Math.ceil(field.range().min());
            }
            if (field.range().max() != null && value > field.range().max()) {
                return (long) Math.floor(field.range().max());
            }
        }
        return value;
    }

    private double clampDouble(Field field, double value) {
        if (field != null && field.range() != null) {
            if (field.range().min() != null && value < field.range().min()) {
                return field.range().min();
            }
            if (field.range().max() != null && value > field.range().max()) {
                return field.range().max();
            }
        }
        return value;
    }

    private static JsonPrimitive number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
    }

    private static JsonArray array(double... numbers) {
        JsonArray array = new JsonArray();
        for (double d : numbers) {
            array.add(number(d));
        }
        return array;
    }

    private static JsonArray arrayOf(JsonElement... elements) {
        JsonArray array = new JsonArray();
        for (JsonElement e : elements) {
            array.add(e.deepCopy());
        }
        return array;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Paths: "a.b", "a[]" (first element), "a{}" (first value)

    public static String parentOf(String path) {
        if (path.endsWith("[]") || path.endsWith("{}")) {
            return path.substring(0, path.length() - 2);
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    /** Path tokens: keys, "[]" and "{}". */
    public static List<String> tokens(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if ((ch == '[' || ch == '{') && i + 1 < path.length() && path.charAt(i + 1) == (ch == '[' ? ']' : '}')) {
                if (!key.isEmpty()) {
                    tokens.add(key.toString());
                    key.setLength(0);
                }
                tokens.add(ch == '[' ? "[]" : "{}");
                i++;
            } else if (ch == '.') {
                if (!key.isEmpty()) {
                    tokens.add(key.toString());
                    key.setLength(0);
                }
            } else {
                key.append(ch);
            }
        }
        if (!key.isEmpty()) {
            tokens.add(key.toString());
        }
        return tokens;
    }

    static JsonElement get(JsonElement root, String path) {
        JsonElement current = root;
        for (String token : tokens(path)) {
            if (current == null) {
                return null;
            }
            if (token.equals("[]")) {
                current = current.isJsonArray() && !current.getAsJsonArray().isEmpty() ? current.getAsJsonArray().get(0) : null;
            } else if (token.equals("{}")) {
                current = current.isJsonObject() && !current.getAsJsonObject().isEmpty()
                    ? current.getAsJsonObject().entrySet().iterator().next().getValue() : null;
            } else {
                current = current.isJsonObject() ? current.getAsJsonObject().get(token) : null;
            }
        }
        return current;
    }

    static boolean remove(JsonElement root, String path) {
        List<String> tokens = tokens(path);
        JsonElement parent = get(root, String.join(".", tokens.subList(0, tokens.size() - 1)).replace(".[]", "[]").replace(".{}", "{}"));
        String last = tokens.get(tokens.size() - 1);
        if (parent != null && parent.isJsonObject() && parent.getAsJsonObject().has(last)) {
            parent.getAsJsonObject().remove(last);
            return true;
        }
        return false;
    }

    /** A copy of the base with the value at the path (containers on the way created from the minimal shapes). */
    private JsonElement with(Component c, JsonElement base, String path, JsonElement value) {
        if (path.isEmpty()) {
            return value.deepCopy();
        }
        List<String> tokens = tokens(path);
        JsonElement root = base.deepCopy();
        if (tokens.get(0).equals("[]") && !root.isJsonArray()) {
            root = new JsonArray();
        }
        if (tokens.get(0).equals("{}") && !root.isJsonObject()) {
            root = new JsonObject();
        }
        if (!tokens.get(0).equals("[]") && !tokens.get(0).equals("{}") && !root.isJsonObject()) {
            root = minimal(c, "");
        }
        JsonElement current = root;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean last = i == tokens.size() - 1;
            String here = prefix + (token.equals("[]") || token.equals("{}") || prefix.isEmpty() ? token : "." + token);
            JsonElement next;
            if (token.equals("[]")) {
                JsonArray array = current.getAsJsonArray();
                if (last) {
                    if (array.isEmpty()) {
                        array.add(value.deepCopy());
                    } else {
                        array.set(0, value.deepCopy());
                    }
                    return root;
                }
                if (array.isEmpty() || !array.get(0).isJsonObject()) {
                    if (array.isEmpty()) {
                        array.add(container(c, here, tokens.get(i + 1)));
                    } else {
                        array.set(0, container(c, here, tokens.get(i + 1)));
                    }
                }
                next = array.get(0);
            } else if (token.equals("{}")) {
                JsonObject map = current.getAsJsonObject();
                String key = map.isEmpty() ? fixtures.mapKey(c.id(), here, null) : map.entrySet().iterator().next().getKey();
                if (last) {
                    map.add(key, value.deepCopy());
                    return root;
                }
                if (!map.has(key) || map.get(key).isJsonPrimitive()) {
                    map.add(key, container(c, here, tokens.get(i + 1)));
                }
                next = map.get(key);
            } else {
                if (!current.isJsonObject()) {
                    return null;
                }
                JsonObject object = current.getAsJsonObject();
                if (last) {
                    for (List<String> group : fixtures.exclusive()) {
                        if (group.contains(token)) {
                            group.stream().filter(other -> !other.equals(token)).forEach(object::remove);
                        }
                    }
                    object.add(token, value.deepCopy());
                    fixtures.together(c.id(), path).entrySet().forEach(e -> object.add(e.getKey(), e.getValue().deepCopy()));
                    return root;
                }
                String nextToken = tokens.get(i + 1);
                JsonElement existing = object.get(token);
                boolean fits = existing != null && (nextToken.equals("[]") ? existing.isJsonArray() : existing.isJsonObject());
                if (!fits) {
                    object.add(token, container(c, here, nextToken));
                }
                next = object.get(token);
            }
            current = next;
            prefix.setLength(0);
            prefix.append(here);
        }
        return root;
    }

    private JsonElement container(Component c, String path, String nextToken) {
        if (nextToken.equals("[]")) {
            return new JsonArray();
        }
        if (nextToken.equals("{}")) {
            return new JsonObject();
        }
        return minimal(c, path);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Output

    public void write(Path out) throws IOException {
        if (Files.exists(out)) {
            try (Stream<Path> files = Files.walk(out)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Path bp = out.resolve("behavior_pack");
        Path rp = out.resolve("resource_pack");
        for (Variant variant : variants) {
            json(bp.resolve(variant.file()), variant.definition());
        }
        json(bp.resolve("loot_tables/paletter_empty.json"), JsonParser.parseString("{\"pools\": []}"));
        json(bp.resolve("manifest.json"), JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture", "description": "Generated from data/model: every documented component field, varied",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "data", "uuid": "a1e5c0de-0000-4000-8000-00000000d002", "version": [1, 0, 0]}],
             "dependencies": [{"uuid": "%s", "version": [1, 0, 0]}]}""".formatted(BP_UUID, minEngine(formatVersion), RP_UUID)));
        json(rp.resolve("manifest.json"), JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture resources", "description": "Textures and geometry for the spec capture",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "resources", "uuid": "a1e5c0de-0000-4000-8000-00000000e002", "version": [1, 0, 0]}]}""".formatted(RP_UUID, minEngine(formatVersion))));
        json(rp.resolve("textures/terrain_texture.json"), JsonParser.parseString("""
            {"resource_pack_name": "paletter", "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
             "texture_data": {"paletter_tex": {"textures": "textures/blocks/paletter_tex"}, "paletter_tex_alt": {"textures": "textures/blocks/paletter_tex_alt"}}}"""));
        json(rp.resolve("textures/item_texture.json"), JsonParser.parseString("""
            {"resource_pack_name": "paletter", "texture_name": "atlas.items",
             "texture_data": {"paletter_icon": {"textures": "textures/items/paletter_icon"}, "paletter_icon_alt": {"textures": "textures/items/paletter_icon_alt"}}}"""));
        png(rp.resolve("textures/blocks/paletter_tex.png"), 200, 60, 60);
        png(rp.resolve("textures/blocks/paletter_tex_alt.png"), 60, 200, 60);
        png(rp.resolve("textures/items/paletter_icon.png"), 60, 60, 200);
        png(rp.resolve("textures/items/paletter_icon_alt.png"), 200, 200, 60);
        json(rp.resolve("models/blocks/paletter_cube.geo.json"), JsonParser.parseString("""
            {"format_version": "1.21.0", "minecraft:geometry": [{"description": {"identifier": "%s", "texture_width": 16, "texture_height": 16},
              "bones": [{"name": "bone_a", "pivot": [0, 0, 0], "cubes": [{"origin": [-8, 0, -8], "size": [16, 8, 16], "uv": [0, 0]}]},
                        {"name": "bone_b", "pivot": [0, 8, 0], "cubes": [{"origin": [-4, 8, -4], "size": [8, 8, 8], "uv": [0, 0]}]}]}]}""".formatted(GEO)));
        json(rp.resolve("block_culling/paletter_culling.json"), JsonParser.parseString("""
            {"format_version": "1.21.80", "minecraft:block_culling_rules": {"description": {"identifier": "paletter:culling"},
             "rules": [{"geometry_part": {"bone": "bone_a", "cube": 0, "face": "down"}, "direction": "down"}]}}"""));
        json(out.resolve("variants.json"), manifest());
        long items = variants.stream().filter(v -> v.kind().equals("item")).count();
        System.out.println(items + " items, " + (variants.size() - items) + " blocks (" + model.components().size() + " components) -> " + out);
    }

    /** {@code variants.json}: what each identifier tests. */
    public JsonArray manifest() {
        JsonArray array = new JsonArray();
        for (Variant v : variants) {
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", v.identifier());
            entry.addProperty("kind", v.kind());
            entry.addProperty("file", v.file());
            entry.addProperty("component", v.componentId());
            entry.addProperty("role", v.role());
            entry.addProperty("path", v.path());
            entry.addProperty("label", v.label());
            entry.add("value", v.value() == null ? JsonNull.INSTANCE : v.value());
            entry.add("json", v.component() == null ? JsonNull.INSTANCE : v.component());
            array.add(entry);
        }
        return array;
    }

    private static void json(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Model.GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    private static void png(Path file, int r, int g, int b) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < 16; y++) {
            raw.write(0);
            for (int x = 0; x < 16; x++) {
                raw.write(r);
                raw.write(g);
                raw.write(b);
                raw.write(255);
            }
        }
        Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            idat.write(buffer, 0, deflater.deflate(buffer));
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(16).putInt(16).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(png, "IDAT", idat.toByteArray());
        chunk(png, "IEND", new byte[0]);
        Files.createDirectories(file.getParent());
        Files.write(file, png.toByteArray());
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
