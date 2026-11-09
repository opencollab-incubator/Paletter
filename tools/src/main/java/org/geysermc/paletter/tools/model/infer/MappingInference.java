package org.geysermc.paletter.tools.model.infer;

import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.client.Capture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.infer.Wire.Leaf;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.stream.Stream;

/**
 * Infers the JSON -> wire mapping of every component field from the spec captures
 * ({@code data/model/captures/bds-<version>/}: the variants BDS was given, its content log and what it sent).
 * <p>
 * Per component the control (companions only) shows what the component adds on the wire at all; per field the
 * variants that change only that field show which wire leaves follow it, and how: identity (possibly renamed, moved
 * into {@code item_properties}, re-typed), linear (unit conversions), a value table (enum -> int, string -> string),
 * a bit set (list of enum values -> int), list element -> leaf, presence. A field nothing on the wire follows is
 * dropped (server only); a field whose every variant BDS rejects is rejected. What fits none of these needs a named
 * hand transform ({@code data/model/transforms.json}).
 */
public final class MappingInference {
    private static final String ABSENT = "<absent>";

    private final Path root;
    private final Path modelDir;
    private Model model;
    private Fixtures fixtures;
    private JsonObject transforms;
    private Feedback feedback;

    public MappingInference(Path root) {
        this.root = root;
        this.modelDir = root.resolve("data/model");
    }

    record Variant(String identifier, String kind, String file, String component, String role, String path, String label,
                   JsonElement value, JsonElement json) {
        static Variant of(JsonObject o) {
            return new Variant(o.get("identifier").getAsString(), o.get("kind").getAsString(), o.get("file").getAsString(),
                o.get("component").getAsString(), o.get("role").getAsString(), o.get("path").getAsString(), o.get("label").getAsString(),
                o.has("value") ? o.get("value") : null, o.has("json") ? o.get("json") : null);
        }
    }

    /** A variant is accepted when BDS sent it and logged no error for it (items that fail to parse are still sent, without the component). */
    record Obs(Variant variant, Map<String, Leaf> wire, List<String> log) {
        boolean accepted() {
            return wire != null && log.stream().noneMatch(line -> line.startsWith("ERROR"));
        }
    }

    record Capture(String name, String bdsVersion, int protocol, List<Variant> variants, Map<String, Map<String, Leaf>> wire,
                   Map<String, List<String>> log, List<String> unattributed, Feedback feedback) {
    }

