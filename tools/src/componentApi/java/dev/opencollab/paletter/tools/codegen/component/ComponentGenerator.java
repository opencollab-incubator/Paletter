package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.MappingWindows;
import dev.opencollab.paletter.tools.model.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Generates Paletter's custom item and block component API from the component model in {@code data/model}:
 * <ul>
 *     <li>{@code components.json}: every component and field as the creator docs describe it (types, defaults, enums,
 *     ranges, docs text, format versions, experiments, status), read as the model every tool shares: with the
 *     {@code errata} of {@code fixtures.json} and what BDS said applied ({@link Fixtures#apply});</li>
 *     <li>{@code bds-feedback.json}: required fields and ranges BDS enforces, fields BDS does not know;</li>
 *     <li>{@code mappings.json}: per component and protocol window, what reaches the wire and how (inferred from BDS);</li>
 *     <li>{@link HandTransforms}: fields no inferred rule explains, by hand transform;</li>
 *     <li>{@code api.json}: Java naming and shaping decisions (names, unions, descriptors, exclusions).</li>
 * </ul>
 * The output is one immutable record per component (nested records and enums for its object and enum types, builders
 * with defaults and validation) in {@code dev.opencollab.paletter.item.component} / {@code block.component}, and the
 * internal encoders in {@code dev.opencollab.paletter.internal.component}. Anything the inputs do not settle fails generation
 * with a message naming the component and field; nothing is guessed silently.
 * <p>
 * This class reads the inputs and builds each component; the work is in {@link ModelFields} (the fields of the model),
 * {@link TypeBuilder} and {@link Unions} (their Java types), {@link Forms}, {@link Limits}, {@link Defaults}, {@link Windows},
 * {@link Captures}, {@link NameCollisions}, {@link TypeMerge} and {@link ModelChecks}. Errata are applied only by
 * {@link Fixtures#apply}; {@link ModelFields#read} decodes the markers it leaves in the model.
 */
public final class ComponentGenerator {
    /** A model problem the inputs have to settle. */
    public static final class GenerationException extends RuntimeException {
        public GenerationException(String message) {
            super(message);
        }
    }

    /**
     * @param components the model ({@link Fixtures#apply}) as JSON, in the shape of {@code components.json}
     * @param transforms the hand transforms ({@link HandTransforms#BY_PATH}): {@code <component>:<path>} -> {@code Transforms} method
     * @param since the Paletter version for {@code @since}
     */
    public record Inputs(JsonObject components, JsonObject fixtures, JsonObject feedback, JsonObject mappings, Map<String, String> transforms,
                         JsonObject api, String since, Context context, JsonObject history) {
        public Inputs(JsonObject components, JsonObject fixtures, JsonObject feedback, JsonObject mappings, Map<String, String> transforms,
                      JsonObject api, String since) {
            this(components, fixtures, feedback, mappings, transforms, api, since, Context.NONE, new JsonObject());
        }

        public Inputs withContext(Context context) {
            return new Inputs(components, fixtures, feedback, mappings, transforms, api, since, context, history);
        }

        /** @param history {@code api-history.json} ({@link ApiHistory}): the names to keep */
        public Inputs withHistory(JsonObject history) {
            return new Inputs(components, fixtures, feedback, mappings, transforms, api, since, context, history);
        }
    }

    /**
     * @param sources the generated sources by path relative to the source root, sorted
     * @param history the new {@code api-history.json}
     */
    public record Output(Map<String, String> sources, String history) {
    }

    /**
     * What the generated names and annotations depend on besides the model.
     *
     * @param reserved the simple names of the public types that are not generated here (hand-written ones, the constants):
     *                 a generated type with such a name gets a {@code Block}/{@code Item} prefix, so that simple names stay unique
     * @param toggles experiment name as the creator docs write it ({@code Upcoming Creator Features}) to toggle id
     *                ({@code upcoming_creator_features}), as the captured servers name them
     * @param gating what the captured servers do with each gated component without experiments ({@link Gating}), by component id
     */
    public record Context(Set<String> reserved, Map<String, String> toggles, Map<String, List<Gating.Result>> gating) {
        public static final Context NONE = new Context(Set.of(), Map.of(), Map.of());
    }

    public static final String API_FILE = "api.json";

    private final Inputs in;
    private final Hand hand;
    private final ApiModel model = new ApiModel();
    private final ModelFields modelFields;
    private final Defaults defaults;
    private final Forms formBuilder;

    private ComponentGenerator(Inputs in) {
        this.in = in;
        this.hand = new Hand(in);
        this.modelFields = new ModelFields(hand);
        this.defaults = new Defaults(hand);
        Limits limits = new Limits(hand);
        this.formBuilder = new Forms(hand, new TypeBuilder(hand, model, limits), limits);
    }

    public static Inputs read(Path modelDir, String since) throws IOException {
        return read(modelDir, since, Context.NONE);
    }

    public static Inputs read(Path modelDir, String since, Context context) throws IOException {
        Fixtures fixtures = Fixtures.read(modelDir);
        JsonObject components = Model.GSON.toJsonTree(fixtures.apply(Model.read(modelDir.resolve("components.json")))).getAsJsonObject();
        return new Inputs(components, fixtures.json, fixtures.feedback, json(modelDir.resolve("mappings.json")),
            HandTransforms.BY_PATH, json(modelDir.resolve(API_FILE)), since, context, json(modelDir.resolve(ApiHistory.FILE)));
    }

    private static JsonObject json(Path file) throws IOException {
        return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
    }

    /**
     * @return generated sources by path relative to the source root ({@code org/geysermc/...java}), sorted
     */
    public static Map<String, String> generate(Inputs in) {
        return generateAll(in).sources();
    }

    /** @return the generated sources and the new {@code api-history.json} */
    public static Output generateAll(Inputs in) {
        ComponentGenerator generator = new ComponentGenerator(in);
        generator.build();
        generator.hand.history.checkShapes(generator.model);
        generator.model.renames.addAll(generator.hand.history.renames(generator.model));
        StandIns.apply(generator.model, generator.hand.history, in.context().reserved());
        Map<String, String> out = new TreeMap<>();
        // api.json "since" overrides the build version (the Paletter version the generated API first shipped in)
        String since = in.api().has("since") ? in.api().get("since").getAsString() : in.since();
        ApiSource.write(generator.model, since, out);
        WireSource.write(generator.model, out);
        return new Output(out, generator.hand.history.write(generator.model));
    }

    static ApiModel model(Inputs in) {
        ComponentGenerator generator = new ComponentGenerator(in);
        generator.build();
        return generator.model;
    }

    private void build() {
        List<JsonObject> docs = new ArrayList<>();
        in.components().getAsJsonArray("components").forEach(e -> docs.add(e.getAsJsonObject()));
        docs.sort(Comparator.comparing(o -> o.get("id").getAsString()));
        List<String> orphans = Fixtures.orphans(in.fixtures(), in.api(), docs.stream().map(d -> d.get("id").getAsString()).collect(java.util.stream.Collectors.toSet()));
        if (!orphans.isEmpty()) {
            throw new GenerationException("hand input that names no component of the model (renamed or removed in the docs?): " + String.join("; ", orphans));
        }
        List<String> staleTransforms = HandTransforms.stale(in.transforms(), docs);
        if (!staleTransforms.isEmpty()) {
            throw new GenerationException("hand transforms that name no component or field of the model (renamed or removed in the docs?): "
                + String.join("; ", staleTransforms));
        }
        JsonObject mappings = in.mappings().has("components") ? in.mappings().getAsJsonObject("components") : new JsonObject();
        JsonObject skip = in.fixtures().has("skip") ? in.fixtures().getAsJsonObject("skip") : new JsonObject();
        Set<String> excluded = new TreeSet<>();
        if (in.api().has("exclude")) {
            in.api().getAsJsonArray("exclude").forEach(e -> excluded.add(e.getAsString()));
        }
        Captures.read(model, in.mappings());
        if (in.mappings().has("traitStates")) {
            model.traitStates = in.mappings().getAsJsonObject("traitStates");
        }
        if (in.mappings().has("envelope")) {
            in.mappings().getAsJsonObject("envelope").entrySet().forEach(e -> model.envelope.put(e.getKey(), e.getValue().getAsJsonArray()));
        }
        if (in.components().has("lists")) {
            in.components().getAsJsonObject("lists").entrySet().forEach(e -> model.lists.put(e.getKey(), e.getValue().getAsString()));
        }
        // Every component's windows well formed before anything reads them
        mappings.entrySet().forEach(e -> Windows.windows(e.getKey(), e.getValue().getAsJsonObject()));
        model.replacements.putAll(Replacements.derive(in.components(), in.mappings(), in.fixtures(), Captures.captureReference(in.mappings())));
        for (JsonObject doc : docs) {
            String id = doc.get("id").getAsString();
            model.pages.put(id, ModelFields.text(doc, "page"));
            String category = doc.get("category").getAsString();
            JsonObject mapping = mappings.has(id) ? mappings.getAsJsonObject(id) : null;
            // the newest window's, below, when the component has a mapping
            String status = "not captured";
            String standalone = hand.apiString("standalone", id);
            if (skip.has(id)) {
                model.omitted.put(id, "not modelled: " + skip.get(id).getAsString());
                continue;
            }
            if (hand.api("handWritten").has(id)) {
                model.omitted.put(id, "hand-written: " + hand.api("handWritten").get(id).getAsString());
                continue;
            }
            if (excluded.contains(id)) {
                model.omitted.put(id, "excluded in api.json");
                continue;
            }
            if (hand.api("retired").has(id)) {
                model.omitted.put(id, "retired, never on the wire: " + hand.api("retired").get(id).getAsString());
                continue;
            }
            if (standalone == null && !category.equals("component") && !category.equals("trait")) {
                model.omitted.put(id, "part of the definition core (CustomItem / CustomBlock)");
                continue;
            }
            // The status of the newest capture decides, unless an older one sent the component: then it is generated, and
            // the windows that do not send it leave it out of their palettes with a report entry
            List<Window> observed = mapping == null ? List.of() : Windows.windows(id, mapping);
            if (mapping != null) {
                // A capture without the component would be absorbed by its neighbour window: no BDS data for that protocol.
                // A build that does not have the component has a window of its own (absent): not missing
                java.util.TreeSet<Integer> missing = new java.util.TreeSet<>(model.windowProtocols.values());
                java.util.TreeSet<Integer> has = MappingWindows.protocols(mapping);
                missing.removeAll(has);
                if (!has.isEmpty() && !missing.isEmpty()) {
                    throw new GenerationException(id + " has no result for the captures of protocols " + missing + ": capture again with the current add-on");
                }
            }
            if (!observed.isEmpty()) {
                status = observed.getLast().status();
            }
            if (observed.stream().noneMatch(Window::sent) && !status.equals("server-only") && !status.equals("rejected")) {
                model.omitted.put(id, status);
                continue;
            }
            if (observed.stream().noneMatch(Window::sent)) {
                // No capture shows its shape, so the docs alone must settle it; what they leave open keeps it omitted
                try {
                    model.components.add(component(doc, mapping, standalone));
                } catch (GenerationException e) {
                    model.omitted.put(id, "not generated, the docs leave its shape open: " + e.getMessage());
                }
                continue;
            }
            model.components.add(component(doc, mapping, standalone));
        }
        NameCollisions.check(model);
        NameCollisions.prefix(model, hand.in.context().reserved(), hand.history);
    }

    private Comp component(JsonObject doc, JsonObject mapping, String standalone) {
        Comp c = new Comp();
        c.id = doc.get("id").getAsString();
        c.name = doc.get("name").getAsString();
        String kind = doc.get("kind").getAsString();
        c.kind = doc.get("category").getAsString().equals("trait") ? Kind.TRAIT : kind.equals("item") ? Kind.ITEM : Kind.BLOCK;
        c.status = doc.get("status").getAsString();
        c.doc = ModelFields.text(doc, "doc");
        c.page = ModelFields.text(doc, "page");
        c.minFormat = doc.has("minFormatVersion") ? doc.get("minFormatVersion").getAsString() : null;
        List<String> experiments = new ArrayList<>();
        doc.getAsJsonArray("experiments").forEach(e -> experiments.add(e.getAsString()));
        c.experiments = experiments;
        c.toggles = experiments.stream().map(e -> in.context().toggles().get(e)).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        c.unmappedExperiments = experiments.stream().filter(e -> !in.context().toggles().containsKey(e)).toList();
        c.gating = in.context().gating().getOrDefault(c.id, List.of());
        if (c.toggles.size() > 1) {
            throw new GenerationException(c.id + " needs several experiment toggles " + c.toggles + ": @BedrockExperiment takes one; make it repeatable");
        }
        String className = hand.apiString("classes", c.id);
        // The name before NameCollisions; a recorded name (api-history.json) replaces it there
        c.className = className != null ? className : standalone != null ? standalone : JavaNames.pascal(c.name.replaceFirst("^[a-z_]+:", ""));
        JavaNames.javaName(c.className, c.id);
        c.windows.addAll(Windows.windows(c.id, mapping));
        if (c.windows.isEmpty()) {
            throw new GenerationException(c.id + " has no window that sends it");
        }
        c.serverOnly = Windows.serverOnly(c);
        c.rejected = Windows.rejected(c);
        c.sincePreview = Windows.sincePreview(model, c, Window::status);
        c.bedrockSince = c.sincePreview != null ? null : Windows.bedrockSince(model, c);
        c.replacement = model.replacements.get(c.id);
        c.transform = hand.transform(c.id, "");
        c.standalone = standalone != null;
        c.standalonePath = standalone == null ? null : hand.standalonePrefix(c.id);

        // Fields after errata, BDS feedback and exclusions
        List<ModelFields.DocField> fields = modelFields.read(c, doc, standalone);
        List<String> forms = ModelFields.forms(c, doc);
        c.root = new ObjType();
        c.root.name = c.className;
        c.root.path = "";
        c.root.doc = c.doc;
        formBuilder.build(c, fields, forms);
        for (Field f : c.root.fields) {
            JavaNames.javaName(f.java, c.id + " " + (f.path == null ? "" : f.path));
        }
        ModelChecks.checkFieldNames(c, c.root);
        Defaults.propagateDefaults(c.root);
        TypeMerge.dedupe(model, c);
        ModelChecks.checkConflicts(c);
        defaults.apply(c, c.root);
        return c;
    }
}
