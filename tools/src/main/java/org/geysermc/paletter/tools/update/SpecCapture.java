package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.CapturePlatform;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.data.Importer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.internal.data.PaletteDataCodec;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.JsonPath;
import org.geysermc.paletter.tools.model.addon.AddonWriter;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.infer.Feedback;
import org.geysermc.paletter.tools.model.infer.Wire;
import org.geysermc.paletter.tools.model.rule.Leaf;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.tools.data.Nbt;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code spec-capture}: generates the spec add-on from {@code data/model}, installs it on a fresh world of a BDS of
 * the channel (experiments on), and records what BDS does with every variant: the content log of the load (what it
 * rejects) and the custom item registry and block properties it sends. Writes
 * {@code data/bds/<version>/spec/} (the input of {@code spec infer}). Reuses the {@code update} tooling
 * (download cache, server control, capture client).
 *
 * <p>Two further modes run the same capture on other add-ons and write beside the spec: {@code --probe-keys} ({@link KeyDiscovery}, {@code spec-keys/}, writes {@code data/model/bds-keys.json}, not read by {@code infer}) and
 * {@code --format-sweep} ({@link FormatSweep}, {@code spec-format/}). {@code --with-format-sweep} runs it on the same
 * server after the spec capture; its capture is an input of {@code infer}. {@code --vanilla-pack <dir>} adds a pack of vanilla block and
 * item files as a source of candidate keys.
 */
public final class SpecCapture {
    private static final String WORLD = "paletter_spec";
    /** The custom creative inventory BDS sent in a capture ({@code id}, {@code block_runtime_id}: the hash of the default state). */
    public static final String CREATIVE_ITEMS = "custom_creative_items.json.gz";
    /** The ERROR and WARN lines of the content log in the order BDS wrote them, beside the sorted {@code content-log.txt}. */
    public static final String RAW_CONTENT_LOG = "content-log.raw.txt";
    /** At most this many captures per build: the first, and the ones of the variants it shrunk. */
    private static final int SHRINK_ROUNDS = 4;

    private final Path root;
    private final Map<String, String> options;
    private Capture.Summary summary;

    public SpecCapture(Path root, Map<String, String> options) {
        this.root = root;
        this.options = options;
    }

    /** What the client recorded in the last capture of {@link #run} (block hashes, experiments), null before it. */
    public Capture.Summary summary() {
        return summary;
    }

