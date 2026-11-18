package org.geysermc.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.codegen.component.HandTransforms;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.addon.JsonPath;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Capture;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Obs;
import org.geysermc.paletter.tools.model.infer.SpecCaptures.Variant;
import org.geysermc.paletter.tools.model.rule.CrossField;
import org.geysermc.paletter.tools.model.rule.Descriptor;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.geysermc.paletter.tools.model.rule.Observation;
import org.geysermc.paletter.tools.model.rule.Rule;
import org.geysermc.paletter.tools.model.rule.Structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one spec capture shows of one component: whether BDS sends it, and per value form and field the wire leaves that
 * follow its JSON value ({@link RuleFit}), the constants it writes whatever the JSON says, and what BDS bore out of the
 * hand transforms. The result is one window of the component in {@code mappings.json}.
 */
final class FieldAnalysis {
    private static final String ABSENT = "<absent>";

    private final Fixtures fixtures;
    private final Map<String, String> transforms;
    private final RuleFit ruleFit;
    private final LinkedFields linked;

    /**
     * @param transforms the hand transforms ({@link HandTransforms#BY_PATH})
     * @param feedback   what BDS said about the schema (enum values it lists)
     * @param linked     where the linked fields go
     */
    FieldAnalysis(Fixtures fixtures, Map<String, String> transforms, Feedback feedback, LinkedFields linked) {
        this.fixtures = fixtures;
        this.transforms = transforms;
        this.ruleFit = new RuleFit(feedback);
        this.linked = linked;
    }

    JsonObject analyze(Capture capture, Component component) {
        List<Obs> observations = new ArrayList<>();
        for (Variant v : capture.variants()) {
            if (v.component().equals(component.id())) {
                observations.add(new Obs(v, capture.wire().get(v.identifier()), capture.log().getOrDefault(v.identifier(), List.of())));
            }
        }
        if (observations.isEmpty()) {
            return null;
        }
        JsonObject result = new JsonObject();
        Obs controlVariant = observations.stream().filter(o -> o.variant().role().equals("control")).findFirst().orElse(null);
        Obs control = controlVariant;
        Obs base = observations.stream().filter(o -> o.variant().role().equals("base")).findFirst().orElse(null);
        long accepted = observations.stream().filter(o -> o != controlVariant && o.accepted()).count();
        long rejected = observations.stream().filter(o -> o != controlVariant && !o.accepted()).count();
        JsonObject counts = new JsonObject();
        counts.addProperty("accepted", accepted);
        counts.addProperty("rejected", rejected);
        result.add("variants", counts);
        if (control == null || !control.accepted()) {
            if (base == null || !base.accepted()) {
                result.addProperty("status", "untestable");
                result.addProperty("note", "the control (companions only) and the base were rejected: "
                    + firstErrors(control == null ? List.of() : control.log()));
                transformEvidence(capture, component, result, observations, controlVariant);
                return result;
            }
            // A companion that needs this component back (geometry <-> material_instances): the base is the control
            result.addProperty("note", "control rejected (" + firstErrors(control == null ? List.of() : control.log())
                + "); fields compared with the base only");
            control = base;
        }
        String filler = component.kind().equals("item") && fixtures.requires(component.id()).isEmpty()
            && !fixtures.filler.has(component.name()) ? fixtures.filler.keySet().iterator().next() : null;
        // Footprint: what any accepted variant changes against the control
        Set<String> footprint = new TreeSet<>();
        for (Obs o : observations) {
            if (o == control || !o.accepted()) {
                continue;
            }
            for (Map.Entry<String, Leaf[]> d : Wire.diff(control.wire(), o.wire()).entrySet()) {
                if (filler != null && d.getValue()[1] == null && d.getKey().startsWith(filler)) {
                    continue;
                }
                footprint.add(d.getKey());
            }
        }
        Set<String> keys = wireKeys(footprint);
        JsonArray wireKeys = new JsonArray();
        keys.forEach(wireKeys::add);
        result.add("wire", wireKeys);
        String status;
        if (control == base && accepted > 0) {
            status = keysOf(base.wire()).contains(component.name()) ? "sent" : "server-only";
            keys.addAll(keysOf(base.wire()).stream().filter(k -> k.equals(component.name())).toList());
            wireKeys = new JsonArray();
            keys.forEach(wireKeys::add);
            result.add("wire", wireKeys);
        } else if (accepted == 0) {
            status = "rejected";
            result.addProperty("note", firstErrors(observations.stream().flatMap(o -> o.log().stream()).toList()));
        } else if (footprint.isEmpty()) {
            status = "server-only";
        } else {
            status = "sent";
        }
        result.addProperty("status", status);
        if (base != null && !base.accepted()) {
            result.addProperty("baseRejected", firstErrors(base.log()));
        }

        // Whole value forms, against the control
        JsonObject forms = new JsonObject();
        Map<String, List<Obs>> byForm = new LinkedHashMap<>();
        for (Obs o : observations) {
            if (o.variant().role().equals("form") || (o.variant().role().equals("base") && !component.hasObjectForm())) {
                String type = o.variant().label().contains(":") ? o.variant().label().substring(0, o.variant().label().indexOf(':')) : "base";
                byForm.computeIfAbsent(type, k -> new ArrayList<>()).add(o);
            }
        }
        for (Map.Entry<String, List<Obs>> form : byForm.entrySet()) {
            forms.add(form.getKey(), field(component, "", form.getValue(), control, filler, true, Map.of()));
        }
        if (!forms.isEmpty()) {
            result.add("forms", forms);
        }
        // Fields, against the base
        JsonObject fields = new JsonObject();
        Map<String, List<Obs>> byField = new LinkedHashMap<>();
        for (Obs o : observations) {
            if (o.variant().role().equals("field") || o.variant().role().equals("omit")) {
                byField.computeIfAbsent(o.variant().path(), k -> new ArrayList<>()).add(o);
            }
        }
        Obs fieldReference = base != null && base.accepted() ? base : control;
        Map<String, Set<String>> moved = LinkedFields.moved(byField, fieldReference);
        for (Field field : component.fields()) {
            List<Obs> list = byField.get(field.path());
            if (list == null) {
                JsonObject skipped = new JsonObject();
                skipped.addProperty("status", "not-generated");
                String why = fixtures.skip(component.id(), field.path());
                if (why != null) {
                    skipped.addProperty("note", why);
                }
                fields.add(field.path(), skipped);
                continue;
            }
            JsonObject f = field(component, field.path(), list, fieldReference, filler, false, moved);
            if (fieldReference == control && base != null) {
                f.addProperty("reference", "control (the base was rejected)");
            }
            if (f.get("status").getAsString().equals("linked")) {
                linked.add(component.id(), field.path(), capture.bdsVersion(), f, list);
            }
            fields.add(field.path(), f);
        }
        result.add("fields", fields);
        JsonObject constants = constants(result, observations, control);
        if (!constants.isEmpty()) {
            result.add("constants", constants);
        }
        JsonArray findings = Findings.findings(component.id(), result);
        if (!findings.isEmpty()) {
            result.add("findings", findings);
        }
        transformEvidence(capture, component, result, observations, controlVariant);
        return result;
    }

