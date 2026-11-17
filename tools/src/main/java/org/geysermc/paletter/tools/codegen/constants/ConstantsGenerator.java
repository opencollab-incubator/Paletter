package org.geysermc.paletter.tools.codegen.constants;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.Renames;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;
import org.geysermc.paletter.internal.vanilla.BlockLayout;
import org.geysermc.paletter.tools.data.VariantNames;

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
    public static final String PACKAGE_DIR = "paletter/src/generated/java/org/geysermc/paletter/vanilla";
    public static final String STATE_DIR = PACKAGE_DIR + "/state";
    static final String PACKAGE = "org.geysermc.paletter.vanilla";
    static final String STATE_PACKAGE = PACKAGE + ".state";
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern CONSTANT = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern CLASS = Pattern.compile("[A-Z][A-Za-z0-9]*");
    /** What the message of a name that needs an override points to. */
    private static final String OVERRIDES = "the name overrides of ConstantsGenerator";
    /** Where a state or enum that needs another name than the default gets it. */
    private static final String HISTORY_NAMES = HISTORY + " (add its entry there under the name it should have)";

    /**
     * The names constants get where they differ from the default (UPPER_SNAKE of a state name, PascalCase of an enum's first
     * state). The names of states and enums are the ones {@code constants-history.json} records
     * ({@code minecraft:facing_direction} is {@code MINECRAFT_FACING_DIRECTION}, as {@code FACING_DIRECTION} is
     * {@code facing_direction}; the enum of {@code wall_connection_type_east} is {@code WallConnectionType}); a new state
     * or enum whose default name does not work fails generation until the history names it.
     *
     * @param blocks block identifier to constant name
     * @param items item identifier to constant name
     * @param states state name to constant name
     * @param enums state name to the name of the enum of its values
     * @param renames the identifiers Bedrock renamed, which keep their old constants as deprecated aliases
     */
    public record Names(Map<String, String> blocks, Map<String, String> items, Map<String, String> states, Map<String, String> enums,
                        Renames renames) {
        /** The names a history records: a state's constant and a string state's enum. */
        public static Names of(Renames renames, JsonObject history) {
            Map<String, String> states = new TreeMap<>();
            Map<String, String> enums = new TreeMap<>();
            object(history, "states").entrySet().forEach(state -> states.put(state.getValue().getAsJsonObject().get("name").getAsString(), state.getKey()));
            object(history, "enums").entrySet().forEach(type -> type.getValue().getAsJsonObject().getAsJsonArray("states")
                .forEach(state -> enums.put(state.getAsString(), type.getKey())));
            return new Names(Map.of(), Map.of(), states, enums, renames);
        }

        Map<String, String> overrides(String kind) {
            return kind.equals("blocks") ? blocks : items;
        }

        String renamed(String kind, String identifier) {
            return kind.equals("blocks") ? renames.blocks().get(identifier) : renames.items().get(identifier);
        }
    }

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
    private final Names names;
    private final JsonObject history;
    private final String version;
    private final List<String> added = new ArrayList<>();
    private final List<String> deprecated = new ArrayList<>();
    private final List<String> tableBlocks = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final JsonObject newHistory = new JsonObject();

    private ConstantsGenerator(VanillaData release, VanillaData preview, Names names, JsonObject history, String version,
                               Sources sources) {
        this.release = release;
        this.sources = sources;
        this.preview = preview;
        this.names = names;
        this.history = history.deepCopy();
        this.history.remove("_generated");
        this.version = version;
    }

    /**
     * Generates from the files of a repository root.
     */
    public static Result generate(Path root) throws IOException {
        return generate(root.resolve(DATA), names(root), readJson(root.resolve(HISTORY)), paletterVersion(root),
            Sources.read(root.resolve(DATA), root.resolve(BDS), root.resolve(VariantNames.FILE)));
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

    /** The names the committed history records, with the renames Paletter bundles. */
    public static Names names(Path root) throws IOException {
        return Names.of(Renames.parse(Files.readAllLines(root.resolve(RENAMES))), readJson(root.resolve(HISTORY)));
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

        // Content only the bundled preview has goes into the same classes, marked @SincePreview and kept out of the history
        List<BlockLayout> previewLayouts = preview == null ? List.of() : layouts(preview);
        StateModel states = states(layouts, previewLayouts);
        states.enums.values().forEach(e -> files.put(STATE_DIR + "/" + e.name + ".java", JavaWriter.enumClass(e, version)));
        files.put(PACKAGE_DIR + "/BedrockStates.java", JavaWriter.states(states, release.version().name()));
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
        List<Constant> blocks = constants("blocks", releaseBlocks, previewBlocks, "BedrockBlocks");
        files.put(PACKAGE_DIR + "/BedrockBlocks.java", JavaWriter.blocks(blocks, byId, states, release.version().name()));
        counts.put("BedrockBlocks", blocks.size());

        List<String> itemIds = new ArrayList<>();
        release.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).forEach(item -> itemIds.add(item.identifier()));
        Map<String, Integer> variantCounts = variantCounts(release);
        List<String> previewItemIds = new ArrayList<>();
        if (preview != null) {
            Set<String> releaseItems = new LinkedHashSet<>(itemIds);
            preview.items().stream().sorted(Comparator.comparingInt(VanillaItem::runtimeId)).map(VanillaItem::identifier)
                .filter(id -> !releaseItems.contains(id)).forEach(previewItemIds::add);
            variantCounts(preview).forEach(variantCounts::putIfAbsent);
        }
        List<Constant> items = constants("items", itemIds, previewItemIds, "BedrockItems");
        files.put(PACKAGE_DIR + "/BedrockItems.java", JavaWriter.items(items, variantCounts, release.version().name()));
        counts.put("BedrockItems", items.size());
        if (preview != null) {
            counts.put("preview only", (int) (blocks.stream().filter(c -> c.preview() != null).count()
                + items.stream().filter(c -> c.preview() != null).count()
                + states.constants.values().stream().filter(c -> c.preview != null).count()));
        }

        List<Variant> variants = variants();
        files.put(PACKAGE_DIR + "/BedrockItemVariants.java", JavaWriter.variants(variants, release.version().name()));
        counts.put("BedrockItemVariants", variants.size());

        files.put(HISTORY, historyJson(newHistory));
        return new Result(files, List.copyOf(added), List.copyOf(deprecated), List.copyOf(tableBlocks), counts,
            release.version().name(), preview == null ? null : preview.version().name());
    }

    /** One line per constant, so the history diffs like the classes. */
    private static String historyJson(JsonObject history) {
        Gson compact = new GsonBuilder().disableHtmlEscaping().create();
        StringBuilder out = new StringBuilder("{\n  \"_generated\": \"Generated by `./gradlew :tools:generateConstants` from the bundled data and renames.txt; "
            + "this history only grows. Do not edit.\",\n");
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
     * @param preview the bundled preview that alone has it ({@code @SincePreview}; not in the history), or null
     */
    record Constant(String name, String identifier, String since, String deprecated, String replacement, String fallback,
                    String bedrock, boolean education, String gating, String preview) {
    }

    /**
     * @param previewIdentifiers what only the bundled preview has: constants marked with the preview and not recorded in
     *                           the history (they come and go with the preview until a release has them)
     */
    private List<Constant> constants(String kind, List<String> identifiers, List<String> previewIdentifiers, String className) {
        Map<String, String> overrides = names.overrides(kind);
        JsonObject previous = object(history, kind);

        previous.entrySet().forEach(entry -> entry(entry, kind));
        Map<String, String> current = new TreeMap<>();
        Map<String, String> nameOf = new HashMap<>();
        for (String identifier : identifiers) {
            String name = overrides.getOrDefault(identifier, constantName(identifier));
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException(identifier + " gives the constant name " + name + ", which is not a Java constant; add it to "
                    + OVERRIDES + " (" + kind + ")");
            }
            String existing = current.put(name, identifier);
            if (existing != null) {
                throw new IllegalStateException("Duplicate constant " + className + "." + name + " for " + existing + " and " + identifier
                    + "; give one a name in " + OVERRIDES + " (" + kind + ")");
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
                sources.gating(kind, entry.getValue()), null));
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
            if (target == null && names.renamed(kind, identifier) != null) {
                target = nameOf.get(names.renamed(kind, identifier));
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
                constant = new Constant(name, identifier, since, deprecatedSince, target, null, bedrock, education, sources.gating(kind, identifier), null);
            } else {
                String fallback = old.has("fallback") ? old.get("fallback").getAsString() : "minecraft:unknown";
                constant = new Constant(name, identifier, since, deprecatedSince, null, fallback, bedrock, education, sources.gating(kind, identifier), null);
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
        for (String identifier : previewIdentifiers) {
            String name = overrides.getOrDefault(identifier, constantName(identifier));
            if (!CONSTANT.matcher(name).matches()) {
                throw new IllegalStateException("Preview " + identifier + " gives the constant name " + name + "; add it to " + OVERRIDES + " (" + kind + ")");
            }
            if (constants.containsKey(name)) {
                throw new IllegalStateException("Duplicate constant " + className + "." + name + " for " + constants.get(name).identifier()
                    + " and the preview's " + identifier + "; give one a name in " + OVERRIDES + " (" + kind + ")");
            }
            constants.put(name, new Constant(name, identifier, version, null, null, null, null, false, null, previewName()));
        }
        return List.copyOf(constants.values());
    }

    /**
     * An item variant constant: the generated constant of {@code BedrockItemVariants}.
     *
     * @param constant the name, the item's identifier, {@code since} and the marks. It has no {@code bedrock} version: the
     *                 names come from dumps of only the newest builds, which say nothing of older ones
     * @param aux the aux value
     */
    record Variant(Constant constant, int aux) {
    }

    /**
     * The constants of {@code BedrockItemVariants}: the names of {@code variant_names.txt} for the newest release, with
     * what only the bundled preview has marked {@code @SincePreview}. The history records each name with its identifier and
     * aux: a name is never dropped (one the data no longer has stays, deprecated; one that is now another name for the same
     * variant becomes an alias of it) and never changes meaning (a changed identifier or aux fails generation: older
     * bundled versions keep the old aux, so it would need a split in {@code renames.txt} and a new name).
     */
    private List<Variant> variants() {
        JsonObject previous = object(history, "variants");
        Map<String, VariantNames.Entry> current = new TreeMap<>();
        sources.variantNames(release.version().name()).forEach(entry -> current.put(entry.name(), entry));
        if (current.isEmpty() && !previous.isEmpty()) {
            throw new IllegalStateException("The newest release " + release.version().name() + " has no variant names in data/model/variant_names.txt, "
                + "but " + HISTORY + " has " + previous.size() + ": that would deprecate them all. Run script-dump on a build of it; names are not carried forward");
        }
        Map<String, String> nameOf = new HashMap<>();
        current.values().forEach(entry -> nameOf.put(entry.identifier() + "@" + entry.aux(), entry.name()));
        Map<String, Variant> variants = new TreeMap<>();
        for (VariantNames.Entry entry : current.values()) {
            checkName(entry);
            JsonObject old = previous.has(entry.name()) ? previous.getAsJsonObject(entry.name()) : null;
            if (old != null) {
                checkUnchanged(entry, old);
            } else {
                added.add("BedrockItemVariants." + entry.name() + " = " + entry.identifier() + "@" + entry.aux());
            }
            String since = old != null ? required(old, "since", "variants." + entry.name()) : version;
            variants.put(entry.name(), new Variant(new Constant(entry.name(), entry.identifier(), since, null, null, null, null, false, null, null),
                entry.aux()));
        }
        // Names the data no longer has stay, deprecated
        for (Map.Entry<String, JsonElement> entry : previous.entrySet()) {
            if (variants.containsKey(entry.getKey())) {
                continue;
            }
            JsonObject old = entry.getValue().getAsJsonObject();
            String name = entry.getKey();
            String identifier = required(old, "identifier", "variants." + name);
            int aux = old.get("aux").getAsInt();
            String deprecatedSince = old.has("deprecated") ? old.get("deprecated").getAsString() : version;
            String target = nameOf.get(identifier + "@" + aux);
            if (target == null && old.has("replacement") && previous.has(old.get("replacement").getAsString())) {
                target = old.get("replacement").getAsString();
            }
            if (!old.has("deprecated")) {
                deprecated.add("BedrockItemVariants." + name + " (" + identifier + "@" + aux + (target != null ? ", now " + target : ", removed") + ")");
            }
            variants.put(name, new Variant(new Constant(name, identifier, required(old, "since", "variants." + name), deprecatedSince, target, null,
                null, false, null, null), aux));
        }
        JsonObject out = new JsonObject();
        for (Variant variant : variants.values()) {
            Constant constant = variant.constant();
            if (constant.replacement() != null && !variants.containsKey(constant.replacement())) {
                throw new IllegalStateException("BedrockItemVariants." + constant.name() + " is deprecated for " + constant.replacement() + ", which does not exist");
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("identifier", constant.identifier());
            entry.addProperty("aux", variant.aux());
            entry.addProperty("since", constant.since());
            if (constant.deprecated() != null) {
                entry.addProperty("deprecated", constant.deprecated());
                if (constant.replacement() != null) {
                    entry.addProperty("replacement", constant.replacement());
                }
            }
            out.add(constant.name(), entry);
        }
        newHistory.add("variants", out);
        // What only the preview has: marked, and not in the history
        if (preview != null) {
            for (VariantNames.Entry entry : sources.variantNames(preview.version().name())) {
                if (variants.containsKey(entry.name())) {
                    continue;
                }
                checkName(entry);
                variants.put(entry.name(), new Variant(new Constant(entry.name(), entry.identifier(), version, null, null, null, null, false, null,
                    previewName()), entry.aux()));
            }
        }
        return List.copyOf(variants.values());
    }

    private static void checkName(VariantNames.Entry entry) {
        if (!CONSTANT.matcher(entry.name()).matches()) {
            throw new IllegalStateException(entry.identifier() + "@" + entry.aux() + " gives the variant name " + entry.name() + ", which is not a Java constant");
        }
    }

    private static void checkUnchanged(VariantNames.Entry entry, JsonObject old) {
        String identifier = old.get("identifier").getAsString();
        int aux = old.get("aux").getAsInt();
        if (!identifier.equals(entry.identifier()) || aux != entry.aux()) {
            throw new IllegalStateException("BedrockItemVariants." + entry.name() + " is " + identifier + "@" + aux + " in " + HISTORY + " but BDS now has "
                + entry.identifier() + "@" + entry.aux() + " under that name; a name keeps its meaning, so this needs a split in renames.txt and a new name");
        }
    }

    private String previewName() {
        return preview == null ? null : preview.version().name();
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

        /**
         * @param gatedItems release name to item identifier to the current experiment toggle that adds it in that release
         * @param gatedBlocks the same for blocks
         * @param variantNames bundled version name to the item variant names of {@code variant_names.txt}
         */
        public Sources(List<VanillaData> releases, Map<String, Map<String, String>> gatedItems, Map<String, Map<String, String>> gatedBlocks,
                       Set<String> educationItems, Set<String> educationBlocks, Map<String, List<VariantNames.Entry>> variantNames) {
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
            return read(data, bds, VariantNames.read(variantNames));
        }

        /**
         * @param variantNames bundled version name to its item variant names, as {@link VariantNames#read} gives them
         */
        public static Sources read(Path data, Path bds, Map<String, List<VariantNames.Entry>> variantNames) throws IOException {
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
            return new Sources(releases, gatedItems, gatedBlocks, educationItems, educationBlocks, variantNames);
        }

        /** The variant names of a bundled version, preview or release; none for a version without. */
        List<VariantNames.Entry> variantNames(String version) {
            return variantNames.getOrDefault(version, List.of());
        }

        /** The bundled release a protocol belongs to: the newest whose first protocol is not above it. */
        public static String releaseOf(List<Bundle> releases, int protocol) {
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
        /** The bundled preview that alone has the state ({@code @SincePreview}; not in the history), or null. */
        String preview;

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
        /** The bundled preview that alone has the enum's states, or null. */
        String preview;
        final Map<String, EnumValue> values = new LinkedHashMap<>();
        final Set<String> states = new TreeSet<>();

        EnumModel(String name, String since) {
            this.name = name;
            this.since = since;
        }
    }

    /**
     * @param preview the bundled preview that alone has the value ({@code @SincePreview}; not in the history), or null
     */
    record EnumValue(String constant, String value, String since, String deprecated, String preview) {
    }

    static final class StateModel {
        final Map<String, StateConstant> constants = new TreeMap<>();
        final Map<String, StateConstant> byName = new HashMap<>();
        final Map<String, EnumModel> enums = new TreeMap<>();
    }

    /**
     * @param previewLayouts the blocks of the bundled preview (empty without one): states and enum values only they have are
     *                       marked with the preview and kept out of the history
     */
    private StateModel states(List<BlockLayout> layouts, List<BlockLayout> previewLayouts) {
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
        Map<String, List<Object>> releaseValues = new TreeMap<>();
        values.forEach((name, list) -> releaseValues.put(name, List.copyOf(list)));
        List<BlockLayout> previewByIdentifier = new ArrayList<>(previewLayouts);
        previewByIdentifier.sort(Comparator.comparing(BlockLayout::identifier));
        for (BlockLayout layout : previewByIdentifier) {
            for (int p = 0; p < layout.properties().size(); p++) {
                String name = layout.properties().get(p);
                List<Object> list = values.computeIfAbsent(name, k -> new ArrayList<>());
                for (Object value : layout.values().get(p)) {
                    if (!list.contains(value)) {
                        list.add(value);
                    }
                }
                if (!releaseValues.containsKey(name)) {
                    usage.merge(name, 1, Integer::sum);
                }
            }
        }

        Map<String, String> overrides = names.states();
        Map<String, String> enumOverrides = names.enums();
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
                    required(v, "since", where), v.has("deprecated") ? v.get("deprecated").getAsString() : null, null));
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
            String constant = overrides.getOrDefault(name, constantName(name));
            if (!CONSTANT.matcher(constant).matches()) {
                throw new IllegalStateException("State " + name + " gives the constant name " + constant + "; add it to " + HISTORY_NAMES);
            }
            if (constantOf.containsKey(constant)) {
                throw new IllegalStateException("Duplicate constant BedrockStates." + constant + " for " + constantOf.get(constant) + " and " + name
                    + "; give one a name in " + HISTORY_NAMES);
            }
            constantOf.put(constant, name);
            JsonObject old = previousStates.has(constant) ? previousStates.getAsJsonObject(constant) : null;
            if (old != null && !required(old, "kind", "states." + constant).equals(kind.name().toLowerCase())) {
                throw new IllegalStateException("BedrockStates." + constant + " (" + name + ") changed from " + old.get("kind").getAsString()
                    + " to " + kind + ", which would change the type of the constant; give the state a new name in " + HISTORY_NAMES);
            }
            List<Integer> ints = new ArrayList<>();
            if (kind == Kind.INT) {
                // A release state keeps the values of the release
                releaseValues.getOrDefault(name, list).forEach(v -> ints.add((Integer) v));
                ints.sort(null);
            }
            StateConstant state = new StateConstant(constant, name, kind, List.copyOf(ints),
                old != null ? required(old, "since", "states." + constant) : version);
            state.blocks = usage.get(name);
            state.preview = old == null && !releaseValues.containsKey(name) ? previewName() : null;
            if (old == null && state.preview == null) {
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
                if (enumOverrides.containsKey(state)) {
                    enumName = enumOverrides.get(state);
                }
            }
            if (enumName == null) {
                enumName = className(states.stream().sorted().findFirst().orElseThrow());
            }
            if (!CLASS.matcher(enumName).matches()) {
                throw new IllegalStateException("Enum name " + enumName + " of " + states + " is no Java class name; set one in " + HISTORY_NAMES);
            }
            if (model.enums.containsKey(enumName)) {
                throw new IllegalStateException("Duplicate enum " + enumName + " for " + states + " and " + model.enums.get(enumName).states
                    + "; set a name in " + HISTORY_NAMES);
            }
            EnumModel created = new EnumModel(enumName, version);
            created.preview = states.stream().allMatch(state -> model.byName.get(state).preview != null) ? previewName() : null;
            model.enums.put(enumName, created);
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
                    // A value no state of the enum has in the release is the preview's
                    boolean previewOnly = preview != null && values.keySet().stream()
                        .filter(other -> model.byName.containsKey(other) && model1.name.equals(model.byName.get(other).enumName))
                        .noneMatch(other -> releaseValues.getOrDefault(other, List.of()).contains(value));
                    model1.values.put((String) value, new EnumValue(constant, (String) value, version, null, previewOnly ? previewName() : null));
                    if (previousEnums.has(model1.name) && !previewOnly) {
                        added.add(model1.name + "." + constant);
                    }
                } else if (existing.deprecated() != null) {
                    model1.values.put((String) value, new EnumValue(existing.constant(), existing.value(), existing.since(), null, null));
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
                    model1.values.put(entry.getKey(), new EnumValue(value.constant(), value.value(), value.since(), version, null));
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
            if (state.preview != null) {
                continue;
            }
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
            if (model1.preview != null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("since", model1.since);
            JsonArray states = new JsonArray();
            // States of deprecated constants keep their enum
            Set<String> all = new TreeSet<>(model1.states);
            model.constants.values().stream().filter(s -> model1.name.equals(s.enumName) && s.preview == null).forEach(s -> all.add(s.name));
            all.removeIf(state -> model.byName.containsKey(state) && model.byName.get(state).preview != null);
            all.forEach(states::add);
            entry.add("states", states);
            JsonObject valuesOut = new JsonObject();
            for (EnumValue value : model1.values.values()) {
                if (value.preview() != null) {
                    continue;
                }
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
            throw new IllegalStateException("\"" + key + "\" in " + HISTORY + " is not an object");
        }
        return parent.getAsJsonObject(key);
    }

}
