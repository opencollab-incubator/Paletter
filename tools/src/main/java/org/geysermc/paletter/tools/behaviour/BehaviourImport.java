package org.geysermc.paletter.tools.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.Provenance;
import org.geysermc.paletter.block.BlockHashes;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.internal.behaviour.BehaviourStore;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.internal.data.VanillaItem;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Builds {@code behaviour.nbt} from the sources that dump BDS: CloudburstMC/Data and AllayMC/Allay (git checkouts,
 * every commit of their dump files is read and identified by its block state hashes), and Paletter's own Script API
 * dumps in {@code data/bds}. See {@link BehaviourStore} for the format and {@link BehaviourReport} for what it reports.
 * <p>
 * Ranking, strongest first: Paletter's own captures of BDS (native, Script API; the wire is ranked at runtime), then
 * CloudburstMC/Data, then Allay. Allay only decides what Data does not have (items). Third-party values the sources
 * disagree on without BDS evidence settling it are stored as disputes next to the value that is used, and values Data
 * says it post-processed are marked. The output has no timestamps: the same inputs give the same bytes.
 */
public final class BehaviourImport {
    /** A dump identifies a version when this share of its block states and the version's palette are the same states. */
    static final double MIN_SIMILARITY = 0.99;

    /** Where the importer writes the generated files, for the generated-by headers and the metadata. */
    static final String COMMAND = "./gradlew :tools:run --args=\"behaviour import --cloudburst-data <checkout of https://github.com/CloudburstMC/Data> "
        + "--allay <checkout of https://github.com/AllayMC/Allay>\"";
    /** The BDS defaults of custom content, written down with their evidence: an input of the importer. */
    static final String DEFAULTS_FILE = "data/behaviour/custom-defaults.json";

    /** The BDS default of a key for custom content, captured by a source named in {@code source}. */
    record CustomDefault(BehaviourKey<?> key, Object value, Provenance.Source source, String reference) {
    }

    /** The vanilla content of one bundled version. */
    record Bundled(DataVersion version, Map<Integer, NbtMap> states, Set<String> items) {
    }

    /** What one source said about one version; {@code match} says how the version was identified. */
    record Capture(Provenance.Source source, String reference, String date, DataVersion version, String match,
                   Values<Integer> blocks, Values<String> items) {
    }

    private final List<Bundled> bundled;
    private final List<Capture> captures = new ArrayList<>();
    private final List<CustomDefault> customDefaults = new ArrayList<>();
    final List<String> log = new ArrayList<>();

    BehaviourImport(List<Bundled> bundled) {
        this.bundled = bundled;
    }

    /** @return the bundled versions of a resources directory, oldest first */
    public static List<Bundled> bundled(Path resources) throws IOException {
        PaletteDataSource source = PaletteDataSource.directory(resources);
        List<Bundled> result = new ArrayList<>();
        for (DataVersion version : source.versions()) {
            VanillaData data = source.load(version);
            Map<Integer, NbtMap> states = new LinkedHashMap<>();
            data.blockStates().forEach(state -> states.put(BlockHashes.hash(state), state));
            Set<String> items = new HashSet<>();
            data.items().stream().map(VanillaItem::identifier).forEach(items::add);
            result.add(new Bundled(version, states, items));
        }
        return result;
    }

