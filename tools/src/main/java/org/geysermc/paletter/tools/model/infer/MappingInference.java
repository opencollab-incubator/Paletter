package org.geysermc.paletter.tools.model.infer;

import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.client.Capture;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.codegen.component.HandTransforms;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.rule.Affix;
import org.geysermc.paletter.tools.model.rule.Bitset;
import org.geysermc.paletter.tools.model.rule.Constant;
import org.geysermc.paletter.tools.model.rule.Count;
import org.geysermc.paletter.tools.model.rule.CrossField;
import org.geysermc.paletter.tools.model.rule.EmptyList;
import org.geysermc.paletter.tools.model.rule.EmptyMap;
import org.geysermc.paletter.tools.model.rule.Identity;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.geysermc.paletter.tools.model.rule.Linear;
import org.geysermc.paletter.tools.model.rule.Observation;
import org.geysermc.paletter.tools.model.rule.OrderedSet;
import org.geysermc.paletter.tools.model.rule.Rule;
import org.geysermc.paletter.tools.model.rule.Structure;
import org.geysermc.paletter.tools.model.rule.Table;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
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
 * hand transform ({@link HandTransforms}).
 */
public final class MappingInference {
    private static final String ABSENT = "<absent>";

    private final Path root;
    private final Path modelDir;
    private Model model;
    private Fixtures fixtures;
    private final Map<String, String> transforms = HandTransforms.BY_PATH;
    private Feedback feedback;

    public MappingInference(Path root) {
        this.root = root;
        this.modelDir = root.resolve("data/model");
    }

    /**
     * @param linked the other fields the variant sets because BDS ties them to the varied one (add-on generator links)
     */
    record Variant(String identifier, String kind, String file, String component, String role, String path, String label,
                   JsonElement value, JsonElement json, List<String> linked) {
        static Variant of(JsonObject o) {
            return new Variant(o.get("identifier").getAsString(), o.get("kind").getAsString(), o.get("file").getAsString(),
                o.get("component").getAsString(), o.get("role").getAsString(), o.get("path").getAsString(), o.get("label").getAsString(),
                o.has("value") ? o.get("value") : null, o.has("json") ? o.get("json") : null,
                o.has("linked") ? o.getAsJsonArray("linked").asList().stream().map(JsonElement::getAsString).toList() : List.of());
        }
    }

    /** A variant is accepted when BDS sent it and logged no error for it (items that fail to parse are still sent, without the component). */
    record Obs(Variant variant, Map<String, Leaf> wire, List<String> log) {
        boolean accepted() {
            return wire != null && log.stream().noneMatch(line -> line.startsWith("ERROR"));
        }
    }

    /**
     * @param definitions {@code <kind> <identifier>} -> what BDS sent for it, flattened: the item's {@code components}, the
     *                    block's property data as sent (components not lifted, {@code block_id} left out)
     */
    record Capture(String name, String bdsVersion, int protocol, List<Variant> variants, Map<String, Map<String, Leaf>> wire,
                   Map<String, List<String>> log, List<String> unattributed, Feedback feedback, Map<String, Map<String, Leaf>> definitions,
                   CustomBlockIds customBlockIds) {
    }

    /**
     * How BDS numbers custom blocks in a capture: {@code vanilla_block_data.block_id} counts up from {@code first}, and the
     * item of a custom block has the runtime id {@code itemIdPlusBlockId - block_id}.
     */
    record CustomBlockIds(int first, int itemIdPlusBlockId) {
        /**
         * @param ownItems the identifiers of the item variants: a block a link gave an item's identifier has no block item
         * @return the numbering, or null for a capture without custom blocks
         */
        static CustomBlockIds of(String capture, List<NbtMap> blocks, List<NbtMap> items, Set<String> ownItems) {
            Map<String, Integer> blockIds = new TreeMap<>();
            for (NbtMap block : blocks) {
                blockIds.put(block.getString("name"), block.getCompound("properties").getCompound("vanilla_block_data").getInt("block_id"));
            }
            if (blockIds.isEmpty()) {
                return null;
            }
            int first = blockIds.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
            if (!new TreeSet<>(blockIds.values()).equals(new TreeSet<>(Stream.iterate(first, i -> i + 1).limit(blockIds.size()).toList()))) {
                throw new IllegalStateException(capture + ": the custom block ids are not " + first + ".." + (first + blockIds.size() - 1));
            }
            Set<Integer> sums = new TreeSet<>();
            for (NbtMap item : items) {
                Integer blockId = ownItems.contains(item.getString("name")) ? null : blockIds.get(item.getString("name"));
                if (blockId != null) {
                    sums.add(((Number) item.get("id")).intValue() + blockId);
                }
            }
            if (sums.isEmpty()) {
                return null; // only blocks that items' links defined: nothing to number
            }
            if (sums.size() != 1) {
                throw new IllegalStateException(capture + ": block item id + block_id is not one constant but " + sums);
            }
            return new CustomBlockIds(first, sums.iterator().next());
        }
    }

    private final List<String> unexplainedBits = new ArrayList<>();
    private final Set<String> linkedFields = new TreeSet<>();

    /**
     * The findings of the last {@link #run} whose bits no format sweep explains: bits every value sets and nothing explains.
     * A human has to name them in the hand transform. {@code check-mappings --strict} and {@code update} fail on them.
     */
    public List<String> unexplainedBits() {
        return unexplainedBits;
    }

    /**
     * The fields of the last {@link #run} whose value no rule follows (nothing on the wire does, or only their presence)
     * while the messages of their variants name another field: what BDS sent says nothing about them, as the other field
     * decided it (status {@code linked}). The add-on
     * generator varies them with that field ({@code bds-feedback.json} links) once the capture shows how they are tied.
     * {@code check-mappings --strict} and {@code update} fail on them.
     */
    public List<String> linkedFields() {
        return List.copyOf(linkedFields);
    }

    /** The generated texts. */
    private record Output(String mappings, String coverage, String feedback, Coverage coverageModel) {
    }

    /**
     * @param check compare with the committed outputs instead of writing them
     * @return false if a committed output differs (check mode); the outputs are written outside check mode
     */
    public boolean run(boolean check) throws IOException {
        Output output = compute();
        String mappingsText = output.mappings();
        String coverageText = output.coverage();
        String feedbackText = output.feedback();
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
        System.out.println(output.coverageModel().summary());
        return true;
    }

