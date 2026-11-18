package dev.opencollab.paletter.tools.model.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.Model.Component;
import dev.opencollab.paletter.tools.model.Model.Field;
import dev.opencollab.paletter.tools.model.Model.JType;
import dev.opencollab.paletter.tools.model.docs.DocsParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.opencollab.paletter.tools.model.addon.AddonGenerator.NAMESPACE;
import static dev.opencollab.paletter.tools.model.addon.JsonPath.get;
import static dev.opencollab.paletter.tools.model.addon.JsonPath.parentOf;
import static dev.opencollab.paletter.tools.model.addon.JsonPath.tokens;

/**
 * The values of the add-on's variants: the base of a component, the values one field takes (one generator per JSON type:
 * numbers, strings, vectors, lists, maps, objects and block descriptors), and the component JSON with one value put in at a
 * path. It reads only the model's fields and the {@link Fixtures} (the docs' ranges and enum values, {@code bds-feedback.json});
 * what a variant is called, numbered and written to disk is {@link AddonGenerator}'s and {@link AddonWriter}'s.
 */
final class VariantValues {
    private final Fixtures fixtures;
    private final Model model;

    VariantValues(Fixtures fixtures, Model model) {
        this.fixtures = fixtures;
        this.model = model;
    }

    /**
     * One value of a variant and its label. A pinned value is one a rule of the mapping inference needs to have seen (every value
     * of a schema enum, the probes and samples of {@code fixtures.json}, the combinations the packed bits and the hash order
     * need): {@link #MAX_VALUES} never cuts it.
     */
    record Labeled(String label, JsonElement value, boolean pinned) {
        Labeled(String label, JsonElement value) {
            this(label, value, false);
        }
    }