    /**
     * Whether BDS bore out each hand transform of the component ({@link HandTransforms} keys {@code <component>:<path>}):
     * {@code verified: {build, variants}} when variants that set the path (the whole component for an empty path) were accepted,
     * {@code unverified: <why>} when none was; a verified whole component transform also lists the fields BDS rejected in every variant ({@code rejectedFields}). {@code transforms} of the result maps the path to one of the two. The conformance
     * suite reproduces every accepted variant, so accepted variants are what a transform has been checked against.
     */
    private void transformEvidence(Capture capture, Component component, JsonObject result, List<Obs> observations, Obs control) {
        JsonObject evidence = new JsonObject();
        for (String key : transforms.keySet()) {
            if (!key.startsWith(component.id() + ":")) {
                continue;
            }
            String path = key.substring(component.id().length() + 1);
            List<Obs> setting = observations.stream().filter(o -> o != control && settingPath(o.variant(), path)).toList();
            long accepted = setting.stream().filter(Obs::accepted).count();
            JsonObject entry = new JsonObject();
            if (accepted > 0) {
                JsonObject verified = new JsonObject();
                verified.addProperty("build", capture.bdsVersion());
                verified.addProperty("variants", accepted);
                entry.add("verified", verified);
                JsonArray rejected = rejectedFields(result);
                if (path.isEmpty() && !rejected.isEmpty()) {
                    entry.add("rejectedFields", rejected);
                }
            } else {
                entry.addProperty("unverified", setting.isEmpty() ? "no variant of the capture sets it"
                    : "BDS rejected all " + setting.size() + " variants that set it: "
                    + firstErrors(setting.stream().flatMap(o -> o.log().stream()).toList()));
            }
            evidence.add(path, entry);
        }
        if (!evidence.isEmpty()) {
            result.add("transforms", evidence);
        }
    }

