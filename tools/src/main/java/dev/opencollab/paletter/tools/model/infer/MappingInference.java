package dev.opencollab.paletter.tools.model.infer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.HandTransforms;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.MappingWindows;
import dev.opencollab.paletter.tools.model.Model;
import dev.opencollab.paletter.tools.model.Model.Component;
import dev.opencollab.paletter.tools.model.infer.SpecCaptures.Capture;
import dev.opencollab.paletter.tools.model.infer.SpecCaptures.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p>
 * The parts: {@link SpecCaptures} loads the captures and picks the one of each data version window, {@link FieldAnalysis}
 * reads one capture of one component, {@link RuleFit} fits the rules of its fields, {@link MappingWindows} merges the
 * captures into protocol windows, {@link LinkedFields} and {@link Findings} collect what a human has to look at,
 * {@link Envelope} what is sent around every component, {@link Feedback} what the next capture's add-on should try, and
 * {@link Coverage} the report.
 */
public final class MappingInference {
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

    private final List<String> unexplainedBits = new ArrayList<>();
    private LinkedFields linkedFields = new LinkedFields();

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
        return linkedFields.list();
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
        Path dir = root.resolve(dev.opencollab.paletter.tools.Layout.BDS);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> dirs = Files.list(dir)) {
                for (Path capture : dirs.map(d -> d.resolve("spec")).filter(d -> Files.exists(d.resolve("variants.json"))).toList()) {
                    Capture loaded = SpecCaptures.load(capture);
                    captures.add(loaded);
                    directories.put(loaded.bdsVersion(), capture);
                }
            }
        }
        // Several kept builds of one window (hotfixes, with or without a protocol bump): the build its data version was
        // imported from defines it
        Set<String> historyKept = new TreeSet<>();
        dev.opencollab.paletter.tools.update.BuildHistory.read(root).forEach((build, entry) -> {
            if (entry.keptByHistoryCheck()) {
                historyKept.add(build);
            }
        });
        Map<Integer, Window> windows = SpecCaptures.windowCaptures(captures, this::dataProtocol, directories, historyKept);
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
            // The bundled data version of the window: what @BedrockSince of a component that older builds do not have names
            String dataVersion = dataVersion(dataProtocol(capture.protocol()));
            if (dataVersion != null) {
                source.addProperty("dataVersion", dataVersion);
            }
            if (replaced.containsKey(dataProtocol(capture.protocol()))) {
                // Older kept builds of the same window, whose captures this one replaces
                JsonArray older = new JsonArray();
                replaced.get(dataProtocol(capture.protocol())).stream().sorted(dev.opencollab.paletter.tools.Versions.ORDER).forEach(older::add);
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
        Map<String, Long> formatBits = Findings.formatDependentBits(root);
        unexplainedBits.clear();
        linkedFields = new LinkedFields();
        FieldAnalysis analysis = new FieldAnalysis(fixtures, transforms, feedback, linkedFields);
        // Per capture: component id -> its result there
        List<Map<String, JsonObject>> resultsByCapture = new ArrayList<>();
        captures.forEach(capture -> resultsByCapture.add(new TreeMap<>()));
        Map<String, dev.opencollab.paletter.tools.model.Schema> schemas = schemas();
        for (Component component : model.components()) {
            List<JsonObject> results = new ArrayList<>();
            List<Integer> resultProtocols = new ArrayList<>();
            List<JsonObject> absent = new ArrayList<>();
            for (int i = 0; i < captures.size(); i++) {
                Capture capture = captures.get(i);
                JsonObject result = analysis.analyze(capture, component);
                if (result != null) {
                    resultsByCapture.get(i).put(component.id(), result);
                    Findings.collectFindings(findings, component.id(), result, formatBits);
                    results.add(result);
                    resultProtocols.add(dataProtocol(capture.protocol()));
                } else {
                    JsonObject none = absent(capture, component.id(), schemas);
                    if (none != null) {
                        results.add(none);
                        resultProtocols.add(dataProtocol(capture.protocol()));
                        absent.add(none);
                    }
                }
            }
            if (absent.size() == results.size()) {
                // Not in any capture: nothing to say where it is missing
                results.clear();
                resultProtocols.clear();
            }
            JsonObject merged = MappingWindows.merge(results, resultProtocols);
            if (merged == null) {
                continue;
            }
            components.add(component.id(), merged);
            coverage.add(component, MappingWindows.newest(merged));
            feedback.steps(component.id(), MappingWindows.newest(merged));
        }
        formats(components);
        mappings.add("envelope", Envelope.windows(captures, resultsByCapture, fixtures, this::dataProtocol));
        mappings.add("components", components);
        coverage.wirePaths(dev.opencollab.paletter.tools.data.WireShapes.uncoveredByBuild(root.resolve(dev.opencollab.paletter.tools.Layout.BDS), mappings, model));
        coverage.findings(findings);
        findings.forEach((id, description) -> {
            if (description.contains(Findings.UNEXPLAINED)) {
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
            JsonObject newest = MappingWindows.newest(e.getValue().getAsJsonObject());
            if (newest.has("wire")) {
                newest.getAsJsonArray("wire").forEach(k -> keys.add(k.getAsString()));
            }
            wireKeys.put(e.getKey(), keys);
        });
        List<Path> sweeps = new ArrayList<>();
        Path bds = root.resolve(dev.opencollab.paletter.tools.Layout.BDS);
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

    /** The committed schemas ({@code data/bds/<build>/schema.json}) by build. */
    private Map<String, dev.opencollab.paletter.tools.model.Schema> schemas() throws IOException {
        Map<String, dev.opencollab.paletter.tools.model.Schema> schemas = new TreeMap<>();
        Path dir = root.resolve(dev.opencollab.paletter.tools.Layout.BDS);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> builds = Files.list(dir)) {
                for (Path file : builds.map(b -> b.resolve(dev.opencollab.paletter.tools.model.Schema.FILE)).filter(Files::isRegularFile).toList()) {
                    schemas.put(file.getParent().getFileName().toString(), dev.opencollab.paletter.tools.model.Schema.read(file));
                }
            }
        }
        return schemas;
    }

    /**
     * The result of a capture without a variant of the component when the build does not have the component (status
     * {@link MappingWindows#ABSENT}): its own schema lacks it while another build's schema names it. Its add-on, made from that
     * schema, had nothing to vary. Null otherwise: then the capture is missing, and the component API fails on it.
     */
    static JsonObject absent(Capture capture, String id, Map<String, dev.opencollab.paletter.tools.model.Schema> schemas) {
        dev.opencollab.paletter.tools.model.Schema own = schemas.get(capture.bdsVersion());
        if (own == null || !own.lacks(id) || schemas.values().stream().noneMatch(s -> s.names(id))
            || capture.variants().stream().anyMatch(v -> v.component().equals(id))) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", MappingWindows.ABSENT);
        result.addProperty("note", "not in the schema of BDS " + capture.bdsVersion() + ", no variant in its add-on");
        return result;
    }

    /** The bundled version name of a preview build's capture ({@code capture.json} says {@code preview}), null for a release. */
    private String previewName(Capture capture) throws IOException {
        Path file = root.resolve(dev.opencollab.paletter.tools.Layout.BDS).resolve(capture.bdsVersion()).resolve("spec/capture.json");
        if (!Files.exists(file)) {
            return null;
        }
        JsonObject meta = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        boolean preview = meta.has("preview") && meta.get("preview").getAsBoolean();
        return preview ? dev.opencollab.paletter.tools.BuildNumber.parse(capture.bdsVersion()).dataVersion() : null;
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

    /** The name of the bundled data version that starts at a window protocol ({@code versions.txt}), null if none does. */
    private String dataVersion(int windowProtocol) {
        try {
            Path versions = root.resolve("paletter/src/main/resources/paletter/versions.txt");
            if (Files.exists(versions)) {
                for (String line : Files.readAllLines(versions, StandardCharsets.UTF_8)) {
                    String[] parts = line.trim().split("\\s+");
                    if (!line.isBlank() && !line.startsWith("#") && Integer.parseInt(parts[1]) == windowProtocol) {
                        return parts[0];
                    }
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return null;
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
    }
}