    /** The base value: every field without a documented default set to a sample (the object form), else a sample of the first form. */
    JsonElement base(Component c) {
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
    static boolean objectBase(Component c) {
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

    JType primaryType(Component c, Field field) {
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
        // A vector the schema also describes by its members (x, y, z) stays a vector: as an object it would have no member without a default
        for (JType type : field.types()) {
            if (type.type().equals("vec3") && c.fields().stream().filter(f -> parentOf(f.path()).equals(path))
                .allMatch(f -> List.of("x", "y", "z").contains(f.name()))) {
                return type;
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
    JsonElement sample(Component c, String path, JType type, Field field) {
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
     * At most this many unpinned values per field and JSON type ({@link Labeled#pinned} ones are all kept). Each generator lists
     * first the values likeliest to change between builds (limits and one past them, invalid values), then the typical ones.
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
    List<Labeled> values(Component c, String path, JType type, Field field) {
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
        // one variant per value and per label (a value generated twice keeps its first label, and is pinned if any copy is)
        Map<String, Labeled> distinct = new LinkedHashMap<>();
        Set<String> labels = new java.util.HashSet<>();
        values.stream().filter(v -> labels.add(v.label)).forEach(v -> distinct.merge(v.value.toString(), v,
            (first, again) -> again.pinned() && !first.pinned() ? new Labeled(first.label, first.value, true) : first));
        List<Labeled> all = new ArrayList<>(distinct.values());
        List<Labeled> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int unpinned = 0;
        for (Labeled v : all) {
            if (v.pinned() || unpinned < MAX_VALUES) {
                kept.add(v);
                unpinned += v.pinned() ? 0 : 1;
            } else {
                dropped.add(v.label);
            }
        }
        if (!dropped.isEmpty()) {
            cut.put(c.id() + " " + path + " (" + type.type() + ")", "kept " + kept.size() + " of " + all.size() + ", cut: "
                + String.join(", ", dropped.subList(0, Math.min(dropped.size(), 12))) + (dropped.size() > 12 ? ", ... (" + (dropped.size() - 12) + " more)" : ""));
        }
        return kept;
    }

    /** The unpinned values cut at {@link #MAX_VALUES} (a field and JSON type, how many were kept of how many, which were cut): no variant has them. */
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
        Set<JsonElement> pinned = new java.util.HashSet<>(strings);
        // every value of a schema enum: a rule that tables the enum needs all of them, so none is cut
        if (fixtures.pinsEnum(c.id(), path)) {
            fixtures.schemaEnum(c.id(), path).forEach(v -> pinned.add(new JsonPrimitive(v)));
        }
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
        return new LinkedHashSet<>(strings).stream().map(s -> new Labeled(s.getAsString(), s, pinned.contains(s))).toList();
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
                // Whole lists the minimal entry never writes, such as an entry without one of its fields
                fixtures.probeValues(c.id(), path).forEach(v -> values.add(new Labeled("[probe] " + v, v.deepCopy(), true)));
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
        if (items.type().equals("string")) {
            values.addAll(orderings(fixtures.listNames(c.id(), path)));
        }
        return values;
    }

    /**
     * A list of names in several orders, for the lists BDS sends as a set ordered by the hash of each name: every order of the first
     * three names, two more sets of three (in the written order and reversed) and all the names (written and reversed). Whatever the
     * hash is, some of them are sent in an order other than the written one, and not always the reversed one.
     */
    private static List<Labeled> orderings(List<String> names) {
        List<Labeled> values = new ArrayList<>();
        if (names.size() < 3) {
            return values;
        }
        List<List<String>> lists = new ArrayList<>();
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] perm : perms) {
            lists.add(List.of(names.get(perm[0]), names.get(perm[1]), names.get(perm[2])));
        }
        for (int start = 1; start + 3 <= names.size(); start++) {
            lists.add(names.subList(start, start + 3));
            lists.add(names.subList(start, start + 3).reversed());
        }
        if (names.size() > 3) {
            lists.add(names);
            lists.add(names.reversed());
        }
        for (List<String> list : lists) {
            JsonArray array = new JsonArray();
            list.forEach(array::add);
            values.add(new Labeled("[" + String.join(",", list) + "]", array, true));
        }
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
                    values.add(new Labeled("{1} " + (other.isJsonPrimitive() ? other.getAsString() : other.toString()), single, true));
                }
            }
            if (items.type().equals("string")) {
                values.addAll(moreElements(c, path, key, items, fixtures.mapValues(c.id(), path, field == null ? null : field.name())));
            }
        }
        return values;
    }

    /** The keys of a map with several entries: the map's key, then {@code <key>_2}, {@code <key>_3}. */
    private static final int KEYS = 3;

    /**
     * The entries of a map of Molang strings, to see them under several keys and in the other forms the schema lists for an element
     * (the elements that are no string do not come from the docs): {@code mapValues} (the strings of {@code fixtures.json}) spread
     * over {@link #KEYS} keys, twice, so that each string is under two different keys; each as the {@code expression} of the object
     * form ({@code {expression, version}}); and numbers. Only what the schema lists for the elements is generated.
     */
    private List<Labeled> moreElements(Component c, String path, String key, JType items, List<JsonElement> mapValues) {
        List<Labeled> values = new ArrayList<>();
        List<JsonElement> pool = mapValues.stream().filter(v -> matches(items, v)).toList();
        for (int shift = 0; shift < 2 && pool.size() > KEYS; shift++) {
            for (int group = 0; group * KEYS < pool.size(); group++) {
                JsonObject map = new JsonObject();
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < KEYS; i++) {
                    JsonElement text = pool.get((group * KEYS + i + shift) % pool.size());
                    map.add(i == 0 ? key : key + "_" + (i + 1), text.deepCopy());
                    texts.add(text.getAsString());
                }
                values.add(new Labeled("{" + KEYS + " keys} " + String.join(" / ", texts), map, true));
            }
        }
        List<String> types = fixtures.schemaElementTypes(c.id(), path);
        List<Schema.Member> members = fixtures.schemaElementMembers(c.id(), path);
        if (types.contains("object") && members.stream().anyMatch(m -> m.types().contains("string"))) {
            List<JsonElement> expressions = new ArrayList<>();
            expressions.add(sample(c, path + "{}", items, null));
            expressions.addAll(pool);
            boolean required = members.stream().anyMatch(Schema.Member::required);
            for (JsonElement expression : expressions) {
                JsonObject element = new JsonObject();
                for (Schema.Member member : members) {
                    if (required && !member.required()) {
                        continue;
                    }
                    JsonElement given = member.types().contains("string") ? expression.deepCopy() : memberSample(c, path, member);
                    if (given != null) {
                        element.add(member.name(), given);
                    }
                }
                JsonObject single = new JsonObject();
                single.add(key, element);
                values.add(new Labeled("{1 object} " + expression.getAsString(), single, true));
            }
        }
        if (types.contains("number") || types.contains("integer")) {
            for (Labeled number : numbers(c, path + "{}", null, null, false)) {
                JsonObject single = new JsonObject();
                single.add(key, number.value());
                values.add(new Labeled("{1 number} " + number.label(), single, true));
            }
        }
        return values;
    }

    /** A value for a member of an object element: the sample of {@code fixtures.json}, else one of its first JSON type. */
    private JsonElement memberSample(Component c, String path, Schema.Member member) {
        JsonElement given = fixtures.sample(c.id(), path + "{}." + member.name(), member.name());
        if (given != null) {
            return given.deepCopy();
        }
        return member.types().isEmpty() ? null : switch (member.types().get(0)) {
            case "integer" -> new JsonPrimitive(1);
            case "number" -> new JsonPrimitive(1.5);
            case "boolean" -> new JsonPrimitive(true);
            case "string" -> new JsonPrimitive("paletter_a");
            default -> null;
        };
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
        if (fixtures.boolCombinations(c.id())) {
            values.addAll(boolCombinations(c, path));
        }
        return values;
    }

    /**
     * The booleans of an object in every combination, for the ones that are bits of one leaf: first those a base can hold, then also
     * those only a variant of their own sets (with the members they need, {@code together}). With every other bit cleared, each bit
     * is seen alone and the others in pairs.
     */
    private List<Labeled> boolCombinations(Component c, String path) {
        List<Field> booleans = c.fields().stream().filter(f -> parentOf(f.path()).equals(path) && f.types().get(0).type().equals("boolean")).toList();
        List<Labeled> values = new ArrayList<>();
        List<Field> baseable = booleans.stream().filter(f -> !fixtures.probeOnly(c.id(), f.path())).toList();
        for (List<Field> set : List.of(baseable, booleans)) {
            if (set.size() < 2 || (set == booleans && baseable.size() == booleans.size())) {
                continue;
            }
            for (int mask = 0; mask < 1 << set.size(); mask++) {
                JsonObject object = minimal(c, path);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < set.size(); i++) {
                    Field field = set.get(i);
                    object.add(field.name(), new JsonPrimitive((mask & 1 << i) != 0));
                    fixtures.together(c.id(), field.path()).entrySet().forEach(e -> object.add(e.getKey(), e.getValue().deepCopy()));
                    names.add(field.name() + "=" + ((mask & 1 << i) != 0));
                }
                values.add(new Labeled("{" + String.join(", ", names) + "}", object, true));
            }
        }
        return values;
    }