    /** The fields of the result that BDS rejected in every variant that sets them: what a whole component transform compiles without proof. */
    private static JsonArray rejectedFields(JsonObject result) {
        JsonArray rejected = new JsonArray();
        if (result.has("fields")) {
            result.getAsJsonObject("fields").entrySet().stream().filter(e -> e.getValue().getAsJsonObject().get("status").getAsString().equals("rejected"))
                .forEach(e -> rejected.add(e.getKey()));
        }
        return rejected;
    }

    /** Whether the variant sets the path: any value variant for the whole component, else a field variant at or below the path. */
    private static boolean settingPath(Variant v, String path) {
        if (path.isEmpty()) {
            return List.of("base", "form", "field").contains(v.role());
        }
        return v.role().equals("field") && (v.path().equals(path) || v.path().startsWith(path + ".") || v.path().startsWith(path + "[]")
            || v.path().startsWith(path + "{}"));
    }

    /**
     * Leaves of the component's wire keys that no rule of a field or form targets and that have the same value in every
     * accepted observation that sends them: what BDS writes whatever the JSON says ({@code grid_size: 3},
     * {@code hasJsonVersionBeforeValidation: 0b}, {@code damage_chance.max: 100} in every accepted variant). A compiler
     * writes them whenever it writes the component (list element leaves per element).
     */
    private static JsonObject constants(JsonObject result, List<Obs> observations, Obs control) {
        JsonObject constants = new JsonObject();
        if (!result.has("wire") || !"sent".equals(result.get("status").getAsString())) {
            return constants;
        }
        List<String> keys = result.getAsJsonArray("wire").asList().stream().map(JsonElement::getAsString).toList();
        Set<String> targeted = new TreeSet<>();
        List<JsonObject> groups = new ArrayList<>();
        if (result.has("forms")) {
            result.getAsJsonObject("forms").entrySet().forEach(e -> groups.add(e.getValue().getAsJsonObject()));
        }
        result.getAsJsonObject("fields").entrySet().forEach(e -> groups.add(e.getValue().getAsJsonObject()));
        for (JsonObject group : groups) {
            if (group.has("rules")) {
                group.getAsJsonArray("rules").forEach(r -> targeted.add(r.getAsJsonObject().get("wire").getAsString()));
            }
            if (group.has("whenAbsent")) {
                targeted.addAll(group.getAsJsonObject("whenAbsent").keySet());
            }
        }
        Map<String, String> values = new TreeMap<>();
        Set<String> varying = new TreeSet<>();
        for (Obs o : observations) {
            if (!o.accepted() || (o == control && !o.variant().role().equals("base"))) {
                continue;
            }
            for (Map.Entry<String, Leaf> leaf : o.wire().entrySet()) {
                String generic = Wire.generic(leaf.getKey());
                boolean ours = keys.stream().anyMatch(k -> generic.equals(k) || generic.startsWith(k + ".") || generic.startsWith(k + "["));
                if (!ours || explained(generic, targeted)) {
                    continue;
                }
                String value = leaf.getValue().toString();
                String before = values.putIfAbsent(generic, value);
                if (before != null && !before.equals(value)) {
                    varying.add(generic);
                }
            }
        }
        values.forEach((path, value) -> {
            if (varying.contains(path)) {
                return;
            }
            // A leaf no accepted variant moved is a constant, whatever its name: a field of the same name is no evidence
            // that it follows the field (such a field stays no-evidence until BDS accepts a second value)
            constants.addProperty(path, value);
        });
        return constants;
    }

    /** Whether a rule explains the leaf: it targets the leaf, a list or compound containing it, or a leaf inside it. */
    static boolean explained(String leaf, Set<String> targeted) {
        for (String t : targeted) {
            if (leaf.equals(t) || leaf.startsWith(t + ".") || leaf.startsWith(t + "[") || t.startsWith(leaf + ".") || t.startsWith(leaf + "[")) {
                return true;
            }
        }
        return false;
    }

