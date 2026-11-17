package org.geysermc.paletter.tools.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.Model.Component;
import org.geysermc.paletter.tools.model.Model.Field;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.JsonPath;
import org.geysermc.paletter.tools.model.rule.Leaf;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The key probe of {@code spec-capture --probe-keys}: which keys of a component does BDS know that the creator docs do
 * not list. Three sources only choose what is asked (nothing in them is a value, BDS answers):
 * <ol>
 *     <li><b>The BDS binary.</b> Its error and description texts name keys next to each other ("'alpha_masked_tint'
 *     requires a non-default 'tint_method'"). A text that names a field the model knows by a specific name (an
 *     underscore, at least six characters) makes the other quoted or underscored identifiers of the text candidates at
 *     the level of that field. A text that names {@code minecraft:x} makes them candidates of the component itself. The
 *     binary does not order its strings by component, so proximity says nothing and is not used.</li>
 *     <li><b>wiki.bedrock.dev</b> ({@link WikiSource}): the keys its component pages list that the model lacks, and the
 *     members its format histories say were added.</li>
 *     <li><b>Vanilla packs</b> (a {@code --vanilla-pack} directory, and the packs of the server): the keys of the
 *     component objects in their block and item files.</li>
 * </ol>
 * Each candidate is one variant per trivial value ({@link AddonGenerator#trivialValues}). BDS's content log answers for
 * blocks: "not present in the Schema" for a key it does not know, a type error for a known key with the wrong type, nothing
 * for a key and type it accepts. Items log nothing for members they ignore; a key counts as accepted there only when it
 * changes the wire against the base. The result is {@code data/model/bds-keys.json}, which {@link Fixtures#apply} turns
 * into fields, so the next normal capture varies them.
 */
final class KeyDiscovery {
    private static final int MAX_PER_COMPONENT = 40;
    private static final Pattern QUOTED = Pattern.compile("['\"`]([a-z][a-z0-9_]{2,})['\"`]");
    private static final Pattern UNDERSCORED = Pattern.compile("(?<![A-Za-z0-9_:.])[a-z][a-z0-9]*(?:_[a-z0-9]+)+");
    private static final Pattern COMPONENT_NAME = Pattern.compile("minecraft:([a-z0-9_]+)");
    private static final Pattern VERSION = Pattern.compile("\\b(\\d+\\.\\d+\\.\\d+)\\b");
    private static final Set<String> NOT_KEYS = Set.of("format_version", "min_engine_version", "min_format_version");

    private KeyDiscovery() {
    }

    /** A key to ask BDS about: {@code level} is the path of the object it would sit in ("" for the component's own object). */
    record Candidate(String component, String level, String key) {
        String path() {
            return level.isEmpty() ? key : level + "." + key;
        }
    }

    /**
     * What the sources said: the candidates with where they came from, and the format versions they name per token (a
     * component name like {@code minecraft:material_instances}, or a key).
     */
    static final class Evidence {
        final Map<Candidate, Set<String>> candidates = new LinkedHashMap<>();
        /** From texts of the BDS binary only: the versions BDS itself names for a token. */
        final Map<String, TreeSet<String>> bdsVersions = new TreeMap<>();
        /** From the "Versioned" changes of the wiki's format histories. */
        final Map<String, TreeSet<String>> wikiVersions = new TreeMap<>();

        void candidate(Candidate candidate, String source) {
            candidates.computeIfAbsent(candidate, k -> new TreeSet<>()).add(source);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gathering

    static Evidence gather(Model model, Path binary, Path cache, List<Path> packs) throws IOException {
        Evidence evidence = new Evidence();
        fromBinary(model, binary, evidence);
        fromWiki(model, cache, evidence);
        for (Path pack : packs) {
            fromPack(model, pack, evidence);
        }
        // every key of a map is accepted (the faces of material_instances): asking for one says nothing
        evidence.candidates.keySet().removeIf(c -> map(model.component(c.component()), c.level()));
        return evidence;
    }

    /** The object levels of a component: its root and every object field that has members. */
    static List<String> levels(Component c) {
        Set<String> levels = new LinkedHashSet<>();
        List<String> candidates = new ArrayList<>();
        candidates.add("");
        for (Field field : c.fields()) {
            if (field.has("object")) {
                candidates.add(field.path());
            }
            if (field.types().stream().anyMatch(t -> t.type().equals("array") && t.items() != null && t.items().type().equals("object"))) {
                candidates.add(field.path() + "[]");
            }
        }
        for (String level : candidates) {
            if (level.isEmpty() || c.fields().stream().anyMatch(f -> JsonPath.parentOf(f.path()).equals(level))) {
                levels.add(level);
            }
        }
        return List.copyOf(levels);
    }

    /** Whether the object at a level has free keys: the model lists a {@code *} or {@code {}} member, or types it a map. */
    private static boolean map(Component c, String level) {
        Field field = c.field(level);
        return c.field(level.isEmpty() ? "*" : level + ".*") != null || c.field(level + "{}") != null || (field != null && field.has("map"));
    }

    /** The level that holds the members the wiki lists under a key: the elements of a list, else the key's own object. */
    private static String level(Component c, String parent) {
        Field field = c.field(parent);
        return field != null && field.has("array") ? parent + "[]" : parent;
    }

    private static boolean known(Component c, String level, String key) {
        String path = level.isEmpty() ? key : level + "." + key;
        return c.field(path) != null || c.fields().stream().anyMatch(f -> f.name().equals(key) && JsonPath.parentOf(f.path()).equals(level));
    }

    private static void fromBinary(Model model, Path binary, Evidence evidence) throws IOException {
        // field names specific enough to anchor a text: where the model has them (component, level of the field)
        Map<String, Set<Candidate>> anchors = new HashMap<>();
        Map<String, List<Component>> byName = new HashMap<>();
        for (Component c : model.components()) {
            if (c.category().equals("component")) {
                byName.computeIfAbsent(c.name().replace("minecraft:", ""), k -> new ArrayList<>()).add(c);
            }
            for (Field field : c.fields()) {
                String name = field.name();
                if (name.length() >= 6 && name.contains("_") && name.matches("[a-z][a-z0-9_]*")) {
                    anchors.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(new Candidate(c.id(), JsonPath.parentOf(field.path()), name));
                }
            }
        }
        Map<Candidate, Integer> support = new LinkedHashMap<>();
        scan(binary, text -> {
            Set<String> keys = new LinkedHashSet<>();
            Matcher quoted = QUOTED.matcher(text);
            while (quoted.find()) {
                keys.add(quoted.group(1));
            }
            Matcher bare = UNDERSCORED.matcher(text);
            while (bare.find()) {
                keys.add(bare.group());
            }
            keys.removeAll(NOT_KEYS);
            List<String> versions = new ArrayList<>();
            if (text.toLowerCase(java.util.Locale.ROOT).contains("format_version") || text.toLowerCase(java.util.Locale.ROOT).contains("format version")) {
                Matcher v = VERSION.matcher(text);
                while (v.find()) {
                    versions.add(v.group(1));
                }
            }
            Set<String> tokens = new LinkedHashSet<>(keys);
            Matcher name = COMPONENT_NAME.matcher(text);
            List<Component> mentioned = new ArrayList<>();
            while (name.find()) {
                mentioned.addAll(byName.getOrDefault(name.group(1), List.of()));
                tokens.add("minecraft:" + name.group(1));
            }
            for (String version : versions) {
                tokens.forEach(t -> evidence.bdsVersions.computeIfAbsent(t, k -> new TreeSet<>(AddonGenerator::compare)).add(version));
            }
            Set<Candidate> places = new LinkedHashSet<>();
            for (String key : keys) {
                places.addAll(anchors.getOrDefault(key, Set.of()));
            }
            mentioned.forEach(c -> places.add(new Candidate(c.id(), "", "")));
            for (Candidate place : places) {
                Component c = model.component(place.component());
                for (String key : keys) {
                    if (!known(c, place.level(), key)) {
                        support.merge(new Candidate(c.id(), place.level(), key), 1, Integer::sum);
                    }
                }
            }
        });
        // the best supported per component
        Map<String, List<Map.Entry<Candidate, Integer>>> perComponent = new TreeMap<>();
        support.entrySet().forEach(e -> perComponent.computeIfAbsent(e.getKey().component(), k -> new ArrayList<>()).add(e));
        perComponent.values().forEach(list -> list.stream()
            .sorted(Map.Entry.<Candidate, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().path()))
            .limit(MAX_PER_COMPONENT).forEach(e -> evidence.candidate(e.getKey(), "bds-strings")));
    }

    private static void fromWiki(Model model, Path cache, Evidence evidence) {
        for (String kind : List.of("block", "item")) {
            for (String page : WikiSource.COMPONENT_PAGES) {
                if (!page.startsWith(kind)) {
                    continue;
                }
                String markdown = WikiSource.page(cache, page);
                if (markdown != null) {
                    for (WikiSource.Key key : WikiSource.keys(kind, markdown)) {
                        Component c = model.component(key.component());
                        String parent = JsonPath.parentOf(key.path());
                        String name = key.path().substring(parent.isEmpty() ? 0 : parent.length() + 1);
                        if (c != null && !known(c, level(c, parent), name)) {
                            evidence.candidate(new Candidate(c.id(), level(c, parent), name), "wiki.bedrock.dev " + page);
                        }
                    }
                }
            }
            for (String page : WikiSource.HISTORY_PAGES) {
                if (!page.startsWith(kind)) {
                    continue;
                }
                String markdown = WikiSource.page(cache, page);
                if (markdown == null) {
                    continue;
                }
                for (WikiSource.Change change : WikiSource.history(kind, markdown)) {
                    Component c = model.component(change.component());
                    if (c == null) {
                        continue;
                    }
                    if (change.versioned()) {
                        evidence.wikiVersions.computeIfAbsent(c.name(), k -> new TreeSet<>(AddonGenerator::compare)).add(change.version());
                        if (change.key() != null) {
                            evidence.wikiVersions.computeIfAbsent(change.key(), k -> new TreeSet<>(AddonGenerator::compare)).add(change.version());
                        }
                    }
                    if (change.key() == null) {
                        continue;
                    }
                    Field parent = change.parent() == null ? null : c.fields().stream().filter(f -> f.name().equals(change.parent())).findFirst().orElse(null);
                    for (String level : parent != null ? List.of(level(c, parent.path())) : levels(c)) {
                        if (!known(c, level, change.key())) {
                            evidence.candidate(new Candidate(c.id(), level, change.key()), "wiki.bedrock.dev " + page);
                        }
                    }
                }
            }
        }
    }

    /** The keys of the component objects of the block and item files below a pack directory. */
    private static void fromPack(Model model, Path pack, Evidence evidence) throws IOException {
        if (!Files.isDirectory(pack)) {
            return;
        }
        try (Stream<Path> files = Files.walk(pack)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String kind = file.toString().replace('\\', '/').contains("/blocks/") ? "block" : file.toString().replace('\\', '/').contains("/items/") ? "item" : null;
                if (kind == null) {
                    continue;
                }
                JsonElement root;
                try {
                    root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                } catch (RuntimeException | IOException e) {
                    continue; // not json (a comment), not readable: nothing to learn from it
                }
                JsonObject definition = root.isJsonObject() && root.getAsJsonObject().has("minecraft:" + kind)
                    ? root.getAsJsonObject().get("minecraft:" + kind).getAsJsonObject() : null;
                if (definition != null && definition.has("components") && definition.get("components").isJsonObject()) {
                    definition.getAsJsonObject("components").entrySet().forEach(e -> {
                        Component c = model.component(kind + ":" + e.getKey());
                        if (c != null && e.getValue().isJsonObject()) {
                            packKeys(c, "", e.getValue().getAsJsonObject(), evidence, "vanilla pack " + pack.getFileName(), 0);
                        }
                    });
                }
            }
        }
    }

    private static void packKeys(Component c, String level, JsonObject object, Evidence evidence, String source, int depth) {
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            String path = level.isEmpty() ? e.getKey() : level + "." + e.getKey();
            if (!known(c, level, e.getKey()) && (level.isEmpty() || c.field(level) != null)) {
                evidence.candidate(new Candidate(c.id(), level, e.getKey()), source);
            }
            if (e.getValue().isJsonObject() && depth < 3 && c.field(path) != null) {
                packKeys(c, path, e.getValue().getAsJsonObject(), evidence, source, depth + 1);
            }
        }
    }

    /** Texts of the binary: printable runs of 12 to 400 characters with a space and an underscore. */
    private static void scan(Path binary, Consumer<String> sink) throws IOException {
        try (FileChannel channel = FileChannel.open(binary, StandardOpenOption.READ)) {
            MappedByteBuffer data = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            int start = 0;
            boolean space = false;
            boolean underscore = false;
            int limit = data.limit();
            for (int i = 0; i <= limit; i++) {
                int b = i == limit ? 0 : data.get(i) & 0xff;
                if (b >= 0x20 && b < 0x7f) {
                    space |= b == ' ';
                    underscore |= b == '_';
                    continue;
                }
                int length = i - start;
                if (space && underscore && length >= 12 && length <= 400) {
                    byte[] bytes = new byte[length];
                    data.get(start, bytes);
                    sink.accept(new String(bytes, StandardCharsets.ISO_8859_1));
                }
                start = i + 1;
                space = false;
                underscore = false;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Probing

    /** What an earlier probe of the same build already answered: keys not to ask again. */
    static Set<String> answered(Fixtures fixtures, String bdsVersion) {
        Set<String> answered = new TreeSet<>();
        if (fixtures.keys.has("components") && fixtures.keys.get("bdsVersion").getAsString().equals(bdsVersion)) {
            fixtures.keys.getAsJsonObject("components").entrySet().forEach(c -> {
                for (String group : List.of("unknown", "known", "inert")) {
                    JsonElement answers = c.getValue().getAsJsonObject().get(group);
                    if (answers != null) {
                        (answers.isJsonArray() ? answers.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList()
                            : answers.getAsJsonObject().keySet()).forEach(path -> answered.add(c.getKey() + ":" + path));
                    }
                }
            });
        }
        return answered;
    }

    /** Adds a base per component with candidates and a variant per candidate and trivial value. */
    static void plan(AddonGenerator generator, Evidence evidence, Set<String> answered) {
        Map<String, List<Candidate>> perComponent = new TreeMap<>();
        evidence.candidates.keySet().stream().filter(c -> !answered.contains(c.component() + ":" + c.path()))
            .sorted(Comparator.comparing(Candidate::component).thenComparing(Candidate::path))
            .forEach(c -> perComponent.computeIfAbsent(c.component(), k -> new ArrayList<>()).add(c));
        perComponent.forEach((component, candidates) -> {
            generator.probeBase(component);
            for (Candidate candidate : candidates) {
                for (JsonElement value : generator.trivialValues(component, candidate.path())) {
                    generator.probe(component, candidate.path(), value);
                }
            }
        });
    }

    /**
     * What BDS answered, per component and path: {@code accepted} (the types it took, and the format version its own texts
     * name), {@code unknown} (blocks: "not present in the Schema"), {@code known} (blocks: no value of any type was
     * accepted, with the first error) and {@code inert} (items: accepted, and the wire is the base's).
     *
     * @param leaves the wire of each variant that BDS sent ({@code block <id>} / {@code item <id>})
     * @param log    the content log lines per variant identifier
     */
    static JsonObject results(AddonGenerator generator, Evidence evidence, String bdsVersion, Map<String, Map<String, Leaf>> leaves,
                              Map<String, List<String>> log, JsonObject previous) {
        Map<String, Map<String, List<AddonGenerator.Variant>>> probes = new TreeMap<>();
        Map<String, AddonGenerator.Variant> bases = new HashMap<>();
        for (AddonGenerator.Variant v : generator.variants()) {
            if (v.role().equals("base")) {
                bases.put(v.componentId(), v);
            } else {
                probes.computeIfAbsent(v.componentId(), k -> new TreeMap<>()).computeIfAbsent(v.path(), k -> new ArrayList<>()).add(v);
            }
        }
        JsonObject components = new JsonObject();
        probes.forEach((component, byPath) -> {
            JsonObject accepted = new JsonObject();
            JsonArray unknown = new JsonArray();
            JsonObject known = new JsonObject();
            JsonArray inert = new JsonArray();
            Map<String, Leaf> baseLeaves = leaves.get(wireKey(bases.get(component)));
            // errors the base has already say nothing about a key
            Set<String> baseErrors = errors(log.getOrDefault(bases.get(component).identifier(), List.of()), Set.of());
            byPath.forEach((path, variants) -> {
                String key = path.substring(path.lastIndexOf('.') + 1);
                boolean isUnknown = false;
                boolean visibleAtAll = false;
                List<String> types = new ArrayList<>();
                Set<JsonElement> examples = new LinkedHashSet<>();
                String firstError = null;
                for (AddonGenerator.Variant v : variants) {
                    List<String> lines = log.getOrDefault(v.identifier(), List.of());
                    Set<String> own = errors(lines, baseErrors);
                    boolean errors = !own.isEmpty();
                    isUnknown |= lines.stream().anyMatch(l -> l.contains("-> " + key + ": ") && l.contains("not present in the Schema"));
                    Map<String, Leaf> wire = leaves.get(wireKey(v));
                    boolean visible = wire != null && (v.kind().equals("block") || (baseLeaves != null && !wire.equals(baseLeaves)));
                    visibleAtAll |= wire != null;
                    if (!errors && visible && !isUnknown) {
                        types.add(v.label().substring("key:".length()));
                        if (v.value().isJsonPrimitive()) {
                            examples.add(v.value());
                        }
                    } else if (firstError == null && errors) {
                        firstError = own.iterator().next().replaceFirst("^.*\\| ", "");
                    }
                }
                if (isUnknown) {
                    unknown.add(path);
                } else if (!types.isEmpty()) {
                    JsonObject found = new JsonObject();
                    JsonArray typeArray = new JsonArray();
                    new LinkedHashSet<>(types).forEach(typeArray::add);
                    found.add("types", typeArray);
                    JsonArray exampleArray = new JsonArray();
                    examples.forEach(exampleArray::add);
                    found.add("examples", exampleArray);
                    TreeSet<String> versions = evidence.bdsVersions.get(key);
                    if (versions != null) {
                        found.addProperty("minFormatVersion", versions.last());
                    }
                    JsonArray sources = new JsonArray();
                    evidence.candidates.entrySet().stream().filter(e -> e.getKey().component().equals(component) && e.getKey().path().equals(path))
                        .flatMap(e -> e.getValue().stream()).distinct().forEach(sources::add);
                    found.add("candidateFrom", sources);
                    accepted.add(path, found);
                } else if (firstError != null) {
                    known.addProperty(path, firstError);
                } else if (visibleAtAll) {
                    inert.add(path);
                }
            });
            JsonObject result = new JsonObject();
            result.add("accepted", accepted);
            if (!unknown.isEmpty()) {
                result.add("unknown", unknown);
            }
            if (!known.isEmpty()) {
                result.add("known", known);
            }
            if (!inert.isEmpty()) {
                result.add("inert", inert);
            }
            components.add(component, result);
        });
        JsonObject json = new JsonObject();
        json.addProperty(Model.GENERATED_KEY, "Generated by `./gradlew :tools:run --args=\"spec-capture --probe-keys\"`: the keys of the components "
            + "that BDS " + bdsVersion + " accepts (the creator docs do not list them) or does not know. Do not edit.");
        json.addProperty("bdsVersion", bdsVersion);
        json.add("components", merge(previous, components, bdsVersion));
        return json;
    }

    /** A probe of the same build adds to the results of the earlier ones; another build's results are replaced. */
    private static JsonObject merge(JsonObject previous, JsonObject fresh, String bdsVersion) {
        if (previous == null || !previous.has("components") || !previous.get("bdsVersion").getAsString().equals(bdsVersion)) {
            return fresh;
        }
        JsonObject merged = previous.getAsJsonObject("components").deepCopy();
        fresh.entrySet().forEach(component -> {
            JsonObject into = merged.has(component.getKey()) ? merged.getAsJsonObject(component.getKey()) : new JsonObject();
            component.getValue().getAsJsonObject().entrySet().forEach(group -> {
                JsonElement existing = into.get(group.getKey());
                if (existing == null) {
                    into.add(group.getKey(), group.getValue());
                } else if (existing.isJsonArray()) {
                    Set<String> union = new TreeSet<>(existing.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList());
                    group.getValue().getAsJsonArray().forEach(e -> union.add(e.getAsString()));
                    JsonArray array = new JsonArray();
                    union.forEach(u -> array.add(new JsonPrimitive(u)));
                    into.add(group.getKey(), array);
                } else {
                    group.getValue().getAsJsonObject().entrySet().forEach(e -> existing.getAsJsonObject().add(e.getKey(), e.getValue()));
                }
            });
            merged.add(component.getKey(), into);
        });
        return merged;
    }

    /** The ERROR lines of a variant, without its file name, that are not in {@code except}. */
    static Set<String> errors(List<String> lines, Set<String> except) {
        Set<String> errors = new LinkedHashSet<>();
        for (String line : lines) {
            String normalized = line.replaceAll("(blocks|items)/[a-z0-9_]+\\.json|paletter:[a-z0-9_]+", "");
            if (line.startsWith("ERROR") && !except.contains(normalized)) {
                errors.add(normalized);
            }
        }
        return errors;
    }

    private static String wireKey(AddonGenerator.Variant v) {
        return v == null ? "" : v.kind() + " " + v.identifier();
    }
}
