package dev.opencollab.paletter.tools.codegen.constants;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import dev.opencollab.paletter.tools.Json;
import dev.opencollab.paletter.tools.codegen.component.ExperimentToggles;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.data.VanillaItem;
import dev.opencollab.paletter.internal.vanilla.BlockLayout;
import dev.opencollab.paletter.tools.codegen.constants.ContentConstants.Constant;
import dev.opencollab.paletter.tools.codegen.constants.StateConstants.StateModel;
import dev.opencollab.paletter.tools.codegen.constants.VariantConstants.Variant;
import dev.opencollab.paletter.tools.data.VariantNames;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;


import java.io.IOException;
import java.io.InputStream;
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
 * Generates the vanilla constants ({@code dev.opencollab.paletter.vanilla.BedrockBlocks}, {@code BedrockStates},
 * {@code BedrockItems}, {@code BedrockItemVariants} and the state enums) from the newest bundled release, with what only a newer bundled preview has
 * marked {@code @SincePreview}, {@code renames.txt} and
 * {@code data/model/constants-history.json}. See {@code docs/data-pipeline.md}.
 * <p>
 * Used by {@code :tools:generateConstants} / {@code checkConstants} ({@link Main}) and by the generator's
 * {@code update} pipeline.
 */
public final class ConstantsGenerator {
    public static final String HISTORY = "data/model/constants-history.json";
    public static final String DATA = "paletter/src/main/resources/paletter";
    public static final String RENAMES = DATA + "/renames.txt";
    public static final String BDS = "data/bds";
    public static final String PACKAGE_DIR = "paletter/src/generated/java/dev/opencollab/paletter/vanilla";
    public static final String STATE_DIR = PACKAGE_DIR + "/state";
    static final String PACKAGE = "dev.opencollab.paletter.vanilla";
    static final String STATE_PACKAGE = PACKAGE + ".state";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Pattern CONSTANT = Pattern.compile("[A-Z_][A-Z0-9_]*");
    static final Pattern CLASS = Pattern.compile("[A-Z][A-Za-z0-9]*");
    /** The hand-written names for content whose default name does not work; see {@link Names}. */
    public static final String NAMES = "data/model/constant-names.txt";

    /** What a message tells to add to {@link #NAMES} for a name that needs an override. */
    static String addName(String kind, String key, String what) {
        return "add the line `" + kind + " " + key + " " + what + "` to " + NAMES;
    }

