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
import org.geysermc.paletter.tools.model.docs.DocsParser;

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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates the spec capture add-on from the component model: per component a control (companions only), a base
 * (every field without a documented default set to a sample, so the definition is valid), and per field and per
 * documented form a set of variants that change only that field. The values come from one generator per JSON type
 * ({@link #values}), which asks for the edge cases likeliest to change between builds: limits and one past them, invalid
 * enum values, wrong lengths and element types, empty strings, lists and maps. Limits come only from the data (the docs'
 * ranges and {@code bds-feedback.json}); without one only safe values are generated. Each variant is its own item or
 * block, listed in {@code variants.json} so the mapping inference can tell which JSON value produced which wire NBT.
 */
public final class AddonGenerator {
    public static final String NAMESPACE = "paletter";
    public static final String BP_UUID = "a1e5c0de-0000-4000-8000-00000000d001";
    public static final String RP_UUID = "a1e5c0de-0000-4000-8000-00000000e001";
    /** The line the script of the add-on prints once it has registered its components; a capture without it did not load the script. */
    public static final String SCRIPT_MARKER = "PALETTER-COMPONENTS";
    private static final String CUSTOM_COMPONENTS = "minecraft:custom_components";

    private final Model model;
    private final Fixtures fixtures;
    private final String formatVersion;
    private final List<Variant> variants = new ArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();
    private final Map<String, Shrunk> shrunk;

    /**
     * @param identifier the item or block identifier
     * @param role       {@code control} (companions only, or the minimal accepted pair for companions that need each other),
     *                   {@code base}, {@code script} (script custom components bound, see {@link #scriptVariants}), {@code form} (a whole value of another JSON type), {@code field} (one field changed),
     *                   {@code omit} (one base field removed), {@code probe} (one key the model does not know added,
     *                   see {@link #probe}), {@code format} (the base or one field at another format version, see
     *                   {@link #atFormat})
     * @param path       the field path ("" for the whole value)
     * @param value      the JSON value at the path (null for control and omit)
     * @param component  the component JSON value as written (null for a control without the component)
     * @param format     the {@code format_version} of the file when it is not the generator's (the format sweep), else null
     * @param linked     the other fields this variant sets because BDS ties them to the varied one ({@link #link}), else empty
     * @param companion  the definition of another item or block a link needs (a block with the item's own identifier), else null
     */
    public record Variant(String identifier, String kind, String file, String componentId, String role, String path, String label,
                          JsonElement value, JsonElement component, JsonObject definition, String format, List<String> linked,
                          JsonObject companion) {
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
        this(model, fixtures, formatVersion, Map.of());
    }

    /**
     * @param shrunk the variants one BDS build rejected, shrunk until it accepted them (by {@link Shrunk#key}): they
     *               replace the generated ones, and the other variants of a component with a shrunk base are built on it
     */
    public AddonGenerator(Model model, Fixtures fixtures, String formatVersion, Map<String, Shrunk> shrunk) {
        this(model, fixtures, formatVersion, shrunk, true);
    }

    private AddonGenerator(Model model, Fixtures fixtures, String formatVersion, Map<String, Shrunk> shrunk, boolean standard) {
        // The schema (if the fixtures have one) is read for the format version the add-on is made for
        this.fixtures = fixtures.forFormat(formatVersion);
        this.model = this.fixtures.apply(model);
        this.formatVersion = formatVersion;
        this.shrunk = Map.copyOf(shrunk);
        if (standard) {
            generate();
        }
    }

    /**
     * A variant one BDS build rejected because of a member it does not vary: a base or control with the members the
     * content log named (or the one field whose omission the build accepted) removed, or a field variant with the base
     * members the log named removed. {@code spec-capture} finds them ({@code SpecCapture.shrink}); the variant keeps its
     * identifier, and the capture's {@code variants.json} its JSON and the label {@code <label> without a.b, c}.
     *
     * @param role    {@code base}, {@code control} or {@code field}
     * @param path    the varied field ({@code ""} for a base or control)
     * @param label   the label of the variant as generated
     * @param json    the component's JSON BDS is given instead
     * @param dropped the paths removed from the generated one
     */
    public record Shrunk(String role, String componentId, String path, String label, JsonElement json, List<String> dropped) {
        private static final String WITHOUT = " without ";

        public String key() {
            return key(role, componentId, path, label);
        }

        public static String key(String role, String componentId, String path, String label) {
            return role + " " + componentId + " " + path + " " + label;
        }

        /** The label a variant was generated with ({@code base} for {@code base without title}). */
        public static String generatedLabel(String label) {
            int at = label.indexOf(WITHOUT);
            return at < 0 ? label : label.substring(0, at);
        }

        String shrunkLabel() {
            return label + WITHOUT + String.join(", ", dropped);
        }

        /** The shrunk variants of a capture, from its {@code variants.json}. */
        public static Map<String, Shrunk> of(JsonArray manifest) {
            Map<String, Shrunk> shrunk = new LinkedHashMap<>();
            for (JsonElement e : manifest) {
                JsonObject v = e.getAsJsonObject();
                String label = v.get("label").getAsString();
                int at = label.indexOf(WITHOUT);
                if (at >= 0) {
                    Shrunk s = new Shrunk(v.get("role").getAsString(), v.get("component").getAsString(), v.get("path").getAsString(),
                        label.substring(0, at), v.get("json"), List.of(label.substring(at + WITHOUT.length()).split(", ")));
                    shrunk.put(s.key(), s);
                }
            }
            return shrunk;
        }
    }

    /**
     * A generator without the standard variants, for the probes of {@code spec-capture}: the caller adds the variants it
     * wants with {@link #probe} and {@link #atFormat}.
     */
    public static AddonGenerator empty(Model model, Fixtures fixtures, String formatVersion) {
        return new AddonGenerator(model, fixtures, formatVersion, Map.of(), false);
    }

    /** The model the variants are generated from: the docs with the errata, the types and the keys BDS gave applied. */
    public Model model() {
        return model;
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
            variant(c, "control", "", "control", null, control(c));
            if (c.name().equals(CUSTOM_COMPONENTS)) {
                scriptVariants(c); // no fields: the docs model's form is not one of the two BDS takes
                continue;
            }
            Shrunk shrunkBase = shrunk.get(Shrunk.key("base", c.id(), "", "base"));
            JsonElement base = shrunkBase != null ? shrunkBase.json().deepCopy() : base(c);
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
                            String label = type + ":" + value.label;
                            variant(c, "field", field.path(), label, value.value, json);
                            Links links = link(c, json, field.path());
                            if (links != null) {
                                variant(c, "field", field.path(), label + LINKED + String.join(", ", links.paths()), value.value,
                                    links.json(), null, links);
                            }
                        }
                    }
                }
            }
            scriptVariants(c); // the placers: with a script component that has onUseOn
        }
    }

    /**
     * Adds the variants of {@code scriptComponents} for a component (role {@code script}). An object value is a map of the item's or
     * block's own members (components, {@code permutations}): the new form of {@code custom_components}, or a placer's variant that
     * adds a script component next to the placer. An array is the old form, which stays under the component's name. The other roles
     * keep the value under the component's name whatever its shape (the format sweep's {@code {}} for {@code custom_components}).
     */
    private void scriptVariants(Component c) {
        for (JsonObject entry : fixtures.scriptVariants(c.id())) {
            JsonElement json = entry.get("json");
            String format = entry.has("format") ? entry.get("format").getAsString() : null;
            variant(c, "script", "", json.toString(), null, json, format);
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
        variant(c, role, path, label, value, json, null);
    }

    private Variant variant(Component c, String role, String path, String label, JsonElement value, JsonElement json, String format) {
        return variant(c, role, path, label, value, json, format, null);
    }

    private Variant variant(Component c, String role, String path, String label, JsonElement value, JsonElement json, String format,
                            Links links) {
        Shrunk shrunk = this.shrunk.get(Shrunk.key(role, c.id(), path, label));
        if (shrunk != null) {
            label = shrunk.shrunkLabel();
            json = shrunk.json().deepCopy();
        }
        String shortName = c.id().replaceFirst("^(item|block):", "").replace("minecraft:", "").replace("trait:", "trait_").replace(':', '_');
        int n = counters.merge(c.id(), 1, Integer::sum);
        String identifier = NAMESPACE + ":" + (c.kind().equals("block") ? "b_" : "") + shortName + "_" + n;
        JsonObject companion = null;
        if (links != null) {
            for (Map.Entry<String, String> sameIdentifier : links.identifiers().entrySet()) {
                set(json, sameIdentifier.getKey(), new JsonPrimitive(identifier));
                if (!sameIdentifier.getValue().equals(c.kind())) {
                    companion = companion(sameIdentifier.getValue(), identifier, format == null ? formatVersion : format);
                }
            }
        }
        JsonObject definition = definition(c, identifier, json, format == null ? formatVersion : format, role.equals("script"));
        String file = (c.kind().equals("block") ? "blocks/" : "items/") + identifier.substring(NAMESPACE.length() + 1) + ".json";
        Variant variant = new Variant(identifier, c.kind(), file, c.id(), role, path, label, value, json, definition, format,
            links == null ? List.of() : links.paths(), companion);
        variants.add(variant);
        return variant;
    }

    /** What a linked variant's label adds to the label of the variant it links ({@code integer:7 linked damage_chance.min}). */
    public static final String LINKED = " linked ";

    /**
     * The fields BDS ties to a field, from what its content log said in the last capture ({@code bds-feedback.json}):
     * <ul>
     *     <li>{@code order <low> <high>} on an object ("max value cannot be lower than min value"): a variant of one of the
     *     two sets the other to its far limit (the low one to its lowest value, the high one to its highest; without a
     *     known limit to the varied value), so every value of the varied one keeps the order;</li>
     *     <li>{@code conflicts <path> '<value>'} on a field (BDS refused its variants for the value another field has in them,
     *     "Cannot use bone visibility feature with: minecraft:geometry.full_block"): the other field takes its second valid
     *     value ({@code fixtures.json} {@code alternatives}), unless the variant has it already;</li>
     *     <li>{@code identifier <path> <kind>} on a field (BDS wants another field to name the item or block itself, "Item
     *     identifier ... does not match the block identifier ..."): the other field gets the variant's own identifier, and
     *     the add-on a minimal {@code <kind>} with that identifier.</li>
     * </ul>
     * The variant as generated stays, so BDS keeps saying what ties the fields; the linked one is generated beside it.
     *
     * @param json the component's JSON of the variant
     * @return the linked JSON and the fields it sets, or null if BDS ties nothing to the field
     */
    private Links link(Component c, JsonElement json, String path) {
        JsonElement linked = json.deepCopy();
        List<String> paths = new ArrayList<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        String name = tokens(path).getLast();
        String parent = parentOf(path);
        for (String fact : fixtures.facts(c.id(), parent)) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("order") && (parts[1].equals(name) || parts[2].equals(name)) && !path.endsWith("[]") && !path.endsWith("{}")) {
                boolean high = parts[2].equals(name);
                String other = (parent.isEmpty() ? "" : parent + ".") + (high ? parts[1] : parts[2]);
                JsonElement limit = limit(c, other, high, get(linked, path));
                if (limit != null && set(linked, other, limit)) {
                    paths.add(other);
                }
            }
        }
        for (String fact : fixtures.facts(c.id(), path)) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("conflicts")) {
                JsonElement alternative = fixtures.alternative(c.id(), parts[1], tokens(parts[1]).getLast());
                if (alternative != null && !alternative.equals(get(linked, parts[1])) && set(linked, parts[1], alternative)) {
                    paths.add(parts[1]);
                }
            } else if (parts[0].equals("identifier") && (parentOf(parts[1]).isEmpty() || get(linked, parentOf(parts[1])) != null)) {
                identifiers.put(parts[1], parts[2]);
                paths.add(parts[1]);
            }
        }
        return paths.isEmpty() ? null : new Links(linked, List.copyOf(paths), identifiers);
    }

    /**
     * @param paths       the fields a link sets
     * @param identifiers of those, the ones that get the variant's own identifier, with the kind of content they name
     */
    private record Links(JsonElement json, List<String> paths, Map<String, String> identifiers) {
    }

    /**
     * The far limit of a number field for an order link: its lowest value ({@code low}) or its highest, from the docs or
     * the content log; without a known limit the value of the varied field, when that is a number.
     */
    private JsonElement limit(Component c, String path, boolean low, JsonElement varied) {
        Field field = withFeedback(c, c.field(path));
        Double limit = field == null || field.range() == null ? null : low ? field.range().min() : field.range().max();
        if (limit != null) {
            return number(limit);
        }
        return varied != null && varied.isJsonPrimitive() && varied.getAsJsonPrimitive().isNumber() ? varied.deepCopy() : null;
    }

    /** Sets a value in an existing object of the JSON; false if the object is not there. */
    private static boolean set(JsonElement root, String path, JsonElement value) {
        String parent = parentOf(path);
        JsonElement object = parent.isEmpty() ? root : get(root, parent);
        if (object == null || !object.isJsonObject() || path.endsWith("[]") || path.endsWith("{}")) {
            return false;
        }
        object.getAsJsonObject().add(tokens(path).getLast(), value.deepCopy());
        return true;
    }

    /** A minimal item or block with the given identifier, for a link that needs it to exist. */
    private static JsonObject companion(String kind, String identifier, String format) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        JsonObject menu = new JsonObject();
        menu.addProperty("category", "none");
        description.add("menu_category", menu);
        JsonObject body = new JsonObject();
        body.add("description", description);
        body.add("components", new JsonObject());
        JsonObject file = new JsonObject();
        file.addProperty("format_version", format);
        file.add("minecraft:" + kind, body);
        return file;
    }

    /**
     * The control of a component whose companions need it back ({@code geometry} and {@code material_instances}: BDS
     * refuses a block with only one of them): the component as its companion's {@code requires} entry has it, so the
     * control is the minimal accepted pair. Null for every other component (the companions alone are the control).
     */
    private JsonElement control(Component c) {
        for (String companion : fixtures.requires(c.id()).keySet()) {
            JsonObject back = fixtures.requires(c.kind() + ":" + companion);
            if (back.has(c.name())) {
                return back.get(c.name()).deepCopy();
            }
        }
        return null;
    }

    /**
     * The whole item or block file of a variant that was generated before (the {@code variants.json} of a capture: the
     * component and the JSON it was given), rebuilt as the capture's add-on had it, so the capture stays valid after the
     * model moved on.
     *
     * @param role the role of the variant: a {@code script} one holds a map of components, any other the component's own value
     * @param json the component's JSON of the variant ({@code null} for a control without the component)
     */
    public JsonObject definitionOf(String componentId, String identifier, String role, JsonElement json) {
        return definition(component(componentId), identifier, json, formatVersion, role.equals("script"));
    }

    /** Adds the base of a component, which the probes of its keys are compared with. */
    public Variant probeBase(String componentId) {
        Component c = component(componentId);
        JsonElement base = base(c);
        return variant(c, "base", "", "base", null, base, null);
    }

    /**
     * Adds a variant of the base with one key the model does not know (a candidate of the key probe) set to a trivial
     * value.
     *
     * @param path the path of the key ({@code level.key})
     * @return the variant, or null if the base cannot hold a value there
     */
    public Variant probe(String componentId, String path, JsonElement value) {
        Component c = component(componentId);
        JsonElement json = with(c, base(c), path, value);
        return json == null ? null : variant(c, "probe", path, "key:" + kind(value), value, json, null);
    }

    /**
     * The trivial values a key is probed with, one per JSON type (a boolean, an integer, a string that names nothing,
     * and the first enum value of the object that holds the key, an empty object and an empty list).
     */
    public List<JsonElement> trivialValues(String componentId, String path) {
        List<JsonElement> values = new ArrayList<>(List.of(new JsonPrimitive(true), new JsonPrimitive(1), new JsonPrimitive("paletter_a")));
        Field parent = component(componentId).field(parentOf(path));
        if (parent != null && !parent.enumValues().isEmpty() && parent.enumValues().get(0).isJsonPrimitive()) {
            values.add(parent.enumValues().get(0));
        }
        values.add(new JsonObject());
        values.add(new JsonArray());
        return values;
    }

    /** {@code boolean}, {@code integer}, {@code string}, {@code object}, {@code array}: the JSON type of a probe value. */
    public static String kind(JsonElement value) {
        if (value.isJsonObject()) {
            return "object";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean() ? "boolean" : primitive.isNumber() ? "integer" : "string";
    }

    /**
     * The base of a component in a file of another format version (the format sweep), with the fields the docs or BDS
     * say need a newer format removed, and one field set when {@code path} is not empty (to {@code value}, or to the
     * sample of the field when that is null).
     *
     * @return the variant, or null if the component itself needs a newer format or the field cannot be set
     */
    public Variant atFormat(String componentId, String format, String path, JsonElement value) {
        Component c = component(componentId);
        if (c.minFormatVersion() != null && compare(c.minFormatVersion(), format) > 0) {
            return null;
        }
        JsonElement json = base(c);
        for (Field field : c.fields()) {
            if (field.minFormatVersion() != null && compare(field.minFormatVersion(), format) > 0 && !field.path().equals(path)) {
                remove(json, field.path());
            }
        }
        if (!path.isEmpty()) {
            Field field = c.field(path);
            value = value != null || field == null ? value : sample(c, path, primaryType(c, field), field);
            json = value == null ? null : with(c, json, path, value);
            if (json == null) {
                return null;
            }
        }
        return variant(c, "format", path, "format " + format + (path.isEmpty() ? "" : " " + kind(value)), path.isEmpty() ? null : value, json, format);
    }

    /** Compares two format versions ({@code 1.21.120} against {@code 1.26.0}) by their numbers. */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private Component component(String id) {
        return model.components().stream().filter(x -> x.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no component " + id));
    }

    /** The whole item or block file of a variant. */
    private JsonObject definition(Component c, String identifier, JsonElement json, String format, boolean script) {
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
                case "component" -> {
                    if (script && json.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                            if (e.getKey().equals("permutations")) {
                                permutations = e.getValue().getAsJsonArray();
                            } else {
                                components.add(e.getKey(), e.getValue());
                            }
                        }
                    } else {
                        components.add(c.name(), json);
                    }
                }
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
                return wrap(c, body, format);
            }
        }
        if (c.kind().equals("item") && components.isEmpty()) {
            fixtures.filler.entrySet().forEach(e -> components.add(e.getKey(), e.getValue().deepCopy()));
        }
        JsonObject body = body(c, description, components);
        if (permutations != null) {
            body.add("permutations", permutations);
        }
        return wrap(c, body, format);
    }

    private static JsonObject body(Component c, JsonObject description, JsonObject components) {
        JsonObject body = new JsonObject();
        body.add("description", description);
        body.add("components", components);
        return body;
    }

    private static JsonObject wrap(Component c, JsonObject body, String format) {
        JsonObject file = new JsonObject();
        file.addProperty("format_version", format);
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
            if (fixtures.fact(c.id(), field.path(), "nonEmpty") && c.fields().stream().noneMatch(f -> parentOf(f.path()).equals(field.path()))) {
                continue; // BDS: "must define at least one ..." and the model knows no member to define
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
            // a sample given for the path names its own type (an item as a string where the docs list objects first)
            JsonElement given = fixtures.sample(c.id(), field.path(), null);
            JsonElement value = given != null && field.types().stream().anyMatch(t -> matches(t, given)) ? given.deepCopy()
                : sample(c, field.path(), primaryType(c, field), field);
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
        List<JsonElement> enums = field == null ? List.of() : field.enumValues().isEmpty() && type.type().equals("string")
            ? bdsStrings(c, path) : field.enumValues();
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

    /** Whether the field is a member of the object form of a vector (a field that is also a list of numbers). */
    private static boolean vectorMember(Component c, String path) {
        Field parent = path.contains(".") ? c.field(parentOf(path)) : null;
        return parent != null && parent.types().stream().anyMatch(t -> t.type().equals("vec3") || (t.type().equals("array")
            && t.items() != null && List.of("number", "integer").contains(t.items().type())));
    }

    /** Whether the model has fields below this path. */
    private static boolean container(Component c, String path) {
        return c.fields().stream().anyMatch(f -> !f.path().equals(path) && (f.path().startsWith(path + ".")
            || f.path().startsWith(path + "[]") || f.path().startsWith(path + "{}")));
    }

    /** A field carrying only the enum values (for list elements). */
    private static Field enumField(Field field) {
        return field == null ? null : new Field(field.path() + "[]", List.of(), null, false, field.enumValues(), null, null, List.of(), "");
    }

    /**
     * At most this many values per field and JSON type. Each generator lists first the values likeliest to change between
     * builds (limits and one past them, invalid values), then the typical ones.
     */
    static final int MAX_VALUES = 32;

    private final Map<String, String> cut = new java.util.TreeMap<>();

    /** An identifier nothing defines. */
    static final String UNKNOWN_IDENTIFIER = "minecraft:paletter_unknown";

    /**
     * The values a field (or the whole value, path "") takes in its variants, for one JSON type: one generator per type,
     * driven by the type and the limits the data gives (the docs' ranges and enum values, {@code bds-feedback.json}). With
     * no known limit only safe values are generated; the next capture's feedback gives the limit, and the next generation
     * its boundary and one past it.
     */
    private List<Labeled> values(Component c, String path, JType type, Field field) {
        Model.Range docsRange = field == null ? null : field.range();
        field = withFeedback(c, field);
        List<Labeled> values = switch (type.type()) {
            case "boolean" -> List.of(new Labeled("true", new JsonPrimitive(true)), new Labeled("false", new JsonPrimitive(false)));
            case "integer", "number" -> numbers(c, path, field, docsRange, type.type().equals("integer"));
            case "string" -> strings(c, path, type, field);
            case "vec3" -> vectors(c, path, field);
            case "array" -> lists(c, path, type, field);
            case "map" -> maps(c, path, type, field);
            case "object" -> objects(c, path, field);
            default -> sampleOnly(c, path);
        };
        // one variant per value and per label (a value generated twice keeps its first label)
        Map<String, Labeled> distinct = new LinkedHashMap<>();
        Set<String> labels = new java.util.HashSet<>();
        values.stream().filter(v -> labels.add(v.label)).forEach(v -> distinct.putIfAbsent(v.value.toString(), v));
        List<Labeled> all = new ArrayList<>(distinct.values());
        if (all.size() > MAX_VALUES) {
            List<String> dropped = all.subList(MAX_VALUES, all.size()).stream().map(v -> v.label).toList();
            cut.put(c.id() + " " + path + " (" + type.type() + ")", "kept " + MAX_VALUES + " of " + all.size() + ", cut: "
                + String.join(", ", dropped.subList(0, Math.min(dropped.size(), 12))) + (dropped.size() > 12 ? ", ... (" + (dropped.size() - 12) + " more)" : ""));
        }
        return all.stream().limit(MAX_VALUES).toList();
    }

    /** The values cut at {@link #MAX_VALUES} (a field and JSON type, how many were kept of how many, which were cut): no variant has them. */
    public List<String> cutValues() {
        return cut.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
    }

    private List<Labeled> sampleOnly(Component c, String path) {
        JsonElement exact = fixtures.sample(c.id(), path, null);
        return exact == null ? List.of() : List.of(new Labeled("sample", exact));
    }

    /**
     * Numbers: the limits (BDS's where its content log gave them, and the docs' where they differ: BDS decides which holds)
     * and one past each, the enum values and one the enum does not list, the default, off-step values
     * where the inference found a step, a non-integer for an integer field, then zero, a negative and a few typical values
     * (for a member of a vector's object form also what its list form takes).
     */
    private List<Labeled> numbers(Component c, String path, Field field, Model.Range docsRange, boolean integer) {
        Set<Double> numbers = new LinkedHashSet<>();
        Model.Range range = field == null ? null : field.range();
        // the field's range is BDS's where the content log gave one; the docs' own bounds follow (the same ones add nothing)
        for (Model.Range limits : java.util.Arrays.asList(range, docsRange)) {
            if (limits != null && limits.min() != null) {
                numbers.add(limits.min());
                numbers.add(limits.min() - 1);
            }
            if (limits != null && limits.max() != null) {
                numbers.add(limits.max());
                numbers.add(limits.max() + 1);
            }
        }
        // The limits of the content log that the schema contradicts: BDS decides which holds, so both are tested
        for (String fact : fixtures.disputed(c.id(), path)) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("range") || parts[0].equals("min")) {
                numbers.add(Double.parseDouble(parts[1]));
                numbers.add(Double.parseDouble(parts[1]) - 1);
            }
            if (parts[0].equals("range") || parts[0].equals("max")) {
                double max = Double.parseDouble(parts[parts.length - 1]);
                numbers.add(max);
                numbers.add(max + 1);
            }
        }
        List<Double> listed = field == null ? List.of() : field.enumValues().stream()
            .filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()).map(JsonElement::getAsDouble).toList();
        numbers.addAll(listed);
        if (!listed.isEmpty()) {
            numbers.add(listed.stream().mapToDouble(d -> d).max().orElseThrow() + 1);
        }
        if (field != null && field.defaultValue() != null && field.defaultValue().isJsonPrimitive()
            && field.defaultValue().getAsJsonPrimitive().isNumber()) {
            numbers.add(field.defaultValue().getAsDouble());
        }
        Double step = factNumber(c, path, "step");
        if (step != null) {
            numbers.add(step / 2);
            numbers.add(step * 1.5);
        }
        if (field == null || field.enumValues().isEmpty()) {
            for (double d : integer ? new double[]{0, 1, 2, 7, 100, -1} : new double[]{0, 0.5, 1, 2.25, 30, -1}) {
                numbers.add(d);
            }
            if (vectorMember(c, path)) {
                for (double d : new double[]{90, -90, 450, 45}) {
                    numbers.add(d);
                }
            }
        }
        List<Labeled> values = new ArrayList<>();
        for (double d : numbers) {
            if (integer && d != Math.rint(d)) {
                continue; // an integer field gets whole numbers here, its one non-integer below: a label always says what is sent
            }
            values.add(new Labeled(d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d), integer ? new JsonPrimitive((long) d) : number(d)));
        }
        if (integer) {
            double fraction = (range != null && range.min() != null ? range.min() : 1) + 0.5;
            values.add(new Labeled(String.valueOf(fraction), new JsonPrimitive(fraction)));
        }
        return values;
    }

    /**
     * Strings: every enum value and one it does not list; without an enum the sample, the docs' examples, a second valid
     * value, the empty string, and for an identifier sample the sample with its namespace toggled (bare vs namespaced) and
     * an identifier nothing defines. A Molang field (its docs say so) also gets a constant beside the expression.
     */
    private List<Labeled> strings(Component c, String path, JType type, Field field) {
        // The probes come first, so that MAX_VALUES never cuts one
        List<JsonElement> strings = new ArrayList<>(fixtures.probeValues(c.id(), path));
        JsonElement exact = fixtures.sample(c.id(), path, null);
        if (exact != null && exact.isJsonPrimitive()) {
            strings.add(exact);
        }
        List<JsonElement> enums = new ArrayList<>(field == null || field.enumValues().isEmpty() ? bdsStrings(c, path) : field.enumValues());
        // Values of the content log the schema does not list: tested as well
        for (String fact : fixtures.disputed(c.id(), path)) {
            java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("'([^']*)'").matcher(fact);
            while (fact.startsWith("values ") && quoted.find()) {
                JsonPrimitive value = new JsonPrimitive(quoted.group(1));
                if (!enums.contains(value)) {
                    enums.add(value);
                }
            }
        }
        if (!enums.isEmpty()) {
            strings.add(new JsonPrimitive(NAMESPACE + "_invalid"));
            strings.addAll(enums);
        } else {
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
            strings.add(new JsonPrimitive(""));
            if (sample != null && sample.isJsonPrimitive() && sample.getAsString().matches("[a-z0-9_./]+(:[a-z0-9_./]+)?")) {
                // an identifier: bare vs namespaced, and one of the vanilla namespace nothing defines (Paletter knows the
                // vanilla identifiers of each version, not those another pack defines)
                String text = sample.getAsString();
                strings.add(new JsonPrimitive(text.contains(":") ? text.substring(text.indexOf(':') + 1) : "minecraft:" + text));
                strings.add(new JsonPrimitive(UNKNOWN_IDENTIFIER));
            }
            if (field != null && field.doc().contains("Molang")) {
                strings.add(new JsonPrimitive("1"));
                strings.add(new JsonPrimitive("0"));
            }
        }
        return new LinkedHashSet<>(strings).stream().map(s -> new Labeled(s.getAsString(), s)).toList();
    }

    /**
     * The strings BDS listed for a field the docs give no values for ({@code values 'never', 'always'}; a long list BDS
     * cuts short, {@code 'none', 'eat', 'drink', ... (7 others)}, gives the ones it names).
     */
    private List<JsonElement> bdsStrings(Component c, String path) {
        List<JsonElement> strings = new ArrayList<>();
        fixtures.facts(c.id(), path).stream().filter(f -> f.startsWith("values ")).findFirst().ifPresent(f -> {
            java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("'([^']*)'").matcher(f);
            while (quoted.find()) {
                strings.add(new JsonPrimitive(quoted.group(1)));
            }
        });
        return strings;
    }

    /**
     * Vectors: the element limits the data gives (the docs' range of the field, or a {@code box} fact of BDS that bounds the
     * sum of this member and its partner) at each extreme and one past it per element, a wrong length, then typical
     * values.
     */
    private List<Labeled> vectors(Component c, String path, Field field) {
        List<Labeled> values = new ArrayList<>();
        double[][] limits = vectorLimits(c, path, field);
        if (limits != null) {
            for (double[] corner : limits) {
                values.add(vector(corner));
                for (int i = 0; i < 3; i++) {
                    double[] past = corner.clone();
                    past[i] += corner == limits[0] ? -1 : 1;
                    values.add(vector(past));
                }
            }
        }
        values.add(new Labeled("1,2", array(1, 2)));
        values.add(new Labeled("1,2,3,4", array(1, 2, 3, 4)));
        values.add(new Labeled("1,2,3", array(1, 2, 3)));
        values.add(new Labeled("-4,5.5,0", arrayOf(number(-4), number(5.5), number(0))));
        values.add(new Labeled("0,0,0", array(0, 0, 0)));
        values.add(new Labeled("16,16,16", array(16, 16, 16)));
        // Between the typical values above in every element (x -4 to 1, y 0 to 5.5, z 0 to 3: BDS took those). BDS rejects it
        // for the selection_box size and the single-form size of collision_box. It is a further distinct value of each
        // element, so that no rule over them is fitted to two points
        values.add(new Labeled("-2,1,1", array(-2, 1, 1)));
        return values;
    }

    private static Labeled vector(double[] v) {
        return new Labeled(label(v[0]) + "," + label(v[1]) + "," + label(v[2]), array(v));
    }

    private static String label(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /**
     * The lowest and highest value of a vector field per element, or null when no data gives them: the docs' range of the
     * field, or BDS's limit of the sum of two members ({@code box origin size -8,0,-8 8,24,8} on the component: "origin" +
     * "size" must be inside Vec3(-8,0,-8) and Vec3(8,24,8)), less the partner's value in the base.
     */
    private double[][] vectorLimits(Component c, String path, Field field) {
        if (field != null && field.range() != null && field.range().min() != null && field.range().max() != null) {
            double lo = field.range().min();
            double hi = field.range().max();
            return new double[][]{{lo, lo, lo}, {hi, hi, hi}};
        }
        String name = path.substring(Math.max(path.lastIndexOf('.'), path.lastIndexOf(']')) + 1);
        for (String scope = path; ; scope = parentOf(scope)) {
            for (String fact : fixtures.facts(c.id(), parentOf(scope))) {
                String[] parts = fact.split(" ");
                if (parts[0].equals("box") && (parts[1].equals(name) || parts[2].equals(name))) {
                    String partnerPath = parentOf(path).isEmpty() ? (parts[1].equals(name) ? parts[2] : parts[1])
                        : parentOf(path) + (parentOf(path).endsWith("]") ? "" : ".") + (parts[1].equals(name) ? parts[2] : parts[1]);
                    JsonElement partner = get(base(c), partnerPath);
                    double[] lo = vec(parts[3]);
                    double[] hi = vec(parts[4]);
                    for (int i = 0; i < 3; i++) {
                        double p = partner != null && partner.isJsonArray() && partner.getAsJsonArray().size() == 3
                            ? partner.getAsJsonArray().get(i).getAsDouble() : 0;
                        lo[i] -= p;
                        hi[i] -= p;
                    }
                    return new double[][]{lo, hi};
                }
            }
            if (scope.isEmpty()) {
                return null;
            }
        }
    }

    private static double[] vec(String commaSeparated) {
        return java.util.Arrays.stream(commaSeparated.split(",")).mapToDouble(Double::parseDouble).toArray();
    }

    /** The number of a BDS fact {@code <name> n} of a field ({@code step 90}), or null. */
    private Double factNumber(Component c, String path, String name) {
        return fixtures.facts(c.id(), path).stream().filter(f -> f.startsWith(name + " "))
            .map(f -> Double.parseDouble(f.substring(name.length() + 1))).findFirst().orElse(null);
    }

    /**
     * Lists: empty, one entry, two, duplicates, the length limits BDS gave and one past them ({@code maxItems},
     * {@code items}: exactly n), an element of the wrong type, then per element type: every combination of a small enum
     * (else each value alone, two and all), numbers below zero, past a full turn and off a step.
     */
    private List<Labeled> lists(Component c, String path, JType type, Field field) {
        List<Labeled> values = new ArrayList<>();
        List<JsonElement> enums = field == null ? List.of() : field.enumValues();
        JsonElement exact = fixtures.sample(c.id(), path, null);
        JType items = type.items() == null ? JType.of("any") : type.items();
        List<JsonElement> listed = enums.isEmpty() ? documentedElements(field) : enums;
        if (items.type().equals("any") && !listed.isEmpty()) {
            items = JType.of("string");
        }
        values.add(new Labeled("[]", new JsonArray()));
        JsonElement first = switch (items.type()) {
            case "object" -> minimal(c, path + "[]");
            case "number", "integer" -> new JsonPrimitive(3);
            case "boolean" -> new JsonPrimitive(true);
            case "string" -> {
                if (!listed.isEmpty()) {
                    yield listed.get(0);
                }
                JsonElement sample = sample(c, path, type, field);
                yield sample != null && sample.isJsonArray() && !sample.getAsJsonArray().isEmpty() ? sample.getAsJsonArray().get(0)
                    : new JsonPrimitive("paletter_a");
            }
            default -> null;
        };
        if (first == null) {
            if (exact != null) {
                values.add(new Labeled("sample", exact));
            }
            return values;
        }
        Double maxItems = factNumber(c, path, "maxItems");
        Double exactly = factNumber(c, path, "items");
        List<Double> counts = new ArrayList<>(java.util.Arrays.asList(maxItems, maxItems == null ? null : maxItems + 1,
            exactly == null ? null : exactly - 1, exactly == null ? null : exactly + 1));
        for (String fact : fixtures.disputed(c.id(), path)) {
            if (fact.startsWith("maxItems ")) {
                counts.add(Double.parseDouble(fact.substring(9)));
                counts.add(Double.parseDouble(fact.substring(9)) + 1);
            }
        }
        for (Double n : counts) {
            if (n != null && n > 2) {
                values.add(new Labeled("[" + n.intValue() + "x]", copies(first, n.intValue())));
            }
        }
        // Not for lists of objects: a number in one crashes BDS 1.26.63.1 while it loads the blocks (b_destructible_by_mining
        // item_specific_speeds[].item [1], b_embedded_visual material_instances [1]), so no variant of the pack is captured
        if (!items.type().equals("object")) {
            values.add(new Labeled("[wrong type]", arrayOf(items.type().equals("string") ? new JsonPrimitive(1) : new JsonPrimitive("paletter_a"))));
        }
        switch (items.type()) {
            case "object" -> {
                values.add(new Labeled("[1]", copies(first, 1)));
                values.add(new Labeled("[2]", copies(first, 2)));
                if (descriptor(c, path + "[]")) {
                    fixtures.descriptorValues().forEach(d -> values.add(new Labeled("[1] " + describe(d), arrayOf(d))));
                }
            }
            case "number", "integer" -> {
                values.add(new Labeled("[3]", array(3)));
                values.add(new Labeled("[3,7]", array(3, 7)));
                values.add(new Labeled("[90,180,270]", array(90, 180, 270)));
                values.add(new Labeled("[180,270,0]", array(180, 270, 0)));
                values.add(new Labeled("[1,2,3]", array(1, 2, 3)));
                values.add(new Labeled("[0.5,0.25,2]", arrayOf(number(0.5), number(0.25), number(2))));
                // below zero and past a full turn (an angle: wrapped, kept or refused), and a half step of 90
                values.add(new Labeled("[-90,450,720]", array(-90, 450, 720)));
                values.add(new Labeled("[45,135,-45]", array(45, 135, -45)));
                Double step = factNumber(c, path, "step");
                if (step != null) {
                    values.add(new Labeled("[" + label(step / 2) + "," + label(step) + "," + label(step * 1.5) + "]", array(step / 2, step, step * 1.5)));
                }
            }
            case "boolean" -> {
                values.add(new Labeled("[true]", arrayOf(new JsonPrimitive(true))));
                values.add(new Labeled("[false,true]", arrayOf(new JsonPrimitive(false), new JsonPrimitive(true))));
            }
            default -> {
                if (listed.size() > 1 && listed.size() <= COMBINED_ENUM) {
                    values.addAll(combinations(listed));
                } else if (!listed.isEmpty()) {
                    for (JsonElement e : listed.stream().limit(12).toList()) {
                        values.add(new Labeled("[" + e.getAsString() + "]", arrayOf(e)));
                    }
                    if (listed.size() > 1) {
                        values.add(new Labeled("[" + listed.get(0).getAsString() + "," + listed.get(1).getAsString() + "]",
                            arrayOf(listed.get(0), listed.get(1))));
                        JsonArray all = new JsonArray();
                        listed.forEach(all::add);
                        values.add(new Labeled("[all]", all));
                    }
                } else {
                    values.add(new Labeled("[1]", arrayOf(first)));
                    values.add(new Labeled("[2]", arrayOf(first, new JsonPrimitive("paletter_b"))));
                }
            }
        }
        values.add(new Labeled("[2x]", copies(first, 2)));
        return values;
    }

    /** Enum lists with at most this many values get every combination of them (trait {@code enabled_states}). */
    static final int COMBINED_ENUM = 4;

    /** Every non-empty combination of the values in their order; the one with all of them is labelled {@code [all]}. */
    private static List<Labeled> combinations(List<JsonElement> listed) {
        List<Labeled> values = new ArrayList<>();
        for (int size = 1; size <= listed.size(); size++) {
            for (int mask = 1; mask < 1 << listed.size(); mask++) {
                if (Integer.bitCount(mask) != size) {
                    continue;
                }
                JsonArray subset = new JsonArray();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < listed.size(); i++) {
                    if ((mask & 1 << i) != 0) {
                        subset.add(listed.get(i).deepCopy());
                        names.add(listed.get(i).getAsString());
                    }
                }
                values.add(new Labeled(size == listed.size() ? "[all]" : "[" + String.join(",", names) + "]", subset));
            }
        }
        return values;
    }

    private static JsonArray copies(JsonElement element, int n) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < n; i++) {
            array.add(element.deepCopy());
        }
        return array;
    }

    /**
     * Maps: empty, one entry, two (the second with the element's second valid value, {@code fixtures.json}
     * {@code alternatives}, where it has one), an entry under a key nothing defines, and one entry with each further
     * value of the element ({@code fixtures.json} {@code mapValues}).
     */
    private List<Labeled> maps(Component c, String path, JType type, Field field) {
        List<Labeled> values = new ArrayList<>();
        JType items = type.items() == null ? JType.of("string") : type.items();
        String key = fixtures.mapKey(c.id(), path + "{}", field == null ? null : field.name());
        values.add(new Labeled("{}", new JsonObject()));
        JsonElement element = items.type().equals("object") ? minimal(c, path + "{}") : sample(c, path + "{}", items, field);
        if (element != null) {
            JsonObject one = new JsonObject();
            one.add(key, element);
            values.add(new Labeled("{1}", one));
            JsonObject two = one.deepCopy();
            JsonElement alternative = fixtures.alternative(c.id(), path + "{}", null);
            two.add(key + "_2", items.type().equals("boolean") ? new JsonPrimitive(false)
                : alternative != null && matches(items, alternative) ? alternative.deepCopy() : element.deepCopy());
            values.add(new Labeled("{2}", two));
            JsonObject unknown = new JsonObject();
            unknown.add(NAMESPACE + "_unknown", element.deepCopy());
            values.add(new Labeled("{unknown key}", unknown));
            for (JsonElement other : fixtures.mapValues(c.id(), path, field == null ? null : field.name())) {
                if (matches(items, other)) {
                    JsonObject single = new JsonObject();
                    single.add(key, other.deepCopy());
                    values.add(new Labeled("{1} " + (other.isJsonPrimitive() ? other.getAsString() : other.toString()), single));
                }
            }
        }
        return values;
    }

    /**
     * Objects: empty, the minimal one, and for an object whose members the docs do not list the docs' example and the
     * example with a second entry under another key (a map: does the key reach the wire?).
     */
    private List<Labeled> objects(Component c, String path, Field field) {
        List<Labeled> values = new ArrayList<>();
        values.add(new Labeled("{}", new JsonObject()));
        if (!path.isEmpty() || !objectBase(c)) {
            JsonObject minimal = minimal(c, path);
            if (!minimal.isEmpty()) {
                values.add(new Labeled("minimal", minimal));
            }
        }
        if (descriptor(c, path)) {
            fixtures.descriptorValues().forEach(d -> values.add(new Labeled(describe(d), d.deepCopy())));
        }
        JsonObject example = field == null || container(c, path) ? null : field.examples().stream()
            .filter(e -> e.isJsonObject() && !e.getAsJsonObject().isEmpty()).map(JsonElement::getAsJsonObject).findFirst().orElse(null);
        if (example != null) {
            values.add(new Labeled("{1}", example.deepCopy()));
            JsonObject two = example.deepCopy();
            Map.Entry<String, JsonElement> first = example.entrySet().iterator().next();
            two.add(first.getKey() + "_2", first.getValue().deepCopy());
            values.add(new Labeled("{2}", two));
        }
        return values;
    }

    /** Whether the object at the path is a block descriptor: it has the members {@code name}, {@code states} and {@code tags}. */
    private static boolean descriptor(Component c, String path) {
        return List.of("name", "states", "tags").stream().allMatch(member -> c.field(path + "." + member) != null);
    }

    /** A descriptor as its variant label: the block without namespace and the states as sent, so a value's JSON type shows. */
    private static String describe(JsonObject descriptor) {
        return descriptor.get("name").getAsString().replaceFirst("^minecraft:", "") + descriptor.get("states");
    }

    /**
     * The strings a list field without enum values documents for its elements: those of its default list, else those its
     * docs text lists ("Can be a list of the following directions: "up", ...").
     */
    private static List<JsonElement> documentedElements(Field field) {
        if (field == null) {
            return List.of();
        }
        JsonElement defaults = field.defaultValue();
        if (defaults != null && defaults.isJsonArray() && !defaults.getAsJsonArray().isEmpty()
            && defaults.getAsJsonArray().asList().stream().allMatch(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())) {
            return defaults.getAsJsonArray().asList();
        }
        return DocsParser.valuesIn(field.doc()).stream().filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()).toList();
    }

    /** The field with the numeric range BDS enforces (content log): where the docs state another, BDS wins. */
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
            } else if (parts[0].equals("max")) {
                max = Double.parseDouble(parts[1]);
                changed = true;
            } else if (parts[0].equals("min")) {
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

    public static JsonElement get(JsonElement root, String path) {
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

    public static boolean remove(JsonElement root, String path) {
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
            if (variant.companion() != null) {
                String folder = variant.companion().has("minecraft:block") ? "blocks/" : "items/";
                json(bp.resolve(folder + variant.identifier().substring(NAMESPACE.length() + 1) + ".json"), variant.companion());
            }
        }
        FixedAddons.writeLootTable(bp);
        JsonObject manifest = JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture", "description": "Generated from data/model: every documented component field, varied",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "data", "uuid": "a1e5c0de-0000-4000-8000-00000000d002", "version": [1, 0, 0]}],
             "dependencies": [{"uuid": "%s", "version": [1, 0, 0]}]}""".formatted(BP_UUID, minEngine(formatVersion), RP_UUID)).getAsJsonObject();
        if (fixtures.hasScriptComponents()) {
            manifest.getAsJsonArray("modules").add(JsonParser.parseString("""
                {"type": "script", "language": "javascript", "uuid": "a1e5c0de-0000-4000-8000-00000000d003", "entry": "scripts/main.js", "version": [1, 0, 0]}"""));
            manifest.getAsJsonArray("dependencies").add(JsonParser.parseString("""
                {"module_name": "@minecraft/server", "version": "%s"}""".formatted(fixtures.scriptComponents().get("module").getAsString())));
            writeScript(bp.resolve("scripts/main.js"));
        }
        json(bp.resolve("manifest.json"), manifest);
        json(rp.resolve("manifest.json"), JsonParser.parseString("""
            {"format_version": 2, "header": {"name": "Paletter spec capture resources", "description": "Textures and geometry for the spec capture",
              "uuid": "%s", "version": [1, 0, 0], "min_engine_version": %s},
             "modules": [{"type": "resources", "uuid": "a1e5c0de-0000-4000-8000-00000000e002", "version": [1, 0, 0]}]}""".formatted(RP_UUID, minEngine(formatVersion))));
        FixedAddons.writeResources(rp);
        json(out.resolve("variants.json"), manifest());
        long items = variants.stream().filter(v -> v.kind().equals("item")).count();
        System.out.println(items + " items, " + (variants.size() - items) + " blocks (" + model.components().size() + " components) -> " + out);
    }

    /**
     * The script of the add-on: on startup it registers every component of {@code scriptComponents} with an empty function per
     * callback (a component's name and parameters never reach the wire, only which callbacks it has), then prints {@link #SCRIPT_MARKER}.
     */
    private void writeScript(Path file) throws IOException {
        JsonObject tables = scriptTables();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            // Generated by AddonGenerator from the scriptComponents of data/model/fixtures.json: do not edit.
            import { system } from "@minecraft/server";

            const tables = %s;

            system.beforeEvents.startup.subscribe(event => {
                const registries = { block: event.blockComponentRegistry, item: event.itemComponentRegistry };
                let count = 0;
                for (const [kind, table] of Object.entries(tables)) {
                    for (const [name, callbacks] of Object.entries(table)) {
                        registries[kind].registerCustomComponent(name, Object.fromEntries(callbacks.map(callback => [callback, () => {}])));
                        count++;
                    }
                }
                console.warn("%s " + count);
            });
            """.formatted(Model.GSON.toJson(tables), SCRIPT_MARKER), StandardCharsets.UTF_8);
    }

    private JsonObject scriptTables() {
        JsonObject tables = new JsonObject();
        tables.add("block", fixtures.scriptComponents().get("block"));
        tables.add("item", fixtures.scriptComponents().get("item"));
        return tables;
    }

    /** The line the script prints when it has registered every component of the table (null without a script). */
    public String scriptMarker() {
        if (!fixtures.hasScriptComponents()) {
            return null;
        }
        return SCRIPT_MARKER + " " + scriptTables().entrySet().stream().mapToInt(e -> e.getValue().getAsJsonObject().size()).sum();
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
            if (v.format() != null) {
                entry.addProperty("format", v.format());
            }
            if (!v.linked().isEmpty()) {
                JsonArray linked = new JsonArray();
                v.linked().forEach(linked::add);
                entry.add("linked", linked);
            }
            array.add(entry);
        }
        return array;
    }

    private static void json(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Model.GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