    /**
     * One field (or the whole value, path ""): which wire leaves follow its JSON value, and how.
     *
     * @param reference the observation the variants are compared with (base for fields, control for forms)
     * @param moved     per field, the leaves its own variants move ({@link LinkedFields#moved}): a linked variant's other fields move these
     */
    private JsonObject field(Component component, String path, List<Obs> variants, Obs reference, String filler, boolean form,
                             Map<String, Set<String>> moved) {
        JsonObject out = new JsonObject();
        List<Obs> accepted = variants.stream().filter(Obs::accepted).toList();
        List<Obs> rejected = variants.stream().filter(o -> !o.accepted()).toList();
        JsonElement referenceValue = form ? null : SpecCaptures.jsonAt(reference.variant().json(), path);
        out.addProperty("accepted", accepted.size());
        out.addProperty("rejected", rejected.size());
        if (!rejected.isEmpty()) {
            JsonArray labels = new JsonArray();
            rejected.forEach(o -> labels.add(o.variant().label()));
            out.add("rejectedValues", labels);
        }
        List<String> errors = new ArrayList<>();
        variants.forEach(o -> o.log().forEach(line -> errors.add(SpecCaptures.errorText(line))));
        Set<String> distinct = new LinkedHashSet<>(errors);
        if (!distinct.isEmpty()) {
            JsonArray array = new JsonArray();
            distinct.stream().limit(4).forEach(array::add);
            out.add("bdsErrors", array);
        }
        Matcher range = Pattern.compile("accepted range \\[(-?[\\d.]+), (-?[\\d.]+)]").matcher(String.join(" ", distinct));
        if (range.find()) {
            JsonArray bounds = new JsonArray();
            bounds.add(literal(range.group(1)));
            bounds.add(literal(range.group(2)));
            out.add("bdsRange", bounds);
        }
        if (distinct.stream().anyMatch(e -> e.contains("not present in the Schema"))) {
            out.addProperty("unknownToBds", true);
        }
        // Observations: the reference (with its own value at the path) and the accepted variants
        List<Observation> observations = new ArrayList<>();
        if (!form) {
            observations.add(new Observation(referenceValue, reference.wire(), "reference"));
        }
        for (Obs o : accepted) {
            JsonElement value = o.variant().role().equals("omit") ? null : form ? o.variant().json() : SpecCaptures.jsonAt(o.variant().json(), path);
            observations.add(new Observation(value, o.wire(), o.variant().label()));
        }
        Set<String> varying = new TreeSet<>();
        // A leaf the linked field moves is left out of a linked variant by name, so a leaf both fields write is masked: the
        // field's own effect on it is lost. Such a field is never "dropped" (server only); it stays open as linked
        boolean maskedByLink = false;
        for (Obs o : accepted) {
            Set<String> byLinked = new TreeSet<>();
            o.variant().linked().forEach(other -> byLinked.addAll(moved.getOrDefault(other, Set.of())));
            for (Map.Entry<String, Leaf[]> d : Wire.diff(reference.wire(), o.wire()).entrySet()) {
                if (filler != null && d.getKey().startsWith(filler) && d.getValue()[1] == null) {
                    continue;
                }
                if (byLinked.contains(Wire.generic(d.getKey()))) {
                    maskedByLink = true;
                    continue;
                }
                varying.add(d.getKey());
            }
        }
        if (form) {
            // a whole value form also "varies" every leaf it shares with the control's absence of the component
            observations.add(0, new Observation(null, reference.wire(), "control"));
        }
        boolean distinctValues = observations.stream().map(p -> Objects.toString(p.json())).distinct().count() > 1;
        String status;
        List<Rule> rules = new ArrayList<>();
        JsonArray unexplained = new JsonArray();
        JsonObject absent = new JsonObject();
        if (accepted.isEmpty()) {
            status = rejected.isEmpty() ? "not-generated" : "rejected";
        } else if (varying.isEmpty()) {
            status = distinctValues ? "dropped" : "no-evidence";
        } else {
            Map<String, Rule> byGeneric = new LinkedHashMap<>();
            for (Rule set : RuleFit.sets(observations, varying, RuleFit.closedValues(component, path))) {
                // the list leaves are explained as a whole: drop them from the leaf by leaf fit
                String list = set.wire;
                varying.removeIf(w -> w.matches(Pattern.quote(list.substring(0, list.length() - 2)) + "\\[\\d+]"));
                byGeneric.put(list + " " + set.kind(), set);
            }
            for (String wire : varying) {
                Rule rule = ruleFit.fit(component, path, wire, observations);
                Leaf whenAbsent = observations.stream().filter(p -> p.json() == null).map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
                boolean anyAbsent = observations.stream().anyMatch(p -> p.json() == null);
                if (anyAbsent && !form) {
                    absent.addProperty(Wire.generic(wire), whenAbsent == null ? "omitted" : whenAbsent.toString());
                }
                boolean setVaries = observations.stream().filter(p -> p.json() != null)
                    .anyMatch(p -> !Objects.equals(p.wire().get(wire), reference.wire().get(wire)));
                if (rule == null && !setVaries && !form) {
                    // only observations without the field differ (another field of the variant moved it)
                    rule = new CrossField();
                }
                if (rule == null && container(component, path)) {
                    // a list/object/map field: its elements' leaves are explained by its child fields
                    rule = new Structure();
                }
                if (rule == null) {
                    JsonObject u = new JsonObject();
                    u.addProperty("wire", wire);
                    JsonArray samples = new JsonArray();
                    observations.stream().limit(6).forEach(p -> samples.add((p.json() == null ? ABSENT : p.json().toString()) + " -> "
                        + (p.wire().get(wire) == null ? "-" : p.wire().get(wire).toString())));
                    u.add("samples", samples);
                    unexplained.add(u);
                } else {
                    String key = Wire.generic(wire) + " " + rule.kind();
                    if (!byGeneric.containsKey(key)) {
                        rule.wire = Wire.generic(wire);
                        byGeneric.put(key, rule);
                    }
                }
            }
            byGeneric.values().forEach(rule -> RuleFit.multipleOf(rule, path, accepted, rejected));
            rules.addAll(mapEntries(byGeneric.values()));
            boolean onlyStructural = rules.stream().noneMatch(Rule::writesValue);
            status = unexplained.isEmpty() ? (onlyStructural && !container(component, path) && !form ? "dropped" : "inferred")
                : rules.isEmpty() ? "unresolved" : "partial";
        }
        if (status.equals("dropped")) {
            // Whichever way the field came to be dropped (no leaf follows it, or only its presence does): not server only if
            // BDS may have decided the wire by another field (an order, a value it cannot be used with). Where no leaf
            // follows it even in the variants linked with that field, it is server only after all
            List<String> others = LinkedFields.linkedTo(component, path, variants, varying.isEmpty() && !maskedByLink);
            if (!others.isEmpty()) {
                status = "linked";
                JsonArray to = new JsonArray();
                others.forEach(to::add);
                out.add("linkedTo", to);
            }
        }
        // A transform of the field, of an object or list containing it, or of the whole component covers it
        String transformKey = HandTransforms.covering(transforms, component.id(), path);
        // (a field whose leaves only follow its presence, under a transform, is the transform's too)
        if ((status.equals("unresolved") || status.equals("partial") || ((status.equals("dropped") || status.equals("linked")) && !rules.isEmpty()))
            && transformKey != null) {
            out.addProperty("transform", transforms.get(transformKey));
            out.remove("linkedTo");
            status = "hand-rule";
        }
        if (!form && (status.equals("unresolved") || status.equals("partial"))) {
            Descriptor descriptor = descriptor(path, reference, accepted);
            if (descriptor != null) {
                // one rule for the whole descriptor: it replaces the leaf rules below it and explains the leaves that moved
                rules.removeIf(r -> r.wire.equals(descriptor.wire) || r.wire.startsWith(descriptor.wire + ".") || r.wire.startsWith(descriptor.wire + "{}"));
                rules.add(descriptor);
                for (int i = unexplained.size() - 1; i >= 0; i--) {
                    if (explained(Wire.generic(unexplained.get(i).getAsJsonObject().get("wire").getAsString()), Set.of(descriptor.wire))) {
                        unexplained.remove(i);
                    }
                }
                status = unexplained.isEmpty() ? "inferred" : "partial";
            }
        }
        out.addProperty("status", status);
        if (!rules.isEmpty()) {
            JsonArray array = new JsonArray();
            rules.forEach(r -> array.add(r.toJson()));
            out.add("rules", array);
        }
        if (!absent.isEmpty()) {
            out.add("whenAbsent", absent);
        }
        if (!unexplained.isEmpty()) {
            JsonArray limited = new JsonArray();
            for (int i = 0; i < Math.min(4, unexplained.size()); i++) {
                limited.add(unexplained.get(i));
            }
            out.add("unexplained", limited);
            if (unexplained.size() > 4) {
                out.addProperty("unexplainedMore", unexplained.size() - 4);
            }
        }
        return out;
    }