    /**
     * The names constants get where they differ from the default (UPPER_SNAKE of the identifier path or state name,
     * PascalCase of an enum's first state). Hand decisions are in {@link #NAMES}, one line {@code <kind> <key> <name>} each
     * ({@code #} comments): {@code blocks} and {@code items} by identifier, {@code states} by state name, {@code enums} by
     * any state of the enum (and the enum's name). A new identifier, state or enum whose default name does not work fails
     * generation until that file names it; a line that matches nothing in the data fails it too. Besides those, states
     * and enums keep the names {@code constants-history.json} records.
     *
     * @param blocks block identifier to constant name
     * @param items item identifier to constant name
     * @param states state name to constant name
     * @param enums state name to the name of the enum of its values
     * @param renames the identifiers Bedrock renamed, which keep their old constants as deprecated aliases
     * @param hand the lines of {@link #NAMES} as {@code <kind> <key>}, each of which must match the data
     */
    public record Names(Map<String, String> blocks, Map<String, String> items, Map<String, String> states, Map<String, String> enums,
                        Renames renames, Set<String> hand) {
        /**
         * @param lines the lines of {@link #NAMES}; they win over the names the history records (a state's constant and a
         *              string state's enum)
         */
        public static Names of(Renames renames, List<String> lines, JsonObject history) {
            Map<String, String> blocks = new TreeMap<>();
            Map<String, String> items = new TreeMap<>();
            Map<String, String> states = new TreeMap<>();
            Map<String, String> enums = new TreeMap<>();
            ConstantsHistory.object(history, "states").entrySet().forEach(state -> states.put(state.getValue().getAsJsonObject().get("name").getAsString(), state.getKey()));
            ConstantsHistory.object(history, "enums").entrySet().forEach(type -> type.getValue().getAsJsonObject().getAsJsonArray("states")
                .forEach(state -> enums.put(state.getAsString(), type.getKey())));
            Map<String, Map<String, String>> kinds = Map.of("blocks", blocks, "items", items, "states", states, "enums", enums);
            Set<String> hand = new TreeSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length != 3 || !kinds.containsKey(parts[0])) {
                    throw new IllegalStateException(NAMES + " line " + (i + 1) + ": expected `blocks|items|states|enums <key> <name>`, got `" + line + "`");
                }
                if (!hand.add(parts[0] + " " + parts[1])) {
                    throw new IllegalStateException(NAMES + " line " + (i + 1) + ": `" + parts[0] + " " + parts[1] + "` is named twice");
                }
                kinds.get(parts[0]).put(parts[1], parts[2]);
            }
            return new Names(blocks, items, states, enums, renames, hand);
        }

        Map<String, String> overrides(String kind) {
            return kind.equals("blocks") ? blocks : items;
        }

        /** @return what Bedrock renamed the identifier to (following chains of renames), or null */
        String renamed(String kind, String identifier) {
            return kind.equals("blocks") ? renames.block(identifier) : renames.item(identifier);
        }
    }

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


    private final Generation g;
    private final List<String> tableBlocks = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    private ConstantsGenerator(VanillaData release, VanillaData preview, Names names, JsonObject history, String version,
                               Sources sources) {
        this.g = new Generation(release, preview, names, history, version, sources);
    }

    /**
     * Generates from the files of a repository root.
     */
    public static Result generate(Path root) throws IOException {
        return generate(root.resolve(DATA), names(root), Json.objectOrEmpty(root.resolve(HISTORY)), paletterVersion(root),
            Sources.read(root.resolve(DATA), root.resolve(BDS), root.resolve(VariantNames.FILE)).withBaseline(root.resolve(Baseline.FILE)));
    }

    /**
     * @param data a directory with {@code versions.txt} and the bundled {@code .nbt} files
     * @param history the committed history; empty for the first generation
     * @param version the Paletter version new and deprecated constants are marked with, e.g. {@code 2.1.0}
     */
    public static Result generate(Path data, Names names, JsonObject history, String version) throws IOException {
        return generate(data, names, history, version, Sources.read(data, null, Map.of()));
    }

    /**
     * @param sources where constants came from: the bundled releases and what BDS captured of them (experiment gating,
     *                Education Edition), see {@link Sources}
     */
    public static Result generate(Path data, Names names, JsonObject history, String version, Sources sources) throws IOException {
        List<Bundle> bundles = readIndex(data);
        Bundle release = bundles.stream().filter(b -> !b.preview()).reduce((a, b) -> b)
            .orElseThrow(() -> new IllegalStateException("No release version in " + data));
        Bundle preview = bundles.stream().filter(b -> b.preview() && b.protocol() > release.protocol()).reduce((a, b) -> b).orElse(null);
        return generate(read(data.resolve(release.name() + ".nbt")), preview == null ? null : read(data.resolve(preview.name() + ".nbt")),
            names, history, version, sources);
    }

    /** Generates with only the newest release as the source of versions: every constant is {@code @since} that release. */
    public static Result generate(VanillaData release, VanillaData preview, Names names, JsonObject history, String version) {
        return generate(release, preview, names, history, version, Sources.of(release));
    }

    public static Result generate(VanillaData release, VanillaData preview, Names names, JsonObject history, String version,
                                  Sources sources) {
        return new ConstantsGenerator(release, preview, names, history, version, sources).run();
    }

    /** The names of {@link #NAMES} and the committed history, with the renames Paletter bundles. */
    public static Names names(Path root) throws IOException {
        Path names = root.resolve(NAMES);
        return Names.of(Renames.parse(Files.readAllLines(root.resolve(RENAMES))), Files.exists(names) ? Files.readAllLines(names) : List.of(),
            Json.objectOrEmpty(root.resolve(HISTORY)));
    }

    /** @return the version in the root {@code gradle.properties} without {@code -SNAPSHOT} */
    public static String paletterVersion(Path root) throws IOException {
        Matcher matcher = Pattern.compile("^version=(.+)$", Pattern.MULTILINE).matcher(Files.readString(root.resolve("gradle.properties")));
        if (!matcher.find()) {
            throw new IllegalStateException("No version in gradle.properties");
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
        String oldest = g.sources.releases().getFirst().version().name();
        List<BlockLayout> layouts = layouts(g.release);
        for (BlockLayout layout : layouts) {
            if (!layout.arithmetic()) {
                tableBlocks.add(layout.identifier() + ": " + layout.problem());
            }
        }

        // Content only the bundled preview has goes into the same classes, marked @SincePreview and kept out of the history
        List<BlockLayout> previewLayouts = g.preview == null ? List.of() : layouts(g.preview);
        StateModel states = new StateConstants(g).states(layouts, previewLayouts);
        states.enums.values().forEach(e -> files.put(STATE_DIR + "/" + e.name + ".java", ConstantsSource.enumClass(e, g.version)));
        files.put(PACKAGE_DIR + "/BedrockStates.java", ConstantsSource.states(states, g.release.version().name()));
        counts.put("BedrockStates", states.constants.size());
        counts.put("state enums", states.enums.size());

        Map<String, BlockLayout> byId = new LinkedHashMap<>();
        layouts.forEach(layout -> byId.put(layout.identifier(), layout));
        List<String> releaseBlocks = new ArrayList<>(byId.keySet());
        List<String> previewBlocks = new ArrayList<>();
        for (BlockLayout layout : previewLayouts) {
            if (!byId.containsKey(layout.identifier())) {
                byId.put(layout.identifier(), layout);
                previewBlocks.add(layout.identifier());
            }
        }
        Map<String, PreviewStates> previewStates = new HashMap<>();
        for (String identifier : previewBlocks) {
            previewStates.put(identifier, previewStates(g.preview, byId.get(identifier)));
        }
        ContentConstants content = new ContentConstants(g);
        List<Constant> blocks = content.constants("blocks", releaseBlocks, previewBlocks, "BedrockBlocks",
            (identifier, old) -> ownStates(identifier, old, previewStates, byId));
        files.put(PACKAGE_DIR + "/BedrockBlocks.java", ConstantsSource.blocks(blocks, byId, content.states, states, g.release.version().name(), oldest));
        counts.put("BedrockBlocks", blocks.size());

        List<String> itemIds = new ArrayList<>();
        g.release.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).forEach(item -> itemIds.add(item.identifier()));
        Map<String, Integer> variantCounts = variantCounts(g.release);
        List<String> previewItemIds = new ArrayList<>();
        if (g.preview != null) {
            Set<String> releaseItems = new LinkedHashSet<>(itemIds);
            g.preview.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).map(VanillaItem::identifier)
                .filter(id -> !releaseItems.contains(id)).forEach(previewItemIds::add);
            variantCounts(g.preview).forEach(variantCounts::putIfAbsent);
        }
        List<Constant> items = new ContentConstants(g).constants("items", itemIds, previewItemIds, "BedrockItems", null);
        files.put(PACKAGE_DIR + "/BedrockItems.java", ConstantsSource.items(items, variantCounts, g.release.version().name(), oldest));
        counts.put("BedrockItems", items.size());
        if (g.preview != null) {
            counts.put("preview only", (int) (blocks.stream().filter(c -> c.preview() != null).count()
                + items.stream().filter(c -> c.preview() != null).count()
                + states.constants.values().stream().filter(c -> c.preview != null).count()));
        }

        List<Variant> variants = new VariantConstants(g).variants();
        Map<String, String> itemNames = new HashMap<>();
        items.stream().filter(c -> c.replacement() == null).forEach(c -> itemNames.putIfAbsent(c.identifier(), c.name()));
        files.put(PACKAGE_DIR + "/BedrockItemVariants.java", ConstantsSource.variants(variants, itemNames, g.release.version().name()));
        counts.put("BedrockItemVariants", variants.size());

        Set<String> unused = new TreeSet<>(g.names.hand());
        unused.removeAll(g.usedNames);
        if (!unused.isEmpty()) {
            throw new IllegalStateException(NAMES + " names " + unused + ", which match nothing in the data anymore; remove the lines");
        }
        if (!g.sources.toggles().isEmpty()) {
            files.put(PACKAGE_DIR + "/BedrockExperiments.java", ConstantsSource.experiments(g.sources.toggles(), oldest));
            counts.put("BedrockExperiments", g.sources.toggles().size());
        }
        files.put(HISTORY, ConstantsHistory.toJson(g.newHistory));
        return new Result(files, List.copyOf(g.added), List.copyOf(g.deprecated), List.copyOf(tableBlocks), counts,
            g.release.version().name(), g.preview == null ? null : g.preview.version().name());
    }

    /**
     * The states of a block only the preview has, as its constant carries them: no release has the block, so a palette
     * without previews has no data for it.
     *
     * @param properties the property names in the preview's state order: the first one varies fastest
     * @param values the values of each property, as NBT, in order of first appearance
     * @param defaultOffset the position of the default state among the states
     */
    record PreviewStates(List<String> properties, List<List<Object>> values, int defaultOffset) {
        /** As the history records it: {@code {"default": n, "properties": [{"name", "type": byte|int|string, "values"}]}}. */
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("default", defaultOffset);
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            for (int p = 0; p < properties.size(); p++) {
                JsonObject property = new JsonObject();
                property.addProperty("name", properties.get(p));
                Object first = values.get(p).getFirst();
                property.addProperty("type", first instanceof Byte ? "byte" : first instanceof Integer ? "int" : "string");
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                for (Object value : values.get(p)) {
                    if (value instanceof Number number) {
                        array.add(number.intValue());
                    } else {
                        array.add((String) value);
                    }
                }
                property.add("values", array);
                list.add(property);
            }
            json.add("properties", list);
            return json;
        }

        /** Reads what {@link #toJson} wrote. */
        static PreviewStates fromJson(JsonObject json, String where) {
            List<String> properties = new ArrayList<>();
            List<List<Object>> values = new ArrayList<>();
            for (com.google.gson.JsonElement element : json.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                properties.add(ConstantsHistory.required(property, "name", where));
                String type = ConstantsHistory.required(property, "type", where);
                List<Object> list = new ArrayList<>();
                for (com.google.gson.JsonElement value : property.getAsJsonArray("values")) {
                    list.add(switch (type) {
                        case "byte" -> (byte) value.getAsInt();
                        case "int" -> value.getAsInt();
                        case "string" -> value.getAsString();
                        default -> throw new IllegalStateException(HISTORY + ": " + where + " has a state of type " + type);
                    });
                }
                values.add(List.copyOf(list));
            }
            return new PreviewStates(List.copyOf(properties), List.copyOf(values), json.get("default").getAsInt());
        }

        /** @return the {@code states} compound of each state, in the order of the properties' product, the first fastest */
        List<NbtMap> enumerate() {
            int count = 1;
            for (List<Object> list : values) {
                count *= list.size();
            }
            List<NbtMap> states = new ArrayList<>(count);
            for (int offset = 0; offset < count; offset++) {
                NbtMapBuilder builder = NbtMap.builder();
                int rest = offset;
                for (int p = 0; p < properties.size(); p++) {
                    builder.put(properties.get(p), values.get(p).get(rest % values.get(p).size()));
                    rest /= values.get(p).size();
                }
                states.add(builder.build());
            }
            return states;
        }
    }

    /**
     * The states of a block constant the newest release lacks: a preview block's from the preview; a removed one's from
     * the newest bundled release that has it, else as the history recorded them (its last bundled release was retired),
     * so its constant keeps its states and older palettes still send it.
     *
     * @param byId the layouts of the release and the preview, by identifier
     * @return the states, or null if they are unknown or no full product describes them (the constant then has one
     *         state without properties)
     */
    private PreviewStates ownStates(String identifier, JsonObject old, Map<String, PreviewStates> previewStates, Map<String, BlockLayout> byId) {
        PreviewStates preview = previewStates.get(identifier);
        if (preview != null) {
            return preview;
        }
        List<VanillaData> releases = g.sources.releases();
        for (int r = releases.size() - 1; r >= 0; r--) {
            VanillaData release = releases.get(r);
            if (release == g.release && byId.containsKey(identifier)) {
                continue;
            }
            BlockLayout layout = layouts(release).stream().filter(l -> l.identifier().equals(identifier)).findFirst().orElse(null);
            if (layout != null) {
                try {
                    return previewStates(release, layout);
                } catch (IllegalStateException e) {
                    // Not a full product in a stride order: the constant cannot carry these states
                    return null;
                }
            }
        }
        return old != null && old.has("states") ? PreviewStates.fromJson(old.getAsJsonObject("states"), "blocks." + identifier) : null;
    }

    /**
     * Reads a preview block's states from the preview's data, and checks that its constant, enumerating them, gives
     * the preview's states in the preview's order.
     *
     * @throws IllegalStateException if the block's states are no full product in a stride order, which a constant
     *                               cannot describe
     */
    static PreviewStates previewStates(VanillaData preview, BlockLayout layout) {
        String identifier = layout.identifier();
        int[] strides = layout.strides();
        if (strides == null) {
            throw new IllegalStateException("The block " + identifier + " of " + preview.version().name() + " needs a lookup table ("
                + layout.problem() + "), which its constant cannot describe");
        }
        List<Integer> order = new ArrayList<>();
        for (int p = 0; p < strides.length; p++) {
            order.add(p);
        }
        order.sort(Comparator.comparingInt(p -> strides[p]));
        List<String> properties = new ArrayList<>();
        List<List<Object>> values = new ArrayList<>();
        for (int p : order) {
            properties.add(layout.properties().get(p));
            values.add(layout.values().get(p));
        }
        List<NbtMap> data = new ArrayList<>();
        for (NbtMap state : preview.blockStates().subList(layout.first(), layout.first() + layout.count())) {
            data.add(state.getCompound("states", NbtMap.EMPTY));
        }
        NbtMap defaultState = preview.blockDefaults().get(identifier);
        int defaultOffset = defaultState == null ? 0 : data.indexOf(defaultState.getCompound("states", NbtMap.EMPTY));
        PreviewStates states = new PreviewStates(List.copyOf(properties), List.copyOf(values), Math.max(defaultOffset, 0));
        if (!states.enumerate().equals(data)) {
            throw new IllegalStateException("The states of the block " + identifier + " of " + preview.version().name()
                + " are not the product of its properties " + properties + " in that order");
        }
        return states;
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
     *     gate it (or, if there is none, the first one that has it). A block is gated with its item (the block to item
     *     relation of the release);</li>
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
        private final Map<String, List<VariantNames.Entry>> variantNames;
        private final List<ExperimentToggles.Toggle> toggles;
        private Baseline baseline = Baseline.NONE;

        /**
         * @param gatedItems release name to item identifier to the current experiment toggle that adds it in that release
         * @param gatedBlocks the same for blocks
         * @param variantNames bundled version name to the item variant names of {@code variant_names.txt}
         */
        public Sources(List<VanillaData> releases, Map<String, Map<String, String>> gatedItems, Map<String, Map<String, String>> gatedBlocks,
                       Set<String> educationItems, Set<String> educationBlocks, Map<String, List<VariantNames.Entry>> variantNames) {
            this(releases, gatedItems, gatedBlocks, educationItems, educationBlocks, variantNames, List.of());
        }

        /**
         * @param toggles the experiment toggles that were current in a captured build, sorted by id ({@link #toggles})
         */
        public Sources(List<VanillaData> releases, Map<String, Map<String, String>> gatedItems, Map<String, Map<String, String>> gatedBlocks,
                       Set<String> educationItems, Set<String> educationBlocks, Map<String, List<VariantNames.Entry>> variantNames,
                       List<ExperimentToggles.Toggle> toggles) {
            this.toggles = List.copyOf(toggles);
            this.variantNames = variantNames;
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

        /**
         * Content of the baseline, the release before the oldest bundled one, is not marked with the Bedrock release it is first
         * in: it is older than any bundled data says.
         *
         * @param file the baseline file ({@link Baseline#FILE}); none if it does not exist
         * @return these sources
         */
        public Sources withBaseline(Path file) throws IOException {
            baseline = Files.exists(file) ? Baseline.read(file) : Baseline.NONE;
            return this;
        }

        /** Whether the baseline release already had the block ({@code blocks}) or item ({@code items}). */
        boolean inBaseline(String kind, String identifier) {
            return baseline.has(kind, identifier);
        }

        /** @return the name of the oldest bundled release */
        String oldest() {
            return releases.getFirst().version().name();
        }

        /** @return the experiment toggles that were current in a captured build, sorted by id */
        public List<ExperimentToggles.Toggle> toggles() {
            return toggles;
        }

        /** @return every bundled release, oldest first */
        public List<VanillaData> releases() {
            return releases;
        }

        /** Only the given release: everything is {@code @since} it, nothing is gated, nothing is education. */
        public static Sources of(VanillaData release) {
            return new Sources(List.of(release), Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
        }

        /**
         * @param data the bundled data directory ({@code versions.txt} and the version files)
         * @param bds the {@code data/bds} directory with the captures, or null for none
         * @param variantNames the {@code variant_names.txt}, or null for none
         */
        public static Sources read(Path data, Path bds, Path variantNames) throws IOException {
            return read(data, bds, VariantNames.read(variantNames, VariantNames.versions(data)));
        }

        /**
         * @param variantNames bundled version name to its item variant names, as {@link VariantNames#read} gives them
         */
        public static Sources read(Path data, Path bds, Map<String, List<VariantNames.Entry>> variantNames) throws IOException {
            List<VanillaData> releases = new ArrayList<>();
            List<Bundle> bundles = readIndex(data);
            for (Bundle bundle : bundles) {
                if (!bundle.preview()) {
                    releases.add(ConstantsGenerator.read(data.resolve(bundle.name() + ".nbt")));
                }
            }
            BdsCaptures captures = BdsCaptures.read(bundles, bds);
            List<ExperimentToggles.Toggle> toggles = ExperimentToggles.read(data, bds);
            return new Sources(releases, captures.gatedItems(), captures.gatedBlocks(), captures.educationItems(), captures.educationBlocks(), variantNames, toggles);
        }

        /** The variant names of a bundled version, preview or release; none for a version without. */
        List<VariantNames.Entry> variantNames(String version) {
            return variantNames.getOrDefault(version, List.of());
        }

        /** The bundled release a protocol belongs to: the newest whose first protocol is not above it. */
        public static String releaseOf(List<Bundle> releases, int protocol) {
            Bundle bundle = bundleOf(releases, protocol);
            return bundle == null ? null : bundle.name();
        }

        /** The bundle a protocol belongs to: the newest whose first protocol is not above it, preview or not. */
        static Bundle bundleOf(List<Bundle> bundles, int protocol) {
            Bundle found = null;
            for (Bundle bundle : bundles) {
                if (bundle.protocol() <= protocol) {
                    found = bundle;
                }
            }
            return found;
        }

        boolean isRelease(String name) {
            return releases.stream().anyMatch(release -> release.version().name().equals(name));
        }

        /** Whether a bundled release has the block or item. */
        boolean has(String kind, String identifier) {
            return releases.stream().anyMatch(release -> has(release, kind.equals("blocks"), identifier));
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
                if (toggle(release, blocks, identifier) == null) {
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
                String toggle = toggle(release, blocks, identifier);
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

        /** The toggle that gates the identifier in a release: for a block also the one its item is gated by. */
        private String toggle(VanillaData release, boolean blocks, String identifier) {
            String name = release.version().name();
            if (!blocks) {
                return gatedItems.getOrDefault(name, Map.of()).get(identifier);
            }
            String toggle = gatedBlocks.getOrDefault(name, Map.of()).get(identifier);
            String item = release.blockItems().get(identifier);
            return toggle != null || item == null ? toggle : gatedItems.getOrDefault(name, Map.of()).get(item);
        }

        boolean isEducation(String kind, String identifier) {
            return (kind.equals("blocks") ? educationBlocks : educationItems).contains(identifier);
        }

        private boolean has(VanillaData release, boolean blocks, String identifier) {
            return (blocks ? blockNames : itemNames).get(release.version().name()).contains(identifier);
        }
    }
}
