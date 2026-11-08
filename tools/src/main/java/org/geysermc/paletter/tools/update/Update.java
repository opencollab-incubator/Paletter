package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.bds.BdsRunner;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.client.NetherNet;
import org.geysermc.paletter.tools.data.Checker;
import org.geysermc.paletter.tools.data.CreativeOrderCheck;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.data.OrderingCheck;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.WireShapes;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.model.infer.Coverage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockPalette;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.tools.client.CapturedData;
import org.geysermc.paletter.tools.client.LevelDat;
import org.geysermc.paletter.tools.codegen.constants.ConstantsGenerator;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.ExperimentContent;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code update}: pulls the newest BDS of a channel, runs the Script API dump and the network captures, compares the
 * result with the committed data and either updates the repository or (with {@code --verify-only}) only reports.
 * See README.md, "Updating data".
 */
public final class Update {
    /**
     * Used only when discovery fails (and always tried as candidates): the current toggles of BDS 1.26.63.1 as
     * discovered there. {@code experimental_vanilla_shapes}, used before, is not a toggle id (the toggle is
     * {@code voxel_shapes}); {@code data_driven_biomes} and {@code experimental_molang_features} are retired toggles BDS
     * still accepts but no longer streams.
     */
    static final List<String> DEFAULT_EXPERIMENTS = List.of("experimental_creator_cameras", "gametest", "poi",
        "upcoming_creator_features", "villager_trades_rebalance", "voxel_shapes", "y_2026_drop_4");
    /**
     * Retired toggles the Linux binary of 1.26.63.1 has as strings but the Windows one only builds from immediates the
     * scanner cannot join; tried as candidates on every platform so the committed record does not depend on the
     * platform of the run (the level.dat oracle still decides).
     */
    static final List<String> KNOWN_RETIRED = List.of("locator_bar", "scripting", "wild_update", "y_2025_drop_2",
        "y_2025_drop_3", "y_2026_drop_1", "y_2026_drop_2", "y_2026_drop_3");
    /** Above this many current toggles, only one capture with all of them is made (no per toggle attribution). */
    static final int EACH_LIMIT = 12;
    private static final String VANILLA_WORLD = "paletter_vanilla";
    private static final String ADDON_WORLD = "paletter_addon";
    private static final String CAPTURE_PACK = "paletter_capture";
    private static final String CREATIVE_WORLD = "paletter_creative";
    private static final String EXPERIMENT_WORLD = "paletter_experiments";

    private final Path root;
    private final Path resources;
    private final Path bdsData;
    private final Map<String, String> options;
    private final boolean preview;
    private final boolean verifyOnly;
    private final boolean importPreview;
    private final boolean keepServer;
    private final int port;
    private final int timeout;
    private Report report;

    public Update(Path root, Map<String, String> options) {
        this.root = root;
        this.resources = root.resolve("paletter/src/main/resources/paletter");
        this.bdsData = root.resolve(Layout.BDS);
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
    public int run() throws Exception {
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
            new BdsRunner(root.resolve(Layout.DUMP_ADDON)).run(server, dumpLog, timeout, port, false);
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
        experimentsReport(distribution, runs, staging);
        creativeOrder(runs);
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
        for (Path stale : org.geysermc.paletter.tools.codegen.constants.Main.stale(root, result.files())) {
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
        Path creative;
        List<String> addonContentLog;
        /** The bundled version this run's network data belongs to (null without a capture). */
        String bundledName;
        final ExperimentRun experiments = new ExperimentRun();
    }

    /** What the experiment step found; see README.md, "Experiments". */
    static final class ExperimentRun {
        /** discovered, --experiments, or fallback (discovery failed) */
        String source = "fallback";
        int candidates;
        Set<String> uiKeys = new TreeSet<>();
        Map<String, String> displayNames = new TreeMap<>();
        Map<String, String> uiKeyOf = new TreeMap<>();
        /** Toggles BDS kept in level.dat (current and retired); empty when not discovered. */
        List<String> accepted = List.of();
        /** Codes of the "Experiment(s) active" line of the discovery start. */
        List<String> activeCodes;
        /** Toggles start game streamed: what the add-on world and the attribution use. */
        List<String> current = DEFAULT_EXPERIMENTS;
        List<String> retired = List.of();
        Map<String, String> codes = new TreeMap<>();
        ExperimentAttribution.Diff all;
        Map<String, ExperimentAttribution.Diff> each = new TreeMap<>();
        Map<String, ExperimentContent> content = new TreeMap<>();
        List<String> unexplained = List.of();
        String attribution = "none";
        long millis;
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
            runs.addonContentLog = addonWorldContentLog(server, work, properties, runs);
            return;
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-vanilla-sequential.log"));
            bds.awaitStarted(timeout);
            capture(address, runs, work.resolve("vanilla-sequential"), null, "vanilla capture (sequential ids)");
        }

        // Experiments: discover the toggles, verify them, capture what each one adds
        if (options.containsKey("experiments")) {
            runs.experiments.source = "--experiments";
            runs.experiments.current = Arrays.stream(options.get("experiments").split(",")).map(String::strip)
                .filter(t -> !t.isEmpty()).sorted().toList();
        }
        try {
            experiments(server, work, address, dump, runs, properties);
        } catch (Exception e) {
            report.fail("Experiments", e);
            report.todos.add("Experiment discovery failed; the add-on world used " + runs.experiments.current + " (" + runs.experiments.source + ")");
        }

        // Add-on world: created once, experiments switched on in level.dat, then captured
        runs.addonContentLog = addonWorldContentLog(server, work, properties, runs);
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
            appliedCheck("add-on world", runs.experiments.current, summary);
        }
        properties.put("block-network-ids-are-hashes", "false");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon-sequential.log"));
            bds.awaitStarted(timeout);
            capture(address, runs, work.resolve("addon-sequential"), null, "add-on capture (sequential ids)");
        }