    /**
     * The descriptor a field's variants send as a whole ({@link Descriptor}): the field itself (an item), or the object that
     * holds it (a block: {@code states} varies, {@code name} and {@code tags} are sent with it). The leaves of the
     * descriptor are found by the {@code name} leaf the variants have; null unless exactly one descriptor fits them. A block
     * descriptor is fitted on the holder, an item descriptor on the field.
     */
    static Descriptor descriptor(String path, Obs reference, List<Obs> accepted) {
        List<Obs> all = new ArrayList<>(List.of(reference));
        all.addAll(accepted);
        List<String> tokens = JsonPath.tokens(path);
        String holder = String.join(".", tokens.subList(0, tokens.size() - 1)).replace(".[]", "[]").replace(".{}", "{}");
        Descriptor found = null;
        for (String at : List.of(path, holder)) {
            List<Observation> observations = all.stream()
                .map(o -> new Observation(SpecCaptures.jsonAt(o.variant().json(), at), o.wire(), o.variant().label()))
                .filter(o -> o.json() != null && !o.json().isJsonNull()).toList();
            if (observations.isEmpty()) {
                continue;
            }
            Set<String> prefixes = new TreeSet<>();
            observations.forEach(o -> o.wire().keySet().stream().filter(k -> k.endsWith(".name"))
                .forEach(k -> prefixes.add(k.substring(0, k.length() - ".name".length()))));
            for (String prefix : prefixes) {
                Descriptor fit = Descriptor.fit(observations, prefix);
                if (fit != null && fit.writesHolder() == at.equals(holder)) {
                    if (found != null) {
                        return null;
                    }
                    fit.wire = Wire.generic(prefix);
                    found = fit;
                }
            }
        }
        return found;
    }

