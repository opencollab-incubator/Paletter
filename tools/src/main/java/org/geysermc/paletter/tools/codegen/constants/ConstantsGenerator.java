package org.geysermc.paletter.tools.codegen.constants;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.vanilla.BlockLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the vanilla constants ({@code org.geysermc.paletter.vanilla.BedrockBlocks}, {@code BedrockStates},
 * {@code BedrockItems}, the state enums and, when a preview is bundled, {@code BedrockPreviewBlocks} /
 * {@code BedrockPreviewItems}) from the newest bundled release, {@code codegen/names.json} and
 * {@code codegen/constants-history.json}. See {@code codegen/README.md}.
 * <p>
 * Used by {@code :tools:generateConstants} / {@code checkConstants} ({@link Main}) and by the generator's
 * {@code update} pipeline.
 */
public final class ConstantsGenerator {
    public static final String NAMES = "data/model/names.json";
    public static final String HISTORY = "data/model/constants-history.json";
    public static final String DATA = "paletter/src/main/resources/paletter";
    public static final String BDS = "data/bds";
    public static final String PACKAGE_DIR = "paletter/src/generated/java/org/geysermc/paletter/vanilla";
    public static final String STATE_DIR = PACKAGE_DIR + "/state";
    static final String PACKAGE = "org.geysermc.paletter.vanilla";
    static final String STATE_PACKAGE = PACKAGE + ".state";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern CONSTANT = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern CLASS = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Set<String> DIRECTIONS = Set.of("north", "south", "east", "west", "up", "down");

    /**
     * What a generation produced.
     *
     * @param files repository relative path to content, including the history
     * @param added constants added since the history (as {@code Class.NAME = identifier})
     * @param deprecated constants deprecated by this generation
     * @param tableBlocks blocks whose states are not a full product in a stride order (lookup table at runtime)
     * @param counts constants per class
     */
    public record Result(Map<String, String> files, List<String> added, List<String> deprecated, List<String> tableBlocks,
                         Map<String, Integer> counts, String release, String preview) {
    }

    /** One bundled version from {@code versions.txt}. */
    public record Bundle(String name, int protocol, boolean preview) {
    }

    private final VanillaData release;
    private final VanillaData preview;
    private final Sources sources;
    private final JsonObject names;
    private final JsonObject history;
    private final String version;
    private final List<String> added = new ArrayList<>();
    private final List<String> deprecated = new ArrayList<>();
    private final List<String> tableBlocks = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final JsonObject newHistory = new JsonObject();

    private ConstantsGenerator(VanillaData release, VanillaData preview, JsonObject names, JsonObject history, String version,
                               Sources sources) {
        this.release = release;
        this.sources = sources;
        this.preview = preview;
        this.names = names;
        this.history = history;
        this.version = version;
    }

    /**
     * Generates from the files of a repository root.
     */
    public static Result generate(Path root) throws IOException {
        return generate(root.resolve(DATA), readJson(root.resolve(NAMES)), readJson(root.resolve(HISTORY)), paletterVersion(root),
            Sources.read(root.resolve(DATA), root.resolve(BDS)));
    }

    /**
     * @param data a directory with {@code versions.txt} and the bundled {@code .nbt} files
     * @param history the committed history; empty for the first generation
     * @param version the Paletter version new and deprecated constants are marked with, e.g. {@code 2.1.0}
     */
    public static Result generate(Path data, JsonObject names, JsonObject history, String version) throws IOException {
        return generate(data, names, history, version, Sources.read(data, null));
    }

    /**
     * @param sources where constants came from: the bundled releases and what BDS captured of them (experiment gating,
     *                Education Edition), see {@link Sources}
     */
    public static Result generate(Path data, JsonObject names, JsonObject history, String version, Sources sources) throws IOException {
        List<Bundle> bundles = readIndex(data);
        Bundle release = bundles.stream().filter(b -> !b.preview()).reduce((a, b) -> b)
            .orElseThrow(() -> new IllegalStateException("No release version in " + data));
        Bundle preview = bundles.stream().filter(b -> b.preview() && b.protocol() > release.protocol()).reduce((a, b) -> b).orElse(null);
        return generate(read(data.resolve(release.name() + ".nbt")), preview == null ? null : read(data.resolve(preview.name() + ".nbt")),
            names, history, version, sources);
    }

    /** Generates with only the newest release as the source of versions: every constant is {@code @since} that release. */
    public static Result generate(VanillaData release, VanillaData preview, JsonObject names, JsonObject history, String version) {
        return generate(release, preview, names, history, version, Sources.of(release));
    }

    public static Result generate(VanillaData release, VanillaData preview, JsonObject names, JsonObject history, String version,
                                  Sources sources) {
        return new ConstantsGenerator(release, preview, names, history, version, sources).run();
    }