        // Creative order probe: every kind of menu category placement, no experiments
        freshWorld(server, CREATIVE_WORLD, root.resolve(Layout.CREATIVE_ADDON));
        properties.put("level-name", CREATIVE_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-creative.log"));
            bds.awaitStarted(timeout);
            capture(address, runs, work.resolve("creative"), work.resolve("creative/custom"), "creative order probe capture");
            runs.creative = work.resolve("creative");
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
    private List<String> addonWorldContentLog(Path server, Path work, Map<String, String> properties, Runs runs) throws Exception {
        Path addon = root.resolve(Layout.CAPTURE_ADDON);
        freshWorld(server, ADDON_WORLD, addon);
        properties.put("level-name", ADDON_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-addon-create.log"));
            bds.awaitStarted(timeout);
        }
        Capture.enableExperiments(server.resolve("worlds").resolve(ADDON_WORLD).resolve("level.dat"), runs.experiments.current);
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
            if (!Files.isDirectory(source)) {
                continue;
            }
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
    // Experiments

    /**
     * Discovers the experiment toggles of this BDS (candidates from the binary, BDS's level.dat round trip as the
     * oracle, start game for which are current), checks that BDS applies them, and attributes content: a capture and a
     * Script API dump with all toggles and with each current toggle alone, against the vanilla ones without any.
     * Leaves the server stopped.
     */
    private void experiments(Path server, Path work, InetSocketAddress address, BdsDump dump, Runs runs,
                             Map<String, String> properties) throws Exception {
        ExperimentRun result = runs.experiments;
        long started = System.nanoTime();
        Path levelDat = server.resolve("worlds").resolve(EXPERIMENT_WORLD).resolve("level.dat");
        freshWorld(server, EXPERIMENT_WORLD, null);
        properties.put("level-name", EXPERIMENT_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-experiments-create.log"));
            bds.awaitStarted(timeout);
        }

        // Candidates: the binary (identifiers, '_' suffixes, imm64 immediates), the experimental packs, ids of earlier
        // records and the built in list
        ExperimentDiscovery.Candidates scanned = ExperimentDiscovery.scan(BdsServer.executable(server));
        result.uiKeys = scanned.uiKeys();
        result.displayNames = ExperimentDiscovery.displayNames(server, scanned.uiKeys());
        List<String> toggles;
        boolean manual = result.source.equals("--experiments");
        if (manual) {
            toggles = result.current;
        } else {
            Set<String> candidates = new TreeSet<>(scanned.ids());
            candidates.addAll(ExperimentDiscovery.packCandidates(server));
            candidates.addAll(DEFAULT_EXPERIMENTS);
            candidates.addAll(KNOWN_RETIRED);
            candidates.addAll(recordedToggles());
            result.candidates = candidates.size();
            LevelDat.setExperiments(levelDat, candidates);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-experiments-discover.log"));
                bds.awaitStarted(timeout);
                result.activeCodes = ExperimentDiscovery.activeCodes(bds.lines(line -> line.contains("Experiment(s) active")));
            }
            result.accepted = LevelDat.enabled(LevelDat.experiments(levelDat));
            if (result.accepted.isEmpty()) {
                throw new IOException("BDS kept none of " + candidates.size() + " candidate toggles in level.dat");
            }
            boolean codesMatch = result.activeCodes != null && result.activeCodes.size() == result.accepted.size();
            report.check(codesMatch ? Report.Status.PASS : Report.Status.DIFF, "Experiments: discovery",
                result.candidates + " candidates (binary " + BdsServer.executable(server).getFileName() + ", experimental "
                    + "packs, known ids); BDS kept " + result.accepted.size() + " toggles in level.dat"
                    + (codesMatch ? " and logged as many active" : "; it logged " + (result.activeCodes == null ? "no" : result.activeCodes.size())
                    + " active (Experiment(s) active line)"));
            toggles = result.accepted;
        }

        // All toggles at once: start game tells which are current (streamed); the rest are retired
        LevelDat.setExperiments(levelDat, toggles);
        Capture.Summary all;
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-experiments-all.log"));
            bds.awaitStarted(timeout);
            all = capture(address, runs, work.resolve("experiments/all"), null, "experiments capture (all toggles)");
        }
        List<String> streamed = all.enabledExperiments();
        if (manual) {
            appliedCheck("--experiments", toggles, all);
        } else {
            result.source = "discovered";
            result.current = streamed;
            result.retired = toggles.stream().filter(t -> !streamed.contains(t)).toList();
            retiredToggles = result.retired;
            List<String> notAccepted = streamed.stream().filter(t -> !toggles.contains(t)).toList();
            boolean uiMatch = streamed.size() == result.uiKeys.size();
            report.check(notAccepted.isEmpty() && uiMatch ? Report.Status.PASS : Report.Status.DIFF, "Experiments: current toggles",
                streamed.size() + " streamed in start game " + streamed + ", " + result.retired.size() + " retired (kept, not streamed); "
                    + result.uiKeys.size() + " `" + ExperimentDiscovery.UI_PREFIX + "*` UI keys in the binary"
                    + (uiMatch ? "" : ": a current toggle may be missing from the candidates; pass it with --experiments or add it to DEFAULT_EXPERIMENTS")
                    + (notAccepted.isEmpty() ? "" : "; streamed but not kept in level.dat: " + notAccepted));
        }
        List<String> packStack = all.packStackExperiments().stream().filter(e -> e.endsWith("=true"))
            .map(e -> e.substring(0, e.length() - 5)).sorted().toList();
        report.check(packStack.equals(streamed) ? Report.Status.PASS : Report.Status.DIFF, "Experiments: resource pack stack",
            packStack.equals(streamed) ? "lists the same toggles as start game" : "lists " + packStack + ", start game " + streamed);
        result.uiKeyOf = ExperimentDiscovery.matchUiKeys(result.current, result.uiKeys);

        // Attribution
        String mode = options.getOrDefault("experiment-attribution", "each");
        if (!List.of("each", "all", "none").contains(mode)) {
            throw new IllegalArgumentException("--experiment-attribution must be each, all or none");
        }
        if (mode.equals("each") && result.current.size() > EACH_LIMIT) {
            report.check(Report.Status.INFO, "Experiments: attribution", result.current.size() + " current toggles (more than "
                + EACH_LIMIT + "): only all together");
            mode = "all";
        }
        result.attribution = mode;
        if (!mode.equals("none") && runs.vanilla != null) {
            CapturedData base = CapturedData.read(runs.vanilla);
            Set<String> creativeBefore = ExperimentAttribution.creativeItems(base);
            BdsRunner runner = new BdsRunner(root.resolve(Layout.DUMP_ADDON));
            Path dumpLog = work.resolve("experiments/bds-dump-all.log");
            try {
                runner.rerun(server, dumpLog, timeout, port, toggles);
                result.all = ExperimentAttribution.diff(base, CapturedData.read(work.resolve("experiments/all")), dump, BdsDump.read(dumpLog));
                if (mode.equals("each")) {
                    for (String toggle : result.current) {
                        LevelDat.setExperiments(levelDat, List.of(toggle));
                        // The dump reruns switch server.properties to their own world
                        BdsServer.configure(server, properties);
                        Path out = work.resolve("experiments").resolve(toggle);
                        try (BdsServer bds = new BdsServer(server, false)) {
                            bds.start(work.resolve("experiments/bds-" + toggle + ".log"));
                            bds.awaitStarted(timeout);
                            List<String> codes = ExperimentDiscovery.activeCodes(bds.lines(line -> line.contains("Experiment(s) active")));
                            if (codes != null && codes.size() == 1) {
                                result.codes.put(toggle, codes.getFirst());
                            }
                            appliedCheck(toggle, List.of(toggle), capture(address, runs, out, null, "experiments capture (" + toggle + ")"));
                        }
                        Path log = work.resolve("experiments/bds-dump-" + toggle + ".log");
                        runner.rerun(server, log, timeout, port, List.of(toggle));
                        ExperimentAttribution.Diff diff = ExperimentAttribution.diff(base, CapturedData.read(out), dump, BdsDump.read(log));
                        result.each.put(toggle, diff);
                        result.content.put(toggle, diff.content(creativeBefore));
                    }
                    result.unexplained = ExperimentAttribution.unexplained(result.all, result.each);
                } else if (result.current.size() == 1) {
                    result.content.put(result.current.getFirst(), result.all.content(creativeBefore));
                }
            } finally {
                // The dump world back without experiments
                LevelDat.setExperiments(server.resolve("worlds").resolve(BdsRunner.WORLD).resolve("level.dat"), List.of());
            }
        }
        result.millis = (System.nanoTime() - started) / 1_000_000;
    }

