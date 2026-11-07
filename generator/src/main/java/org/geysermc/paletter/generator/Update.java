package org.geysermc.paletter.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.capture.Capture;
import org.geysermc.paletter.codegen.Codegen;
import org.geysermc.paletter.codegen.ShapeInference;
import org.geysermc.paletter.codegen.constants.ConstantsGenerator;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code update}: pulls the newest BDS of a channel, runs the Script API dump and the network captures, compares the
 * result with the committed data and either updates the repository or (with {@code --verify-only}) only reports.
 * See README.md, "Updating data".
 */
final class Update {
    static final List<String> DEFAULT_EXPERIMENTS = List.of("upcoming_creator_features", "gametest",
        "experimental_creator_cameras", "y_2026_drop_4", "experimental_molang_features", "experimental_vanilla_shapes",
        "data_driven_biomes");
    private static final String VANILLA_WORLD = "paletter_vanilla";
    private static final String ADDON_WORLD = "paletter_addon";
    private static final String CAPTURE_PACK = "paletter_capture";

    private final Path root;
    private final Path resources;
    private final Path fallbacks;
    private final Path dumps;
    private final Path codegenCaptures;
    private final Map<String, String> options;
    private final boolean preview;
    private final boolean verifyOnly;
    private final boolean importPreview;
    private final boolean keepServer;
    private final int port;
    private final int timeout;
    private Report report;

    Update(Path root, Map<String, String> options) {
        this.root = root;
        this.resources = root.resolve("paletter/src/main/resources/paletter");
        this.fallbacks = root.resolve("generator/fallbacks");
        this.dumps = root.resolve("generator/bds-dumps");
        this.codegenCaptures = root.resolve("codegen/captures");
        this.options = options;
        String channel = options.getOrDefault("channel", "release");
        if (!channel.equals("release") && !channel.equals("preview")) {
            throw new IllegalArgumentException("--channel must be release or preview");
        }
        this.preview = channel.equals("preview");
        this.verifyOnly = options.containsKey("verify-only");
        this.importPreview = options.containsKey("import-preview");
        this.keepServer = options.containsKey("keep-server");
        this.port = Integer.parseInt(options.getOrDefault("port", "19260"));
        this.timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
    }

    /** @return the process exit code */
    int run() throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(preview, options.get("version"));
        report = new Report("Paletter data " + (verifyOnly ? "verification" : "update") + ": BDS " + distribution.version()
            + (preview ? " (preview)" : ""));
        report.header.put("Date", LocalDate.now().toString());
        report.header.put("Mode", verifyOnly ? "verify only (nothing written)" : "update");
        report.header.put("BDS", distribution.version() + " " + distribution.platform() + " (" + distribution.url() + ")");
        Path work = cache.resolve("runs").resolve(distribution.platform() + "-" + distribution.version());
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        report.header.put("Work directory", "`" + work + "` (logs, captures)");

        try {
            pipeline(cache, distribution, work);
        } catch (Exception e) {
            report.fail("pipeline", e);
        }

