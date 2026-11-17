package org.geysermc.paletter.tools.model.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.Model.JType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the spec capture add-on from the component model: per component a control (companions only), a base
 * (every field without a documented default set to a sample, so the definition is valid), and per field and per
 * documented form a set of variants that change only that field. The values come from one generator per JSON type
 * ({@link VariantValues}), which asks for the edge cases likeliest to change between builds: limits and one past them, invalid
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
    final Fixtures fixtures;
    final String formatVersion;
    private final VariantValues values;
    private final List<Variant> variants = new ArrayList<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();
    private final Map<String, Shrunk> shrunk;
    final boolean standard;

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
        this.values = new VariantValues(this.fixtures);
        this.formatVersion = formatVersion;
        this.shrunk = Map.copyOf(shrunk);
        this.standard = standard;
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

    /** Writes the add-on to {@code out}, see {@link AddonWriter}. */
    public void write(Path out) throws IOException {
        new AddonWriter(this).write(out);
    }

    /** {@code variants.json}: what each identifier tests. */
    public JsonArray manifest() {
        return new AddonWriter(this).manifest();
    }

    /** The line the script prints when it has registered every component of the table (null without a script). */
    public String scriptMarker() {
        return new AddonWriter(this).scriptMarker();
    }

    /** The values cut at {@link VariantValues#MAX_VALUES}, see {@link VariantValues#cutValues}. */
    public List<String> cutValues() {
        return values.cutValues();
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
            JsonElement base = shrunkBase != null ? shrunkBase.json().deepCopy() : values.base(c);
            variant(c, "base", "", "base", null, base);
            // Whole value forms (shorthands, alternative representations)
            for (JType form : c.forms()) {
                if (form == c.forms().get(0) && VariantValues.objectBase(c)) {
                    continue;
                }
                for (VariantValues.Labeled value : values.values(c, "", form, null)) {
                    variant(c, "form", "", form + ":" + value.label(), value.value(), value.value());
                }
            }
            // Fields
            for (Field field : c.fields()) {
                if (fixtures.skip(c.id(), field.path()) != null || skippedParent(c, field.path())) {
                    continue;
                }
                if (JsonPath.get(base, field.path()) != null && !field.path().endsWith("[]") && !field.path().endsWith("{}")) {
                    JsonElement omitted = base.deepCopy();
                    if (JsonPath.remove(omitted, field.path())) {
                        variant(c, "omit", field.path(), "omitted", null, omitted);
                    }
                }
                for (JType type : field.types()) {
                    for (VariantValues.Labeled value : values.values(c, field.path(), type, field)) {
                        JsonElement json = values.with(c, base, field.path(), value.value());
                        if (json != null) {
                            String label = type + ":" + value.label();
                            variant(c, "field", field.path(), label, value.value(), json);
                            Links links = link(c, json, field.path());
                            if (links != null) {
                                variant(c, "field", field.path(), label + LINKED + String.join(", ", links.paths()), value.value(),
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
                JsonPath.set(json, sameIdentifier.getKey(), new JsonPrimitive(identifier));
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
        String name = JsonPath.tokens(path).getLast();
        String parent = JsonPath.parentOf(path);
        for (String fact : fixtures.facts(c.id(), parent)) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("order") && (parts[1].equals(name) || parts[2].equals(name)) && !path.endsWith("[]") && !path.endsWith("{}")) {
                boolean high = parts[2].equals(name);
                String other = (parent.isEmpty() ? "" : parent + ".") + (high ? parts[1] : parts[2]);
                JsonElement limit = limit(c, other, high, JsonPath.get(linked, path));
                if (limit != null && JsonPath.set(linked, other, limit)) {
                    paths.add(other);
                }
            }
        }
        for (String fact : fixtures.facts(c.id(), path)) {
            String[] parts = fact.split(" ");
            if (parts[0].equals("conflicts")) {
                JsonElement alternative = fixtures.alternative(c.id(), parts[1], JsonPath.tokens(parts[1]).getLast());
                if (alternative != null && !alternative.equals(JsonPath.get(linked, parts[1])) && JsonPath.set(linked, parts[1], alternative)) {
                    paths.add(parts[1]);
                }
            } else if (parts[0].equals("identifier") && (JsonPath.parentOf(parts[1]).isEmpty() || JsonPath.get(linked, JsonPath.parentOf(parts[1])) != null)) {
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
        Field field = values.withFeedback(c, c.field(path));
        Double limit = field == null || field.range() == null ? null : low ? field.range().min() : field.range().max();
        if (limit != null) {
            return VariantValues.number(limit);
        }
        return varied != null && varied.isJsonPrimitive() && varied.getAsJsonPrimitive().isNumber() ? varied.deepCopy() : null;
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
        JsonElement base = values.base(c);
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
        JsonElement json = values.with(c, values.base(c), path, value);
        return json == null ? null : variant(c, "probe", path, "key:" + kind(value), value, json, null);
    }

    /**
     * The trivial values a key is probed with, one per JSON type (a boolean, an integer, a string that names nothing,
     * and the first enum value of the object that holds the key, an empty object and an empty list).
     */
    public List<JsonElement> trivialValues(String componentId, String path) {
        List<JsonElement> values = new ArrayList<>(List.of(new JsonPrimitive(true), new JsonPrimitive(1), new JsonPrimitive("paletter_a")));
        Field parent = component(componentId).field(JsonPath.parentOf(path));
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
     * sample of the field when that is null). With no path, a {@code value} replaces the whole base (a form or input the docs do
     * not give the base).
     *
     * @return the variant, or null if the component itself needs a newer format or the field cannot be set
     */
    public Variant atFormat(String componentId, String format, String path, JsonElement value) {
        Component c = component(componentId);
        if (c.minFormatVersion() != null && compare(c.minFormatVersion(), format) > 0) {
            return null;
        }
        JsonElement json = values.base(c);
        for (Field field : c.fields()) {
            if (field.minFormatVersion() != null && compare(field.minFormatVersion(), format) > 0 && !field.path().equals(path)) {
                JsonPath.remove(json, field.path());
            }
        }
        if (path.isEmpty() && value != null) {
            json = value.deepCopy();
        }
        if (!path.isEmpty()) {
            Field field = c.field(path);
            value = value != null || field == null ? value : values.sample(c, path, values.primaryType(c, field), field);
            json = value == null ? null : values.with(c, json, path, value);
            if (json == null) {
                return null;
            }
        }
        return variant(c, "format", path, "format " + format + (path.isEmpty() && value == null ? "" : " " + kind(value)), value, json, format);
    }

    /**
     * The companions alone (the control of the component, which adds nothing of it) in a file of another format version: what
     * the base at that format is compared with, as the file's envelope changes with the format (items below 1.16.100 are old-style).
     */
    public Variant controlAt(String componentId, String format) {
        Component c = component(componentId);
        return variant(c, "control", "", "control " + format, null, control(c), format);
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
    JsonObject definition(Component c, String identifier, JsonElement json, String format, boolean script) {
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

}