    /** @return false (in check mode) if a committed output differs */
    public boolean run(boolean check) throws IOException {
        model = Model.read(modelDir.resolve("components.json"));
        fixtures = Fixtures.read(modelDir);
        model = fixtures.apply(model);
        transforms = Files.exists(modelDir.resolve("transforms.json"))
            ? JsonParser.parseString(Files.readString(modelDir.resolve("transforms.json"))).getAsJsonObject() : new JsonObject();
        List<Capture> captures = new ArrayList<>();
        Path dir = root.resolve(org.geysermc.paletter.tools.Layout.BDS);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> dirs = Files.list(dir)) {
                for (Path capture : dirs.map(d -> d.resolve("spec")).filter(d -> Files.exists(d.resolve("variants.json"))).toList()) {
                    captures.add(load(capture));
                }
            }
        }
        // By protocol, not by name: the oldest build first, the newest last
        captures.sort(java.util.Comparator.comparingInt(Capture::protocol));
        // What BDS says about the schema is read from the newest build only: an older one knows fewer members
        feedback = captures.isEmpty() ? new Feedback() : captures.getLast().feedback();
        JsonObject mappings = new JsonObject();
        JsonArray sources = new JsonArray();
        for (Capture capture : captures) {
            JsonObject source = new JsonObject();
            source.addProperty("capture", "data/bds/" + capture.bdsVersion() + "/spec");
            source.addProperty("bdsVersion", capture.bdsVersion());
            source.addProperty("protocol", capture.protocol());
            source.addProperty("variants", capture.variants().size());
            source.addProperty("accepted", capture.variants().stream().filter(v -> capture.wire().containsKey(v.identifier())).count());
            sources.add(source);
        }
        mappings.add("captures", sources);
        mappings.addProperty("formatVersion", fixtures.formatVersion);
        JsonObject components = new JsonObject();
        Coverage coverage = new Coverage(model, fixtures, transforms);
        for (Component component : model.components()) {
            List<JsonObject> results = new ArrayList<>();
            List<Integer> resultProtocols = new ArrayList<>();
            for (Capture capture : captures) {
                JsonObject result = analyze(capture, component);
                if (result != null) {
                    results.add(result);
                    resultProtocols.add(dataProtocol(capture.protocol()));
                }
            }
            JsonObject merged = merge(results, resultProtocols);
            if (merged == null) {
                continue;
            }
            components.add(component.id(), merged);
            coverage.add(component, newest(merged));
        }
        mappings.add("components", components);
        String mappingsText = Model.GSON.toJson(mappings) + "\n";
        String coverageText = coverage.markdown(captures);
        String feedbackText = Model.GSON.toJson(feedback.json()) + "\n";
        if (check) {
            boolean same = mappingsText.equals(read(modelDir.resolve("mappings.json")))
                && feedbackText.equals(read(modelDir.resolve("bds-feedback.json")))
                && coverageText.equals(read(modelDir.resolve("coverage.md")));
            System.out.println(same ? "data/model/mappings.json and coverage.md are up to date"
                : "data/model/mappings.json or coverage.md is stale: run ./gradlew :tools:inferMappings");
            return same;
        }
        Files.writeString(modelDir.resolve("mappings.json"), mappingsText, StandardCharsets.UTF_8);
        Files.writeString(modelDir.resolve("coverage.md"), coverageText, StandardCharsets.UTF_8);
        Files.writeString(modelDir.resolve("bds-feedback.json"), feedbackText, StandardCharsets.UTF_8);
        System.out.println(coverage.summary());
        return true;
    }

    private static Set<String> keysOf(Map<String, Leaf> wire) {
        Set<String> keys = new TreeSet<>();
        wire.keySet().forEach(k -> keys.add(Wire.segments(k).get(0).replaceAll("\\[\\d+]", "")));
        return keys;
    }

    /**
     * The protocol windows are in terms of {@code WireContext.protocol()}, the first protocol of a palette's data version,
     * not of the protocol the capture was made on (a capture of 1.26.45.1 is protocol 2169, its data version 26.40 starts at
     * 2168): the first protocol of the newest bundled data version that is not newer than the capture, or the capture's
     * own protocol if no bundled version is that old.
     */
    private int dataProtocol(int capture) {
        int found = capture;
        try {
            Path versions = root.resolve("paletter/src/main/resources/paletter/versions.txt");
            if (Files.exists(versions)) {
                int best = Integer.MIN_VALUE;
                for (String line : Files.readAllLines(versions, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    int protocol = Integer.parseInt(line.trim().split("\\s+")[1]);
                    if (protocol <= capture && protocol > best) {
                        best = protocol;
                    }
                }
                if (best != Integer.MIN_VALUE) {
                    found = best;
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return found;
    }

    /**
     * One result per capture (oldest first) into the component's windows: a capture whose wire relevant part
     * ({@link #wireShape}) equals the previous window's joins that window ({@code protocols}); a different one starts a
     * new window, kept beside the first as {@code protocol<N>} (N: its first protocol). A window whose status is not
     * {@code sent} (rejected, server only) records that the component is not on the wire there.
     *
     * @return the first window with the others attached, or null without results
     */
    static JsonObject merge(List<JsonObject> results, List<Integer> protocols) {
        JsonObject first = null;
        JsonObject current = null;
        JsonElement currentShape = null;
        JsonArray currentProtocols = null;
        for (int i = 0; i < results.size(); i++) {
            JsonObject result = results.get(i);
            JsonElement shape = wireShape(result);
            if (current != null && shape.equals(currentShape)) {
                currentProtocols.add(protocols.get(i));
                continue;
            }
            current = result;
            currentShape = shape;
            currentProtocols = new JsonArray();
            currentProtocols.add(protocols.get(i));
            current.add("protocols", currentProtocols);
            if (first == null) {
                first = current;
            } else {
                first.add("protocol" + protocols.get(i), current);
            }
        }
        return first;
    }

    /** @return the window of the newest capture of a merged component */
    static JsonObject newest(JsonObject merged) {
        JsonObject newest = merged;
        int newestProtocol = merged.getAsJsonArray("protocols").get(0).getAsInt();
        for (String key : merged.keySet()) {
            if (key.matches("protocol\\d+") && Integer.parseInt(key.substring("protocol".length())) > newestProtocol) {
                newestProtocol = Integer.parseInt(key.substring("protocol".length()));
                newest = merged.getAsJsonObject(key);
            }
        }
        return newest;
    }

    /**
     * What of a result decides the bytes on the wire: the status, the wire keys, the constants and per form and field
     * its status, rules, transform and absent values. What BDS said about the JSON (errors, ranges, accepted and
     * rejected counts) is not part of it: another build with other messages or a different variant count is no new window.
     */
    static JsonElement wireShape(JsonObject result) {
        JsonObject shape = new JsonObject();
        for (String key : List.of("status", "wire", "constants")) {
            if (result.has(key)) {
                shape.add(key, result.get(key));
            }
        }
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = new JsonObject();
                for (String key : List.of("status", "rules", "transform", "whenAbsent")) {
                    if (e.getValue().getAsJsonObject().has(key)) {
                        field.add(key, e.getValue().getAsJsonObject().get(key));
                    }
                }
                kept.add(e.getKey(), field);
            }
            shape.add(group, kept);
        }
        return shape;
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Loading

    static Capture load(Path dir) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("capture.json"))).getAsJsonObject();
        List<Variant> variants = new ArrayList<>();
        JsonParser.parseString(Files.readString(dir.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()
            .forEach(e -> variants.add(Variant.of(e.getAsJsonObject())));
        Map<String, Map<String, Leaf>> wire = new LinkedHashMap<>();
        NbtMap items = Nbt.read(dir.resolve("custom_item_registry.nbt"));
        for (NbtMap item : items.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            NbtMap data = item.getCompound("data");
            if (item.getBoolean("componentBased") || !data.isEmpty()) {
                wire.put("item " + item.getString("name"), Wire.flatten(data.getCompound("components")));
            }
        }
        NbtMap blocks = Nbt.read(dir.resolve("custom_block_properties.nbt"));
        for (NbtMap block : blocks.getList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            Map<String, Leaf> flat = Wire.flatten(block.getCompound("properties"));
            flat.remove("vanilla_block_data.block_id");
            // Block components sit under "components"; lifted to the top level like item components
            Map<String, Leaf> leaves = new TreeMap<>();
            flat.forEach((key, leaf) -> leaves.put(key.startsWith("components.") ? key.substring("components.".length()) : key, leaf));
            wire.put("block " + block.getString("name"), leaves);
        }
        Map<String, Map<String, Leaf>> byVariant = new LinkedHashMap<>();
        for (Variant v : variants) {
            Map<String, Leaf> leaves = wire.get(v.kind() + " " + v.identifier());
            if (leaves != null) {
                byVariant.put(v.identifier(), leaves);
            }
        }
        Map<String, List<String>> log = new TreeMap<>();
        List<String> unattributed = new ArrayList<>();
        Feedback feedback = new Feedback();
        Pattern blockFile = Pattern.compile("blocks/([a-z0-9_]+)\\.json");
        Pattern itemFile = Pattern.compile("items/([a-z0-9_]+)\\.json");
        Pattern itemName = Pattern.compile("'(paletter:[a-z0-9_]+)'");
        Pattern bare = Pattern.compile("(paletter:[a-z0-9_]+)");
        for (String line : Files.readAllLines(dir.resolve("content-log.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            feedback.add(line.contains("[Item]") ? "item" : "block", line);
            String id = null;
            Matcher m;
            if ((m = blockFile.matcher(line)).find() || (m = itemFile.matcher(line)).find()) {
                id = "paletter:" + m.group(1);
            } else if ((m = itemName.matcher(line)).find() || (m = bare.matcher(line)).find()) {
                id = m.group(1);
            }
            if (id == null) {
                unattributed.add(line);
            } else {
                log.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
            }
        }
        return new Capture(dir.getFileName().toString(), meta.get("bdsVersion").getAsString(), meta.get("protocol").getAsInt(),
            variants, byVariant, log, unattributed, feedback);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Analysis

    private JsonObject analyze(Capture capture, Component component) {
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
        Set<String> keys = new TreeSet<>();
        for (String path : footprint) {
            List<String> segments = Wire.segments(path);
            String top = segments.get(0).replaceAll("\\[\\d+]", "");
            keys.add(top.equals("item_properties") && segments.size() > 1 ? top + "." + segments.get(1).replaceAll("\\[\\d+]", "") : top);
        }
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
            forms.add(form.getKey(), field(component, "", form.getValue(), control, filler, true));
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
            Obs reference = base != null && base.accepted() ? base : control;
            JsonObject f = field(component, field.path(), list, reference, filler, false);
            if (reference == control && base != null) {
                f.addProperty("reference", "control (the base was rejected)");
            }
            fields.add(field.path(), f);
        }
        result.add("fields", fields);
        JsonObject constants = constants(component, result, observations, control, base);
        if (!constants.isEmpty()) {
            result.add("constants", constants);
        }
        return result;
    }

    /**
     * Leaves of the component's wire keys that no rule of a field or form targets and that have the same value in every
     * accepted observation that sends them: what BDS writes whatever the JSON says ({@code grid_size: 3},
     * {@code hasJsonVersionBeforeValidation: 0b}, {@code damage_chance.max: 100} in every accepted variant). A compiler
     * writes them whenever it writes the component (list element leaves per element).
     */
    private static JsonObject constants(Component component, JsonObject result, List<Obs> observations, Obs control, Obs base) {
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
        String shortName = component.name().replaceFirst("^minecraft:", "");
        values.forEach((path, value) -> {
            if (varying.contains(path)) {
                return;
            }
            // A field whose variants could not move the leaf (one accepted value), named like the leaf and set to its
            // value in the base: the leaf follows the field (enchantable slot -> slot and item_properties.enchantable_slot)
            String last = path.substring(path.lastIndexOf('.') + 1);
            if (base != null && base.accepted()) {
                for (Map.Entry<String, JsonElement> f : result.getAsJsonObject("fields").entrySet()) {
                    JsonObject field = f.getValue().getAsJsonObject();
                    String status = field.get("status").getAsString();
                    String token = AddonGenerator.tokens(f.getKey()).getLast();
                    JsonElement json = f.getKey().contains("[") || f.getKey().contains("{") ? null : jsonAt(base.variant().json(), f.getKey());
                    if ((status.equals("no-evidence") || status.equals("dropped")) && (last.equals(token) || last.equals(shortName + "_" + token))
                        && json != null && json.isJsonPrimitive() && value.equals(json.getAsString() + value.substring(value.lastIndexOf(" (")))) {
                        JsonObject rule = new JsonObject();
                        rule.addProperty("kind", "identity");
                        rule.addProperty("nbt", value.substring(value.lastIndexOf(" (") + 2, value.length() - 1));
                        JsonArray tags = new JsonArray();
                        tags.add("assumed-by-name");
                        rule.add("tags", tags);
                        rule.addProperty("wire", path);
                        if (!field.has("rules")) {
                            field.add("rules", new JsonArray());
                        }
                        field.getAsJsonArray("rules").add(rule);
                        return;
                    }
                }
            }
            constants.addProperty(path, value);
        });
        return constants;
    }

    /** Whether a rule explains the leaf: it targets the leaf, a list or compound containing it, or a leaf inside it. */
    private static boolean explained(String leaf, Set<String> targeted) {
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
     */
    private JsonObject field(Component component, String path, List<Obs> variants, Obs reference, String filler, boolean form) {
        JsonObject out = new JsonObject();
        List<Obs> accepted = variants.stream().filter(Obs::accepted).toList();
        List<Obs> rejected = variants.stream().filter(o -> !o.accepted()).toList();
        JsonElement referenceValue = form ? null : jsonAt(reference.variant().json(), path);
        out.addProperty("accepted", accepted.size());
        out.addProperty("rejected", rejected.size());
        if (!rejected.isEmpty()) {
            JsonArray labels = new JsonArray();
            rejected.forEach(o -> labels.add(o.variant().label()));
            out.add("rejectedValues", labels);
        }
        List<String> errors = new ArrayList<>();
        variants.forEach(o -> o.log().forEach(line -> errors.add(errorText(line))));
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
        List<Pair> observations = new ArrayList<>();
        if (!form) {
            observations.add(new Pair(referenceValue, reference.wire(), "reference"));
        }
        for (Obs o : accepted) {
            JsonElement value = o.variant().role().equals("omit") ? null : form ? o.variant().json() : jsonAt(o.variant().json(), path);
            observations.add(new Pair(value, o.wire(), o.variant().label()));
        }
        Set<String> varying = new TreeSet<>();
        for (Obs o : accepted) {
            for (Map.Entry<String, Leaf[]> d : Wire.diff(reference.wire(), o.wire()).entrySet()) {
                if (filler != null && d.getKey().startsWith(filler) && d.getValue()[1] == null) {
                    continue;
                }
                varying.add(d.getKey());
            }
        }
        if (form) {
            // a whole value form also "varies" every leaf it shares with the control's absence of the component
            observations.add(0, new Pair(null, reference.wire(), "control"));
        }
        boolean distinctValues = observations.stream().map(p -> Objects.toString(p.json)).distinct().count() > 1;
        String status;
        JsonArray rules = new JsonArray();
        JsonArray unexplained = new JsonArray();
        JsonObject absent = new JsonObject();
        if (accepted.isEmpty()) {
            status = rejected.isEmpty() ? "not-generated" : "rejected";
        } else if (varying.isEmpty()) {
            status = distinctValues ? "dropped" : "no-evidence";
        } else {
            Map<String, JsonObject> byGeneric = new LinkedHashMap<>();
            for (String wire : varying) {
                JsonObject rule = fit(component, path, wire, observations);
                Leaf whenAbsent = observations.stream().filter(p -> p.json == null).map(p -> p.wire.get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
                boolean anyAbsent = observations.stream().anyMatch(p -> p.json == null);
                if (anyAbsent && !form) {
                    absent.addProperty(Wire.generic(wire), whenAbsent == null ? "omitted" : whenAbsent.toString());
                }
                boolean setVaries = observations.stream().filter(p -> p.json != null)
                    .anyMatch(p -> !Objects.equals(p.wire.get(wire), reference.wire().get(wire)));
                if (rule == null && !setVaries && !form) {
                    // only observations without the field differ (another field of the variant moved it)
                    rule = new JsonObject();
                    rule.addProperty("kind", "cross-field");
                    JsonArray t = new JsonArray();
                    t.add("depends-on-other-fields");
                    rule.add("tags", t);
                }
                if (rule == null && container(component, path)) {
                    // a list/object/map field: its elements' leaves are explained by its child fields
                    rule = new JsonObject();
                    rule.addProperty("kind", "structure");
                    JsonArray t = new JsonArray();
                    t.add("explained-by-child-fields");
                    rule.add("tags", t);
                }
                if (rule == null) {
                    JsonObject u = new JsonObject();
                    u.addProperty("wire", wire);
                    JsonArray samples = new JsonArray();
                    observations.stream().limit(6).forEach(p -> samples.add((p.json == null ? ABSENT : p.json.toString()) + " -> "
                        + (p.wire.get(wire) == null ? "-" : p.wire.get(wire).toString())));
                    u.add("samples", samples);
                    unexplained.add(u);
                } else {
                    String key = Wire.generic(wire) + " " + rule.get("kind").getAsString();
                    JsonObject existing = byGeneric.get(key);
                    if (existing == null) {
                        rule.addProperty("wire", Wire.generic(wire));
                        byGeneric.put(key, rule);
                    }
                }
            }
            byGeneric.values().forEach(rules::add);
            boolean onlyStructural = rules.asList().stream().allMatch(r -> List.of("structure", "cross-field", "constant", "empty-list")
                .contains(r.getAsJsonObject().get("kind").getAsString()));
            status = unexplained.isEmpty() ? (onlyStructural && !container(component, path) && !form ? "dropped" : "inferred")
                : rules.isEmpty() ? "unresolved" : "partial";
        }
        // A transform of the field, of an object or list containing it, or of the whole component covers it
        String transformKey = component.id() + ":" + path;
        for (String p = path; !transforms.has(transformKey); ) {
            int cut = Math.max(p.lastIndexOf('.'), p.lastIndexOf("[]"));
            if (cut < 0) {
                transformKey = transforms.has(component.id() + ":") ? component.id() + ":" : transformKey;
                break;
            }
            p = p.substring(0, cut);
            transformKey = transforms.has(component.id() + ":" + p) ? component.id() + ":" + p : transformKey;
        }
        if ((status.equals("unresolved") || status.equals("partial")) && transforms.has(transformKey)) {
            out.add("transform", transforms.get(transformKey));
            status = "hand-rule";
        }
        out.addProperty("status", status);
        if (!rules.isEmpty()) {
            out.add("rules", rules);
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

    record Pair(JsonElement json, Map<String, Leaf> wire, String label) {
    }

    /** Whether the model has fields below this path (a list, object or map with documented contents). */
    private static boolean container(Component component, String path) {
        String prefix = path.isEmpty() ? "" : path;
        return component.fields().stream().anyMatch(f -> !f.path().equals(prefix) && (prefix.isEmpty() || f.path().startsWith(prefix + ".")
            || f.path().startsWith(prefix + "[]") || f.path().startsWith(prefix + "{}")));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rule fitting

    private JsonObject fit(Component component, String path, String wire, List<Pair> observations) {
        List<Pair> set = observations.stream().filter(p -> p.json != null && !p.json.isJsonNull()).toList();
        if (set.isEmpty()) {
            return null;
        }
        String jsonName = path.isEmpty() ? "" : AddonGenerator.tokens(path).getLast();
        boolean scalars = set.stream().allMatch(p -> p.json.isJsonPrimitive());
        JsonObject rule = null;
        Field field = component.field(path);
        boolean enumerated = field != null && !field.enumValues().isEmpty();
        if (scalars) {
            rule = scalar(set, p -> p.json, wire);
            if (rule != null && rule.get("kind").getAsString().equals("table") && !tableAllowed(set, enumerated)) {
                rule = affix(set, wire);
            }
        } else if (set.stream().allMatch(p -> p.json.isJsonArray())) {
            rule = array(set, wire);
        } else if (set.stream().allMatch(p -> p.json.isJsonObject())) {
            rule = object(set, wire);
        } else {
            // Mixed forms (shorthand and object): fit each JSON type separately; take the scalar part if it fits
            List<Pair> primitive = set.stream().filter(p -> p.json.isJsonPrimitive()).toList();
            rule = primitive.isEmpty() ? null : scalar(primitive, p -> p.json, wire);
        }
        if (rule == null) {
            return null;
        }
        Leaf sample = set.stream().map(p -> p.wire.get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (sample != null) {
            rule.addProperty("nbt", sample.type());
        }
        rule.add("tags", tags(component, path, jsonName, wire, rule, set));
        return rule;
    }

    private static boolean numericTable(JsonObject rule) {
        return rule.get("kind").getAsString().equals("table")
            && rule.getAsJsonObject("table").keySet().stream().anyMatch(k -> k.matches("-?[\\d.]+"));
    }

    /** A value table generalizes only for closed sets: booleans and documented enums. */
    static boolean tableAllowed(List<Pair> set, boolean enumerated) {
        return set.stream().allMatch(p -> p.json.getAsJsonPrimitive().isBoolean())
            || (enumerated && set.stream().allMatch(p -> p.json.getAsJsonPrimitive().isString()));
    }

    /** Free strings: leaf = prefix + value + suffix (namespace added, "<>" event suffix). */
    static JsonObject affix(List<Pair> set, String wire) {
        String prefix = null;
        String suffix = null;
        for (Pair p : set) {
            if (!p.json.getAsJsonPrimitive().isString() || p.wire.get(wire) == null || !(p.wire.get(wire).value() instanceof String leaf)) {
                return null;
            }
            String x = p.json.getAsString();
            if (!x.contains(":") && leaf.contains("minecraft:" + x)) {
                x = "minecraft:" + x; // the default namespace is added
            }
            int at = leaf.indexOf(x);
            if (at < 0) {
                // a namespace the leaf adds to a bare value
                return null;
            }
            String pre = leaf.substring(0, at);
            String suf = leaf.substring(at + x.length());
            if ((prefix != null && !prefix.equals(pre)) || (suffix != null && !suffix.equals(suf))) {
                return null;
            }
            prefix = pre;
            suffix = suf;
        }
        JsonObject rule = new JsonObject();
        rule.addProperty("kind", "affix");
        rule.addProperty("prefix", prefix);
        rule.addProperty("suffix", suffix);
        return rule;
    }

    interface Getter {
        JsonElement get(Pair p);
    }

    /** identity, linear or table between a scalar JSON value and a leaf. */
    static JsonObject scalar(List<Pair> set, Getter getter, String wire) {
        List<JsonElement> xs = new ArrayList<>();
        List<Leaf> ys = new ArrayList<>();
        for (Pair p : set) {
            JsonElement x = getter.get(p);
            if (x == null || !x.isJsonPrimitive()) {
                return null;
            }
            xs.add(x);
            ys.add(p.wire.get(wire));
        }
        boolean identity = true;
        for (int i = 0; i < xs.size(); i++) {
            if (ys.get(i) == null || !same(xs.get(i), ys.get(i))) {
                identity = false;
                break;
            }
        }
        if (identity) {
            JsonObject rule = new JsonObject();
            rule.addProperty("kind", "identity");
            return rule;
        }
        // linear: y = a x + b over numbers (booleans as 0/1)
        if (xs.stream().allMatch(x -> x.getAsJsonPrimitive().isNumber()) && ys.stream().allMatch(y -> y != null && y.numeric())) {
            Set<Double> distinct = new TreeSet<>();
            xs.forEach(x -> distinct.add(x.getAsDouble()));
            if (distinct.size() >= 2) {
                int i = 0;
                int j = -1;
                for (int k = 1; k < xs.size(); k++) {
                    if (xs.get(k).getAsDouble() != xs.get(0).getAsDouble()) {
                        j = k;
                        break;
                    }
                }
                double a = (ys.get(j).number() - ys.get(i).number()) / (xs.get(j).getAsDouble() - xs.get(i).getAsDouble());
                double b = ys.get(i).number() - a * xs.get(i).getAsDouble();
                boolean fits = true;
                for (int k = 0; k < xs.size(); k++) {
                    if (!close(a * xs.get(k).getAsDouble() + b, ys.get(k).number())) {
                        fits = false;
                        break;
                    }
                }
                if (fits && Math.abs(a) > 1e-9) { // scale 0 is a constant, below
                    JsonObject rule = new JsonObject();
                    rule.addProperty("kind", "linear");
                    rule.addProperty("scale", round(a));
                    rule.addProperty("offset", round(b));
                    return rule;
                }
            }
        }
        // table: a function from JSON value to leaf (absent leaf = not sent)
        Map<String, String> table = new TreeMap<>();
        for (int i = 0; i < xs.size(); i++) {
            String key = xs.get(i).getAsJsonPrimitive().isString() ? xs.get(i).getAsString() : xs.get(i).toString();
            String value = ys.get(i) == null ? "<not sent>" : String.valueOf(ys.get(i).value());
            String existing = table.putIfAbsent(key, value);
            if (existing != null && !existing.equals(value)) {
                return null;
            }
        }
        if (table.size() < 2 || table.values().stream().distinct().count() < 2) {
            if (table.size() == 1 || table.values().stream().distinct().count() == 1) {
                // every tested value gives the same leaf: a constant the field's presence switches on
                JsonObject rule = new JsonObject();
                rule.addProperty("kind", "constant");
                rule.addProperty("value", table.values().iterator().next());
                return rule;
            }
        }
        JsonObject rule = new JsonObject();
        rule.addProperty("kind", "table");
        JsonObject entries = new JsonObject();
        table.forEach(entries::addProperty);
        rule.add("table", entries);
        return rule;
    }

    /** element k -> leaf, bit set, or list identity. */
    static JsonObject array(List<Pair> set, String wire) {
        // bit set: a list of strings -> int
        if (set.stream().allMatch(p -> p.json.getAsJsonArray().asList().stream().allMatch(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()))
            && set.stream().allMatch(p -> p.wire.get(wire) != null && p.wire.get(wire).numeric())) {
            Map<String, Long> bits = new TreeMap<>();
            for (Pair p : set) {
                if (p.json.getAsJsonArray().size() == 1) {
                    bits.put(p.json.getAsJsonArray().get(0).getAsString(), (long) p.wire.get(wire).number());
                }
            }
            boolean fits = bits.size() >= 2;
            for (Pair p : set) {
                long expected = 0;
                for (JsonElement e : p.json.getAsJsonArray()) {
                    Long bit = bits.get(e.getAsString());
                    if (bit == null) {
                        fits = false;
                        break;
                    }
                    expected |= bit;
                }
                if (!fits || expected != (long) p.wire.get(wire).number()) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                JsonObject rule = new JsonObject();
                rule.addProperty("kind", "bitset");
                JsonObject table = new JsonObject();
                bits.forEach(table::addProperty);
                rule.add("bits", table);
                return rule;
            }
        }
        // element k of the JSON list -> the leaf (index of the leaf first)
        Matcher index = Pattern.compile("\\[(\\d+)]$").matcher(wire);
        List<Integer> candidates = new ArrayList<>();
        if (index.find()) {
            candidates.add(Integer.parseInt(index.group(1)));
        }
        for (int k = 0; k < 4; k++) {
            if (!candidates.contains(k)) {
                candidates.add(k);
            }
        }
        for (int k : candidates) {
            final int at = k;
            List<Pair> having = set.stream().filter(p -> p.json.getAsJsonArray().size() > at).toList();
            boolean shorterUnsent = set.stream().filter(p -> p.json.getAsJsonArray().size() <= at).allMatch(p -> p.wire.get(wire) == null);
            if (having.isEmpty() || !shorterUnsent || having.stream().anyMatch(p -> !p.json.getAsJsonArray().get(at).isJsonPrimitive())) {
                continue;
            }
            JsonObject rule = scalar(having, p -> p.json.getAsJsonArray().get(at), wire);
            if (rule != null && having.size() < 2 && !rule.get("kind").getAsString().equals("identity")) {
                rule = null;
            }
            if (rule != null && numericTable(rule)) {
                rule = null;
            }
            if (rule != null && !rule.get("kind").getAsString().equals("constant")) {
                rule.addProperty("element", k);
                return rule;
            }
        }
        // list length (an empty marker or a count)
        if (set.stream().allMatch(p -> p.wire.get(wire) != null && p.wire.get(wire).numeric()
            && (long) p.wire.get(wire).number() == p.json.getAsJsonArray().size())) {
            JsonObject rule = new JsonObject();
            rule.addProperty("kind", "count");
            return rule;
        }
        if (set.stream().allMatch(p -> (p.json.getAsJsonArray().isEmpty()) == (p.wire.get(wire) != null && "[]".equals(p.wire.get(wire).value())))) {
            JsonObject rule = new JsonObject();
            rule.addProperty("kind", "empty-list");
            return rule;
        }
        return null;
    }

    /** map entry: the JSON key appears in the leaf path and its value maps; or presence of an (empty) object. */
    static JsonObject object(List<Pair> set, String wire) {
        // presence: the leaf exists iff the object is set
        boolean presence = set.stream().allMatch(p -> p.wire.get(wire) != null);
        Set<String> values = new TreeSet<>();
        set.forEach(p -> values.add(String.valueOf(p.wire.get(wire) == null ? null : p.wire.get(wire).value())));
        if (presence && values.size() == 1) {
            JsonObject rule = new JsonObject();
            rule.addProperty("kind", "constant");
            rule.addProperty("value", values.iterator().next());
            return rule;
        }
        // map entry: JSON {key: v} -> leaf ...key... = f(v)
        List<String> segments = Wire.segments(wire);
        for (Pair p : set) {
            for (Map.Entry<String, JsonElement> entry : p.json.getAsJsonObject().entrySet()) {
                if (segments.contains(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    List<Pair> having = set.stream().filter(q -> q.json.getAsJsonObject().has(entry.getKey())
                        && q.json.getAsJsonObject().get(entry.getKey()).isJsonPrimitive()).toList();
                    JsonObject rule = scalar(having, q -> q.json.getAsJsonObject().get(entry.getKey()), wire);
                    if (rule != null && !numericTable(rule)) {
                        rule.addProperty("mapEntry", true);
                        return rule;
                    }
                }
            }
        }
        return null;
    }

    private JsonArray tags(Component component, String path, String jsonName, String wire, JsonObject rule, List<Pair> set) {
        Set<String> tags = new LinkedHashSet<>();
        List<String> segments = Wire.segments(Wire.generic(wire));
        String leafName = segments.getLast().replace("[]", "");
        String kind = rule.get("kind").getAsString();
        if (kind.equals("constant") || kind.equals("empty-list")) {
            // a leaf the field's presence switches on (a sibling default BDS writes with it), not the field's value
            JsonArray array = new JsonArray();
            array.add(!jsonName.isEmpty() && leafName.equals(jsonName) ? "presence" : "injected-default");
            return array;
        }
        if (path.isEmpty() && wire.endsWith(".value")) {
            tags.add("wrapped-in-value");
        }
        if (!jsonName.isEmpty() && !leafName.equals(jsonName) && !jsonName.equals("[]") && !jsonName.equals("{}")) {
            tags.add("renamed");
        }
        if (wire.startsWith("item_properties")) {
            tags.add("item_properties");
        } else if (!segments.get(0).replace("[]", "").equals(component.name()) && component.category().equals("component")) {
            tags.add("outside-component:" + segments.get(0).replace("[]", ""));
        }
        int jsonDepth = path.isEmpty() ? 0 : AddonGenerator.tokens(path).stream().filter(t -> !t.equals("[]") && !t.equals("{}")).toList().size();
        int wireDepth = segments.size() - (component.category().equals("component") ? 1 : 0);
        if (!path.isEmpty() && wireDepth != jsonDepth && !wire.startsWith("item_properties")) {
            tags.add(wireDepth > jsonDepth ? "nested" : "flattened");
        }
        JsonElement x = set.getFirst().json;
        Leaf y = set.stream().map(p -> p.wire.get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (y != null && x.isJsonPrimitive()) {
            JsonPrimitive p = x.getAsJsonPrimitive();
            String jsonType = p.isBoolean() ? "boolean" : p.isNumber() ? (isInteger(p) ? "integer" : "number") : "string";
            String nbt = y.type();
            if (!(jsonType.equals("integer") && nbt.equals("int")) && !(jsonType.equals("number") && nbt.equals("float"))
                && !(jsonType.equals("string") && nbt.equals("string"))) {
                tags.add("type:" + jsonType + "->" + nbt);
            }
        }
        if (kind.equals("linear")) {
            tags.add("unit-conversion");
        }
        if (kind.equals("table")) {
            boolean numbers = rule.getAsJsonObject("table").entrySet().stream().allMatch(e -> e.getValue().getAsString().matches("-?\\d+"));
            tags.add(numbers ? "enum->int" : "value-map");
        }
        if (rule.has("element")) {
            tags.add("list-element");
        }
        JsonArray array = new JsonArray();
        tags.forEach(array::add);
        return array;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    static JsonElement jsonAt(JsonElement root, String path) {
        if (root == null || root.isJsonNull()) {
            return null;
        }
        JsonElement current = root;
        for (String token : AddonGenerator.tokens(path)) {
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

    private static boolean same(JsonElement x, Leaf y) {
        JsonPrimitive p = x.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return y.numeric() && y.number() == (p.getAsBoolean() ? 1 : 0);
        }
        if (p.isNumber()) {
            return y.numeric() && close(p.getAsDouble(), y.number());
        }
        return y.value() instanceof String s && s.equals(p.getAsString());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-5 * Math.max(1, Math.abs(a));
    }

    private static double round(double d) {
        return Math.round(d * 1e6) / 1e6;
    }

    private static boolean isInteger(JsonPrimitive p) {
        String s = p.getAsString();
        return s.matches("-?\\d+");
    }

    private static JsonElement literal(String s) {
        return s.contains(".") ? new JsonPrimitive(Double.parseDouble(s)) : new JsonPrimitive(Long.parseLong(s));
    }

    /** The part of a content log line after the file and JSON path, e.g. "the input is outside of the accepted range [1, 54]". */
    static String errorText(String line) {
        String text = line.replaceFirst("^(ERROR|WARN) ", "");
        int arrow = text.lastIndexOf("-> ");
        if (arrow >= 0) {
            text = text.substring(arrow + 3);
        }
        return text.replaceAll("paletter:[a-z0-9_]+", "<id>").replace("'", "").trim();
    }

    private static String firstErrors(List<String> log) {
        Set<String> errors = new LinkedHashSet<>();
        log.forEach(l -> errors.add(errorText(l)));
        return String.join("; ", errors.stream().limit(3).toList());
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