    public int run() throws Exception {
        boolean preview = options.getOrDefault("channel", "release").equals("preview");
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(preview, options.get("version"));
        int port = Integer.parseInt(options.getOrDefault("port", "19270"));
        int timeout = Integer.parseInt(options.getOrDefault("timeout", "600"));
        Path work = distribution.runDirectory(cache, "spec-");
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        boolean probeKeys = options.containsKey("probe-keys");
        boolean formatSweep = options.containsKey("format-sweep");
        Path out = options.containsKey("out") ? Path.of(options.get("out"))
            : root.resolve(Layout.BDS).resolve(distribution.version()).resolve(probeKeys ? "spec-keys" : formatSweep ? "spec-format" : "spec");

        // update passes reuse-server: the server its captures ran on is still extracted, and this run removes it
        Path server = distribution.extract(cache, !options.containsKey("keep-server") && !options.containsKey("reuse-server"));
        try {
            Path modelDir = root.resolve("data/model");
            Fixtures fixtures = Fixtures.read(modelDir).forBuild(distribution.version())
                // The add-on is the one place that takes BDS's schema: full enums and limits to vary
                .withNewestSchema(distribution.version());
            // The add-on asks for no newer format than the build is: older builds refuse packs of a newer engine version
            String formatVersion = AddonGenerator.formatVersionFor(fixtures.formatVersion, distribution.version());
            Model docs = Model.read(modelDir.resolve("components.json"));
            AddonGenerator generator;
            KeyDiscovery.Evidence evidence = null;
            if (probeKeys || formatSweep) {
                generator = AddonGenerator.empty(docs, fixtures, formatVersion);
                List<Path> packs = new ArrayList<>();
                try (Stream<Path> behaviorPacks = Files.list(server.resolve("behavior_packs"))) {
                    // not the add-ons an earlier capture on this server installed: their files are ours, not BDS's
                    behaviorPacks.filter(pack -> !pack.getFileName().toString().startsWith("paletter_spec")).forEach(packs::add);
                }
                if (options.containsKey("vanilla-pack")) {
                    packs.add(Path.of(options.get("vanilla-pack")));
                }
                evidence = KeyDiscovery.gather(generator.model(), BdsServer.executable(server), cache, packs);
                if (probeKeys) {
                    KeyDiscovery.plan(generator, evidence, KeyDiscovery.answered(fixtures, distribution.version()));
                } else {
                    FormatSweep.plan(generator, fixtures, evidence, formatVersion);
                }
            } else {
                generator = new AddonGenerator(docs, fixtures, formatVersion);
            }
            Path addon = work.resolve("addon");
            // A variant the build rejects for a member it does not vary says nothing (a rejected base: no variant of its
            // component does): shrink it and capture again
            Map<String, AddonGenerator.Shrunk> shrunk = new LinkedHashMap<>();
            Run run;
            for (int round = 1; ; round++) {
                generator.write(addon);
                try {
                    run = captureRun(server, addon, work, port, timeout, distribution.version(), generator.scriptMarker());
                } catch (IOException e) {
                    if (!String.valueOf(e.getMessage()).startsWith("BDS did not start")) {
                        throw e;
                    }
                    // BDS now and then hangs while it starts: once more, on the same add-on
                    System.out.println(e.getMessage().lines().findFirst().orElse("") + "; starting it again");
                    run = captureRun(server, addon, work, port, timeout, distribution.version(), generator.scriptMarker());
                }
                if (evidence != null || round == SHRINK_ROUNDS) {
                    break;
                }
                Map<String, AddonGenerator.Shrunk> more = shrink(generator.variants(), leaves(work.resolve("custom")),
                    logByVariant(run.contentLog()), shrunk);
                if (more.isEmpty()) {
                    break;
                }
                more.values().forEach(x -> System.out.println("BDS " + distribution.version() + " rejected " + x.componentId() + " "
                    + x.role() + " " + x.label() + "; capturing again without " + String.join(", ", x.dropped())));
                shrunk.putAll(more);
                generator = new AddonGenerator(docs, fixtures, formatVersion, shrunk);
            }
            List<String> contentLog = run.contentLog();
            summary = run.summary();
            int protocol = run.protocol();
            Files.createDirectories(out);
            for (String file : List.of("custom_item_registry.nbt", "custom_block_properties.nbt")) {
                Files.copy(work.resolve("custom").resolve(file), out.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
            // The creative inventory of the variants: per block the hash BDS sent for its default state (what verifies the
            // trait states, TraitStateTable in infer)
            try (InputStream in = Files.newInputStream(work.resolve("custom").resolve("custom_creative_items.json"));
                 java.io.OutputStream gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(out.resolve(CREATIVE_ITEMS)))) {
                in.transferTo(gz);
            }
            Files.writeString(out.resolve("content-log.txt"), String.join("\n", contentLog) + "\n", StandardCharsets.UTF_8);
            // In the order BDS wrote it: what a line follows says what it is about, which the sorted log cannot
            Files.writeString(out.resolve(RAW_CONTENT_LOG), String.join("\n", run.rawLog()) + "\n", StandardCharsets.UTF_8);
            Files.writeString(out.resolve("variants.json"), Model.GSON.toJson(generator.manifest()) + "\n", StandardCharsets.UTF_8);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("bdsVersion", distribution.version());
            metadata.addProperty(CapturePlatform.KEY, CapturePlatform.host());
            metadata.addProperty("protocol", protocol);
            metadata.addProperty("preview", preview);
            metadata.addProperty("formatVersion", formatVersion);
            // Values the generator cut at AddonGenerator.MAX_VALUES, which no capture of this add-on varies
            JsonArray cut = new JsonArray();
            generator.cutValues().forEach(cut::add);
            if (!cut.isEmpty()) {
                metadata.add("valuesCut", cut);
            }
            metadata.addProperty("nativeCodec", summary.nativeCodec());
            metadata.addProperty("codecProtocol", summary.codecProtocol());
            // creative block hashes that the client's palette (traits from the generated TraitStateTable) did and did not match
            metadata.addProperty("resolvedHashes", summary.resolvedHashes());
            metadata.addProperty("unresolvedHashes", summary.unresolvedHashes());
            JsonArray echoed = new JsonArray();
            summary.experiments().forEach(echoed::add);
            metadata.add("experiments", echoed);
            Files.writeString(out.resolve("capture.json"), new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
            if (evidence != null) {
                Map<String, Map<String, Leaf>> leaves = leaves(out);
                Map<String, List<String>> log = logByVariant(contentLog);
                if (probeKeys) {
                    JsonObject keys = KeyDiscovery.results(generator, evidence, distribution.version(), leaves, log, fixtures.keys);
                    Files.writeString(modelDir.resolve(Fixtures.KEYS_FILE), Model.GSON.toJson(keys) + "\n", StandardCharsets.UTF_8);
                } else {
                    Files.writeString(out.resolve("format-sweep.json"), Model.GSON.toJson(
                        FormatSweep.report(generator, distribution.version(), formatVersion, leaves, log)) + "\n", StandardCharsets.UTF_8);
                }
            }
            System.out.println("Spec capture of BDS " + distribution.version() + " (protocol " + protocol + "): " + generator.variants().size()
                + " variants, " + contentLog.size() + " content log lines -> " + out);
            if (options.containsKey("with-format-sweep")) {
                // The same server once more, with the add-on of the format sweep: its own capture next to this one
                Map<String, String> sweep = new LinkedHashMap<>(options);
                sweep.remove("with-format-sweep");
                sweep.put("format-sweep", "true");
                sweep.put("reuse-server", "true");
                sweep.put("out", out.resolveSibling("spec-format").toString());
                return new SpecCapture(root, sweep).run();
            }
            return 0;
        } finally {
            if (!options.containsKey("keep-server")) {
                try {
                    BdsDistribution.deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }
    }

    /** What one BDS run of an add-on gave: its content log (sorted, and the ERROR and WARN lines as BDS wrote them), the capture and the protocol it spoke. */
    private record Run(List<String> contentLog, List<String> rawLog, Capture.Summary summary, int protocol) {
    }

    /** Installs the add-on on a fresh world, starts BDS with the experiments on and captures what it sends a client. */
    private Run captureRun(Path server, Path addon, Path work, int port, int timeout, String version, String marker) throws Exception {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("gamemode", "creative");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("transport", "raknet");
        properties.put("enable-lan-visibility", "true");
        properties.put("online-mode", "false");
        properties.put("allow-list", "false");
        properties.put("view-distance", "4");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");
        properties.put("level-name", WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        freshWorld(server, addon);
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-create.log"));
            bds.awaitStarted(timeout);
        }
        List<String> experiments = options.containsKey("experiments")
            ? Arrays.asList(options.get("experiments").split(",")) : Update.DEFAULT_EXPERIMENTS;
        Capture.enableExperiments(server.resolve("worlds").resolve(WORLD).resolve("level.dat"), experiments);

        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-capture.log"));
            bds.awaitStarted(timeout);
            // Without its script the script custom components would look like ones without callbacks: wrong evidence that looks valid
            if (marker != null && !bds.sawLine(line -> line.matches(".*" + marker + "(\\D.*)?"))) {
                throw new IOException("The add-on's script did not register all its components (no line with \"" + marker
                    + "\"); the scripting log:\n  " + String.join("\n  ", bds.lines(line -> line.contains("Scripting"))));
            }
            List<String> rawLog = bds.lines(line -> line.matches(".*(ERROR|WARN)\\] \\[.*") && !line.contains("TRANSPORT")
                && !line.contains(AddonGenerator.SCRIPT_MARKER))
                .stream().map(SpecCapture::contentLine).toList();
            Capture.Reachable reachable = Capture.reachable(bds, address, 20);
            boolean raknet = reachable == null || !reachable.netherNet();
            int protocol = reachable != null && reachable.protocol() != 0 ? reachable.protocol()
                : Integer.parseInt(options.getOrDefault("protocol", "0"));
            if (protocol == 0) {
                throw new IOException("No protocol from the server status; pass --protocol");
            }
            Capture.Summary summary = Capture.capture(address, protocol, reference(protocol), version, work.resolve("capture"),
                work.resolve("custom"), 120, !raknet);
            return new Run(attribute(rawLog), rawLog, summary, protocol);
        }
    }

    /**
     * The variants this capture's build rejected because of a member they do not vary, shrunk: the members the content log
     * names are removed (by path, or quoted first in a message about the whole component: an unknown key, a wrong type, a
     * value out of range, a key that needs another one). A base whose log names none of its members becomes the omit variant (the
     * base without one field) that the build accepted and that keeps the most of it. Field variants are shrunk only where
     * the base was accepted, and never lose the field they vary. A variant whose log names nothing it can lose stays as
     * it is.
     *
     * @param before the shrinks of the earlier rounds, which the dropped paths add to
     * @return the new shrinks, by {@link AddonGenerator.Shrunk#key}
     */
    static Map<String, AddonGenerator.Shrunk> shrink(List<AddonGenerator.Variant> variants, Map<String, Map<String, Leaf>> leaves,
                                                     Map<String, List<String>> log, Map<String, AddonGenerator.Shrunk> before) {
        java.util.Set<String> rejectedBases = new java.util.HashSet<>();
        variants.stream().filter(v -> v.role().equals("base") && !accepted(v, leaves, log)).forEach(v -> rejectedBases.add(v.componentId()));
        Map<String, AddonGenerator.Shrunk> shrunk = new LinkedHashMap<>();
        for (AddonGenerator.Variant v : variants) {
            boolean field = v.role().equals("field");
            if (!(field || v.role().equals("base") || v.role().equals("control")) || v.component() == null || v.component().isJsonNull()
                || accepted(v, leaves, log) || (field && rejectedBases.contains(v.componentId()))) {
                continue;
            }
            JsonElement json = v.component().deepCopy();
            List<String> dropped = new ArrayList<>();
            for (String line : log.getOrDefault(v.identifier(), List.of())) {
                Feedback.Located at = line.startsWith("ERROR") ? Feedback.locate(v.kind(), line) : null;
                if (at == null || !at.component().equals(v.componentId())) {
                    continue;
                }
                List<String> named = new ArrayList<>();
                if (!at.path().isEmpty()) {
                    named.add(at.path());
                } else {
                    // a message about the whole component names the member it is about first
                    java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("^[\"']([a-z_]+)[\"']").matcher(at.message());
                    if (quoted.find()) {
                        named.add(quoted.group(1));
                    }
                }
                for (String path : named) {
                    if (!dropped.contains(path) && !(field && related(path, v.path())) && JsonPath.remove(json, path)) {
                        dropped.add(path);
                    }
                }
            }
            if (dropped.isEmpty() && v.role().equals("base")) {
                AddonGenerator.Variant omit = variants.stream().filter(o -> o.componentId().equals(v.componentId()) && o.role().equals("omit")
                    && accepted(o, leaves, log)).max(java.util.Comparator.comparingInt(o -> o.component().toString().length())).orElse(null);
                if (omit == null) {
                    continue;
                }
                json = omit.component().deepCopy();
                dropped.add(omit.path());
            }
            if (dropped.isEmpty()) {
                continue;
            }
            String label = AddonGenerator.Shrunk.generatedLabel(v.label());
            AddonGenerator.Shrunk earlier = before.get(AddonGenerator.Shrunk.key(v.role(), v.componentId(), v.path(), label));
            if (earlier != null) {
                dropped.addAll(0, earlier.dropped());
            }
            AddonGenerator.Shrunk s = new AddonGenerator.Shrunk(v.role(), v.componentId(), v.path(), label, json, List.copyOf(dropped));
            shrunk.put(s.key(), s);
        }
        return shrunk;
    }

    /** Whether one path is the other or contains it ({@code a} and {@code a.b}, {@code a[]}). */
    private static boolean related(String a, String b) {
        String x = a.length() <= b.length() ? a : b;
        String y = a.length() <= b.length() ? b : a;
        return y.equals(x) || y.startsWith(x + ".") || y.startsWith(x + "[]") || y.startsWith(x + "{}");
    }

    /** A variant is accepted when BDS sent it and logged no error for it (items that fail to parse are still sent). */
    static boolean accepted(AddonGenerator.Variant v, Map<String, Map<String, Leaf>> leaves, Map<String, List<String>> log) {
        return leaves.containsKey(v.kind() + " " + v.identifier())
            && log.getOrDefault(v.identifier(), List.of()).stream().noneMatch(line -> line.startsWith("ERROR"));
    }

    /**
     * A console line of BDS as a content log line: {@code ERROR ...} or {@code WARN ...} without the timestamp. BDS
     * sometimes writes {@code NO LOG FILE! - } before the timestamp (seen once on 1.26.45.1, on every line); the inference
     * reads the level from the start of the line, so a line in any other shape fails the capture instead of being kept.
     */
    static String contentLine(String console) {
        String line = console.replaceFirst("^(?:NO LOG FILE! - )?\\[[^]]*(ERROR|WARN)\\] ", "$1 ");
        if (!line.startsWith("ERROR ") && !line.startsWith("WARN ")) {
            throw new IllegalStateException("A content log line of BDS in an unknown shape: " + console);
        }
        return line;
    }

    /**
     * BDS logs item errors as "Error Parsing Item 'x':" followed by the field errors; the field errors get the item
     * name prefixed so the (sorted) log still says which variant they belong to.
     */
    static List<String> attribute(List<String> lines) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        String item = null;
        java.util.regex.Pattern parsing = java.util.regex.Pattern.compile("Error Parsing Item '([^']+)'");
        for (String line : lines) {
            java.util.regex.Matcher m = parsing.matcher(line);
            if (m.find()) {
                item = m.group(1);
                out.add(line);
            } else if (line.contains("[Item]") && line.contains("Failed to parse field") && item != null) {
                out.add(line.replace("Failed to parse field", "'" + item + "': Failed to parse field"));
            } else {
                if (!line.contains("[Item]")) {
                    item = null;
                }
                out.add(line);
            }
        }
        return List.copyOf(out);
    }

    /** The content log lines per variant identifier (the file name or the quoted item name says which variant a line is about). */
    static Map<String, List<String>> logByVariant(List<String> lines) {
        Map<String, List<String>> log = new LinkedHashMap<>();
        java.util.regex.Pattern file = java.util.regex.Pattern.compile("(?:blocks|items)/([a-z0-9_]+)\\.json");
        java.util.regex.Pattern name = java.util.regex.Pattern.compile("(paletter:[a-z0-9_]+)");
        for (String line : lines) {
            java.util.regex.Matcher m = file.matcher(line);
            String id = m.find() ? "paletter:" + m.group(1) : (m = name.matcher(line)).find() ? m.group(1) : null;
            if (id != null) {
                log.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(line);
            }
        }
        return log;
    }

    /** The wire leaves of every item and block BDS sent, by {@code item <id>} / {@code block <id>}, from a capture's NBT files. */
    static Map<String, Map<String, Leaf>> leaves(Path capture) throws IOException {
        Map<String, Map<String, Leaf>> leaves = new LinkedHashMap<>();
        for (NbtMap item : Nbt.read(capture.resolve("custom_item_registry.nbt")).getList("items", NbtType.COMPOUND)) {
            if (item.getBoolean("componentBased") || !item.getCompound("data").isEmpty()) {
                leaves.put("item " + item.getString("name"), Wire.flatten(item.getCompound("data").getCompound("components")));
            }
        }
        for (NbtMap block : Nbt.read(capture.resolve("custom_block_properties.nbt")).getList("blocks", NbtType.COMPOUND)) {
            Map<String, Leaf> flat = Wire.flatten(block.getCompound("properties"));
            flat.remove("vanilla_block_data.block_id");
            leaves.put("block " + block.getString("name"), flat);
        }
        return leaves;
    }

    private VanillaData reference(int protocol) throws IOException {
        Path resources = root.resolve("paletter/src/main/resources/paletter");
        List<Importer.IndexEntry> index = Importer.readIndex(resources);
        Importer.IndexEntry entry = index.stream().filter(e -> e.protocol() == protocol).findFirst()
            .orElse(index.stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow());
        return org.geysermc.paletter.tools.data.BundledFiles.read(resources.resolve(entry.name() + ".nbt"));
    }

    /**
     * Removes the packs in {@code packs} whose manifest has the spec add-on's UUID. A safeguard for a server that
     * {@code update} reuses: when the creative order probe pack ({@code FixedAddons.writeCreative}) had that UUID, BDS
     * loaded it for the spec world in place of the spec add-on.
     */
    static void removePacksWithUuid(Path packs, String uuid) throws IOException {
        if (!Files.isDirectory(packs)) {
            return;
        }
        try (Stream<Path> directories = Files.list(packs)) {
            for (Path pack : directories.filter(Files::isDirectory).toList()) {
                Path manifest = pack.resolve("manifest.json");
                if (!Files.exists(manifest)) {
                    continue;
                }
                com.google.gson.JsonElement json;
                try {
                    json = com.google.gson.JsonParser.parseString(Files.readString(manifest));
                } catch (RuntimeException e) {
                    continue;
                }
                com.google.gson.JsonObject header = json.isJsonObject() ? json.getAsJsonObject().getAsJsonObject("header") : null;
                if (header != null && header.has("uuid") && uuid.equals(header.get("uuid").getAsString())) {
                    BdsDistribution.deleteRecursively(pack);
                }
            }
        }
    }

    private static void freshWorld(Path server, Path addon) throws IOException {
        Path directory = server.resolve("worlds").resolve(WORLD);
        BdsDistribution.deleteRecursively(directory);
        Files.createDirectories(directory);
        Map<String, String> uuids = Map.of("behavior", AddonGenerator.BP_UUID, "resource", AddonGenerator.RP_UUID);
        for (String kind : List.of("behavior", "resource")) {
            List<String> ids = new ArrayList<>(List.of(uuids.get(kind)));
            install(addon.resolve(kind + "_pack"), server.resolve(kind + "_packs"), "paletter_spec", uuids.get(kind));
            if (kind.equals("behavior")) {
                for (AddonWriter.EnginePack pack : AddonWriter.enginePacks()) {
                    if (Files.isDirectory(addon.resolve(pack.directory()))) {
                        install(addon.resolve(pack.directory()), server.resolve("behavior_packs"), "paletter_spec_" + pack.directory(), pack.uuid());
                        ids.add(pack.uuid());
                    }
                }
            }
            Files.writeString(directory.resolve("world_" + kind + "_packs.json"), ids.stream()
                .map(id -> "{\"pack_id\": \"" + id + "\", \"version\": [1, 0, 0]}").collect(java.util.stream.Collectors.joining(", ", "[", "]\n")));
        }
    }

    /** Copies a pack into the server's pack folder, replacing an installed pack with the same name or uuid. */
    private static void install(Path source, Path packs, String name, String uuid) throws IOException {
        Path target = packs.resolve(name);
        BdsDistribution.deleteRecursively(target);
        removePacksWithUuid(packs, uuid);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