    /**
     * Reads every source and writes {@code behaviour.nbt} into the resources and the report.
     *
     * @param cloudburstData a checkout of https://github.com/CloudburstMC/Data, or null
     * @param allay a checkout of https://github.com/AllayMC/Allay (a partial clone is enough), or null
     */
    public static void run(Path root, @Nullable Path cloudburstData, @Nullable Path allay) throws IOException {
        Path resources = root.resolve("paletter/src/main/resources/paletter");
        BehaviourImport importer = new BehaviourImport(bundled(resources));
        importer.scriptApi(root.resolve("data/bds"));
        importer.customDefaults(root.resolve(DEFAULTS_FILE));
        if (cloudburstData != null) {
            importer.cloudburstData(new GitHistory(cloudburstData));
        }
        if (allay != null) {
            importer.allay(new GitHistory(allay));
        }
        BehaviourStore store = importer.build();
        try (var out = Files.newOutputStream(resources.resolve("behaviour.nbt"))) {
            store.write(out);
        }
        Path report = root.resolve("data/behaviour/REPORT.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, new BehaviourReport(importer.bundled, importer.captures, store, importer.log, importer.customDefaults).render());
        System.out.println(store);
        System.out.println("Wrote " + resources.resolve("behaviour.nbt") + " (" + Files.size(resources.resolve("behaviour.nbt"))
            + " bytes) and " + report);
    }

    List<Capture> captures() {
        return captures;
    }

    List<CustomDefault> customDefaults() {
        return customDefaults;
    }

    /**
     * {@code data/behaviour/custom-defaults.json}: {@code {"defaults": [{"key": "block:friction", "value": 0.6,
     * "source": "NATIVE", "reference": "what captured it"}]}}, the values BDS uses for custom blocks and items that do
     * not set the component. A missing file or list means no default is known. Values are never guessed: every entry
     * names a direct BDS source and its evidence.
     */
    void customDefaults(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!root.has("defaults")) {
            return;
        }
        for (JsonElement element : root.getAsJsonArray("defaults")) {
            JsonObject entry = element.getAsJsonObject();
            String name = entry.get("key").getAsString();
            BehaviourKey<?> key = name.startsWith("block:") ? org.geysermc.paletter.behaviour.BlockBehaviour.key(name.substring(6))
                : name.startsWith("item:") ? ItemBehaviour.key(name.substring(5)) : null;
            if (key == null) {
                throw new IOException(file + ": unknown key " + name);
            }
            JsonElement value = entry.get("value");
            Object typed;
            if (key.type() == Float.class) {
                typed = (float) value.getAsDouble();
            } else if (key.type() == Integer.class) {
                typed = value.getAsInt();
            } else if (key.type() == Boolean.class) {
                typed = value.getAsBoolean();
            } else if (key.type() == String.class) {
                typed = value.getAsString();
            } else {
                throw new IOException(file + ": defaults of " + key + " are not supported (type " + key.type().getSimpleName() + ")");
            }
            customDefaults.add(new CustomDefault(key, typed, Provenance.Source.valueOf(entry.get("source").getAsString()),
                entry.get("reference").getAsString()));
        }
    }

    // ---- Sources ----