    /** Whether start game reported exactly the requested toggles; retired ones are kept but never streamed. */
    private void appliedCheck(String what, List<String> requested, Capture.Summary summary) {
        List<String> echoed = summary.enabledExperiments();
        List<String> retired = retiredToggles;
        List<String> expected = requested.stream().filter(t -> !retired.contains(t)).sorted().toList();
        boolean ok = echoed.equals(expected);
        report.check(ok ? Report.Status.PASS : Report.Status.FAIL, "Experiments applied (" + what + ")",
            ok ? "start game reports " + echoed : "requested " + expected + ", start game reports " + echoed
                + (requested.stream().anyMatch(t -> !echoed.contains(t)) ? " (BDS dropped or retired toggles)" : ""));
    }

    /** Retired toggles of this run: BDS keeps them in level.dat but never streams them. */
    private List<String> retiredToggles = List.of();

    /** Toggle ids of the committed experiment records ({@code experiments.json} of every version in data/bds). */
    private Set<String> recordedToggles() throws IOException {
        Set<String> ids = new TreeSet<>();
        if (!Files.isDirectory(bdsData)) {
            return ids;
        }
        try (Stream<Path> dirs = Files.list(bdsData)) {
            for (Path file : dirs.map(d -> d.resolve("experiments.json")).filter(Files::exists).toList()) {
                JsonObject toggles = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("toggles");
                if (toggles != null) {
                    ids.addAll(toggles.keySet());
                }
            }
        }
        return ids;
    }