    /** An object holding both vectors of a box varied together: where it goes in the component, and the value. */
    record Joint(String path, Labeled value) {
    }

    /** The levels the origin (above the lowest point of the box) and the size take, per axis. */
    private static final double[] ORIGIN_LEVELS = {0, 1, 3};
    private static final double[] SIZE_LEVELS = {1, 2, 4};

    /**
     * The boxes ({@code origin} and {@code size} vectors of an object) of a component with both members varied together, which one
     * member at a time (the field variants) cannot tell: a number of the wire may follow either. Per axis the origin and the size
     * take every combination of three levels (sizes never below zero), spread over nine boxes, each axis at another level than
     * the one before. The boxes stay inside the limit BDS gave for the box ({@code box} fact of the component). Without one yet
     * (BDS says it when a box is past it: one such box is written, and the next run has the fact) they stay inside the tightest limit
     * of the other components, which is borrowed and unverified for this one. A box too small for the levels has none.
     */
    List<Joint> joints(Component c) {
        List<Joint> joints = new ArrayList<>();
        for (Field origin : c.fields()) {
            String parent = parentOf(origin.path());
            Field size = origin.name().equals("origin") ? c.field(parent.isEmpty() ? "size" : parent + ".size") : null;
            if (size == null || !isVector(origin) || !isVector(size) || fixtures.probeOnly(c.id(), origin.path())) {
                continue;
            }
            double[][] own = boxFact(c, parent);
            if (own == null) {
                // No limit of BDS's yet for this component: a box past any limit makes BDS say it, and the next run has the fact
                JsonObject past = new JsonObject();
                past.add("origin", array(0, 0, 0));
                past.add("size", array(1000, 1000, 1000));
                joints.add(new Joint(parent, new Labeled("0,0,0 + 1000,1000,1000 (past any limit)", past, true)));
            }
            double[][] limits = own != null ? own : boxLimits(c, parent);
            if (limits == null) {
                continue;
            }
            double reach = ORIGIN_LEVELS[ORIGIN_LEVELS.length - 1] + SIZE_LEVELS[SIZE_LEVELS.length - 1];
            if (java.util.stream.IntStream.range(0, 3).anyMatch(i -> limits[1][i] - limits[0][i] < reach)) {
                continue;
            }
            for (int k = 0; k < 9; k++) {
                double[] o = new double[3];
                double[] s = new double[3];
                for (int i = 0; i < 3; i++) {
                    o[i] = limits[0][i] + ORIGIN_LEVELS[(k + i) % 3];
                    s[i] = SIZE_LEVELS[(k / 3 + i) % 3];
                }
                JsonObject box = new JsonObject();
                box.add("origin", array(o));
                box.add("size", array(s));
                joints.add(new Joint(parent, new Labeled(vector(o).label() + " + " + vector(s).label(), box, true)));
            }
        }
        return joints;
    }