    public static JsonObject readJson(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** @return the version in the root {@code build.gradle.kts} without {@code -SNAPSHOT} */
    public static String paletterVersion(Path root) throws IOException {
        Matcher matcher = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(root.resolve("build.gradle.kts")));
        if (!matcher.find()) {
            throw new IllegalStateException("No version in build.gradle.kts");
        }
        return matcher.group(1).replace("-SNAPSHOT", "");
    }

    public static List<Bundle> readIndex(Path data) throws IOException {
        List<Bundle> bundles = new ArrayList<>();
        for (String line : Files.readAllLines(data.resolve("versions.txt"))) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            bundles.add(new Bundle(parts[0], Integer.parseInt(parts[1]), parts.length > 2 && parts[2].equals("preview")));
        }
        bundles.sort(Comparator.comparingInt(Bundle::protocol));
        return bundles;
    }

    private static VanillaData read(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return PaletteDataCodec.read(stream);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Result run() {
        Map<String, String> files = new TreeMap<>();
        List<BlockLayout> layouts = layouts(release);
        for (BlockLayout layout : layouts) {
            if (!layout.arithmetic()) {
                tableBlocks.add(layout.identifier() + ": " + layout.problem());
            }
        }

        StateModel states = states(layouts);
        states.enums.values().forEach(e -> files.put(STATE_DIR + "/" + e.name + ".java", JavaWriter.enumClass(e, version)));
        files.put(PACKAGE_DIR + "/BedrockStates.java", JavaWriter.states(states, release.version().name()));
        counts.put("BedrockStates", states.constants.size());
        counts.put("state enums", states.enums.size());

        Map<String, BlockLayout> byId = new LinkedHashMap<>();
        layouts.forEach(layout -> byId.put(layout.identifier(), layout));
        List<Constant> blocks = constants("blocks", new ArrayList<>(byId.keySet()), "BedrockBlocks");
        files.put(PACKAGE_DIR + "/BedrockBlocks.java", JavaWriter.blocks("BedrockBlocks", blocks, byId, states, release.version().name(), false));
        counts.put("BedrockBlocks", blocks.size());

        List<String> itemIds = new ArrayList<>();
        release.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).forEach(item -> itemIds.add(item.identifier()));
        Map<String, Integer> variantCounts = variantCounts(release);
        List<Constant> items = constants("items", itemIds, "BedrockItems");
        files.put(PACKAGE_DIR + "/BedrockItems.java", JavaWriter.items("BedrockItems", items, variantCounts, release.version().name(), false));
        counts.put("BedrockItems", items.size());

        if (preview != null) {
            List<BlockLayout> previewLayouts = layouts(preview);
            Map<String, BlockLayout> previewById = new LinkedHashMap<>();
            previewLayouts.stream().filter(l -> !byId.containsKey(l.identifier())).forEach(l -> previewById.put(l.identifier(), l));
            List<Constant> previewBlocks = previewConstants("blocks", new ArrayList<>(previewById.keySet()));
            files.put(PACKAGE_DIR + "/BedrockPreviewBlocks.java",
                JavaWriter.blocks("BedrockPreviewBlocks", previewBlocks, previewById, states, preview.version().name(), true));
            counts.put("BedrockPreviewBlocks", previewBlocks.size());
            Set<String> releaseItems = new LinkedHashSet<>(itemIds);
            List<String> previewItemIds = new ArrayList<>();
            preview.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).map(VanillaItem::identifier)
                .filter(id -> !releaseItems.contains(id)).forEach(previewItemIds::add);
            List<Constant> previewItems = previewConstants("items", previewItemIds);
            files.put(PACKAGE_DIR + "/BedrockPreviewItems.java",
                JavaWriter.items("BedrockPreviewItems", previewItems, variantCounts(preview), preview.version().name(), true));
            counts.put("BedrockPreviewItems", previewItems.size());
        }

        files.put(HISTORY, historyJson(newHistory));
        return new Result(files, List.copyOf(added), List.copyOf(deprecated), List.copyOf(tableBlocks), counts,
            release.version().name(), preview == null ? null : preview.version().name());
    }

    /** One line per constant, so the history diffs like the classes. */
    private static String historyJson(JsonObject history) {
        Gson compact = new GsonBuilder().disableHtmlEscaping().create();
        StringBuilder out = new StringBuilder("{\n");
        List<String> sections = new ArrayList<>(history.keySet());
        for (int s = 0; s < sections.size(); s++) {
            out.append("  ").append(compact.toJson(sections.get(s))).append(": {\n");
            JsonObject section = history.getAsJsonObject(sections.get(s));
            List<String> keys = new ArrayList<>(section.keySet());
            for (int k = 0; k < keys.size(); k++) {
                out.append("    ").append(compact.toJson(keys.get(k))).append(": ").append(compact.toJson(section.get(keys.get(k))))
                    .append(k == keys.size() - 1 ? "\n" : ",\n");
            }
            out.append(s == sections.size() - 1 ? "  }\n" : "  },\n");
        }
        return out.append("}\n").toString();
    }

    private static List<BlockLayout> layouts(VanillaData data) {
        try {
            return BlockLayout.of(data.blockStates());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Bundled data " + data.version().name() + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Integer> variantCounts(VanillaData data) {
        // Variants beyond the base one: damage values and block states of the creative inventory
        Map<String, Set<String>> variants = new HashMap<>();
        data.creative().forEachStack(stack -> {
            if (stack.damage() != 0) {
                variants.computeIfAbsent(stack.identifier(), id -> new TreeSet<>()).add(String.valueOf(stack.damage()));
            }
        });
        Map<String, Integer> counts = new HashMap<>();
        variants.forEach((id, set) -> counts.put(id, set.size()));
        return counts;
    }

    // ---- Blocks and items ----------------------------------------------------------------------------------------

    /**
     * A generated constant.
     *
     * @param identifier the identifier it resolves to (for a removed one: the one it had)
     * @param replacement for a deprecated alias: the constant it points to
     * @param fallback for a removed one: what it resolves to
     * @param deprecated the version it was deprecated in, or null
     * @param bedrock the bundled Bedrock release it first appears in without an experiment, or null (preview constants)
     * @param education whether BDS only offers it with Education Edition features on
     * @param gating the experiment toggles that gate it in bundled releases, e.g. {@code `y_2026_drop_3` in 26.40}, or null
     */
    record Constant(String name, String identifier, String since, String deprecated, String replacement, String fallback,
                    String bedrock, boolean education, String gating) {
    }

    private List<Constant> constants(String kind, List<String> identifiers, String className) {
        JsonObject overrides = object(names, kind);
        JsonObject previous = object(history, kind);
        JsonObject renamed = object(object(names, "renamed"), kind);
        JsonObject fallbacks = object(object(names, "fallbacks"), kind);

        previous.entrySet().forEach(entry -> entry(entry, kind));
        Map<String, String> current = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        for (String identifier : identifiers) {
            String name = overrides.has(identifier) ? overrides.get(identifier).getAsString() : constantName(identifier);
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException(identifier + " gives the constant name " + name + ", which is not a Java constant; add it to "
                    + NAMES + " " + kind);
            }
            String existing = current.put(name, identifier);
            if (existing != null) {
                throw new IllegalStateException("Duplicate constant " + className + "." + name + " for " + existing + " and " + identifier
                    + "; give one a name in " + NAMES + " " + kind);
            }
            nameOf.put(identifier, name);
        }

        Map<String, Constant> constants = new TreeMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            JsonObject old = previous.has(entry.getKey()) ? previous.getAsJsonObject(entry.getKey()) : null;
            String since = old != null ? required(old, "since", kind + "." + entry.getKey()) : version;
            if (old == null) {
                added.add(className + "." + entry.getKey() + " = " + entry.getValue());
            }
            // What the history recorded stays (a version leaving the bundle must not change it); else it is derived
            String bedrock = old != null && old.has("bedrock") ? old.get("bedrock").getAsString() : sources.firstVersion(kind, entry.getValue());
            boolean education = old != null && old.has("education") && old.get("education").getAsBoolean() || sources.isEducation(kind, entry.getValue());
            constants.put(entry.getKey(), new Constant(entry.getKey(), entry.getValue(), since, null, null, null, bedrock, education,
                sources.gating(kind, entry.getValue())));
        }
        // Constants the data no longer has under that name stay, deprecated
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(previous.asMap()).entrySet()) {
            String name = entry.getKey();
            if (constants.containsKey(name)) {
                continue;
            }
            JsonObject old = entry(entry, kind);
            String identifier = required(old, "identifier", kind + "." + name);
            String since = required(old, "since", kind + "." + name);
            String bedrock = old.has("bedrock") ? old.get("bedrock").getAsString() : sources.firstVersion(kind, identifier);
            boolean education = old.has("education") && old.get("education").getAsBoolean() || sources.isEducation(kind, identifier);
            String deprecatedSince = old.has("deprecated") ? old.get("deprecated").getAsString() : version;
            String target = nameOf.get(identifier);
            if (target == null && renamed.has(identifier)) {
                target = nameOf.get(renamed.get(identifier).getAsString());
            }
            if (target == null && old.has("replacement")) {
                target = old.get("replacement").getAsString();
                if (!current.containsKey(target) && !previous.has(target)) {
                    throw new IllegalStateException(HISTORY + ": " + kind + "." + name + " is an alias of " + target
                        + ", which is neither generated nor in the history");
                }
            }
            Constant constant;
            if (target != null) {
                constant = new Constant(name, identifier, since, deprecatedSince, target, null, bedrock, education, sources.gating(kind, identifier));
            } else {
                String fallback = fallbacks.has(identifier) ? fallbacks.get(identifier).getAsString()
                    : old.has("fallback") ? old.get("fallback").getAsString() : "minecraft:unknown";
                constant = new Constant(name, identifier, since, deprecatedSince, null, fallback, bedrock, education, sources.gating(kind, identifier));
            }
            if (!old.has("deprecated")) {
                deprecated.add(className + "." + name + " (" + identifier + (target != null ? ", now " + target : ", removed; resolves to " + constant.fallback()) + ")");
            }
            constants.put(name, constant);
        }
        // Aliases point at constants that exist
        for (Constant constant : constants.values()) {
            if (constant.replacement() != null && !constants.containsKey(constant.replacement())) {
                throw new IllegalStateException(className + "." + constant.name() + " is deprecated for " + constant.replacement() + ", which does not exist");
            }
        }

        JsonObject out = new JsonObject();
        for (Constant constant : constants.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", constant.identifier());
            entry.addProperty("since", constant.since());
            entry.addProperty("bedrock", constant.bedrock());
            if (constant.education()) {
                entry.addProperty("education", true);
            }
            if (constant.deprecated() != null) {
                entry.addProperty("deprecated", constant.deprecated());
                if (constant.replacement() != null) {
                    entry.addProperty("replacement", constant.replacement());
                } else {
                    entry.addProperty("fallback", constant.fallback());
                }
            }
            out.add(constant.name(), entry);
        }
        newHistory.add(kind, out);
        return List.copyOf(constants.values());
    }

    private List<Constant> previewConstants(String kind, List<String> identifiers) {
        JsonObject overrides = object(names, kind);
        Map<String, Constant> constants = new TreeMap<>();
        for (String identifier : identifiers) {
            String name = overrides.has(identifier) ? overrides.get(identifier).getAsString() : constantName(identifier);
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException("Preview " + identifier + " gives the constant name " + name + "; add it to " + NAMES + " " + kind);
            }
            if (constants.put(name, new Constant(name, identifier, version, null, null, null, null, false, null)) != null) {
                throw new IllegalStateException("Duplicate preview constant " + name + "; give one a name in " + NAMES + " " + kind);
            }
        }
        return List.copyOf(constants.values());
    }

    /** UPPER_SNAKE of the path; other namespaces than {@code minecraft} are kept as a prefix. */
    static String constantName(String identifier) {
        int colon = identifier.indexOf(':');
        String namespace = colon == -1 ? "minecraft" : identifier.substring(0, colon);
        String path = identifier.substring(colon + 1);
        String name = namespace.equals("minecraft") ? path : namespace + "_" + path;
        return name.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT);
    }

    // ---- Where constants came from ---------------------------------------------------------------------------------

    /**
     * What decides {@code @since} and {@code @Education} of a constant, besides the newest release itself.
     * <ul>
     *     <li>{@code releases}: every bundled release, oldest first. A constant is {@code @since} the first of them that
     *     has the identifier and does not offer it only through an experiment;</li>
     *     <li>{@code gatedItems} / {@code gatedBlocks}: per release, what the BDS captures of that release
     *     ({@code data/bds/<bds version>/experiments.json}) show to need a current experiment toggle in that release.
     *     Content that is only gated in the first release that has it is {@code @since} the next one that does not
     *     gate it (or, if there is none, the first one that has it);</li>
     *     <li>{@code educationItems} / {@code educationBlocks}: what BDS offers only to a world with Education Edition
     *     features (captured by {@code education-capture}, {@code data/bds/<bds version>/education.json}).</li>
     * </ul>
     */
    public static final class Sources {
        private final List<VanillaData> releases;
        private final Map<String, Map<String, String>> gatedItems;
        private final Map<String, Map<String, String>> gatedBlocks;
        private final Set<String> educationItems;
        private final Set<String> educationBlocks;
        private final Map<String, Set<String>> blockNames = new HashMap<>();
        private final Map<String, Set<String>> itemNames = new HashMap<>();

        /**
         * @param gatedItems release name to item identifier to the current experiment toggle that adds it in that release
         * @param gatedBlocks the same for blocks
         */
        public Sources(List<VanillaData> releases, Map<String, Map<String, String>> gatedItems, Map<String, Map<String, String>> gatedBlocks,
                       Set<String> educationItems, Set<String> educationBlocks) {
            this.releases = List.copyOf(releases);
            this.gatedItems = gatedItems;
            this.gatedBlocks = gatedBlocks;
            this.educationItems = educationItems;
            this.educationBlocks = educationBlocks;
            for (VanillaData release : releases) {
                Set<String> blocks = new HashSet<>();
                release.blockStates().forEach(state -> blocks.add(state.getString("name")));
                blockNames.put(release.version().name(), blocks);
                Set<String> items = new HashSet<>();
                release.items().forEach(item -> items.add(item.identifier()));
                itemNames.put(release.version().name(), items);
            }
        }

        /** @return every bundled release, oldest first */
        public List<VanillaData> releases() {
            return releases;
        }

        /** Only the given release: everything is {@code @since} it, nothing is gated, nothing is education. */
        public static Sources of(VanillaData release) {
            return new Sources(List.of(release), Map.of(), Map.of(), Set.of(), Set.of());
        }

        /**
         * @param data the bundled data directory ({@code versions.txt} and the version files)
         * @param bds the {@code data/bds} directory with the captures, or null for none
         */
        public static Sources read(Path data, Path bds) throws IOException {
            List<VanillaData> releases = new ArrayList<>();
            List<Bundle> bundles = new ArrayList<>();
            for (Bundle bundle : readIndex(data)) {
                if (!bundle.preview()) {
                    bundles.add(bundle);
                    releases.add(ConstantsGenerator.read(data.resolve(bundle.name() + ".nbt")));
                }
            }
            Map<String, Map<String, String>> gatedItems = new TreeMap<>();
            Map<String, Map<String, String>> gatedBlocks = new TreeMap<>();
            Set<String> educationItems = new TreeSet<>();
            Set<String> educationBlocks = new TreeSet<>();
            if (bds != null && Files.isDirectory(bds)) {
                List<Path> captures;
                try (var stream = Files.list(bds)) {
                    captures = stream.filter(Files::isDirectory).sorted().toList();
                }
                for (Path capture : captures) {
                    Path experiments = capture.resolve("experiments.json");
                    if (Files.exists(experiments)) {
                        JsonObject json = readJson(experiments);
                        String release = releaseOf(bundles, json.get("protocol").getAsInt());
                        if (release != null) {
                            for (Map.Entry<String, JsonElement> toggle : json.getAsJsonObject("toggles").entrySet()) {
                                JsonObject value = toggle.getValue().getAsJsonObject();
                                if (value.get("status").getAsString().equals("current") && value.has("content")) {
                                    JsonObject content = value.getAsJsonObject("content");
                                    for (String item : strings(content, "itemsAdded", "scriptItemsAdded")) {
                                        gatedItems.computeIfAbsent(release, r -> new TreeMap<>()).putIfAbsent(item, toggle.getKey());
                                    }
                                    for (String block : strings(content, "blocksAdded", "dataDrivenBlocks")) {
                                        gatedBlocks.computeIfAbsent(release, r -> new TreeMap<>()).putIfAbsent(block, toggle.getKey());
                                    }
                                }
                            }
                        }
                    }
                    Path education = capture.resolve("education.json");
                    if (Files.exists(education)) {
                        JsonObject json = readJson(education);
                        educationItems.addAll(strings(json, "items"));
                        educationBlocks.addAll(strings(json, "blocks"));
                    }
                }
            }
            return new Sources(releases, gatedItems, gatedBlocks, educationItems, educationBlocks);
        }

        /** The bundled release a protocol belongs to: the newest whose first protocol is not above it. */
        static String releaseOf(List<Bundle> releases, int protocol) {
            String name = null;
            for (Bundle bundle : releases) {
                if (bundle.protocol() <= protocol) {
                    name = bundle.name();
                }
            }
            return name;
        }

        private static List<String> strings(JsonObject object, String... keys) {
            List<String> values = new ArrayList<>();
            for (String key : keys) {
                if (object.has(key)) {
                    object.getAsJsonArray(key).forEach(element -> values.add(element.getAsString()));
                }
            }
            return values;
        }

        /**
         * @param kind {@code blocks} or {@code items}
         * @return the name of the first release that has the identifier without an experiment, else the first one that
         *         has it at all, else the oldest release
         */
        String firstVersion(String kind, String identifier) {
            boolean blocks = kind.equals("blocks");
            String first = null;
            for (VanillaData release : releases) {
                if (!has(release, blocks, identifier)) {
                    continue;
                }
                if (first == null) {
                    first = release.version().name();
                }
                if (!(blocks ? gatedBlocks : gatedItems).getOrDefault(release.version().name(), Map.of()).containsKey(identifier)) {
                    return release.version().name();
                }
            }
            return first != null ? first : releases.getFirst().version().name();
        }

        /**
         * @return the experiment toggles that gate the identifier in bundled releases, as
         *         {@code `toggle` in 26.40, 26.50} (a toggle per clause, separated by semicolons), or null if none does
         */
        String gating(String kind, String identifier) {
            boolean blocks = kind.equals("blocks");
            Map<String, List<String>> versions = new TreeMap<>();
            for (VanillaData release : releases) {
                String name = release.version().name();
                String toggle = (blocks ? gatedBlocks : gatedItems).getOrDefault(name, Map.of()).get(identifier);
                if (toggle != null && has(release, blocks, identifier)) {
                    versions.computeIfAbsent(toggle, t -> new ArrayList<>()).add(name);
                }
            }
            if (versions.isEmpty()) {
                return null;
            }
            List<String> clauses = new ArrayList<>();
            versions.forEach((toggle, names) -> clauses.add("{@code " + toggle + "} in " + String.join(", ", names)));
            return String.join("; ", clauses);
        }

        boolean isEducation(String kind, String identifier) {
            return (kind.equals("blocks") ? educationBlocks : educationItems).contains(identifier);
        }

        private boolean has(VanillaData release, boolean blocks, String identifier) {
            return (blocks ? blockNames : itemNames).get(release.version().name()).contains(identifier);
        }
    }

    // ---- States --------------------------------------------------------------------------------------------------

    enum Kind { BOOL, INT, ENUM }

    /** A state constant. */
    static final class StateConstant {
        final String constant;
        final String name;
        final Kind kind;
        final List<Integer> ints;
        String enumName;
        final String since;
        String deprecated;
        String replacement;
        int blocks;

        StateConstant(String constant, String name, Kind kind, List<Integer> ints, String since) {
            this.constant = constant;
            this.name = name;
            this.kind = kind;
            this.ints = ints;
            this.since = since;
        }
    }

    /** A generated enum. */
    static final class EnumModel {
        final String name;
        final String since;
        final Map<String, EnumValue> values = new LinkedHashMap<>();
        final Set<String> states = new TreeSet<>();

        EnumModel(String name, String since) {
            this.name = name;
            this.since = since;
        }
    }

    record EnumValue(String constant, String value, String since, String deprecated) {
    }

    static final class StateModel {
        final Map<String, StateConstant> constants = new TreeMap<>();
        final Map<String, StateConstant> byName = new HashMap<>();
        final Map<String, EnumModel> enums = new TreeMap<>();
    }

    private StateModel states(List<BlockLayout> layouts) {
        // Every state name with its values, in order of first appearance over the blocks sorted by identifier (so the
        // palette order of blocks does not matter), and how many blocks have it
        Map<String, List<Object>> values = new TreeMap<>();
        Map<String, Integer> usage = new HashMap<>();
        List<BlockLayout> byIdentifier = new ArrayList<>(layouts);
        byIdentifier.sort(Comparator.comparing(BlockLayout::identifier));
        for (BlockLayout layout : byIdentifier) {
            for (int p = 0; p < layout.properties().size(); p++) {
                List<Object> list = values.computeIfAbsent(layout.properties().get(p), k -> new ArrayList<>());
                for (Object value : layout.values().get(p)) {
                    if (!list.contains(value)) {
                        list.add(value);
                    }
                }
                usage.merge(layout.properties().get(p), 1, Integer::sum);
            }
        }

        JsonObject overrides = object(names, "states");
        JsonObject enumOverrides = object(names, "enums");
        JsonObject previousStates = object(history, "states");
        JsonObject previousEnums = object(history, "enums");
        StateModel model = new StateModel();

        // Enums of the history keep their states and values
        Map<String, String> enumOfState = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : previousEnums.entrySet()) {
            JsonObject old = entry(entry, "enums");
            EnumModel model1 = new EnumModel(entry.getKey(), required(old, "since", "enums." + entry.getKey()));
            if (!old.has("states") || !old.has("values")) {
                throw new IllegalStateException(HISTORY + ": enums." + entry.getKey() + " lacks \"states\" or \"values\"");
            }
            for (JsonElement state : old.getAsJsonArray("states")) {
                enumOfState.put(state.getAsString(), entry.getKey());
            }
            for (Map.Entry<String, JsonElement> value : old.getAsJsonObject("values").entrySet()) {
                JsonObject v = value.getValue().getAsJsonObject();
                String where = "enums." + entry.getKey() + "." + value.getKey();
                model1.values.put(required(v, "value", where), new EnumValue(value.getKey(), required(v, "value", where),
                    required(v, "since", where), v.has("deprecated") ? v.get("deprecated").getAsString() : null));
            }
            model.enums.put(model1.name, model1);
        }

        Map<String, String> constantOf = new TreeMap<>();
        for (Map.Entry<String, List<Object>> entry : values.entrySet()) {
            String name = entry.getKey();
            List<Object> list = entry.getValue();
            Kind kind;
            if (list.stream().allMatch(v -> v instanceof Byte b && (b == 0 || b == 1))) {
                kind = Kind.BOOL;
            } else if (list.stream().allMatch(v -> v instanceof Integer)) {
                kind = Kind.INT;
            } else if (list.stream().allMatch(v -> v instanceof String)) {
                kind = Kind.ENUM;
            } else {
                throw new IllegalStateException("State " + name + " has values of no supported kind: " + list);
            }
            String constant = overrides.has(name) ? overrides.get(name).getAsString() : constantName(name);
            if (!CONSTANT.matcher(constant).matches()) {
                throw new IllegalStateException("State " + name + " gives the constant name " + constant + "; add it to " + NAMES + " states");
            }
            if (constantOf.containsKey(constant)) {
                throw new IllegalStateException("Duplicate constant BedrockStates." + constant + " for " + constantOf.get(constant) + " and " + name
                    + "; give one a name in " + NAMES + " states");
            }
            constantOf.put(constant, name);
            JsonObject old = previousStates.has(constant) ? previousStates.getAsJsonObject(constant) : null;
            if (old != null && !required(old, "kind", "states." + constant).equals(kind.name().toLowerCase())) {
                throw new IllegalStateException("BedrockStates." + constant + " (" + name + ") changed from " + old.get("kind").getAsString()
                    + " to " + kind + ", which would change the type of the constant; give the state a new name in " + NAMES + " states");
            }
            List<Integer> ints = new ArrayList<>();
            if (kind == Kind.INT) {
                list.forEach(v -> ints.add((Integer) v));
                ints.sort(null);
            }
            StateConstant state = new StateConstant(constant, name, kind, List.copyOf(ints),
                old != null ? required(old, "since", "states." + constant) : version);
            state.blocks = usage.get(name);
            if (old == null) {
                added.add("BedrockStates." + constant + " = " + name);
            }
            model.constants.put(constant, state);
            model.byName.put(name, state);
        }

        // String states: keep their enum, join an enum with the same values, or get a new one
        Map<List<String>, List<String>> groups = new LinkedHashMap<>();
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            List<String> stateValues = new ArrayList<>();
            values.get(state.name).forEach(v -> stateValues.add((String) v));
            String existing = enumOfState.get(state.name);
            if (existing != null) {
                state.enumName = existing;
                continue;
            }
            Set<String> sorted = new TreeSet<>(stateValues);
            for (EnumModel model1 : model.enums.values()) {
                Set<String> enumValues = new TreeSet<>();
                model1.values.values().stream().filter(v -> v.deprecated() == null).forEach(v -> enumValues.add(v.value()));
                if (enumValues.equals(sorted)) {
                    state.enumName = model1.name;
                    break;
                }
            }
            if (state.enumName == null) {
                groups.computeIfAbsent(List.copyOf(sorted), k -> new ArrayList<>()).add(state.name);
            }
        }
        for (List<String> states : groups.values()) {
            String enumName = null;
            for (String state : states) {
                if (enumOverrides.has(state)) {
                    enumName = enumOverrides.get(state).getAsString();
                }
            }
            if (enumName == null) {
                enumName = className(states.stream().sorted().findFirst().orElseThrow());
            }
            if (!CLASS.matcher(enumName).matches()) {
                throw new IllegalStateException("Enum name " + enumName + " of " + states + " is no Java class name; set one in " + NAMES + " enums");
            }
            if (model.enums.containsKey(enumName)) {
                throw new IllegalStateException("Duplicate enum " + enumName + " for " + states + " and " + model.enums.get(enumName).states
                    + "; set a name in " + NAMES + " enums");
            }
            model.enums.put(enumName, new EnumModel(enumName, version));
            for (String state : states) {
                model.byName.get(state).enumName = enumName;
            }
            added.add("enum " + enumName + " for " + states);
        }
        // Enum values: the existing ones in their order, then new values in order of first appearance
        for (StateConstant state : model.constants.values()) {
            if (state.kind != Kind.ENUM) {
                continue;
            }
            EnumModel model1 = model.enums.get(state.enumName);
            model1.states.add(state.name);
            for (Object value : values.get(state.name)) {
                EnumValue existing = model1.values.get((String) value);
                if (existing == null) {
                    String constant = ((String) value).replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT);
                    if (!CONSTANT.matcher(constant).matches()) {
                        throw new IllegalStateException("Value " + value + " of " + state.name + " is no Java constant name");
                    }
                    model1.values.put((String) value, new EnumValue(constant, (String) value, version, null));
                    if (previousEnums.has(model1.name)) {
                        added.add(model1.name + "." + constant);
                    }
                } else if (existing.deprecated() != null) {
                    model1.values.put((String) value, new EnumValue(existing.constant(), existing.value(), existing.since(), null));
                }
            }
        }
        // Values no state has anymore are deprecated; the names in an enum must be unique
        for (EnumModel model1 : model.enums.values()) {
            Set<String> current = new LinkedHashSet<>();
            for (String state : model1.states) {
                values.getOrDefault(state, List.of()).forEach(v -> current.add((String) v));
            }
            Set<String> constants = new LinkedHashSet<>();
            for (Map.Entry<String, EnumValue> entry : new ArrayList<>(model1.values.entrySet())) {
                EnumValue value = entry.getValue();
                if (!current.contains(value.value()) && value.deprecated() == null) {
                    model1.values.put(entry.getKey(), new EnumValue(value.constant(), value.value(), value.since(), version));
                    deprecated.add(model1.name + "." + value.constant() + " (no state has " + value.value() + " anymore)");
                }
                if (!constants.add(value.constant())) {
                    throw new IllegalStateException("Duplicate constant " + model1.name + "." + value.constant());
                }
            }
        }

        // States the data no longer has stay, deprecated
        for (Map.Entry<String, JsonElement> entry : new TreeMap<>(previousStates.asMap()).entrySet()) {
            if (model.constants.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject old = entry(entry, "states");
            String where = "states." + entry.getKey();
            String name = required(old, "name", where);
            Kind kind;
            try {
                kind = Kind.valueOf(required(old, "kind", where).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(HISTORY + ": " + where + " has the unknown kind " + old.get("kind") + " (bool, int or enum)");
            }
            if (kind == Kind.ENUM && !old.has("enum")) {
                throw new IllegalStateException(HISTORY + ": " + where + " is an enum state without \"enum\"");
            }
            List<Integer> ints = new ArrayList<>();
            if (old.has("values")) {
                for (String value : old.get("values").getAsString().split(",")) {
                    try {
                        ints.add(Integer.parseInt(value.strip()));
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(HISTORY + ": " + where + " has the value " + value + ", which is no integer");
                    }
                }
            }
            StateConstant state = new StateConstant(entry.getKey(), name, kind, ints, required(old, "since", where));
            state.enumName = old.has("enum") ? old.get("enum").getAsString() : null;
            state.deprecated = old.has("deprecated") ? old.get("deprecated").getAsString() : version;
            StateConstant renamedTo = model.byName.get(name);
            if (renamedTo != null && renamedTo.kind == kind) {
                state.replacement = renamedTo.constant;
            }
            if (!old.has("deprecated")) {
                deprecated.add("BedrockStates." + entry.getKey() + " (" + name + (state.replacement != null ? ", now " + state.replacement : ", removed") + ")");
            }
            if (kind == Kind.ENUM && !model.enums.containsKey(state.enumName)) {
                throw new IllegalStateException("BedrockStates." + entry.getKey() + " needs enum " + state.enumName + ", which is not in the history");
            }
            model.constants.put(entry.getKey(), state);
        }

        JsonObject statesOut = new JsonObject();
        for (StateConstant state : model.constants.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", state.name);
            entry.addProperty("kind", state.kind.name().toLowerCase());
            entry.addProperty("since", state.since);
            if (state.kind == Kind.INT) {
                List<String> ints = new ArrayList<>();
                state.ints.forEach(value -> ints.add(String.valueOf(value)));
                entry.addProperty("values", String.join(",", ints));
            }
            if (state.enumName != null) {
                entry.addProperty("enum", state.enumName);
            }
            if (state.deprecated != null) {
                entry.addProperty("deprecated", state.deprecated);
                if (state.replacement != null) {
                    entry.addProperty("replacement", state.replacement);
                }
            }
            statesOut.add(state.constant, entry);
        }
        newHistory.add("states", statesOut);
        JsonObject enumsOut = new JsonObject();
        for (EnumModel model1 : model.enums.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("since", model1.since);
            JsonArray states = new JsonArray();
            // States of deprecated constants keep their enum
            Set<String> all = new TreeSet<>(model1.states);
            model.constants.values().stream().filter(s -> model1.name.equals(s.enumName)).forEach(s -> all.add(s.name));
            all.forEach(states::add);
            entry.add("states", states);
            JsonObject valuesOut = new JsonObject();
            for (EnumValue value : model1.values.values()) {
                JsonObject v = new JsonObject();
                v.addProperty("value", value.value());
                v.addProperty("since", value.since());
                if (value.deprecated() != null) {
                    v.addProperty("deprecated", value.deprecated());
                }
                valuesOut.add(value.constant(), v);
            }
            entry.add("values", valuesOut);
            enumsOut.add(model1.name, entry);
        }
        newHistory.add("enums", enumsOut);
        return model;
    }

    /** PascalCase of a state name without namespace and without a trailing direction ({@code _east}, ...). */
    static String className(String state) {
        String path = state.substring(state.indexOf(':') + 1);
        List<String> parts = new ArrayList<>(List.of(path.split("_")));
        if (parts.size() > 1 && DIRECTIONS.contains(parts.getLast())) {
            parts.removeLast();
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private static JsonObject entry(Map.Entry<String, JsonElement> entry, String section) {
        if (!entry.getValue().isJsonObject()) {
            throw new IllegalStateException(HISTORY + ": " + section + "." + entry.getKey() + " is not an object");
        }
        return entry.getValue().getAsJsonObject();
    }

    private static String required(JsonObject object, String key, String where) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalStateException(HISTORY + ": " + where + " lacks \"" + key + "\"");
        }
        return value.getAsString();
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (!parent.has(key)) {
            return new JsonObject();
        }
        if (!parent.get(key).isJsonObject()) {
            throw new IllegalStateException("\"" + key + "\" in " + NAMES + " or " + HISTORY + " is not an object");
        }
        return parent.getAsJsonObject(key);
    }

}