    /**
     * The "Experiments" report section, the committed record {@code data/bds/<bds version>/experiments.json} and the
     * experiment marks of the bundled version.
     */
    private void experimentsReport(BdsDistribution distribution, Runs runs, Staging staging) throws IOException {
        ExperimentRun result = runs.experiments;
        List<String> lines = new ArrayList<>();
        lines.add("Source: **" + result.source + "**" + (result.candidates > 0 ? " (" + result.candidates + " candidates)" : "")
            + ", attribution: " + result.attribution + ", " + (result.millis / 1000) + " s for this step.");
        lines.add("");
        lines.add("| Toggle | Status | Log code | UI key | Name | Items | Blocks | Creative stacks |");
        lines.add("|---|---|---|---|---|---|---|---|");
        for (String toggle : result.current) {
            String key = result.uiKeyOf.get(toggle);
            ExperimentAttribution.Diff diff = result.each.get(toggle);
            ExperimentContent content = result.content.get(toggle);
            lines.add("| `" + toggle + "` | current | " + result.codes.getOrDefault(toggle, "") + " | " + (key == null ? "" : "`" + key + "`")
                + " | " + (key == null ? "" : result.displayNames.getOrDefault(key, "")) + " | "
                + (content == null ? "" : content.items().size()) + " | " + (content == null ? "" : content.blocks().size()) + " | "
                + (diff == null ? "" : diff.creativeAdded().size()) + " |");
        }
        for (String toggle : result.retired) {
            lines.add("| `" + toggle + "` | retired | | | | | | |");
        }
        lines.add("");
        result.each.forEach((toggle, diff) -> {
            lines.add("**" + toggle + "**" + (diff.isEmpty() ? ": no item, block or creative change" : ""));
            lines.add("");
            diff.lines().forEach(line -> lines.add("- " + line));
            lines.add("");
        });
        if (result.all != null) {
            lines.add("**All toggles together**" + (result.all.isEmpty() ? ": no change" : ""));
            lines.add("");
            result.all.lines().forEach(line -> lines.add("- " + line));
            lines.add("");
        }
        if (!result.unexplained.isEmpty()) {
            lines.add("Only with several toggles together (not explained by any single one): " + String.join(", ", result.unexplained));
            report.check(Report.Status.INFO, "Experiments: interactions", result.unexplained.size() + " changes only appear with several toggles together");
        }
        docsExperiments(result, lines);
        report.section("Experiments", lines);

        if (!result.source.equals("discovered") || (preview && !importPreview)) {
            return;
        }
        stage(bdsData.resolve(distribution.version()).resolve("experiments.json"), record(distribution, runs), "experiment record");
        if (runs.bundledName != null && result.attribution.equals("each")) {
            Path file = staging.resources.resolve(runs.bundledName + ".nbt");
            VanillaData data = read(file);
            Map<String, ExperimentContent> marks = new TreeMap<>();
            result.content.forEach((toggle, content) -> {
                if (!content.isEmpty()) {
                    marks.put(toggle, content);
                }
            });
            boolean same = new TreeMap<>(data.experiments()).equals(marks);
            report.check(same ? Report.Status.PASS : Report.Status.DIFF, "Experiment marks vs bundled " + runs.bundledName,
                same ? "identical" : "bundled " + new TreeSet<>(data.experiments().keySet()) + ", captured " + marks.keySet());
            Files.write(file, write(data.withExperiments(marks)));
        }
    }