    /**
     * Map entries whose key is the last segment of their leaf: two or more keys with the same rule below one parent make
     * one rule for every key ({@code textures{}}); a single key says nothing about others and keeps its own leaf.
     */
    static List<Rule> mapEntries(java.util.Collection<Rule> rules) {
        Map<String, List<Rule>> byParent = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.key != null && rule.mapValue() && rule.wire.endsWith("." + rule.key)) {
                byParent.computeIfAbsent(rule.wire.substring(0, rule.wire.length() - rule.key.length() - 1) + " " + rule.kind() + " " + rule.nbt, k -> new ArrayList<>()).add(rule);
            }
        }
        List<Rule> out = new ArrayList<>();
        Set<String> merged = new java.util.HashSet<>();
        for (Rule rule : rules) {
            List<Rule> siblings = byParent.values().stream().filter(l -> l.contains(rule)).findFirst().orElse(List.of());
            if (siblings.size() >= 2) {
                String parent = rule.wire.substring(0, rule.wire.length() - rule.key.length() - 1);
                if (merged.add(parent)) {
                    // the first sibling stands for every key
                    rule.key = null;
                    rule.wire = parent + "{}";
                    out.add(rule);
                }
                continue;
            }
            rule.key = null;
            out.add(rule);
        }
        return out;
    }

    /** Whether the model has fields below this path (a list, object or map with documented contents). */
    private static boolean container(Component component, String path) {
        String prefix = path.isEmpty() ? "" : path;
        return component.fields().stream().anyMatch(f -> !f.path().equals(prefix) && (prefix.isEmpty() || f.path().startsWith(prefix + ".")
            || f.path().startsWith(prefix + "[]") || f.path().startsWith(prefix + "{}")));
    }

    /** The keys of the wire paths a variant changes: the top level key, and for {@code item_properties} the one below it. */
    static Set<String> wireKeys(Set<String> paths) {
        Set<String> keys = new TreeSet<>();
        for (String path : paths) {
            List<String> segments = Wire.segments(path);
            String top = segments.get(0).replaceAll("\\[\\d+]", "");
            keys.add(top.equals("item_properties") && segments.size() > 1 ? top + "." + segments.get(1).replaceAll("\\[\\d+]", "") : top);
        }
        return keys;
    }

    private static Set<String> keysOf(Map<String, Leaf> wire) {
        Set<String> keys = new TreeSet<>();
        wire.keySet().forEach(k -> keys.add(Wire.segments(k).get(0).replaceAll("\\[\\d+]", "")));
        return keys;
    }

    private static JsonElement literal(String s) {
        return s.contains(".") ? new JsonPrimitive(Double.parseDouble(s)) : new JsonPrimitive(Long.parseLong(s));
    }

    private static String firstErrors(List<String> log) {
        Set<String> errors = new LinkedHashSet<>();
        log.forEach(l -> errors.add(SpecCaptures.errorText(l)));
        return String.join("; ", errors.stream().limit(3).toList());
    }
}