        Path reportFile = options.containsKey("report") ? Path.of(options.get("report")) : work.resolve("report.md");
        report.write(reportFile);
        if (!reportFile.startsWith(work)) {
            report.write(work.resolve("report.md"));
        }
        System.out.println("Report: " + reportFile.toAbsolutePath());
        boolean ok = !report.failed() && (!verifyOnly || !report.differs()) && (verifyOnly || report.todos.isEmpty());
        System.out.println(report.failed() ? "FAILED" : report.differs() ? (verifyOnly ? "DIFFERENCES (verify failed)" : "UPDATED") : "IDENTICAL");
        return ok ? 0 : 1;
    }

    private void pipeline(Path cache, BdsDistribution distribution, Path work) throws Exception {
        Path server = distribution.extract(cache, !keepServer);
        report.header.put("Server", "`" + server + "`" + (keepServer ? " (kept)" : " (removed after the run)"));
        BdsDump dump;
        Runs runs = new Runs();
        runs.bdsVersion = distribution.version();
        try {
            // 1. Script API dump
            Path dumpLog = work.resolve("bds-dump.log");
            new BdsRunner(root.resolve("bds-addon")).run(server, dumpLog, timeout, port, false);
            String[] json = BdsDump.json(dumpLog);
            dump = BdsDump.parse(json[1], json[0]);
            Files.writeString(work.resolve("bds-dump.json"), json[1]);
            runs.dumpLog = dumpLog;
            runs.dumpJson = BdsDump.canonicalJson(json[1]);
            report.check(dump.errors().isEmpty() ? Report.Status.PASS : Report.Status.FAIL, "Script API dump",
                "BDS reports " + json[0] + "; " + dump.items().size() + " items, " + dump.blockDefaults().size() + " blocks, "
                    + dump.itemTags().size() + " item tags" + (dump.errors().isEmpty() ? "" : "; addon errors: " + dump.errors()));

            // 2. Network captures (vanilla and add-on, hashed and sequential block ids)
            captures(server, work, dump, runs);
        } finally {
            if (!keepServer) {
                try {
                    BdsDistribution.deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }

        // 3. Compare and update
        scriptApi(distribution, dump, runs);
        Staging staging = new Staging(work.resolve("staging"));
        if (runs.vanilla != null) {
            networkData(distribution, dump, runs, staging);
        } else {
            missingNetworkData(distribution, dump);
        }
        contentLog(runs);
        if (runs.addon != null) {
            shapes(distribution, runs);
        }
        constants(staging);
        staging.apply();
    }

    /**
     * Regenerates the vanilla constants from the staged data (what the repository will have after this run), and lists
     * the constants added and deprecated. Staged with the data, so a data import never leaves stale constants.
     */
    private void constants(Staging staging) throws IOException {
        ConstantsGenerator.Result result;
        try {
            result = ConstantsGenerator.generate(staging.resources, ConstantsGenerator.readJson(root.resolve(ConstantsGenerator.NAMES)),
                ConstantsGenerator.readJson(root.resolve(ConstantsGenerator.HISTORY)), ConstantsGenerator.paletterVersion(root));
        } catch (IllegalStateException e) {
            // A name collision or a state that changed its kind needs a codegen/names.json entry
            report.todos.add("Vanilla constants: " + e.getMessage());
            report.check(Report.Status.FAIL, "Vanilla constants", e.getMessage());
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("From " + result.release() + (result.preview() == null ? "" : " and preview " + result.preview()) + ": " + result.counts());
        result.added().forEach(added -> lines.add("added `" + added + "`"));
        result.deprecated().forEach(deprecated -> lines.add("deprecated `" + deprecated + "`"));
        result.tableBlocks().forEach(block -> lines.add("lookup table (states are not a full product in a stride order): " + block));
        report.section("Vanilla constants", lines);
        report.check(result.added().isEmpty() && result.deprecated().isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Vanilla constants",
            result.added().size() + " added, " + result.deprecated().size() + " deprecated, " + result.tableBlocks().size() + " blocks with a lookup table");
        for (Path stale : org.geysermc.paletter.codegen.constants.Main.stale(root, result.files())) {
            report.repoChanges.add((verifyOnly ? "would " : "") + "remove `" + root.relativize(stale).toString().replace('\\', '/') + "` (constants)");
            if (!verifyOnly) {
                Files.delete(stale);
            }
        }
        for (Map.Entry<String, String> file : result.files().entrySet()) {
            stage(root.resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8), "vanilla constants");
        }
    }

    /** What the server runs gave; fields stay null when a step was impossible. */
    private static final class Runs {
        Path dumpLog;
        String dumpJson;
        Integer protocol;
        String bdsVersion;
        boolean netherNet;
        String existing;
        VanillaData reference;
        String referenceName;
        Path imported;
        Path vanilla;
        Path addon;
        Path customCapture;
        List<String> addonContentLog;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Server runs

    private void captures(Path server, Path work, BdsDump dump, Runs runs) throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("level-type", "FLAT");
        properties.put("level-seed", "0");
        properties.put("gamemode", "creative");
        properties.put("server-port", String.valueOf(port));
        properties.put("server-portv6", String.valueOf(port + 1));
        properties.put("transport", "raknet");
        properties.put("enable-lan-visibility", "false");
        properties.put("online-mode", "false");
        properties.put("allow-list", "false");
        properties.put("view-distance", "4");
        properties.put("content-log-console-output-enabled", "true");
        properties.put("content-log-level", "warning");

        // Vanilla world
        freshWorld(server, VANILLA_WORLD, null);
        properties.put("level-name", VANILLA_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        properties.put("enable-lan-visibility", "true"); // otherwise the RakNet pong does not carry the protocol
        BdsServer.configure(server, properties);
        properties.put("enable-lan-visibility", "false");
        List<String> network = null;
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-vanilla.log"));
            bds.awaitStarted(timeout);
            String[] pong = Capture.ping(address, 2000);
            boolean raknet = pong != null && bds.sawLine(line -> line.contains("IPv4 supported, port: " + port));
            String[] netherNet = raknet ? null : Capture.netherNetPing(address, 10);
            if (!raknet && netherNet == null) {
                network = bds.lines(line -> line.contains("Accepting clients") || line.contains("supported, port")
                    || line.contains("NetherNet"));
            } else {
                runs.netherNet = !raknet;
                if (raknet && pong.length > 3) {
                    runs.protocol = Integer.parseInt(pong[2]);
                    report.header.put("Protocol", runs.protocol + " (RakNet pong: " + pong[3] + ")");
                } else if (!raknet && !netherNet[0].equals("0")) {
                    runs.protocol = Integer.parseInt(netherNet[0]);
                    report.header.put("Protocol", runs.protocol + " (NetherNet LAN discovery: " + netherNet[1] + ")");
                } else {
                    runs.protocol = Integer.parseInt(options.getOrDefault("protocol", "0"));
                    if (runs.protocol == 0) {
                        throw new IOException("The server's status carries no protocol version; pass --protocol");
                    }
                    report.header.put("Protocol", runs.protocol + " (from --protocol)");
                }
                report.header.put("Transport", raknet ? "RakNet" : "NetherNet (HTTP signaling on server-port, WebRTC; "
                    + "self signed identity bound to the login chain)");
                runs.existing = bundledName(runs.protocol);
                runs.reference = reference(runs.existing, dump, runs);
                Capture.Summary summary = capture(address, runs, work.resolve("vanilla"), null, "vanilla capture");
                runs.vanilla = work.resolve("vanilla");
                hashCheck("vanilla", summary);
            }
        }
        if (network != null) {
            report.check(Report.Status.FAIL, "Network capture", "BDS answers neither a RakNet ping nor NetherNet signaling on port " + port);
            report.section("Network capture impossible", List.of(
                "Network lines of the server:", "", "```", String.join("\n", network), "```"));
            runs.addonContentLog = addonWorldContentLog(server, work, properties);
            return;
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-vanilla-sequential.log"));
            bds.awaitStarted(timeout);
            capture(address, runs, work.resolve("vanilla-sequential"), null, "vanilla capture (sequential ids)");
        }

        // Add-on world: created once, experiments switched on in level.dat, then captured
        runs.addonContentLog = addonWorldContentLog(server, work, properties);
        properties.put("level-name", ADDON_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon.log"));
            bds.awaitStarted(timeout);
            Capture.Summary summary = capture(address, runs, work.resolve("addon"), work.resolve("addon/custom"), "add-on capture");
            runs.addon = work.resolve("addon");
            runs.customCapture = work.resolve("addon/custom");
            hashCheck("add-on", summary);
            report.check(Report.Status.INFO, "Experiments echoed by BDS", String.join(", ", summary.experiments()));
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon-sequential.log"));
            bds.awaitStarted(timeout);
            capture(address, runs, work.resolve("addon-sequential"), null, "add-on capture (sequential ids)");
        }

        OrderingCheck ordering = new OrderingCheck();
        ordering.bundledOrder("reference palette " + runs.reference.version().name(), runs.reference.blockStates());
        ordering.sequential("vanilla", work.resolve("vanilla"), work.resolve("vanilla-sequential"));
        ordering.sequential("add-on", work.resolve("addon"), work.resolve("addon-sequential"));
        ordering.customBlockIds("add-on", work.resolve("addon"));
        ordering.passes.forEach(pass -> report.check(Report.Status.PASS, "Block numbering", pass));
        ordering.failures.forEach(failure -> report.check(Report.Status.FAIL, "Block numbering", failure));
        if (!ordering.withinBlockOrder.isEmpty()) {
            report.todos.add("Block palette: " + ordering.withinBlockOrder.size() + " creative states of generated blocks sit at another "
                + "position within their block than generated; fix the state order of those blocks before importing: "
                + String.join("; ", ordering.withinBlockOrder.subList(0, Math.min(10, ordering.withinBlockOrder.size()))));
        }
    }

    /**
     * Creates the add-on world (packs installed, experiments on) and returns the content log of a start with the
     * experiments enabled: the definitions BDS rejects. Leaves the server stopped.
     */
    private List<String> addonWorldContentLog(Path server, Path work, Map<String, String> properties) throws Exception {
        Path addon = root.resolve("capture/addon");
        freshWorld(server, ADDON_WORLD, addon);
        properties.put("level-name", ADDON_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon-create.log"));
            bds.awaitStarted(timeout);
        }
        List<String> experiments = options.containsKey("experiments")
            ? Arrays.asList(options.get("experiments").split(",")) : DEFAULT_EXPERIMENTS;
        Capture.enableExperiments(server.resolve("worlds").resolve(ADDON_WORLD).resolve("level.dat"), experiments);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon-content-log.log"));
            bds.awaitStarted(timeout);
            return bds.lines(line -> line.matches(".*(ERROR|WARN)\\] \\[.*") && !line.contains("TRANSPORT"))
                .stream().map(line -> line.replaceFirst("^\\[[^]]*(ERROR|WARN)\\] ", "$1 ")).sorted().distinct().toList();
        }
    }

    private Capture.Summary capture(InetSocketAddress address, Runs runs, Path out, Path customOut, String what) throws Exception {
        Capture.Summary summary = Capture.capture(address, runs.protocol, runs.reference, runs.bdsVersion, out, customOut, 90, runs.netherNet);
        report.check(summary.nativeCodec() ? Report.Status.PASS : Report.Status.DIFF, what,
            (summary.blockNetworkIdsHashed() ? "hashed" : "sequential") + " block ids; protocol " + summary.protocol()
                + (summary.nativeCodec() ? "" : ", decoded with the " + summary.codecProtocol() + " codec (no codec for "
                + summary.protocol() + " in the protocol library: update it and rerun)"));
        return summary;
    }

    private void hashCheck(String label, Capture.Summary summary) {
        if (summary.unresolvedHashes() == 0) {
            report.check(Report.Status.PASS, "Block hashes (" + label + ")", "all " + summary.resolvedHashes()
                + " creative block ids are FNV-1a 32 hashes of palette states");
        } else {
            report.check(Report.Status.FAIL, "Block hashes (" + label + ")", summary.unresolvedHashes() + " creative block ids match no "
                + "palette state (" + summary.resolvedHashes() + " do): the palette lacks states this version has");
            report.todos.add("Block palette: " + summary.unresolvedHashes() + " creative block ids of the " + label
                + " capture match no known state; the palette needs those states (see `creativeBlockIdsUnresolved` in capture.json)");
        }
    }

    private void freshWorld(Path server, String world, Path addon) throws IOException {
        Path directory = server.resolve("worlds").resolve(world);
        BdsDistribution.deleteRecursively(directory);
        Files.createDirectories(directory);
        if (addon == null) {
            return;
        }
        String[] packs = {"behavior", "resource"};
        for (String kind : packs) {
            Path source = addon.resolve(kind + "_pack");
            Path target = server.resolve(kind + "_packs").resolve(CAPTURE_PACK);
            BdsDistribution.deleteRecursively(target);
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
            JsonObject header = JsonParser.parseString(Files.readString(source.resolve("manifest.json")))
                .getAsJsonObject().getAsJsonObject("header");
            Files.writeString(directory.resolve("world_" + kind + "_packs.json"), "[{\"pack_id\": \""
                + header.get("uuid").getAsString() + "\", \"version\": " + header.get("version") + "}]\n");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparison

    /** The Script API view against the committed baseline dump of the newest bundled release. */
    private void scriptApi(BdsDistribution distribution, BdsDump dump, Runs runs) throws IOException {
        Path baselineFile = latestDump();
        Path target = dumps.resolve(distribution.version() + ".json.gz");
        if (baselineFile == null) {
            report.check(Report.Status.DIFF, "Script API vs baseline", "no baseline dump in generator/bds-dumps");
        } else {
            String baselineVersion = baselineFile.getFileName().toString().replace(".json.gz", "");
            BdsDump baseline = BdsDump.parse(readGzipText(baselineFile), baselineVersion);
            DataDiff diff = ScriptApiDiff.of(baseline, dump);
            report.check(diff.identical() ? Report.Status.PASS : Report.Status.DIFF, "Script API vs baseline " + baselineVersion,
                diff.identical() ? "identical" : diff.count() + " differences");
            report.diff("Script API: BDS " + distribution.version() + " vs baseline " + baselineVersion, diff);
            if (runs.vanilla == null) {
                fallbackTodos(diff.sections.get("new blocks"), diff.sections.get("new items"), "Script API");
            }
        }
        if (!preview || importPreview) {
            stage(target, gzip(runs.dumpJson), "Script API baseline " + distribution.version());
        }
    }

    /**
     * New blocks and items an older bundled version lacks fall back to {@code minecraft:unknown} there (Paletter's
     * default). Lists them per fallback file as INFO: candidates for a curated fallback, no decision required.
     */
    private void fallbackTodos(List<String> blocks, List<String> items, String source) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            if (entry.preview()) {
                continue;
            }
            VanillaData data = read(resources.resolve(entry.name() + ".nbt"));
            Set<String> knownBlocks = new TreeSet<>();
            data.blockStates().forEach(state -> knownBlocks.add(BlockStates.name(state)));
            Set<String> knownItems = new TreeSet<>();
            data.items().forEach(item -> knownItems.add(item.identifier()));
            VersionFallbacksView existing = new VersionFallbacksView(Importer.readFallbacks(fallbacks.resolve(entry.name() + ".json")));
            List<String> needBlocks = identifiers(blocks).stream()
                .filter(id -> !knownBlocks.contains(id) && !existing.covers(id, true)).toList();
            List<String> needItems = identifiers(items).stream()
                .filter(id -> !knownItems.contains(id) && !existing.covers(id, false)).toList();
            if (!needBlocks.isEmpty() || !needItems.isEmpty()) {
                lines.add("- `generator/fallbacks/" + entry.name() + ".json`: "
                    + (needBlocks.isEmpty() ? "" : needBlocks.size() + " blocks " + code(needBlocks))
                    + (needBlocks.isEmpty() || needItems.isEmpty() ? "" : "; ")
                    + (needItems.isEmpty() ? "" : needItems.size() + " items " + code(needItems)));
            }
        }
        if (!lines.isEmpty()) {
            report.check(Report.Status.INFO, "Fallbacks", "new content falls back to minecraft:unknown in older versions ("
                + source + "); curated fallbacks are optional, see the list");
            List<String> section = new ArrayList<>(List.of("Without a curated fallback these fall back to `minecraft:unknown` "
                + "(block and item) in the older version. Add a target to the file for a better replacement; nothing is required.", ""));
            section.addAll(lines);
            report.section("Fallbacks (" + source + "): content that falls back to minecraft:unknown", section);
        }
    }

    private record VersionFallbacksView(org.geysermc.paletter.data.VersionFallbacks fallbacks) {
        boolean covers(String identifier, boolean block) {
            return fallbacks.unmapped().contains(identifier)
                || (block ? fallbacks.blocks().containsKey(identifier) : fallbacks.items().containsKey(identifier));
        }
    }

    private static List<String> identifiers(List<String> lines) {
        return lines == null ? List.of() : lines.stream().map(line -> line.split(" ")[0]).toList();
    }

    private static String code(List<String> identifiers) {
        return String.join(", ", identifiers.stream().map(id -> "`" + id + "`").toList());
    }

    private void networkData(BdsDistribution distribution, BdsDump dump, Runs runs, Staging staging) throws Exception {
        String name = runs.existing != null ? runs.existing : options.getOrDefault("name", versionName(distribution.version()));
        report.header.put("Bundled version", runs.existing != null ? name + " (existing, same protocol)" : name + " (NEW protocol)");
        // The capture in the bundled format, imported into a scratch directory
        Path scratch = staging.directory.resolveSibling("imported");
        Files.createDirectories(scratch);
        new Importer(scratch, fallbacks).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog);
        VanillaData captured = read(scratch.resolve(name + ".nbt"));
        runs.imported = scratch.resolve(name + ".nbt");

        if (runs.existing != null) {
            VanillaData bundled = read(resources.resolve(name + ".nbt"));
            DataDiff network = DataDiff.of(bundled, captured, true, false);
            report.check(network.identical() ? Report.Status.PASS : Report.Status.DIFF, "Network data vs bundled " + name,
                network.identical() ? "identical (items, runtime ids, components, data driven blocks, creative layout)"
                    : network.count() + " differences");
            report.diff("Network data: BDS " + distribution.version() + " vs bundled " + name, network);
            if (!network.identical()) {
                report.todos.add("BDS " + distribution.version() + " has the protocol of bundled " + name + " but sends different data; "
                    + "decide whether to re-import " + name + " (`import --source " + runs.vanilla + " --name " + name
                    + " --protocol " + runs.protocol + " --bds-dump " + runs.dumpLog + "`)");
            }
            // Refresh what comes from the Script API (item tags, block defaults); keep the network data
            VanillaData refreshed = new VanillaData(bundled.version(), bundled.blockStates(), bundled.items(), bundled.dataDrivenBlocks(),
                bundled.creative(), captured.itemTags(), Importer.readFallbacks(fallbacks.resolve(name + ".json")), captured.blockDefaults());
            DataDiff bds = DataDiff.of(bundled, refreshed, false, true);
            report.check(bds.identical() ? Report.Status.PASS : Report.Status.DIFF, "Item tags and block defaults vs bundled " + name,
                bds.identical() ? "identical" : bds.count() + " differences");
            report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + name, bds);
            Files.write(staging.resources.resolve(name + ".nbt"), write(refreshed));
        } else {
            Importer.IndexEntry latest = Importer.readIndex(resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
            VanillaData previous = read(resources.resolve(latest.name() + ".nbt"));
            DataDiff diff = DataDiff.of(previous, captured, true, true);
            report.check(Report.Status.DIFF, "New protocol " + runs.protocol, "not bundled yet; " + diff.count() + " differences to " + latest.name());
            report.diff("What changed: " + name + " (BDS " + distribution.version() + ") vs bundled " + latest.name(), diff);
            fallbackTodos(diff.sections.get("new blocks"), diff.sections.get("new items"), "network");
            if (!preview || importPreview) {
                new Importer(staging.resources, fallbacks).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog, preview);
                report.check(Report.Status.INFO, "Import", "imported " + name + " (" + runs.protocol + ")" + (preview ? " as a PREVIEW version" : ""));
                Map<String, List<String>> problems = new Checker(staging.resources).problems(preview);
                // Content without a curated fallback is listed above (INFO); broken or unused fallbacks need fixing
                problems.forEach((version, list) -> list.forEach(problem ->
                    report.todos.add("`generator/fallbacks/" + version + ".json`: " + problem)));
            } else {
                report.check(Report.Status.INFO, "Import", "preview not imported (pass --import-preview to bundle it as a preview version)");
            }
        }
    }

    private void missingNetworkData(BdsDistribution distribution, BdsDump dump) throws IOException {
        Importer.IndexEntry latest = Importer.readIndex(resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
        report.header.put("Bundled version", versionName(distribution.version()) + " (protocol unknown: no network capture)");
        VanillaData previous = read(resources.resolve(latest.name() + ".nbt"));
        // Item tags and block defaults are Script API data, so they can still be compared with the bundled newest release
        Map<String, NbtMap> defaults = Importer.defaults(dump, previous.blockStates());
        VanillaData fromDump = new VanillaData(previous.version(), previous.blockStates(), previous.items(), previous.dataDrivenBlocks(),
            previous.creative(), dump.itemTags(), previous.fallbacks(), defaults);
        DataDiff bds = DataDiff.of(previous, fromDump, false, true);
        report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + latest.name(), bds);
        report.check(Report.Status.SKIP, "Network data", "no capture; nothing imported");
    }

    /** Add-on definitions BDS rejects (content log errors and warnings) against the committed list. */
    private void contentLog(Runs runs) throws IOException {
        if (runs.addonContentLog == null) {
            return;
        }
        Path file = root.resolve("capture/addon/content-log.txt");
        String current = String.join("\n", runs.addonContentLog) + "\n";
        String committed = Files.exists(file) ? Files.readString(file).replace("\r\n", "\n") : "";
        Set<String> before = new TreeSet<>(committed.lines().filter(l -> !l.isBlank()).toList());
        Set<String> after = new TreeSet<>(runs.addonContentLog);
        List<String> lines = new ArrayList<>();
        DataDiff.difference(after, before).forEach(line -> lines.add("- new: `" + line.replace("`", "'") + "`"));
        DataDiff.difference(before, after).forEach(line -> lines.add("- gone: `" + line.replace("`", "'") + "`"));
        report.check(lines.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Add-on content log",
            lines.isEmpty() ? runs.addonContentLog.size() + " known rejections/warnings, unchanged"
                : lines.size() + " changes in what BDS rejects or warns about in capture/addon");
        if (!lines.isEmpty()) {
            report.section("Add-on content log changes", lines);
        }
        if (!preview || importPreview) {
            stage(file, current.getBytes(StandardCharsets.UTF_8), "add-on content log");
        }
    }

    /** Component wire shapes: the new add-on and vanilla captures against the committed ones. */
    private void shapes(BdsDistribution distribution, Runs runs) throws Exception {
        Path previous = latestCodegenCapture();
        Path target = codegenCaptures.resolve("bds-" + distribution.version() + "-addon");
        List<String> files = List.of("custom_block_properties.nbt", "custom_item_registry.nbt");

        if (previous != null) {
            JsonObject before = infer(List.of(previous), List.of());
            JsonObject after = infer(List.of(runs.customCapture), List.of());
            ShapeDiff diff = ShapeDiff.of(before, after);
            report(diff, "add-on components vs " + previous.getFileName());
        }
        // Vanilla data driven items and blocks: the reference version against the capture
        if (runs.imported != null) {
            JsonObject before = infer(List.of(), List.of(resources.resolve(runs.referenceName + ".nbt")));
            JsonObject after = infer(List.of(), List.of(runs.imported));
            report(ShapeDiff.of(before, after), "vanilla components vs bundled " + runs.referenceName);
        }
        // Coverage: everything captured must be in the committed spec the types are generated from
        JsonObject committed = JsonParser.parseString(Files.readString(root.resolve("codegen/spec/inferred.json"))).getAsJsonObject();
        ShapeDiff coverage = ShapeDiff.of(committed, infer(List.of(runs.customCapture), List.of()));
        List<String> uncovered = new ArrayList<>(coverage.newComponents.stream().map(c -> c + ": not in the generated types").toList());
        coverage.wireChanges.stream().filter(c -> c.contains(": new field") || c.contains(": type ")).forEach(uncovered::add);
        report.check(uncovered.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Spec coverage",
            uncovered.isEmpty() ? "every captured component shape is in codegen/spec/inferred.json" : uncovered.size() + " shapes not in the spec");
        uncovered.forEach(line -> report.wireChanges.add("not covered by codegen/spec/inferred.json: " + line));

        boolean same = Files.isDirectory(target);
        for (String file : files) {
            same &= Files.exists(target.resolve(file))
                && OrderingCheck.readGzip(target.resolve(file)).equals(OrderingCheck.readGzip(runs.customCapture.resolve(file)));
        }
        report.check(same ? Report.Status.PASS : Report.Status.DIFF, "Add-on capture vs codegen/captures",
            same ? "`" + root.relativize(target) + "` has the same NBT" : "`" + root.relativize(target) + "` is missing or differs");
        // Codegen splits shapes by protocol: every capture directory carries the protocol of the server it came from
        byte[] metadata = captureMetadata(distribution.version(), runs.protocol);
        if (!preview || importPreview) {
            stage(target.resolve("capture.json"), metadata, "capture protocol");
        } else if (!same || !Files.exists(target.resolve("capture.json"))) {
            report.check(Report.Status.INFO, "Add-on capture", "preview capture not copied to codegen/captures (pass --import-preview)");
        }
        if (!same && (!preview || importPreview)) {
            for (String file : files) {
                stageCopy(target.resolve(file), runs.customCapture.resolve(file), "component capture " + file);
            }
            if (!verifyOnly) {
                System.out.println("Regenerating component types (codegen)");
                Codegen.main(new String[]{"generate", "--root", root.toString()});
                report.check(Report.Status.INFO, "Codegen", "reran `:codegen:generateComponents` with the new capture; review the generated diff");
            }
        }
    }

    /** {@code capture.json} of a codegen capture directory: {@code {"bdsVersion": ..., "protocol": n}}. */
    static byte[] captureMetadata(String bdsVersion, int protocol) {
        JsonObject json = new JsonObject();
        json.addProperty("bdsVersion", bdsVersion);
        json.addProperty("protocol", protocol);
        return (new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void report(ShapeDiff diff, String what) {
        if (diff.identical()) {
            report.check(Report.Status.PASS, "Component shapes", what + ": identical");
            return;
        }
        if (!diff.wireChanges.isEmpty()) {
            report.todos.add("WIRE FORMAT CHANGE in " + what + ": review the generated component types (see the top of this report)");
        }
        report.check(Report.Status.DIFF, "Component shapes", what + ": "
            + diff.wireChanges.size() + " wire format changes, " + diff.newComponents.size() + " new components, " + diff.notes.size() + " notes");
        diff.wireChanges.forEach(change -> report.wireChanges.add(what + ": " + change));
        List<String> lines = new ArrayList<>();
        diff.newComponents.forEach(c -> lines.add("- new component `" + c + "`"));
        diff.notes.forEach(n -> lines.add("- " + n));
        if (!lines.isEmpty()) {
            report.section("Component shape notes: " + what, lines);
        }
    }

    /** Runs the codegen inference on a scratch root holding only the given captures and palettes. */
    private JsonObject infer(List<Path> captureDirs, List<Path> palettes) throws IOException {
        Path scratch = Files.createTempDirectory("paletter-shapes");
        try {
            Path data = Files.createDirectories(scratch.resolve("paletter/src/main/resources/paletter"));
            for (Path palette : palettes) {
                Files.copy(palette, data.resolve(palette.getFileName()));
            }
            Path captures = Files.createDirectories(scratch.resolve("codegen/captures"));
            for (Path dir : captureDirs) {
                Path copy = Files.createDirectories(captures.resolve("capture"));
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".nbt")).toList()) {
                        Files.copy(file, copy.resolve(file.getFileName()));
                    }
                }
            }
            return ShapeInference.infer(scratch, List.of());
        } finally {
            BdsDistribution.deleteRecursively(scratch);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Staging: everything the run would write, compared with (or copied into) the repository at the end

    private final class Staging {
        final Path directory;
        final Path resources;

        Staging(Path directory) throws IOException {
            this.directory = directory;
            this.resources = directory.resolve("resources");
            Files.createDirectories(resources);
            try (Stream<Path> files = Files.list(Update.this.resources)) {
                for (Path file : files.toList()) {
                    Files.copy(file, resources.resolve(file.getFileName()));
                }
            }
        }

        void apply() throws IOException {
            try (Stream<Path> files = Files.list(resources)) {
                for (Path file : files.sorted().toList()) {
                    stageCopy(Update.this.resources.resolve(file.getFileName()), file, "bundled data");
                }
            }
        }
    }

    private void stage(Path target, byte[] content, String what) throws IOException {
        byte[] existing = Files.exists(target) ? Files.readAllBytes(target) : null;
        if (existing != null && Arrays.equals(existing, content)) {
            return;
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        report.repoChanges.add((verifyOnly ? "would " : "") + (existing == null ? "add" : "update") + " `" + relative + "` (" + what + ")");
        if (!verifyOnly) {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        }
    }

    private void stageCopy(Path target, Path source, String what) throws IOException {
        stage(target, Files.readAllBytes(source), what);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    private String bundledName(int protocol) throws IOException {
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            if (entry.protocol() == protocol) {
                return entry.name();
            }
        }
        return null;
    }

    /**
     * The palette block hashes are resolved against: the bundled version of the protocol, or for a new protocol the
     * newest bundled release adjusted with the Script API: removed blocks dropped, new blocks and blocks whose states
     * changed generated from their Script API states (legacy alias states, known from the baseline dump of that
     * release, left out). Within a generated block the state order is a guess (existing keys and values first, last
     * key varying fastest); the order of blocks, and so every other block's sequential id, does not depend on it. The
     * block hash and sequential order checks verify the result.
     */
    private VanillaData reference(String existing, BdsDump dump, Runs runs) throws IOException {
        if (existing != null) {
            runs.referenceName = existing;
            return read(resources.resolve(existing + ".nbt"));
        }
        Importer.IndexEntry latest = Importer.readIndex(resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
        runs.referenceName = latest.name();
        VanillaData data = read(resources.resolve(latest.name() + ".nbt"));
        Path baselineFile = latestDump();
        BdsDump baseline = baselineFile == null ? null : BdsDump.parse(readGzipText(baselineFile), baselineFile.getFileName().toString());

        Map<String, List<NbtMap>> palette = new LinkedHashMap<>();
        data.blockStates().forEach(state -> palette.computeIfAbsent(BlockStates.name(state), n -> new ArrayList<>()).add(state));
        List<NbtMap> states = new ArrayList<>();
        List<String> generated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        palette.forEach((name, blockStates) -> {
            Map<String, List<Object>> scriptStates = dump.blockStates().get(name);
            if (!dump.blockStates().isEmpty() && scriptStates == null) {
                removed.add(name);
                return;
            }
            // Unchanged in the Script API since the baseline: keep the palette (it knows better, e.g. minecraft:chalkboard)
            Map<String, List<Object>> before = baseline == null ? null : baseline.blockStates().get(name);
            Map<String, List<Object>> current = values(blockStates);
            Map<String, List<Object>> network = scriptStates == null || (before != null && sameValues(before, scriptStates))
                ? null : networkStates(name, scriptStates, baseline, current.keySet());
            if (network == null || sameValues(network, current)) {
                states.addAll(blockStates);
                return;
            }
            // Existing keys and values keep their order, new ones are appended
            Map<String, List<Object>> ordered = new LinkedHashMap<>();
            current.forEach((key, values) -> {
                if (network.containsKey(key)) {
                    List<Object> merged = new ArrayList<>();
                    values.stream().filter(v -> contains(network.get(key), v)).forEach(merged::add);
                    network.get(key).stream().filter(v -> !contains(merged, v)).forEach(merged::add);
                    ordered.put(key, merged);
                }
            });
            network.forEach(ordered::putIfAbsent);
            List<NbtMap> combinations = combinations(name, ordered);
            states.addAll(combinations);
            generated.add(name + " (" + blockStates.size() + " -> " + combinations.size() + " states)");
        });
        // The Script API dump is canonical (values sorted); new blocks take the value order other blocks with the same
        // state use (e.g. minecraft:corner "none" first, like every vanilla stairs block)
        Map<String, List<Object>> valueOrder = new LinkedHashMap<>();
        palette.values().forEach(blockStates -> values(blockStates).forEach(valueOrder::putIfAbsent));
        dump.blockStates().forEach((name, scriptValues) -> {
            if (!palette.containsKey(name)) {
                Map<String, List<Object>> values = new LinkedHashMap<>();
                scriptValues.forEach((key, list) -> {
                    List<Object> order = valueOrder.getOrDefault(key, List.of());
                    List<Object> sorted = new ArrayList<>();
                    order.stream().filter(v -> contains(list, v)).forEach(v -> sorted.add(list.stream()
                        .filter(w -> contains(List.of(v), w)).findFirst().orElseThrow()));
                    list.stream().filter(v -> !contains(sorted, v)).forEach(sorted::add);
                    values.put(key, sorted);
                });
                List<NbtMap> combinations = combinations(name, values);
                states.addAll(combinations);
                generated.add(name + " (new, " + combinations.size() + " states)");
            }
        });
        states.sort(Comparator.comparing((NbtMap state) -> BlockPalette.nameHash(BlockStates.name(state)), Long::compareUnsigned));
        if (!generated.isEmpty() || !removed.isEmpty()) {
            report.check(Report.Status.INFO, "Reference palette", latest.name() + " adjusted with the Script API: "
                + (generated.isEmpty() ? "" : "generated " + String.join(", ", generated)) + (removed.isEmpty() ? "" : "; removed " + removed));
            if (!generated.isEmpty()) {
                report.todos.add("Block palette: the states of " + generated.size() + " blocks were generated from the Script API. Their order "
                    + "within each block is a guess that only creative stacks verify (sequential ids of other blocks do not depend on it): "
                    + String.join(", ", generated));
            }
        }
        return new VanillaData(new DataVersion(latest.name() + "+", 0), states, data.items(), data.dataDrivenBlocks(), data.creative(),
            data.itemTags(), data.fallbacks(), data.blockDefaults());
    }

    /**
     * The Script API states of a block minus its legacy alias states: the states the baseline dump reported for the
     * block that the palette of the same release does not have. Without a baseline, only the palette's states count.
     */
    private static Map<String, List<Object>> networkStates(String name, Map<String, List<Object>> script, BdsDump baseline,
                                                           Set<String> paletteKeys) {
        Map<String, List<Object>> before = baseline == null ? null : baseline.blockStates().get(name);
        Set<String> aliases = new TreeSet<>((before != null ? before : script).keySet());
        aliases.removeAll(paletteKeys);
        Map<String, List<Object>> network = new LinkedHashMap<>();
        script.forEach((key, values) -> {
            if (!aliases.contains(key)) {
                network.put(key, values);
            }
        });
        return network;
    }

    private static Map<String, List<Object>> values(List<NbtMap> states) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (NbtMap state : states) {
            BlockStates.states(state).forEach((key, value) -> {
                List<Object> list = values.computeIfAbsent(key, k -> new ArrayList<>());
                if (!contains(list, value)) {
                    list.add(value);
                }
            });
        }
        return values;
    }

    private static boolean sameValues(Map<String, List<Object>> a, Map<String, List<Object>> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (String key : a.keySet()) {
            List<Object> x = a.get(key);
            List<Object> y = b.get(key);
            if (x.size() != y.size() || !x.stream().allMatch(v -> contains(y, v))) {
                return false;
            }
        }
        return true;
    }

    /** Script API booleans arrive as bytes and numbers as ints: compare numbers by value. */
    private static boolean contains(List<Object> values, Object value) {
        for (Object candidate : values) {
            if (candidate instanceof Number x && value instanceof Number y ? x.intValue() == y.intValue() : candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static List<NbtMap> combinations(String name, Map<String, List<Object>> values) {
        List<NbtMap> combinations = new ArrayList<>();
        combinations.add(NbtMap.EMPTY);
        for (Map.Entry<String, List<Object>> state : values.entrySet()) {
            List<NbtMap> next = new ArrayList<>();
            for (NbtMap partial : combinations) {
                for (Object value : state.getValue()) {
                    org.cloudburstmc.nbt.NbtMapBuilder builder = partial.toBuilder();
                    builder.put(state.getKey(), value);
                    next.add(builder.build());
                }
            }
            combinations = next;
        }
        return combinations.stream().map(states -> BlockStates.of(name, states)).toList();
    }

    /** {@code 1.26.70.23} -> {@code 26.70}, {@code 1.26.63.1} -> {@code 26.60}: hotfixes share their release's name. */
    static String versionName(String bdsVersion) {
        String[] parts = bdsVersion.split("[.-]");
        int offset = parts[0].equals("1") ? 1 : 0;
        return parts[offset] + "." + (Integer.parseInt(parts[offset + 1]) / 10 * 10);
    }

    private Path latestDump() throws IOException {
        if (!Files.isDirectory(dumps)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dumps)) {
            return files.filter(f -> f.toString().endsWith(".json.gz"))
                .max(Comparator.comparing(f -> versionKey(f.getFileName().toString().replace(".json.gz", "")))).orElse(null);
        }
    }

    private Path latestCodegenCapture() throws IOException {
        try (Stream<Path> files = Files.list(codegenCaptures)) {
            return files.filter(Files::isDirectory)
                .max(Comparator.comparing(f -> versionKey(f.getFileName().toString().replace("bds-", "").replace("-addon", "")))).orElse(null);
        }
    }

    /** Sortable form of a dotted version. */
    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("[.-]")) {
            key.append(String.format("%08d", part.chars().allMatch(Character::isDigit) && !part.isEmpty() ? Integer.parseInt(part) : 0));
        }
        return key.toString();
    }

    private static VanillaData read(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            return PaletteDataCodec.read(stream);
        }
    }

    private static byte[] write(VanillaData data) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        PaletteDataCodec.write(data, bytes);
        return bytes.toByteArray();
    }

    private static byte[] gzip(String text) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String readGzipText(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