    /** Which toggle each experiment named in the creator docs ({@code codegen/model/components.json}) is. */
    private void docsExperiments(ExperimentRun result, List<String> lines) throws IOException {
        Path model = root.resolve(Layout.MODEL + "/components.json");
        if (!Files.exists(model)) {
            return;
        }
        Set<String> names = new TreeSet<>();
        for (var component : JsonParser.parseString(Files.readString(model)).getAsJsonObject().getAsJsonArray("components")) {
            var experiments = component.getAsJsonObject().getAsJsonArray("experiments");
            if (experiments != null) {
                experiments.forEach(name -> names.add(name.getAsString()));
            }
        }
        if (names.isEmpty()) {
            return;
        }
        Map<String, String> byName = new TreeMap<>();
        result.retired.forEach(t -> byName.put(ExperimentDiscovery.normalizeName(t), t + " (retired)"));
        result.current.forEach(t -> byName.put(ExperimentDiscovery.normalizeName(t), t));
        result.uiKeyOf.forEach((toggle, key) -> {
            String display = result.displayNames.get(key);
            if (display != null) {
                byName.put(ExperimentDiscovery.normalizeName(display), toggle);
            }
        });
        lines.add("");
        lines.add("Experiments the creator docs name (`data/model/components.json`):");
        lines.add("");
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            String toggle = byName.get(ExperimentDiscovery.normalizeName(name));
            lines.add("- " + name + ": " + (toggle == null ? "**no toggle of this BDS**" : "`" + toggle + "`"));
            if (toggle == null && !name.startsWith("experimental (")) {
                unknown.add(name);
            }
        }
        report.check(unknown.isEmpty() ? Report.Status.PASS : Report.Status.INFO, "Experiments: creator docs",
            unknown.isEmpty() ? "every experiment the docs name is a toggle" : "the docs name experiments this BDS does not have: " + unknown);
    }

    /**
     * {@code data/bds/<bds version>/experiments.json}: toggles and per toggle content. Candidate counts differ by
     * platform and stay in the report; ids of earlier records are candidates again, so the toggle set converges.
     */
    private byte[] record(BdsDistribution distribution, Runs runs) {
        ExperimentRun result = runs.experiments;
        JsonObject json = new JsonObject();
        json.addProperty("bdsVersion", distribution.version());
        if (runs.protocol != null) {
            json.addProperty("protocol", runs.protocol);
        }
        JsonObject toggles = new JsonObject();
        List<String> all = new ArrayList<>(result.current);
        all.addAll(result.retired);
        all.stream().sorted().forEach(toggle -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("status", result.current.contains(toggle) ? "current" : "retired");
            if (result.codes.containsKey(toggle)) {
                entry.addProperty("logCode", result.codes.get(toggle));
            }
            String key = result.uiKeyOf.get(toggle);
            if (key != null) {
                entry.addProperty("uiKey", key);
                if (result.displayNames.containsKey(key)) {
                    entry.addProperty("name", result.displayNames.get(key));
                }
            }
            ExperimentAttribution.Diff diff = result.each.get(toggle);
            if (diff != null) {
                JsonObject content = new JsonObject();
                content.add("itemsAdded", array(diff.itemsAdded()));
                content.add("itemsChanged", array(diff.itemsChanged()));
                content.add("scriptItemsAdded", array(diff.scriptItemsAdded()));
                content.add("blocksAdded", array(diff.blocksAdded()));
                content.add("dataDrivenBlocks", array(diff.dataDrivenBlocks()));
                content.add("creativeAdded", array(diff.creativeAdded()));
                content.add("removed", array(diff.removed()));
                entry.add("content", content);
            }
            toggles.add(toggle, entry);
        });
        json.add("toggles", toggles);
        json.add("uiKeys", array(List.copyOf(result.uiKeys)));
        if (!result.unexplained.isEmpty()) {
            json.add("onlyTogether", array(result.unexplained));
        }
        return (new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static com.google.gson.JsonArray array(List<String> values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparison

    /** The Script API view against the committed baseline dump of the newest bundled release. */
    private void scriptApi(BdsDistribution distribution, BdsDump dump, Runs runs) throws IOException {
        Path baselineFile = latestDump();
        Path target = bdsData.resolve(distribution.version()).resolve("dump.json.gz");
        if (baselineFile == null) {
            report.check(Report.Status.DIFF, "Script API vs baseline", "no baseline dump in data/bds/*/dump.json.gz");
        } else {
            String baselineVersion = baselineFile.getFileName().toString().replace(".json.gz", "");
            BdsDump baseline = BdsDump.parse(readGzipText(baselineFile), baselineVersion);
            Diff diff = Diff.scriptApi(baseline, dump);
            report.check(diff.identical() ? Report.Status.PASS : Report.Status.DIFF, "Script API vs baseline " + baselineVersion,
                diff.identical() ? "identical" : diff.count() + " differences");
            report.diff("Script API: BDS " + distribution.version() + " vs baseline " + baselineVersion, diff);
            if (runs.vanilla == null) {
                fallbackTodos(diff.lines("new blocks"), diff.lines("new items"), "Script API");
            }
        }
        if (!preview || importPreview) {
            stage(target, gzip(runs.dumpJson), "Script API baseline " + distribution.version());
        }
    }

    /**
     * New blocks and items an older bundled version lacks fall back to {@code minecraft:unknown} there (Paletter's
     * default; it bundles no fallbacks). Lists them per version as INFO: what a consumer may want to define a fallback for.
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
            List<String> needBlocks = identifiers(blocks).stream().filter(id -> !knownBlocks.contains(id)).toList();
            List<String> needItems = identifiers(items).stream().filter(id -> !knownItems.contains(id)).toList();
            if (!needBlocks.isEmpty() || !needItems.isEmpty()) {
                lines.add("- " + entry.name() + ": "
                    + (needBlocks.isEmpty() ? "" : needBlocks.size() + " blocks " + code(needBlocks))
                    + (needBlocks.isEmpty() || needItems.isEmpty() ? "" : "; ")
                    + (needItems.isEmpty() ? "" : needItems.size() + " items " + code(needItems)));
            }
        }
        if (!lines.isEmpty()) {
            report.check(Report.Status.INFO, "Fallbacks", "new content falls back to unknown in older versions unless a consumer defines a fallback ("
                + source + "), see the list");
            List<String> section = new ArrayList<>(List.of("Paletter bundles no fallbacks: these fall back to `minecraft:unknown` "
                + "(block and item) in the older version unless a consumer defines a fallback "
                + "(`PaletteLayer.Builder#blockFallback` / `#itemFallback`). Nothing is required here.", ""));
            section.addAll(lines);
            report.section("Fallbacks (" + source + "): content that falls back to unknown", section);
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
        runs.bundledName = name;
        // The capture in the bundled format, imported into a scratch directory
        Path scratch = staging.directory.resolveSibling("imported");
        Files.createDirectories(scratch);
        new Importer(scratch).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog);
        VanillaData captured = read(scratch.resolve(name + ".nbt"));
        runs.imported = scratch.resolve(name + ".nbt");

        if (runs.existing != null) {
            VanillaData bundled = read(resources.resolve(name + ".nbt"));
            Diff network = Diff.vanilla(bundled, captured, true, false);
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
                bundled.creative(), captured.itemTags(), bundled.fallbacks(), captured.blockDefaults(), bundled.experiments(),
                captured.blockItems());
            Diff bds = Diff.vanilla(bundled, refreshed, false, true);
            report.check(bds.identical() ? Report.Status.PASS : Report.Status.DIFF, "Item tags and block defaults vs bundled " + name,
                bds.identical() ? "identical" : bds.count() + " differences");
            report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + name, bds);
            Files.write(staging.resources.resolve(name + ".nbt"), write(refreshed));
        } else {
            Importer.IndexEntry latest = Importer.readIndex(resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
            VanillaData previous = read(resources.resolve(latest.name() + ".nbt"));
            Diff diff = Diff.vanilla(previous, captured, true, true);
            report.check(Report.Status.DIFF, "New protocol " + runs.protocol, "not bundled yet; " + diff.count() + " differences to " + latest.name());
            report.diff("What changed: " + name + " (BDS " + distribution.version() + ") vs bundled " + latest.name(), diff);
            fallbackTodos(diff.lines("new blocks"), diff.lines("new items"), "network");
            if (!preview || importPreview) {
                new Importer(staging.resources).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog, preview);
                report.check(Report.Status.INFO, "Import", "imported " + name + " (" + runs.protocol + ")" + (preview ? " as a PREVIEW version" : ""));
                // Content older versions lack is listed above (INFO); there are no bundled fallbacks to check
                new Checker(staging.resources).problems(preview);
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
            previous.creative(), dump.itemTags(), previous.fallbacks(), defaults, previous.experiments(),
            Importer.blockItems(dump, previous.blockStates(), previous.items()));
        Diff bds = Diff.vanilla(previous, fromDump, false, true);
        report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + latest.name(), bds);
        report.check(Report.Status.SKIP, "Network data", "no capture; nothing imported");
    }

    /** Add-on definitions BDS rejects (content log errors and warnings) against the committed list. */
    /**
     * Paletter's default creative ordering ({@code CreativeOrdering.BDS}) against what BDS sent for the add-on and the
     * creative order probe (capture/CREATIVE-ORDER.md). A difference means BDS changed where it puts custom content.
     */
    private void creativeOrder(Runs runs) throws IOException {
        if (runs.existing == null) {
            report.check(Report.Status.INFO, "Creative order", "skipped: protocol " + runs.protocol + " is not bundled, "
                + "so Paletter has no vanilla layout of this version to place custom content in");
            return;
        }
        String[][] captures = {{"add-on", runs.addon == null ? null : runs.addon.toString(), runs.customCapture == null ? null : runs.customCapture.toString()},
            {"creative order probe", runs.creative == null ? null : runs.creative.toString(), runs.creative == null ? null : runs.creative.resolve("custom").toString()}};
        for (String[] capture : captures) {
            if (capture[1] == null) {
                report.check(Report.Status.FAIL, "Creative order (" + capture[0] + ")", "no capture");
                continue;
            }
            Path directory = Path.of(capture[1]);
            Path custom = Path.of(capture[2]);
            CreativeOrderCheck.Result result = CreativeOrderCheck.check(org.geysermc.paletter.Paletter.bundled(), runs.protocol,
                directory.resolve("creative_items.json"), custom.resolve("custom_item_registry.nbt"), custom.resolve("custom_block_properties.nbt"));
            if (result.matches()) {
                report.check(Report.Status.PASS, "Creative order (" + capture[0] + ")", "CreativeOrdering.BDS reproduces the "
                    + "creative inventory BDS sent (" + result.customStacks() + " custom stacks)");
            } else {
                report.check(Report.Status.DIFF, "Creative order (" + capture[0] + ")", "BDS places custom content differently "
                    + "from CreativeOrdering.BDS: " + result.differences().size() + "+ differences");
                List<String> lines = new ArrayList<>();
                result.differences().forEach(difference -> lines.add("- `" + difference.replace("`", "'") + "`"));
                report.section("Creative order: " + capture[0], lines);
                report.todos.add("Creative order: BDS changed where it puts custom items/blocks (" + capture[0] + "); see "
                    + "capture/CREATIVE-ORDER.md, update CreativeOrdering.BDS and the committed probe captures");
            }
        }
    }

    private void contentLog(Runs runs) throws IOException {
        if (runs.addonContentLog == null) {
            return;
        }
        Path file = root.resolve(Layout.CAPTURE_ADDON + "/content-log.txt");
        String current = String.join("\n", runs.addonContentLog) + "\n";
        String committed = Files.exists(file) ? Files.readString(file).replace("\r\n", "\n") : "";
        Set<String> before = new TreeSet<>(committed.lines().filter(l -> !l.isBlank()).toList());
        Set<String> after = new TreeSet<>(runs.addonContentLog);
        List<String> lines = new ArrayList<>();
        Diff.difference(after, before).forEach(line -> lines.add("- new: `" + line.replace("`", "'") + "`"));
        Diff.difference(before, after).forEach(line -> lines.add("- gone: `" + line.replace("`", "'") + "`"));
        report.check(lines.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Add-on content log",
            lines.isEmpty() ? runs.addonContentLog.size() + " known rejections/warnings, unchanged"
                : lines.size() + " changes in what BDS rejects or warns about in data/addons/capture");
        if (!lines.isEmpty()) {
            report.section("Add-on content log changes", lines);
        }
        if (!preview || importPreview) {
            stage(file, current.getBytes(StandardCharsets.UTF_8), "add-on content log");
        }
    }

    /** Component wire shapes: the new add-on and vanilla captures against the committed ones. */
    private void shapes(BdsDistribution distribution, Runs runs) throws Exception {
        Path previous = latestAddonCapture();
        Path target = bdsData.resolve(distribution.version()).resolve("addon");
        List<String> files = List.of("custom_block_properties.nbt", "custom_item_registry.nbt");
        WireShapes captured = WireShapes.ofCapture(runs.customCapture);

        if (previous != null) {
            report(Diff.shapes(WireShapes.ofCapture(previous).components(), captured.components()),
                "add-on components vs " + previous.getParent().getFileName());
        }
        // Vanilla data driven items and blocks: the reference version against the capture
        if (runs.imported != null) {
            report(Diff.shapes(WireShapes.ofPalette(resources.resolve(runs.referenceName + ".nbt")).components(),
                WireShapes.ofPalette(runs.imported).components()), "vanilla components vs bundled " + runs.referenceName);
        }
        // Coverage: everything captured must be something the component model can produce
        JsonObject mappings = JsonParser.parseString(Files.readString(root.resolve(Layout.MODEL + "/mappings.json"))).getAsJsonObject();
        List<String> uncovered = new ArrayList<>(captured.uncovered(mappings));
        uncovered.removeAll(WireShapes.knownGaps(root.resolve(Layout.MODEL)));
        report.check(uncovered.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Spec coverage",
            uncovered.isEmpty() ? "every captured component shape is in " + Layout.MODEL + "/mappings.json" : uncovered.size() + " shapes not in the mappings");
        uncovered.forEach(line -> report.wireChanges.add("not covered by " + Layout.MODEL + "/mappings.json: " + line));

        boolean same = Files.isDirectory(target);
        for (String file : files) {
            same &= Files.exists(target.resolve(file))
                && Nbt.readGzip(target.resolve(file)).equals(Nbt.readGzip(runs.customCapture.resolve(file)));
        }
        report.check(same ? Report.Status.PASS : Report.Status.DIFF, "Add-on capture vs " + root.relativize(bdsData),
            same ? "`" + root.relativize(target) + "` has the same NBT" : "`" + root.relativize(target) + "` is missing or differs");
        if (!same && (!preview || importPreview)) {
            for (String file : files) {
                stageCopy(target.resolve(file), runs.customCapture.resolve(file), "component capture " + file);
            }
            report.check(Report.Status.INFO, "Component model", "new add-on capture staged; run the model checks (checkMappings, checkComponentApi) and review the diff");
        } else if (!same) {
            report.check(Report.Status.INFO, "Add-on capture", "preview capture not copied to " + root.relativize(bdsData) + " (pass --import-preview)");
        }
    }

    private void report(Diff diff, String what) {
        if (diff.identical()) {
            report.check(Report.Status.PASS, "Component shapes", what + ": identical");
            return;
        }
        if (!diff.lines(Diff.WIRE_CHANGES).isEmpty()) {
            report.todos.add("WIRE FORMAT CHANGE in " + what + ": review the component model and the generated component types (see the top of this report)");
        }
        report.check(Report.Status.DIFF, "Component shapes", what + ": "
            + diff.lines(Diff.WIRE_CHANGES).size() + " wire format changes, " + diff.lines(Diff.NEW_COMPONENTS).size() + " new components");
        diff.lines(Diff.WIRE_CHANGES).forEach(change -> report.wireChanges.add(what + ": " + change));
        List<String> lines = new ArrayList<>();
        diff.lines(Diff.NEW_COMPONENTS).forEach(c -> lines.add("- new component `" + c + "`"));
        if (!lines.isEmpty()) {
            report.section("Component shape notes: " + what, lines);
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
     * release, left out). Within a generated block the state order follows {@link #keyOrder}; it is only partly
     * observable (creative stacks), but the order of blocks, and so every other block's sequential id, does not depend
     * on it. The block hash and sequential order checks verify the result.
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
        Map<String, List<String>> strides = new LinkedHashMap<>();
        palette.forEach((name, blockStates) -> strides.put(name, strideOrder(blockStates)));
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
            // Existing values keep their order, new ones are appended
            Map<String, List<Object>> merged = new LinkedHashMap<>();
            network.forEach((key, values) -> {
                List<Object> list = new ArrayList<>();
                current.getOrDefault(key, List.of()).stream().filter(v -> contains(values, v)).forEach(list::add);
                values.stream().filter(v -> !contains(list, v)).forEach(list::add);
                merged.put(key, list);
            });
            List<String> keys = keyOrder(strides.get(name), new ArrayList<>(network.keySet()), strides);
            List<NbtMap> combinations = combinations(name, slowestFirst(keys, merged));
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
                List<NbtMap> combinations = combinations(name, slowestFirst(
                    keyOrder(similarBlockOrder(values.keySet(), strides), new ArrayList<>(values.keySet()), strides), values));
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
                    + "within each block is inferred from the reference palette (Update#keyOrder) and only creative stacks verify it (sequential ids of other blocks do not depend on it): "
                    + String.join(", ", generated));
            }
        }
        return new VanillaData(new DataVersion(latest.name() + "+", 0), states, data.items(), data.dataDrivenBlocks(), data.creative(),
            data.itemTags(), data.fallbacks(), data.blockDefaults(), data.experiments(), data.blockItems());
    }

    private static final List<String> DIRECTIONS = List.of("north", "east", "south", "west", "up", "down");

    /**
     * The order in which BDS enumerates a generated block's states, as keys from the fastest varying (stride 1) to the
     * slowest. Bedrock enumerates every combination with the first registered state varying fastest; registration
     * order cannot be observed, so it is inferred from the reference palette (replayed on 26.40 -> 26.50 -> 26.60 in
     * KeyOrderTest: right for 126 of the 127 blocks whose states changed or that are new with several states):
     * <ol>
     *     <li>the keys of {@code base} (the block's own order in the reference palette, or that of the most similar
     *     block for a new block) stay first, in their order: states added to an existing block vary slower than the old
     *     ones (stairs gained {@code minecraft:corner} as their slowest state in 26.50, fire and redstone wire their
     *     connections in 26.60; icicle, new in 26.60, starts like pointed dripstone). The exception seen:
     *     {@code minecraft:trip_wire} put the connections it gained in 26.50 first;</li>
     *     <li>the other keys follow, each time the one that the fewest remaining keys precede in other blocks of the
     *     reference palette (e.g. {@code minecraft:connection_north} before {@code _east} on 58 blocks of 26.50);</li>
     *     <li>without such evidence, keys that only differ by a direction suffix go north, east, south, west, up, down
     *     (every such family in the palette: walls, pale moss carpet, connections, redstone wire), and otherwise the
     *     order of {@code keys} (the Script API's, which matches the palette for only about 64% of blocks).</li>
     * </ol>
     *
     * @param base known order of some of the keys, fastest first; may be null or contain keys not in {@code keys}
     * @param keys every key of the block
     * @param reference fastest first key order of each block of the reference palette
     */
    static List<String> keyOrder(List<String> base, List<String> keys, Map<String, List<String>> reference) {
        List<String> order = new ArrayList<>();
        if (base != null) {
            base.stream().filter(keys::contains).forEach(order::add);
        }
        List<String> remaining = new ArrayList<>(keys);
        remaining.removeAll(order);
        Map<String, Integer> precedes = new java.util.HashMap<>();
        for (List<String> blockKeys : reference.values()) {
            for (int i = 0; i < blockKeys.size(); i++) {
                for (int j = i + 1; j < blockKeys.size(); j++) {
                    precedes.merge(blockKeys.get(i) + '\0' + blockKeys.get(j), 1, Integer::sum);
                }
            }
        }
        while (!remaining.isEmpty()) {
            String best = null;
            int bestBeaten = Integer.MAX_VALUE;
            for (String key : remaining) {
                int beaten = 0;
                for (String other : remaining) {
                    if (!other.equals(key) && precedes.getOrDefault(other + '\0' + key, 0) > precedes.getOrDefault(key + '\0' + other, 0)) {
                        beaten++;
                    }
                }
                if (beaten < bestBeaten || (beaten == bestBeaten && directionBefore(key, best))) {
                    best = key;
                    bestBeaten = beaten;
                }
            }
            order.add(best);
            remaining.remove(best);
        }
        return order;
    }

    /** Whether {@code key} and {@code other} only differ by a direction suffix, {@code key}'s coming first. */
    private static boolean directionBefore(String key, String other) {
        int a = key.lastIndexOf('_');
        int b = other.lastIndexOf('_');
        if (a < 0 || b < 0 || !key.substring(0, a).equals(other.substring(0, b))) {
            return false;
        }
        int x = DIRECTIONS.indexOf(key.substring(a + 1));
        int y = DIRECTIONS.indexOf(other.substring(b + 1));
        return x >= 0 && y >= 0 && x < y;
    }

    /** The key order of the reference block with the most keys that are all keys of a new block, or null. */
    static List<String> similarBlockOrder(Set<String> keys, Map<String, List<String>> reference) {
        List<String> best = null;
        for (List<String> order : reference.values()) {
            if (!order.isEmpty() && keys.containsAll(order) && (best == null || order.size() > best.size())) {
                best = order;
            }
        }
        return best;
    }

    /** The keys of a block's states from the fastest varying to the slowest (the first index at which each changes). */
    static List<String> strideOrder(List<NbtMap> states) {
        Map<String, Integer> firstChange = new LinkedHashMap<>();
        NbtMap first = BlockStates.states(states.get(0));
        for (String key : first.keySet()) {
            for (int i = 1; i < states.size(); i++) {
                if (!java.util.Objects.equals(BlockStates.states(states.get(i)).get(key), first.get(key))) {
                    firstChange.put(key, i);
                    break;
                }
            }
        }
        List<String> keys = new ArrayList<>(firstChange.keySet());
        keys.sort(Comparator.comparingInt(firstChange::get));
        return keys;
    }

    /** {@link #combinations} varies the last key fastest. */
    private static Map<String, List<Object>> slowestFirst(List<String> fastestFirst, Map<String, List<Object>> values) {
        Map<String, List<Object>> ordered = new LinkedHashMap<>();
        for (int i = fastestFirst.size() - 1; i >= 0; i--) {
            ordered.put(fastestFirst.get(i), values.get(fastestFirst.get(i)));
        }
        return ordered;
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

    static List<NbtMap> combinations(String name, Map<String, List<Object>> values) {
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
        // BDS's palette has every state's keys sorted (whatever the enumeration order); creative stacks resolved
        // through this palette carry these instances, so match it byte for byte
        return combinations.stream().map(states -> BlockStates.of(name, NbtMap.fromMap(new java.util.TreeMap<>(states)))).toList();
    }

    /** {@code 1.26.70.23} -> {@code 26.70}, {@code 1.26.63.1} -> {@code 26.60}: hotfixes share their release's name. */
    static String versionName(String bdsVersion) {
        String[] parts = bdsVersion.split("[.-]");
        int offset = parts[0].equals("1") ? 1 : 0;
        return parts[offset] + "." + (Integer.parseInt(parts[offset + 1]) / 10 * 10);
    }

    private Path latestDump() throws IOException {
        if (!Files.isDirectory(bdsData)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(bdsData)) {
            return dirs.map(d -> d.resolve("dump.json.gz")).filter(Files::exists)
                .max(Comparator.comparing(f -> versionKey(f.getParent().getFileName().toString()))).orElse(null);
        }
    }

    /** The newest committed add-on capture directory ({@code <bdsData>/<version>/addon}), null if there is none. */
    private Path latestAddonCapture() throws IOException {
        if (!Files.isDirectory(bdsData)) {
            return null;
        }
        try (Stream<Path> files = Files.list(bdsData)) {
            return files.filter(f -> Files.exists(f.resolve("addon/custom_item_registry.nbt")))
                .max(Comparator.comparing(f -> versionKey(f.getFileName().toString()))).map(f -> f.resolve("addon")).orElse(null);
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