    private static boolean isVector(Field field) {
        return field.types().stream().anyMatch(t -> t.type().equals("vec3"));
    }

    /**
     * The lowest and highest point a box of the object at a path may reach: BDS's {@code box origin size} fact of the object, else the
     * intersection of those of every object of the model with an origin and a size; null when no component has one.
     */
    private double[][] boxLimits(Component c, String parent) {
        double[][] own = boxFact(c, parent);
        if (own != null) {
            return own;
        }
        double[][] common = null;
        for (Component other : model.components()) {
            for (Field field : other.fields()) {
                double[][] fact = field.name().equals("origin") ? boxFact(other, parentOf(field.path())) : null;
                if (fact == null) {
                    continue;
                }
                if (common == null) {
                    common = fact;
                } else {
                    for (int i = 0; i < 3; i++) {
                        common[0][i] = Math.max(common[0][i], fact[0][i]);
                        common[1][i] = Math.min(common[1][i], fact[1][i]);
                    }
                }
            }
        }
        return common;
    }

    /** The {@code box} fact BDS gave for the object at the path, else for the component (it reports the limit on the component). */
    private double[][] boxFact(Component c, String parent) {
        double[][] fact = boxFactAt(c, parent);
        return fact != null || parent.isEmpty() ? fact : boxFactAt(c, "");
    }

    private double[][] boxFactAt(Component c, String parent) {
        return fixtures.facts(c.id(), parent).stream().map(f -> f.split(" ")).filter(p -> p[0].equals("box") && p.length == 5
            && List.of(p[1], p[2]).containsAll(List.of("origin", "size"))).map(p -> new double[][]{vec(p[3]), vec(p[4])}).findFirst().orElse(null);
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
    Field withFeedback(Component c, Field field) {
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

    static JsonPrimitive number(double d) {
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

    /** A copy of the base with the value at the path (containers on the way created from the minimal shapes). */
    JsonElement with(Component c, JsonElement base, String path, JsonElement value) {
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
}