    private Output compute() throws IOException {
        model = Model.read(modelDir.resolve("components.json"));
        fixtures = Fixtures.read(modelDir);
        model = fixtures.apply(model);
        List<Capture> captures = new ArrayList<>();
        Map<String, Path> directories = new HashMap<>();
        Path dir = root.resolve(org.geysermc.paletter.tools.Layout.BDS);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> dirs = Files.list(dir)) {
                for (Path capture : dirs.map(d -> d.resolve("spec")).filter(d -> Files.exists(d.resolve("variants.json"))).toList()) {
                    Capture loaded = load(capture);
                    captures.add(loaded);
                    directories.put(loaded.bdsVersion(), capture);
                }
            }
        }
        // Several kept builds of one window (hotfixes, with or without a protocol bump): the build its data version was
        // imported from defines it
        Set<String> historyKept = new TreeSet<>();
        org.geysermc.paletter.tools.update.BuildHistory.read(root).forEach((build, entry) -> {
            if (entry.keptByHistoryCheck()) {
                historyKept.add(build);
            }
        });
        Map<Integer, Window> windows = windowCaptures(captures, this::dataProtocol, directories, historyKept);
        Map<Integer, List<String>> replaced = new TreeMap<>();
        Map<Integer, List<String>> differing = new TreeMap<>();
        windows.forEach((window, selected) -> {
            if (!selected.replaces().isEmpty()) {
                replaced.put(window, selected.replaces());
            }
            if (!selected.differs().isEmpty()) {
                differing.put(window, selected.differs());
                System.out.println("GAP: " + String.join(", ", selected.differs()) + " differ from " + selected.capture().bdsVersion()
                    + " (window " + window + "): each needs a data version of its own; their captures are not used");
            }
        });
        captures = new ArrayList<>(windows.values().stream().map(Window::capture).toList());
        // By protocol, not by name: the oldest build first, the newest last
        captures.sort(java.util.Comparator.comparingInt(Capture::protocol));
        // What BDS says about the schema is read from the newest release: an older one knows fewer members, and a preview
        // may not know some yet. Members only the newest preview knows stay (their API is @SincePreview)
        List<Capture> releases = new ArrayList<>();
        Capture newestPreview = null;
        for (Capture capture : captures) {
            if (previewName(capture) == null) {
                releases.add(capture);
            } else {
                newestPreview = capture;
            }
        }
        feedback = releases.isEmpty() ? (captures.isEmpty() ? new Feedback() : captures.getLast().feedback()) : releases.getLast().feedback();
        if (newestPreview != null && !releases.isEmpty()) {
            feedback.knownIn(newestPreview.feedback());
        }
        JsonObject mappings = new JsonObject();
        mappings.addProperty(Model.GENERATED_KEY, "Generated by `./gradlew :tools:inferMappings` (spec infer) from components.json, "
            + "fixtures.json, the hand transforms (HandTransforms) and the spec captures under data/bds. Do not edit.");
        JsonArray sources = new JsonArray();
        for (Capture capture : captures) {
            JsonObject source = new JsonObject();
            source.addProperty("capture", "data/bds/" + capture.bdsVersion() + "/spec");
            source.addProperty("bdsVersion", capture.bdsVersion());
            source.addProperty("protocol", capture.protocol());
            source.addProperty("windowProtocol", dataProtocol(capture.protocol()));
            if (replaced.containsKey(dataProtocol(capture.protocol()))) {
                // Older kept builds of the same window, whose captures this one replaces
                JsonArray older = new JsonArray();
                replaced.get(dataProtocol(capture.protocol())).stream().sorted(org.geysermc.paletter.tools.Versions.ORDER).forEach(older::add);
                source.add("replaces", older);
            }
            if (differing.containsKey(dataProtocol(capture.protocol()))) {
                // Kept builds of the window with other spec data: a gap, a data version of their own is missing
                JsonArray other = new JsonArray();
                differing.get(dataProtocol(capture.protocol())).forEach(other::add);
                source.add("differs", other);
            }
            // A preview build: what only its window has is preview API (@SincePreview with this version name)
            if (previewName(capture) != null) {
                source.addProperty("preview", previewName(capture));
            }
            source.addProperty("variants", capture.variants().size());
            source.addProperty("accepted", capture.variants().stream().filter(v -> capture.wire().containsKey(v.identifier())).count());
            if (capture.customBlockIds() != null) {
                source.addProperty("firstCustomBlockId", capture.customBlockIds().first());
                source.addProperty("blockItemIdPlusBlockId", capture.customBlockIds().itemIdPlusBlockId());
            }
            sources.add(source);
        }
        mappings.add("captures", sources);
        mappings.addProperty("formatVersion", fixtures.formatVersion);
        JsonObject traitStates = TraitStateTable.infer(root, model);
        if (!traitStates.isEmpty()) {
            mappings.add("traitStates", traitStates);
        }
        JsonObject components = new JsonObject();
        Coverage coverage = new Coverage(model, fixtures, transforms);
        Map<String, String> findings = new TreeMap<>();
        Map<String, Long> formatBits = formatDependentBits();
        unexplainedBits.clear();
        linkedFields.clear();
        // Per capture: component id -> its result there
        List<Map<String, JsonObject>> resultsByCapture = new ArrayList<>();
        captures.forEach(capture -> resultsByCapture.add(new TreeMap<>()));
        for (Component component : model.components()) {
            List<JsonObject> results = new ArrayList<>();
            List<Integer> resultProtocols = new ArrayList<>();
            for (int i = 0; i < captures.size(); i++) {
                Capture capture = captures.get(i);
                JsonObject result = analyze(capture, component);
                if (result != null) {
                    resultsByCapture.get(i).put(component.id(), result);
                    collectFindings(findings, component.id(), result, formatBits);
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
            steps(component.id(), newest(merged));
        }
        formats(components);
        mappings.add("envelope", envelopeWindows(captures, resultsByCapture));
        mappings.add("components", components);
        coverage.wirePaths(org.geysermc.paletter.tools.data.WireShapes.uncoveredByBuild(root.resolve(org.geysermc.paletter.tools.Layout.BDS), mappings, model));
        coverage.findings(findings);
        findings.forEach((id, description) -> {
            if (description.contains(UNEXPLAINED)) {
                unexplainedBits.add(id + ": " + description);
            }
        });
        return new Output(Model.GSON.toJson(mappings) + "\n", coverage.markdown(captures), Model.GSON.toJson(feedback.json()) + "\n", coverage);
    }

    /**
     * Adds {@code formats} ({@link FormatInference}) to the components the format sweeps asked, and the formats BDS accepted them
     * at to the feedback ({@code format <F>}). The newest build that swept a component decides.
     */
    private void formats(JsonObject components) throws IOException {
        Map<String, Set<String>> wireKeys = new HashMap<>();
        components.entrySet().forEach(e -> {
            Set<String> keys = new TreeSet<>();
            JsonObject newest = newest(e.getValue().getAsJsonObject());
            if (newest.has("wire")) {
                newest.getAsJsonArray("wire").forEach(k -> keys.add(k.getAsString()));
            }
            wireKeys.put(e.getKey(), keys);
        });
        List<Path> sweeps = new ArrayList<>();
        Path bds = root.resolve(org.geysermc.paletter.tools.Layout.BDS);
        if (Files.isDirectory(bds)) {
            try (Stream<Path> builds = Files.list(bds)) {
                builds.map(b -> b.resolve("spec-format")).filter(d -> Files.exists(d.resolve("variants.json"))).sorted().forEach(sweeps::add);
            }
        }
        Map<String, java.util.TreeMap<String, JsonObject>> swept = new TreeMap<>();
        for (Path sweep : sweeps.stream().sorted(Comparator.comparingInt((Path d) -> protocolOf(d))).toList()) {
            swept.putAll(FormatInference.read(sweep, model, fixtures, wireKeys));
        }
        swept.forEach((id, byFormat) -> {
            if (!components.has(id)) {
                return;
            }
            JsonObject formats = new JsonObject();
            byFormat.forEach((format, entry) -> {
                formats.add(format, entry);
                feedback.format(id, format);
            });
            components.getAsJsonObject(id).add("formats", formats);
        });
    }

    private static int protocolOf(Path capture) {
        try {
            return JsonParser.parseString(Files.readString(capture.resolve("capture.json"))).getAsJsonObject().get("protocol").getAsInt();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final String UNEXPLAINED = "UNEXPLAINED";

    /**
     * Adds the findings of a capture's result. Captures come oldest first and the newest decides: an older window's finding
     * must not hide a bit that only a newer build sets.
     */
    static void collectFindings(Map<String, String> findings, String componentId, JsonObject result, Map<String, Long> formatBits) {
        if (result.has("findings")) {
            result.getAsJsonArray("findings").forEach(f -> findings.put(f.getAsJsonObject().get("id").getAsString(),
                describe(f.getAsJsonObject(), formatBits.getOrDefault(componentId + " " + leafOfFinding(f.getAsJsonObject()), 0L))));
        }
    }

    /**
     * What a finding says, and whether the format sweep explains its bits: a bit BDS sets or clears by the add-on's
     * {@code format_version} is explained, as Paletter writes the current format.
     *
     * @param formatBits the bits of the finding's leaf that the format sweep saw change with the format version
     */
    private static String describe(JsonObject finding, long formatBits) {
        long bits = 0;
        for (JsonElement bit : finding.getAsJsonArray("bits")) {
            bits |= 1L << bit.getAsInt();
        }
        String what = "bits " + finding.get("bits") + " are set in every value (" + finding.get("values") + ") of " + finding.getAsJsonArray("fields").size()
            + " fields that pack into one leaf, and no field explains them";
        return what + ((bits & ~formatBits) == 0
            ? ". Explained: BDS sets them by the add-on's format_version (format sweeps, data/bds/*/spec-format/format-sweep.json), and Paletter writes the current format."
            : ". " + UNEXPLAINED + ": the format sweep does not change bits " + Long.toBinaryString(bits & ~formatBits) + " (binary); name them in the hand transform.");
    }

    /** The leaf name of a finding: the last word of its id ({@code residual-bits <component> <leaf>}). */
    private static String leafOfFinding(JsonObject finding) {
        String id = finding.get("id").getAsString();
        return id.substring(id.lastIndexOf(' ') + 1);
    }

    /**
     * {@code <component> <leaf name>} -> the bits that differ between format versions in the format sweeps of every build
     * ({@code data/bds/<build>/spec-format/format-sweep.json}, field {@code differingBits}, binary).
     */
    private Map<String, Long> formatDependentBits() throws IOException {
        Map<String, Long> out = new TreeMap<>();
        Path dir = root.resolve(org.geysermc.paletter.tools.Layout.BDS);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> sweeps;
        try (Stream<Path> builds = Files.list(dir)) {
            sweeps = builds.map(b -> b.resolve("spec-format/format-sweep.json")).filter(Files::exists).toList();
        }
        for (Path sweep : sweeps) {
            JsonObject components = JsonParser.parseString(Files.readString(sweep)).getAsJsonObject().getAsJsonObject("components");
            components.entrySet().forEach(component -> component.getValue().getAsJsonObject().entrySet().forEach(key -> {
                JsonObject formats = key.getValue().getAsJsonObject().getAsJsonObject("formats");
                formats.entrySet().forEach(format -> {
                    JsonObject differs = format.getValue().getAsJsonObject().getAsJsonObject("differs");
                    if (differs == null) {
                        return;
                    }
                    differs.entrySet().forEach(leaf -> {
                        JsonObject change = leaf.getValue().getAsJsonObject();
                        if (change.has("differingBits")) {
                            String name = leaf.getKey().substring(leaf.getKey().lastIndexOf('.') + 1);
                            out.merge(component.getKey() + " " + name, Long.parseLong(change.get("differingBits").getAsString(), 2), (a, b) -> a | b);
                        }
                    });
                });
            }));
        }
        return out;
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

    /**
     * The protocol windows are in terms of {@code WireContext.protocol()}, the first protocol of a palette's data version,
     * not of the protocol the capture was made on (a capture of 1.26.45.1 is protocol 2169, its data version 26.40 starts at
     * 2168): the first protocol of the newest bundled data version that is not newer than the capture, or the capture's
     * own protocol if no bundled version is that old.
     */
    /** The bundled version name of a preview build's capture ({@code capture.json} says {@code preview}), null for a release. */
    private String previewName(Capture capture) throws IOException {
        Path file = root.resolve(org.geysermc.paletter.tools.Layout.BDS).resolve(capture.bdsVersion()).resolve("spec/capture.json");
        if (!Files.exists(file)) {
            return null;
        }
        JsonObject meta = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        boolean preview = meta.has("preview") && meta.get("preview").getAsBoolean();
        return preview ? org.geysermc.paletter.tools.update.Update.versionName(capture.bdsVersion()) : null;
    }

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
        return org.geysermc.paletter.tools.model.MappingWindows.newest(merged);
    }

    /**
     * Feeds the whole steps of a component's newest rules back to the generator ({@link Feedback#step}): a linear rule onto
     * an integer leaf whose input step ({@code 1 / scale}) is a whole number of at least 2.
     */
    private void steps(String componentId, JsonObject newest) {
        if (!newest.has("fields")) {
            return;
        }
        for (Map.Entry<String, JsonElement> field : newest.getAsJsonObject("fields").entrySet()) {
            JsonObject result = field.getValue().getAsJsonObject();
            for (Rule rule : Rule.all(result)) {
                if (rule instanceof Linear linear && "int".equals(rule.nbt)) {
                    double step = Math.abs(1 / linear.scale);
                    if (step >= 2 && Math.abs(step - Math.rint(step)) < 1e-3) {
                        feedback.step(componentId, field.getKey(), Math.round(step));
                    }
                }
            }
        }
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

    /**
     * The capture that defines a data version window, the other kept builds of the window with the same spec data
     * ({@code replaces}) and those with other data ({@code differs}, each a gap: it needs a data version of its own), both
     * oldest first.
     */
    record Window(Capture capture, List<String> replaces, List<String> differs) {
    }

    /**
     * The capture of each window (by {@code window}, the data protocol of a capture's protocol): the newest build that a
     * history check did not keep, which is the build the window's data version was imported from; the newest of all when
     * every one was. The others are compared with it ({@link SpecDiff#compare} and {@link SpecDiff#acceptance}): the same
     * data and it stands for them, other data and they are a gap, never the window's capture.
     *
     * @param directories the spec capture directory of each build
     * @param historyKept the builds whose captures a history check kept ({@code data/bds/builds.json})
     * @throws IllegalStateException when two captures of a window were made with different spec add-ons, so nothing
     *                               could be said about them: recapture
     */
    static Map<Integer, Window> windowCaptures(List<Capture> captures, java.util.function.IntUnaryOperator window,
                                               Map<String, Path> directories, Set<String> historyKept) throws IOException {
        Map<Integer, List<Capture>> byWindow = new TreeMap<>();
        for (Capture capture : captures) {
            byWindow.computeIfAbsent(window.applyAsInt(capture.protocol()), w -> new ArrayList<>()).add(capture);
        }
        Map<Integer, Window> windows = new TreeMap<>();
        List<String> unpaired = new ArrayList<>();
        for (Map.Entry<Integer, List<Capture>> entry : byWindow.entrySet()) {
            List<Capture> builds = new ArrayList<>(entry.getValue());
            builds.sort(java.util.Comparator.comparing(Capture::bdsVersion, org.geysermc.paletter.tools.Versions.ORDER));
            Capture chosen = builds.stream().filter(capture -> !historyKept.contains(capture.bdsVersion())).reduce((a, b) -> b)
                .orElse(builds.getLast());
            List<String> replaces = new ArrayList<>();
            List<String> differs = new ArrayList<>();
            for (Capture other : builds) {
                if (other == chosen) {
                    continue;
                }
                SpecDiff.Pairing pairing = SpecDiff.pairing(other, chosen);
                if (!pairing.same()) {
                    unpaired.add(other.bdsVersion() + " vs " + chosen.bdsVersion() + ": " + pairing.mismatch());
                    continue;
                }
                Path before = directories.get(other.bdsVersion());
                Path after = directories.get(chosen.bdsVersion());
                boolean same = SpecDiff.compare(before, after).isEmpty() && SpecDiff.acceptance(before, after).isEmpty();
                (same ? replaces : differs).add(other.bdsVersion());
            }
            windows.put(entry.getKey(), new Window(chosen, replaces, differs));
        }
        if (!unpaired.isEmpty()) {
            throw new IllegalStateException("Spec captures of one data version window cannot be compared: " + String.join("; ", unpaired));
        }
        return windows;
    }

    static Capture load(Path dir) throws IOException {
        JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve("capture.json"))).getAsJsonObject();
        List<Variant> variants = new ArrayList<>();
        JsonParser.parseString(Files.readString(dir.resolve("variants.json"), StandardCharsets.UTF_8)).getAsJsonArray()
            .forEach(e -> variants.add(Variant.of(e.getAsJsonObject())));
        Map<String, Map<String, Leaf>> wire = new LinkedHashMap<>();
        Map<String, Map<String, Leaf>> definitions = new LinkedHashMap<>();
        NbtMap items = Nbt.read(dir.resolve("custom_item_registry.nbt"));
        for (NbtMap item : items.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            NbtMap data = item.getCompound("data");
            if (item.getBoolean("componentBased") || !data.isEmpty()) {
                wire.put("item " + item.getString("name"), Wire.flatten(data.getCompound("components")));
                definitions.put("item " + item.getString("name"), wire.get("item " + item.getString("name")));
            }
        }
        NbtMap blocks = Nbt.read(dir.resolve("custom_block_properties.nbt"));
        for (NbtMap block : blocks.getList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND)) {
            Map<String, Leaf> flat = Wire.flatten(block.getCompound("properties"));
            flat.remove("vanilla_block_data.block_id");
            definitions.put("block " + block.getString("name"), flat);
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
        Map<String, Variant> byIdentifier = new LinkedHashMap<>();
        variants.forEach(v -> byIdentifier.put(v.identifier(), v));
        for (String line : Files.readAllLines(dir.resolve("content-log.txt"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String id = null;
            Matcher m;
            if ((m = blockFile.matcher(line)).find() || (m = itemFile.matcher(line)).find()) {
                id = "paletter:" + m.group(1);
            } else if ((m = itemName.matcher(line)).find() || (m = bare.matcher(line)).find()) {
                id = m.group(1);
            }
            feedback.add(line.contains("[Item]") ? "item" : "block", line, id == null ? null : byIdentifier.get(id));
            if (id == null) {
                unattributed.add(line);
            } else {
                log.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
            }
        }
        Set<String> ownItems = new TreeSet<>();
        variants.stream().filter(v -> v.kind().equals("item")).forEach(v -> ownItems.add(v.identifier()));
        CustomBlockIds customBlockIds = CustomBlockIds.of(dir.toString(), blocks.getList("blocks", org.cloudburstmc.nbt.NbtType.COMPOUND),
            items.getList("items", org.cloudburstmc.nbt.NbtType.COMPOUND), ownItems);
        return new Capture(dir.getFileName().toString(), meta.get("bdsVersion").getAsString(), meta.get("protocol").getAsInt(),
            variants, byVariant, log, unattributed, feedback, definitions, customBlockIds);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Envelope

    /**
     * What BDS sends around the components of every custom item and block, per kind as protocol windows (oldest first; a
     * capture whose envelope equals the previous window's joins it): {@code {item: [{protocols, leaves}], block: [...]}},
     * leaves as {@code path: "value (type)"}. Item paths are relative to the {@code components} compound, block paths to
     * the block property data.
     * <p>
     * The envelope of a capture is what every accepted control variant of the kind sends alike, once the leaves its
     * companions (fixtures {@code requires}, the item filler) and its description explain are taken out: the rules of the
     * companion and of the description write those (a {@code minecraft:wearable} companion sets
     * {@code item_properties.max_stack_size} to 1). What is left is written whatever the definition says:
     * {@code item_properties} defaults, {@code item_tags}, {@code molangVersion}, {@code vanilla_block_data.material}.
     *
     * @param results per capture: component id -> its result in that capture
     */
    private JsonObject envelopeWindows(List<Capture> captures, List<Map<String, JsonObject>> results) {
        JsonObject out = new JsonObject();
        for (String kind : List.of("item", "block")) {
            JsonArray windows = new JsonArray();
            JsonObject current = null;
            for (int i = 0; i < captures.size(); i++) {
                JsonObject leaves = new JsonObject();
                envelope(captures.get(i), kind, results.get(i)).forEach((path, leaf) -> leaves.addProperty(path, leaf.toString()));
                if (current == null || !current.get("leaves").equals(leaves)) {
                    current = new JsonObject();
                    current.add("protocols", new JsonArray());
                    current.add("leaves", leaves);
                    windows.add(current);
                }
                current.getAsJsonArray("protocols").add(dataProtocol(captures.get(i).protocol()));
            }
            out.add(kind, windows);
        }
        return out;
    }

    /**
     * The envelope of one capture and kind (see {@link #envelopeWindows}): a leaf is in it when every control that does
     * not explain it sends it with the same value, and at least one such control exists.
     */
    private Map<String, Leaf> envelope(Capture capture, String kind, Map<String, JsonObject> results) {
        Set<String> description = targets(results.get(kind + ":description"));
        List<Map<String, Leaf>> sent = new ArrayList<>();
        List<Set<String>> explainedBy = new ArrayList<>();
        for (Variant v : capture.variants()) {
            Map<String, Leaf> leaves = capture.definitions().get(kind + " " + v.identifier());
            if (!v.kind().equals(kind) || !v.role().equals("control") || leaves == null
                || capture.log().getOrDefault(v.identifier(), List.of()).stream().anyMatch(line -> line.startsWith("ERROR"))) {
                continue;
            }
            Set<String> explained = new TreeSet<>(description);
            for (String companion : companions(v)) {
                explained.addAll(targets(results.get(kind + ":" + companion)));
            }
            sent.add(leaves);
            explainedBy.add(explained);
        }
        return shared(sent, explainedBy);
    }

    /**
     * The leaves every control that does not explain them sends alike.
     *
     * @param sent        per control its leaves (block components under {@code components.})
     * @param explainedBy per control the wire paths its companions and description explain
     */
    static Map<String, Leaf> shared(List<Map<String, Leaf>> sent, List<Set<String>> explainedBy) {
        Map<String, Leaf> envelope = new TreeMap<>();
        Set<String> paths = new TreeSet<>();
        sent.forEach(leaves -> paths.addAll(leaves.keySet()));
        for (String path : paths) {
            // Block components sit under "components" in the data; the rules name them without it
            String generic = Wire.generic(path.startsWith("components.") ? path.substring("components.".length()) : path);
            Leaf value = null;
            boolean same = true;
            for (int i = 0; i < sent.size() && same; i++) {
                if (explained(generic, explainedBy.get(i))) {
                    continue;
                }
                Leaf leaf = sent.get(i).get(path);
                same = leaf != null && (value == null || value.equals(leaf));
                value = leaf;
            }
            if (same && value != null) {
                envelope.put(path, value);
            }
        }
        return envelope;
    }

    /** The companion components of a control, as the add-on generator writes them: fixtures {@code requires}, else the item filler. */
    private Set<String> companions(Variant control) {
        Set<String> companions = new TreeSet<>(fixtures.requires(control.component()).keySet());
        if (control.kind().equals("item") && companions.isEmpty() && control.json() == null) {
            companions.addAll(fixtures.filler.keySet());
        }
        return companions;
    }

    /** The wire leaves the rules of a result write: rule targets, absent values and constants of its forms and fields. */
    private static Set<String> targets(JsonObject result) {
        Set<String> targets = new TreeSet<>();
        if (result == null) {
            return targets;
        }
        if (result.has("constants")) {
            targets.addAll(result.getAsJsonObject("constants").keySet());
        }
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = e.getValue().getAsJsonObject();
                if (field.has("rules")) {
                    field.getAsJsonArray("rules").forEach(r -> targets.add(r.getAsJsonObject().get("wire").getAsString()));
                }
                if (field.has("whenAbsent")) {
                    targets.addAll(field.getAsJsonObject("whenAbsent").keySet());
                }
            }
        }
        return targets;
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
        Map<String, Set<String>> moved = moved(byField, fieldReference);
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
                boolean tried = list.stream().anyMatch(o -> o.accepted() && !o.variant().linked().isEmpty());
                linkedFields.add(component.id() + " " + field.path() + " (BDS " + capture.bdsVersion() + "): no rule follows its value, and"
                    + " its variants' messages name " + String.join(", ", f.getAsJsonArray("linkedTo").asList().stream().map(JsonElement::getAsString).toList())
                    + (tried ? "; BDS accepted variants linked with it, which no rule explains: it needs a rule or a hand transform"
                    : "; vary them together (bds-feedback.json links) and capture again"));
            }
            fields.add(field.path(), f);
        }
        result.add("fields", fields);
        JsonObject constants = constants(result, observations, control);
        if (!constants.isEmpty()) {
            result.add("constants", constants);
        }
        JsonArray findings = findings(component.id(), result);
        if (!findings.isEmpty()) {
            result.add("findings", findings);
        }
        transformEvidence(capture, component, result, observations, controlVariant);
        return result;
    }

    /**
     * Per field, the wire leaves its own variants move against the reference (generic paths): what a variant that sets the
     * field only because BDS ties it to the varied one ({@link Variant#linked}) moves besides the varied field.
     */
    private static Map<String, Set<String>> moved(Map<String, List<Obs>> byField, Obs reference) {
        Map<String, Set<String>> moved = new TreeMap<>();
        byField.forEach((path, observations) -> {
            Set<String> leaves = new TreeSet<>();
            observations.stream().filter(o -> o.accepted() && o.variant().linked().isEmpty())
                .forEach(o -> Wire.diff(reference.wire(), o.wire()).keySet().forEach(leaf -> leaves.add(Wire.generic(leaf))));
            moved.put(path, leaves);
        });
        return moved;
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
     * Bits every value of a boolean or enum table sets that no field explains. A field explains the bits its own table varies;
     * several fields that pack into one leaf ({@code face_dimming} and {@code isotropic} into {@code packed_bools}, whichever face) explain the
     * union (leaves are told apart by name). What remains is set whatever the JSON says: a bit whose meaning the capture never varied. The rules get
     * {@code residualBits}, and the component one finding per leaf name in {@code findings}.
     */
    static JsonArray findings(String componentId, JsonObject result) {
        Map<String, List<JsonObject>> byWire = new TreeMap<>();
        Map<JsonObject, String> owner = new java.util.IdentityHashMap<>();
        for (String group : List.of("forms", "fields")) {
            if (!result.has(group)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : result.getAsJsonObject(group).entrySet()) {
                JsonObject field = e.getValue().getAsJsonObject();
                if (!field.has("rules")) {
                    continue;
                }
                for (JsonElement r : field.getAsJsonArray("rules")) {
                    JsonObject rule = r.getAsJsonObject();
                    if (integralTable(rule) != null) {
                        byWire.computeIfAbsent(leafName(rule.get("wire").getAsString()), k -> new ArrayList<>()).add(rule);
                        owner.put(rule, e.getKey().isEmpty() ? "(value)" : e.getKey());
                    }
                }
            }
        }
        Map<String, JsonObject> findings = new TreeMap<>();
        for (Map.Entry<String, List<JsonObject>> wire : byWire.entrySet()) {
            long explained = 0;
            for (JsonObject rule : wire.getValue()) {
                long[] values = integralTable(rule);
                for (long v : values) {
                    explained |= v ^ values[0];
                }
            }
            for (JsonObject rule : wire.getValue()) {
                long[] values = integralTable(rule);
                long common = values[0];
                for (long v : values) {
                    common &= v;
                }
                long residual = common & ~explained;
                if (residual == 0) {
                    continue;
                }
                JsonArray bits = new JsonArray();
                for (int bit = 0; bit < 63; bit++) {
                    if ((residual >> bit & 1) != 0) {
                        bits.add(bit);
                    }
                }
                rule.add("residualBits", bits);
                JsonObject finding = findings.computeIfAbsent("residual-bits " + componentId + " " + wire.getKey(), id -> {
                    JsonObject f = new JsonObject();
                    f.addProperty("id", id);
                    f.add("bits", new JsonArray());
                    f.add("wires", new JsonArray());
                    f.add("fields", new JsonArray());
                    f.add("values", new JsonArray());
                    return f;
                });
                union(finding.getAsJsonArray("bits"), bits);
                union(finding.getAsJsonArray("wires"), new JsonPrimitive(rule.get("wire").getAsString()));
                union(finding.getAsJsonArray("fields"), new JsonPrimitive(owner.get(rule)));
                for (long v : values) {
                    union(finding.getAsJsonArray("values"), new JsonPrimitive(String.valueOf(v)));
                }
            }
        }
        JsonArray out = new JsonArray();
        findings.values().forEach(f -> {
            sorted(f.getAsJsonArray("values"), Comparator.comparingLong(e -> Long.parseLong(e.getAsString())));
            out.add(f);
        });
        return out;
    }

    /** The last segment of a wire path: the leaf the bits sit in, whichever map key (face) the path goes through. */
    private static String leafName(String wire) {
        return wire.substring(wire.lastIndexOf('.') + 1);
    }

    /** The values of a table rule onto an integral leaf, when every one is a non negative integer; else null. */
    private static long[] integralTable(JsonObject rule) {
        return Rule.of(rule) instanceof Table table ? table.integral() : null;
    }

    private static void union(JsonArray into, JsonElement element) {
        List<JsonElement> add = element.isJsonArray() ? element.getAsJsonArray().asList() : List.of(element);
        add.stream().filter(e -> !into.contains(e)).forEach(into::add);
    }

    private static void sorted(JsonArray array, Comparator<JsonElement> order) {
        List<JsonElement> copy = new ArrayList<>(array.asList());
        copy.sort(order);
        while (!array.isEmpty()) {
            array.remove(array.size() - 1);
        }
        copy.forEach(array::add);
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
     * @param moved     per field, the leaves its own variants move ({@link #moved}): a linked variant's other fields move these
     */
    private JsonObject field(Component component, String path, List<Obs> variants, Obs reference, String filler, boolean form,
                             Map<String, Set<String>> moved) {
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
        List<Observation> observations = new ArrayList<>();
        if (!form) {
            observations.add(new Observation(referenceValue, reference.wire(), "reference"));
        }
        for (Obs o : accepted) {
            JsonElement value = o.variant().role().equals("omit") ? null : form ? o.variant().json() : jsonAt(o.variant().json(), path);
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
            for (OrderedSet set : OrderedSet.fit(observations, varying, closedValues(component, path))) {
                // the list leaves are explained as a whole: drop them from the leaf by leaf fit
                String list = set.wire;
                varying.removeIf(w -> w.matches(Pattern.quote(list.substring(0, list.length() - 2)) + "\\[\\d+]"));
                byGeneric.put(list + " ordered-set", set);
            }
            for (String wire : varying) {
                Rule rule = fit(component, path, wire, observations);
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
            byGeneric.values().forEach(rule -> steps(rule, path, accepted, rejected));
            rules.addAll(mapEntries(byGeneric.values()));
            boolean onlyStructural = rules.stream().noneMatch(Rule::writesValue);
            status = unexplained.isEmpty() ? (onlyStructural && !container(component, path) && !form ? "dropped" : "inferred")
                : rules.isEmpty() ? "unresolved" : "partial";
        }
        if (status.equals("dropped")) {
            // Whichever way the field came to be dropped (no leaf follows it, or only its presence does): not server only if
            // BDS may have decided the wire by another field (an order, a value it cannot be used with). Where no leaf
            // follows it even in the variants linked with that field, it is server only after all
            List<String> others = linkedTo(component, path, variants, varying.isEmpty() && !maskedByLink);
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
     * The other fields the messages of a field's unlinked variants name ({@link Feedback#otherFields}).
     *
     * @param exceptTried without the ones BDS accepted a linked variant with: those were varied together
     */
    private static List<String> linkedTo(Component component, String path, List<Obs> variants, boolean exceptTried) {
        Set<String> others = new TreeSet<>();
        for (Obs o : variants) {
            if (o.variant().linked().isEmpty()) {
                o.log().forEach(line -> others.addAll(Feedback.otherFields(component, path, o.variant().json(), Feedback.message(line))));
            }
        }
        if (exceptTried) {
            variants.stream().filter(Obs::accepted).forEach(o -> o.variant().linked().forEach(others::remove));
        }
        return List.copyOf(others);
    }

    /**
     * Map entries whose key is the last segment of their leaf: two or more keys with the same rule below one parent make
     * one rule for every key ({@code textures{}}); a single key says nothing about others and keeps its own leaf.
     */
    static List<Rule> mapEntries(java.util.Collection<Rule> rules) {
        Map<String, List<Rule>> byParent = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.key != null && rule instanceof Identity && rule.wire.endsWith("." + rule.key)) {
                byParent.computeIfAbsent(rule.wire.substring(0, rule.wire.length() - rule.key.length() - 1) + " " + rule.nbt, k -> new ArrayList<>()).add(rule);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Rule fitting: which kind of rule a JSON shape can have, in order. A new kind of rule is fitted here (Rule)

    private Rule fit(Component component, String path, String wire, List<Observation> observations) {
        List<Observation> set = observations.stream().filter(p -> p.json() != null && !p.json().isJsonNull()).toList();
        if (set.isEmpty()) {
            return null;
        }
        String jsonName = path.isEmpty() ? "" : AddonGenerator.tokens(path).getLast();
        boolean scalars = set.stream().allMatch(p -> p.json().isJsonPrimitive());
        Rule rule;
        Field field = component.field(path);
        // an enum of the docs, or one BDS lists in its content log ("expects one of the following strings")
        boolean enumerated = field != null && !field.enumValues().isEmpty() || feedback.has(component.id(), path, "values");
        if (scalars) {
            rule = scalar(set, Observation::json, wire);
            if (rule instanceof Table && !tableAllowed(set, enumerated)) {
                rule = Affix.fit(set, wire);
            }
        } else if (set.stream().allMatch(p -> p.json().isJsonArray())) {
            rule = array(set, wire, enumerated || feedback.has(component.id(), path + "[]", "values"));
        } else if (set.stream().allMatch(p -> p.json().isJsonObject())) {
            rule = object(set, wire, enumerated || feedback.has(component.id(), path + "{}", "values"));
        } else {
            rule = mixed(set, wire, enumerated, enumerated || feedback.has(component.id(), path + "[]", "values"));
        }
        if (rule == null) {
            return null;
        }
        Leaf sample = set.stream().map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (sample != null) {
            rule.nbt = sample.type();
        }
        rule.tags = tags(component, path, jsonName, wire, rule, set);
        return rule;
    }

    /**
     * Mixed forms (shorthand and object, or a vector as a list and as an object): each JSON type is fitted separately, the
     * scalar part first, else the list part. A leaf only the list form sends is the list part's to explain: that the
     * scalar form does not send it says nothing about what a list puts there ({@code uv_lock}: a byte for the boolean, the
     * bones for the list).
     *
     * @param enumerated     whether the scalar values are of a closed set (an enum)
     * @param closedElements whether the list's elements are
     */
    static Rule mixed(List<Observation> set, String wire, boolean enumerated, boolean closedElements) {
        List<Observation> primitive = set.stream().filter(p -> p.json().isJsonPrimitive()).toList();
        List<Observation> lists = set.stream().filter(p -> p.json().isJsonArray()).toList();
        Rule rule = primitive.isEmpty() ? null : scalar(primitive, Observation::json, wire);
        if (rule instanceof Table && !tableAllowed(primitive, enumerated)) {
            rule = Affix.fit(primitive, wire);
        }
        boolean scalarSilent = rule instanceof Constant
            && primitive.stream().allMatch(p -> p.wire().get(wire) == null) && lists.stream().anyMatch(p -> p.wire().get(wire) != null);
        if ((rule == null || scalarSilent) && !lists.isEmpty()) {
            rule = array(lists, wire, closedElements);
        }
        return rule;
    }

    /** A value table generalizes only for closed sets: booleans and documented enums. */
    static boolean tableAllowed(List<Observation> set, boolean enumerated) {
        return set.stream().allMatch(p -> p.json().getAsJsonPrimitive().isBoolean())
            || (enumerated && set.stream().allMatch(p -> p.json().getAsJsonPrimitive().isString()));
    }

    /** identity, linear, table (or constant) between a scalar JSON value and a leaf, in that order. */
    static Rule scalar(List<Observation> set, Function<Observation, JsonElement> getter, String wire) {
        List<JsonElement> xs = new ArrayList<>();
        List<Leaf> ys = new ArrayList<>();
        for (Observation p : set) {
            JsonElement x = getter.apply(p);
            if (x == null || !x.isJsonPrimitive()) {
                return null;
            }
            xs.add(x);
            ys.add(p.wire().get(wire));
        }
        Rule rule = Identity.fit(xs, ys);
        if (rule == null) {
            rule = Linear.fit(xs, ys);
        }
        return rule != null ? rule : Table.fit(xs, ys);
    }

    /**
     * A linear rule onto whole steps of its input ({@code degrees / 90}) gets {@code multipleOf}: when every accepted value
     * is a multiple of the step and BDS refused a variant whose value is not. The API refuses such values too.
     */
    private static void steps(Rule rule, String path, List<Obs> accepted, List<Obs> rejected) {
        if (!(rule instanceof Linear linear) || !"int".equals(rule.nbt)) {
            return;
        }
        double step = 1 / linear.scale;
        if (Math.abs(step) < 2 || Math.abs(step - Math.rint(step)) > 1e-3) {
            return;
        }
        long n = Math.abs((long) Math.rint(step));
        Integer element = rule.element;
        Function<Obs, List<Double>> numbers = o -> {
            JsonElement value = o.variant().role().equals("omit") ? null : jsonAt(o.variant().json(), path);
            List<Double> out = new ArrayList<>();
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.add(value.getAsDouble());
            } else if (value != null && value.isJsonArray()) {
                List<JsonElement> list = value.getAsJsonArray().asList();
                for (int k = 0; k < list.size(); k++) {
                    if ((element == null || element == k) && list.get(k).isJsonPrimitive() && list.get(k).getAsJsonPrimitive().isNumber()) {
                        out.add(list.get(k).getAsDouble());
                    }
                }
            }
            return out;
        };
        java.util.function.DoublePredicate multiple = x -> Math.abs(x / n - Math.rint(x / n)) < 1e-9;
        boolean acceptedOnSteps = accepted.stream().flatMap(o -> numbers.apply(o).stream()).allMatch(multiple::test);
        boolean refusedOffSteps = rejected.stream().anyMatch(o -> numbers.apply(o).stream().anyMatch(x -> !multiple.test(x)));
        if (acceptedOnSteps && refusedOffSteps) {
            rule.multipleOf = n;
        }
    }

    /** The strings the docs list as the values of a field (of its elements, for a list); empty if they list none. */
    private static Set<String> closedValues(Component component, String path) {
        Field field = component.field(path);
        Set<String> values = new TreeSet<>();
        if (field != null) {
            field.enumValues().stream().filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()).forEach(e -> values.add(e.getAsString()));
        }
        return values;
    }

    /**
     * A constant, bit set, element k -> leaf, count or empty list.
     *
     * @param closed whether the elements are of a closed set of values (an enum): only then a table of the strings seen
     *               is a rule, as for a scalar ({@link #tableAllowed})
     */
    static Rule array(List<Observation> set, String wire, boolean closed) {
        // the same leaf whatever the list (a structure the presence of the field adds)
        if (set.stream().map(p -> String.valueOf(p.wire().get(wire) == null ? null : p.wire().get(wire).value())).distinct().count() == 1) {
            return new Constant(set.get(0).wire().get(wire) == null ? Rule.NOT_SENT : String.valueOf(set.get(0).wire().get(wire).value()));
        }
        Rule bitset = Bitset.fit(set, wire);
        if (bitset != null) {
            return bitset;
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
        Rule best = null;
        for (int k : candidates) {
            final int at = k;
            List<Observation> having = set.stream().filter(p -> p.json().getAsJsonArray().size() > at).toList();
            boolean shorterUnsent = set.stream().filter(p -> p.json().getAsJsonArray().size() <= at).allMatch(p -> p.wire().get(wire) == null);
            if (having.isEmpty() || !shorterUnsent || having.stream().anyMatch(p -> !p.json().getAsJsonArray().get(at).isJsonPrimitive())) {
                continue;
            }
            Rule rule = scalar(having, p -> p.json().getAsJsonArray().get(at), wire);
            if (rule != null && having.size() < 2 && !(rule instanceof Identity)) {
                rule = null;
            }
            if (rule instanceof Table table && table.numericKeys()) {
                rule = null;
            }
            if (rule instanceof Table && !closed) {
                rule = null;
            }
            if (rule != null && !(rule instanceof Constant)) {
                rule.element = k;
                if (best == null || offset(rule) < offset(best)) {
                    best = rule;
                }
            }
        }
        if (best != null) {
            return best;
        }
        Rule count = Count.fit(set, wire);
        return count != null ? count : EmptyList.fit(set, wire);
    }

    /**
     * How far a list element rule is from passing the value through: 0 for identity and for a pure scale. Two observations
     * of a vector fit a line on every element ({@code [90, 180, 270]} and {@code [180, 270, 0]} put RY on element 0 with
     * offset 1 as well as on element 1 with offset 0), so the element whose rule needs no offset explains the leaf.
     */
    private static double offset(Rule rule) {
        return rule instanceof Linear linear ? Math.abs(linear.offset) : 0;
    }

    /**
     * A constant, an empty map, or a map entry: the JSON key appears in the leaf path and its value maps.
     * <p>
     * An entry's rule needs the evidence a scalar's does: a leaf that is the same for the one value the entry ever had
     * is no constant (another value was never tried), and a table of the strings seen is a rule only for a closed set of
     * values ({@link #tableAllowed}).
     *
     * @param closed whether the values of the entries are of a closed set (an enum)
     */
    static Rule object(List<Observation> set, String wire, boolean closed) {
        // presence: the leaf exists iff the object is set
        boolean presence = set.stream().allMatch(p -> p.wire().get(wire) != null);
        Set<String> values = new TreeSet<>();
        set.forEach(p -> values.add(String.valueOf(p.wire().get(wire) == null ? null : p.wire().get(wire).value())));
        if (presence && values.size() == 1) {
            return new Constant(values.iterator().next());
        }
        Rule emptyMap = EmptyMap.fit(set, wire);
        if (emptyMap != null) {
            return emptyMap;
        }
        // map entry: JSON {key: v} -> leaf ...key... = f(v)
        List<String> segments = Wire.segments(wire);
        for (Observation p : set) {
            for (Map.Entry<String, JsonElement> entry : p.json().getAsJsonObject().entrySet()) {
                if (segments.contains(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    List<Observation> having = set.stream().filter(q -> q.json().getAsJsonObject().has(entry.getKey())
                        && q.json().getAsJsonObject().get(entry.getKey()).isJsonPrimitive()).toList();
                    Function<Observation, JsonElement> value = q -> q.json().getAsJsonObject().get(entry.getKey());
                    Rule rule = scalar(having, value, wire);
                    boolean oneValue = rule instanceof Constant && having.stream().map(q -> value.apply(q).toString()).distinct().count() < 2;
                    boolean openTable = rule instanceof Table && !having.stream().allMatch(q -> value.apply(q).getAsJsonPrimitive().isBoolean()
                        || (closed && value.apply(q).getAsJsonPrimitive().isString()));
                    if (rule != null && !(rule instanceof Table table && table.numericKeys()) && !oneValue && !openTable) {
                        rule.mapEntry = true;
                        rule.key = entry.getKey();
                        return rule;
                    }
                }
            }
        }
        return null;
    }

    private List<String> tags(Component component, String path, String jsonName, String wire, Rule rule, List<Observation> set) {
        Set<String> tags = new LinkedHashSet<>();
        List<String> segments = Wire.segments(Wire.generic(wire));
        String leafName = segments.getLast().replace("[]", "");
        if (rule.presence()) {
            // a leaf the field's presence switches on (a sibling default BDS writes with it), not the field's value
            return List.of(!jsonName.isEmpty() && leafName.equals(jsonName) ? "presence" : "injected-default");
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
        JsonElement x = set.getFirst().json();
        Leaf y = set.stream().map(p -> p.wire().get(wire)).filter(Objects::nonNull).findFirst().orElse(null);
        if (y != null && x.isJsonPrimitive()) {
            JsonPrimitive p = x.getAsJsonPrimitive();
            String jsonType = p.isBoolean() ? "boolean" : p.isNumber() ? (isInteger(p) ? "integer" : "number") : "string";
            String nbt = y.type();
            if (!(jsonType.equals("integer") && nbt.equals("int")) && !(jsonType.equals("number") && nbt.equals("float"))
                && !(jsonType.equals("string") && nbt.equals("string"))) {
                tags.add("type:" + jsonType + "->" + nbt);
            }
        }
        tags.addAll(rule.kindTags());
        if (rule.element != null) {
            tags.add("list-element");
        }
        return List.copyOf(tags);
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