    /** {@code data/bds/<bds>/dump.json.gz}: max stack size and durability per item; the version from the BDS protocol. */
    void scriptApi(Path bds) throws IOException {
        if (!Files.isDirectory(bds)) {
            return;
        }
        Map<DataVersion, Path> newest = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(bds)) {
            for (Path dir : dirs.sorted((a, b) -> compare(bdsOrder(a), bdsOrder(b))).toList()) {
                Path dump = dir.resolve("dump.json.gz");
                Path experiments = dir.resolve("experiments.json");
                if (Files.isRegularFile(dump) && Files.isRegularFile(experiments)) {
                    int protocol = JsonParser.parseString(Files.readString(experiments)).getAsJsonObject().get("protocol").getAsInt();
                    DataVersion version = versionOfProtocol(protocol);
                    if (version != null) {
                        newest.put(version, dir);
                    }
                }
            }
        }
        newest.forEach((version, dir) -> {
            Values<String> items = new Values<>();
            try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(dir.resolve("dump.json.gz"))), StandardCharsets.UTF_8)) {
                JsonObject all = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("items");
                for (Map.Entry<String, JsonElement> item : all.entrySet()) {
                    JsonObject fields = item.getValue().getAsJsonObject();
                    items.put(ItemBehaviour.MAX_STACK_SIZE, item.getKey(), fields.get("max_stack").getAsInt());
                    items.put(ItemBehaviour.MAX_DAMAGE, item.getKey(), fields.has("max_durability") ? fields.get("max_durability").getAsInt() : 0);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String bdsVersion = dir.getFileName().toString();
            captures.add(new Capture(Provenance.Source.SCRIPT_API, "Paletter data/bds/" + bdsVersion + "/dump.json.gz", "",
                version, "BDS " + bdsVersion, new Values<>(), items));
        });
    }

    private static List<Integer> bdsOrder(Path dir) {
        List<Integer> parts = new ArrayList<>();
        for (String part : dir.getFileName().toString().split("\\.")) {
            parts.add(part.chars().allMatch(Character::isDigit) && !part.isEmpty() ? Integer.parseInt(part) : -1);
        }
        return parts;
    }

    private static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /** {@code blocks.json}, before October 2026 {@code block_properties.json}; no items. */
    void cloudburstData(GitHistory git) {
        walk(git, Provenance.Source.CLOUDBURST_DATA, List.of("blocks.json", "block_properties.json"), null);
    }

    /** {@code data/resources/unpacked/block_states_raw.json} and {@code items_raw.json} of the same commit. */
    void allay(GitHistory git) {
        walk(git, Provenance.Source.ALLAY_DATA, List.of("data/resources/unpacked/block_states_raw.json"),
            "data/resources/unpacked/items_raw.json");
    }

    /**
     * Reads the commits of a source newest first; the newest commit of each version wins. History before the first
     * commit that identifies no version after one that did is older than every bundled version and is not read.
     */
    private void walk(GitHistory git, Provenance.Source source, List<String> blockFiles, @Nullable String itemFile) {
        Set<DataVersion> found = new HashSet<>();
        for (GitHistory.Commit commit : git.commits(blockFiles.toArray(String[]::new))) {
            Values<Integer> blocks = null;
            String file = null;
            for (String candidate : blockFiles) {
                if (git.exists(commit, candidate)) {
                    String text = git.text(commit, candidate);
                    if (isBlockDump(text)) {
                        blocks = parse(() -> EndstoneDump.blocks(new StringReader(text)));
                        file = candidate;
                        break;
                    }
                }
            }
            if (blocks == null) {
                log.add(source + " " + commit.shortHash() + " (" + commit.subject() + "): no block dump");
                continue;
            }
            Identified identified = identify(blocks.ids);
            if (identified == null) {
                log.add(source + " " + commit.shortHash() + " " + commit.date() + " (" + commit.subject() + "): matches no bundled version");
                if (!found.isEmpty()) {
                    break;
                }
                continue;
            }
            String match = file + ", " + identified.describe();
            if (!found.add(identified.version())) {
                log.add(source + " " + commit.shortHash() + " " + commit.date() + " (" + commit.subject() + "): " + identified.version().name()
                    + ", older than the commit used");
                continue;
            }
            Values<String> items = itemFile != null && git.exists(commit, itemFile)
                ? EndstoneDump.items(new StringReader(git.text(commit, itemFile))) : new Values<>();
            captures.add(new Capture(source, git.reference(commit), commit.date(), identified.version(), match, blocks, items));
            log.add(source + " " + commit.shortHash() + " " + commit.date() + " (" + commit.subject() + "): " + identified.version().name() + ", " + match);
        }
    }

    private static boolean isBlockDump(String text) {
        try {
            return EndstoneDump.isBlockDump(new StringReader(text));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private interface Parse<T> {
        T get() throws IOException;
    }

    private static <T> T parse(Parse<T> parse) {
        try {
            return parse.get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Version identification ----

    record Identified(DataVersion version, double similarity, int onlyInDump, int onlyInPalette) {
        String describe() {
            return similarity == 1 ? "same block states" : String.format(java.util.Locale.ROOT,
                "%.4f of the block states (%d only in the dump, %d only in the palette)", similarity, onlyInDump, onlyInPalette);
        }
    }

    /** @return the bundled version whose block states are the dump's, or the most similar one above {@link #MIN_SIMILARITY} */
    @Nullable Identified identify(Set<Integer> hashes) {
        Identified best = null;
        for (Bundled version : bundled) {
            Set<Integer> palette = version.states().keySet();
            int common = (int) hashes.stream().filter(palette::contains).count();
            double similarity = (double) common / (hashes.size() + palette.size() - common);
            if (best == null || similarity > best.similarity()) {
                best = new Identified(version.version(), similarity, hashes.size() - common, palette.size() - common);
            }
        }
        return best != null && best.similarity() >= MIN_SIMILARITY ? best : null;
    }

    private @Nullable DataVersion versionOfProtocol(int protocol) {
        DataVersion result = null;
        for (Bundled version : bundled) {
            if (version.version().protocolVersion() <= protocol) {
                result = version.version();
            }
        }
        return result;
    }

    Bundled bundled(DataVersion version) {
        return bundled.stream().filter(b -> b.version().equals(version)).findFirst().orElseThrow();
    }

    // ---- Store ----

    /**
     * Stores the captures in application order: by version, and within a version the weakest source first, so the
     * strongest decides every id it covers and the others only fill the ids it lacks.
     */
    BehaviourStore build() {
        captures.sort(Comparator.comparing((Capture c) -> c.version())
            .thenComparing(c -> -c.source().ordinal()));
        BehaviourStore.Builder builder = new BehaviourStore.Builder(bundled.stream().map(Bundled::version).toList());
        List<Set<Integer>> coveredBlocks = new ArrayList<>();
        List<Set<String>> coveredItems = new ArrayList<>();
        for (Capture capture : captures) {
            Bundled palette = bundled(capture.version());
            Set<Integer> blocks = covered(capture, palette.states().keySet(), Capture::blocks);
            Set<String> items = covered(capture, palette.items(), Capture::items);
            int index = builder.capture(capture.version(), capture.source(), capture.reference(), blocks, items);
            coveredBlocks.add(blocks);
            coveredItems.add(items);
            capture.blocks().byKey.forEach((key, values) -> builder.blocks(raw(key), index, restrict(values, blocks)));
            capture.items().byKey.forEach((key, values) -> builder.items(raw(key), index, restrict(values, items)));
        }
        marks(builder, coveredBlocks, Capture::blocks);
        marks(builder, coveredItems, Capture::items);
        for (CustomDefault custom : customDefaults) {
            builder.defaultOf(raw(custom.key()), custom.value(), custom.source(), custom.reference());
        }
        return builder.metadata(metadata()).build();
    }

    /** Capture {@code i} of {@link #captures} is capture {@code i} of the builder. */
    private <I> void marks(BehaviourStore.Builder builder, List<Set<I>> covered, java.util.function.Function<Capture, Values<I>> values) {
        int n = captures.size();
        // Own observations: [capture] -> key -> id -> (alternative capture -> its value), and -> note
        List<Map<BehaviourKey<?>, Map<I, Map<Integer, Object>>>> disputes = new ArrayList<>();
        List<Map<BehaviourKey<?>, Map<I, String>>> notes = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            Map<BehaviourKey<?>, Map<I, Map<Integer, Object>>> disputed = new LinkedHashMap<>();
            Map<BehaviourKey<?>, Map<I, String>> noted = new LinkedHashMap<>();
            disputes.add(disputed);
            notes.add(noted);
            Capture capture = captures.get(a);
            Values<I> own = values.apply(capture);
            if (!capture.source().thirdParty()) {
                continue;
            }
            int self = a;
            own.postProcessed.forEach((key, byId) -> byId.forEach((id, note) -> {
                if (covered.get(self).contains(id) && own.byKey.getOrDefault(key, Map.of()).containsKey(id)) {
                    noted.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, note);
                }
            }));
            for (int b = 0; b < n; b++) {
                Capture other = captures.get(b);
                if (b == a || !other.version().equals(capture.version()) || !other.source().thirdParty()
                    || other.source().ordinal() <= capture.source().ordinal()) {
                    continue;
                }
                Values<I> weaker = values.apply(other);
                int alternative = b;
                own.byKey.forEach((key, byId) -> {
                    Map<I, Object> theirs = weaker.byKey.get(key);
                    if (theirs == null) {
                        return;
                    }
                    byId.forEach((id, value) -> {
                        Object alt = theirs.get(id);
                        // The capture must decide the id: no stronger capture of the version has the key for it (a
                        // direct one would settle it: the sources are only cross-checked then)
                        if (alt != null && !alt.equals(value) && covered.get(self).contains(id) && !strongerHas(capture, key, id, values)) {
                            disputed.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(id, k -> new LinkedHashMap<>())
                                .put(alternative, alt);
                        }
                    });
                });
            }
        }
        // Carry: a source's value that no other source of its version could be checked against, and that is the same
        // as in a version where it was disputed or post-processed, is disputed / post-processed in the same way. Only
        // what a source's capture itself observed is carried, never what was carried: applied after the loop
        List<Runnable> carried = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            int self = a;
            Capture capture = captures.get(a);
            if (!capture.source().thirdParty()) {
                continue;
            }
            Values<I> own = values.apply(capture);
            for (Map.Entry<BehaviourKey<?>, Map<I, Object>> entry : own.byKey.entrySet()) {
                BehaviourKey<?> key = entry.getKey();
                for (Map.Entry<I, Object> value : entry.getValue().entrySet()) {
                    I id = value.getKey();
                    if (!covered.get(a).contains(id)) {
                        continue;
                    }
                    if (!checkedAgainstAnother(a, key, id, values)) {
                        for (int c : nearest(a)) {
                            Map<Integer, Object> alts = disputes.get(c).getOrDefault(key, Map.of()).get(id);
                            if (alts != null && Objects.equals(values.apply(captures.get(c)).byKey.get(key).get(id), value.getValue())) {
                                carried.add(() -> disputes.get(self).computeIfAbsent(key, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(id, k -> new LinkedHashMap<>()).putAll(alts));
                                break;
                            }
                        }
                    }
                    if (!own.reportsPostProcessing.contains(key) && !notes.get(a).getOrDefault(key, Map.of()).containsKey(id)) {
                        for (int c : nearest(a)) {
                            String note = notes.get(c).getOrDefault(key, Map.of()).get(id);
                            if (note != null && Objects.equals(values.apply(captures.get(c)).byKey.get(key).get(id), value.getValue())) {
                                String carriedNote = note + " (carried from " + captures.get(c).version().name() + ": the value is the same)";
                                carried.add(() -> notes.get(self).computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, carriedNote));
                                break;
                            }
                        }
                    }
                }
            }
        }
        carried.forEach(Runnable::run);
        emit(builder, disputes, notes);
    }

    private <I> boolean strongerHas(Capture capture, BehaviourKey<?> key, I id, java.util.function.Function<Capture, Values<I>> values) {
        for (Capture other : captures) {
            if (other != capture && other.version().equals(capture.version()) && other.source().ordinal() < capture.source().ordinal()
                && values.apply(other).byKey.getOrDefault(key, Map.of()).containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if another third-party capture of the version has a value for the key and id, equal or not */
    private <I> boolean checkedAgainstAnother(int a, BehaviourKey<?> key, I id, java.util.function.Function<Capture, Values<I>> values) {
        Capture capture = captures.get(a);
        for (int b = 0; b < captures.size(); b++) {
            Capture other = captures.get(b);
            if (b != a && other.version().equals(capture.version()) && other.source().thirdParty()
                && values.apply(other).byKey.getOrDefault(key, Map.of()).containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    /** @return the other captures of the source of capture {@code a}, the nearest version first, older before newer */
    private List<Integer> nearest(int a) {
        Capture capture = captures.get(a);
        int at = versionIndex(capture.version());
        List<Integer> same = new ArrayList<>();
        for (int c = 0; c < captures.size(); c++) {
            if (c != a && captures.get(c).source() == capture.source()) {
                same.add(c);
            }
        }
        same.sort(Comparator.comparingInt((Integer c) -> Math.abs(versionIndex(captures.get(c).version()) - at))
            .thenComparingInt(c -> versionIndex(captures.get(c).version())));
        return same;
    }

    private int versionIndex(DataVersion version) {
        for (int i = 0; i < bundled.size(); i++) {
            if (bundled.get(i).version().equals(version)) {
                return i;
            }
        }
        throw new IllegalArgumentException(version.toString());
    }

    private <I> void emit(BehaviourStore.Builder builder, List<Map<BehaviourKey<?>, Map<I, Map<Integer, Object>>>> disputes,
                          List<Map<BehaviourKey<?>, Map<I, String>>> notes) {
        for (int a = 0; a < disputes.size(); a++) {
            int capture = a;
            disputes.get(a).forEach((key, byId) -> {
                Map<Integer, Map<I, Object>> byAlternative = new TreeMap<>();
                byId.forEach((id, alts) -> alts.forEach((alt, value) ->
                    byAlternative.computeIfAbsent(alt, k -> new LinkedHashMap<>()).put(id, value)));
                byAlternative.forEach((alt, values) -> builder.dispute(raw(key), capture, alt, values));
            });
            notes.get(a).forEach((key, byId) -> {
                Map<String, List<I>> byNote = new TreeMap<>();
                byId.forEach((id, note) -> byNote.computeIfAbsent(note, k -> new ArrayList<>()).add(id));
                byNote.forEach((note, ids) -> builder.postProcessed(key, capture, ids, note));
            });
        }
    }

    /**
     * @return what the file says about how it was generated: the command, the ranking, and every capture with its
     * repository, licence and commit. No timestamps, so the same inputs give the same file.
     */
    private NbtMap metadata() {
        List<NbtMap> sources = new ArrayList<>();
        for (Capture capture : captures) {
            sources.add(NbtMap.builder()
                .putString("source", capture.source().name())
                .putString("kind", capture.source().thirdParty() ? "third-party dump of BDS" : "Paletter capture of BDS")
                .putString("what", describe(capture.source()))
                .putString("reference", capture.reference())
                .putString("version", capture.version().name())
                .putString("date", capture.date())
                .putString("identified_by", capture.match())
                .build());
        }
        return NbtMap.builder()
            .putString("generated_by", "behaviour import (tools, org.geysermc.paletter.tools.behaviour.BehaviourImport)")
            .putString("do_not_edit", "Generated file: regenerate it with the command, never edit it")
            .putString("command", COMMAND)
            .putString("reproducible", "The same sources at the same commits give the same bytes: no timestamps, ordered output")
            .putString("ranking", "Direct BDS evidence (native capture, Script API, wire) > CloudburstMC/Data > Allay")
            .putList("bundled_versions", org.cloudburstmc.nbt.NbtType.STRING, bundled.stream().map(b -> b.version().name()).toList())
            .putList("sources", org.cloudburstmc.nbt.NbtType.COMPOUND, sources)
            .putInt("custom_defaults", customDefaults.size())
            .build();
    }

    static String describe(Provenance.Source source) {
        return switch (source) {
            case CLOUDBURST_DATA -> "https://github.com/CloudburstMC/Data (Apache-2.0): native BDS dump; post-processes some values (shapeSource)";
            case ALLAY_DATA -> "https://github.com/AllayMC/Allay data/ (MIT): raw Endstone DevTools dump of BDS";
            case SCRIPT_API -> "Paletter data/bds: BDS Script API dumps";
            default -> source.name();
        };
    }

    /**
     * @return the ids of the palette the capture covers, without those a stronger capture of the same version covers
     * with every key of this one (they would never decide anything), in palette order
     */
    private <I> Set<I> covered(Capture capture, Set<I> palette, java.util.function.Function<Capture, Values<I>> values) {
        Set<I> ids = new java.util.LinkedHashSet<>(palette); // palette order
        ids.retainAll(values.apply(capture).ids);
        for (Capture stronger : captures) {
            if (stronger.version().equals(capture.version()) && stronger.source().ordinal() < capture.source().ordinal()
                && values.apply(stronger).byKey.keySet().containsAll(values.apply(capture).byKey.keySet())) {
                ids.removeAll(values.apply(stronger).ids);
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static BehaviourKey<Object> raw(BehaviourKey<?> key) {
        return (BehaviourKey<Object>) key;
    }

    private static <I> Map<I, Object> restrict(Map<I, Object> values, Set<I> ids) {
        Map<I, Object> result = new LinkedHashMap<>();
        values.forEach((id, value) -> {
            if (ids.contains(id)) {
                result.put(id, value);
            }
        });
        return result;
    }
}
