package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.Nbt;
import org.geysermc.paletter.tools.data.VariantNames;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.bds.ScriptApiDump;
import org.geysermc.paletter.tools.bds.BdsInventory;
import org.geysermc.paletter.tools.bds.BdsRunner;
import org.geysermc.paletter.tools.bds.BdsServer;
import org.geysermc.paletter.tools.bds.CapturePlatform;
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
import org.geysermc.paletter.tools.Versions;
import org.geysermc.paletter.tools.model.Fixtures;
import org.geysermc.paletter.tools.model.Model;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.FixedAddons;
import org.geysermc.paletter.tools.shape.ShapeImport;
import org.geysermc.paletter.tools.shape.ShapeProbe;
import org.geysermc.paletter.tools.model.infer.Coverage;
import org.geysermc.paletter.tools.model.infer.MappingInference;
import org.geysermc.paletter.tools.model.infer.SpecDiff;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockRegistry;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.tools.client.Capture;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.geysermc.paletter.tools.data.RecipeData;
import org.geysermc.paletter.tools.client.CapturedData;
import org.geysermc.paletter.tools.client.LevelDat;
import org.geysermc.paletter.tools.codegen.component.HandTransforms;
import org.geysermc.paletter.tools.codegen.constants.ConstantsGenerator;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.tools.data.BundledFiles;
import org.geysermc.paletter.tools.whitebox.Whitebox;
import org.geysermc.paletter.tools.whitebox.WhiteboxCoverage;
import org.geysermc.paletter.tools.whitebox.WhiteboxRun;
import org.geysermc.paletter.internal.data.VanillaData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code update}: pulls the newest BDS of a channel, runs the Script API dump and the network captures, compares the
 * result with the committed data and either updates the repository or (with {@code --verify-only}) only reports.
 * See docs/data-pipeline.md, "Updating for a new BDS build".
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
    private final boolean keepServer;
    private final int port;
    private final int timeout;
    /**
     * {@code --against <build>}: a history check of an earlier or later build ({@link BuildHistory}). Its captures are
     * compared with the committed captures of that build instead of the newest ones, and only {@code data/bds/<this
     * build>} is written (when anything differs); bundled data, constants, the content log and the model stay as they are.
     */
    private final String against;
    /** In a history check: what this run would write into {@code data/bds/<build>}, written only when it differs. */
    private final Map<Path, byte[]> pending = new LinkedHashMap<>();
    private Path buildDirectory;
    /** The build this run captures ({@link #begin}). */
    private BdsDistribution distribution;
    private Integer protocol;
    private Report report;

    /** The report of an update, in {@code data/bds/<build>/}. */
    public static final String REPORT = "update-report.md";
    /** The exit code of an update that wrote everything but left decisions (the report's TODO list). */
    public static final int EXIT_TODOS = 2;
    /** The file in the work directory that holds the exit code of the run, as plain text. */
    public static final String EXIT_CODE = "exit-code";

    public Update(Path root, Map<String, String> options) {
        // Normalised, so that every path built from it compares with startsWith
        root = root.toAbsolutePath().normalize();
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
        this.keepServer = options.containsKey("keep-server");
        this.port = Integer.parseInt(options.getOrDefault("port", "19260"));
        this.timeout = Integer.parseInt(options.getOrDefault("timeout", "300"));
        this.against = options.containsKey("against") ? Versions.requireBuild(options.get("against")) : null;
        if (against != null && preview) {
            throw new IllegalArgumentException("--against checks release builds only");
        }
    }

    /** The report of the run ({@link #begin}). */
    Report report() {
        return report;
    }

    /** The protocol of the build this run captured, null if no capture worked. */
    public Integer protocol() {
        return protocol;
    }

    /**
     * Whether a history check ({@code --against}) found anything that differs from the build it was compared with: any
     * check that compared something and found it changed. Checks against the bundled data instead are not comparisons
     * of the two builds ({@link #notCompared}).
     */
    public boolean changed() {
        return !report.changes().isEmpty();
    }

    /** What a history check could not compare with the other build: the report's "Not compared" list. */
    public List<String> notCompared() {
        return List.copyOf(report.notCompared);
    }

    /** Whether a step of the run failed. */
    public boolean failed() {
        return report.failed();
    }

    /** @return the process exit code */
    public int run() throws Exception {
        Path cache = options.containsKey("cache") ? Path.of(options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(preview, options.get("version"));
        begin(distribution);
        Path work = distribution.runDirectory(cache, "");
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        report.header.put("Work directory", "`" + work + "` (logs, captures)");

        try {
            if (samePlatform()) {
                pipeline(cache, distribution, work);
            }
        } catch (Exception e) {
            report.fail("pipeline", e);
        }
        if (against == null && !verifyOnly && !report.failed()) {
            // New captures of a bundled build change what the record says per data version
            track(report, root, List.of(resources.resolve(BuildTable.RESOURCE), bdsData.resolve(BuildTable.TABLE)), "build table",
                () -> BuildTable.run(root, false));
        }
        return finish(work);
    }

    /** Starts the report of a run of {@code distribution} and checks where it may write. */
    void begin(BdsDistribution distribution) {
        report = new Report(against != null ? "Paletter build check: BDS " + distribution.version() + " against " + against
            : "Paletter data " + (verifyOnly ? "verification" : "update") + ": BDS " + distribution.version() + (preview ? " (preview)" : ""));
        report.against = against;
        buildDirectory = BdsDistribution.inside(bdsData, distribution.version());
        if (against != null && !Files.isDirectory(BdsDistribution.inside(bdsData, against))) {
            throw new IllegalArgumentException("--against " + against + ": no captures in " + relative(bdsData.resolve(against)));
        }
        if (against != null && !verifyOnly && Files.exists(buildDirectory)) {
            throw new IllegalArgumentException(root.relativize(buildDirectory) + " exists: a history check only adds builds (use --verify-only)");
        }
        report.header.put("Date", LocalDate.now().toString());
        report.header.put("Mode", against != null ? "history check against " + against + (verifyOnly ? " (nothing written)"
            : " (captures written to data/bds/" + distribution.version() + " only if they differ)") : verifyOnly ? "verify only (nothing written)" : "update");
        report.header.put("BDS", distribution.version() + " " + distribution.platform() + " (" + distribution.url() + ")");
        this.distribution = distribution;
    }

    /** Notes the protocols, keeps a history check's captures when they differ and writes the report. */
    private int finish(Path work) throws IOException {
        if (against != null && protocol != null) {
            // Protocols are version stamps to the comparison, but builds of the same data under two protocols are worth saying
            Integer againstProtocol = BuildHistory.protocol(bdsData.resolve(against));
            if (againstProtocol != null && againstProtocol.intValue() != protocol) {
                report.againstNotes.add("this build speaks protocol " + protocol + ", " + against + " protocol " + againstProtocol
                    + "; the comparison leaves protocols aside");
            }
        }

        // A history check keeps the captures of a build only if they differ from the build it was compared with
        boolean kept = against != null && !verifyOnly && !report.failed() && changed();
        if (kept) {
            keep(work);
        }
        // An update keeps its report with the data of its build, so a later run can be diffed against it; an update that
        // changed no data file keeps the committed report, so the report is never the only change. A history check keeps
        // its report with the captures of a kept build
        Path reportFile = options.containsKey("report") ? Path.of(options.get("report"))
            : against != null ? (kept ? buildDirectory.resolve(REPORT) : work.resolve("report.md"))
            : verifyOnly || report.repoChanges.isEmpty() ? work.resolve("report.md") : bdsData.resolve(distribution.version()).resolve(REPORT);
        report.generatedBy = command(distribution);
        report.write(reportFile);
        if (!reportFile.startsWith(work)) {
            report.write(work.resolve("report.md"));
        }
        System.out.println("Report: " + reportFile.toAbsolutePath());
        if (against != null) {
            System.out.println(report.failed() ? "FAILED" : changed() ? "DIFFERS FROM " + against + (kept ? " (kept)" : "") : "IDENTICAL TO " + against);
            return report.failed() || (verifyOnly && changed()) ? 1 : 0;
        }
        System.out.println(report.failed() ? "FAILED" : report.differs() ? (verifyOnly ? "DIFFERENCES (verify failed)" : "UPDATED") : "IDENTICAL");
        return exitCode(report, verifyOnly, work);
    }

    /**
     * The process exit code of a run with {@code report}, also written to {@link #EXIT_CODE} in its work directory:
     * {@code ./gradlew :tools:run} turns every non-zero exit into a build failure with exit code 1, so a caller that
     * needs to tell {@link #EXIT_TODOS} from a failure (the BDS data workflow) reads the file.
     */
    static int exitCode(Report report, boolean verifyOnly, Path work) throws IOException {
        int code = report.failed() || (verifyOnly && report.differs()) ? 1 : !verifyOnly && report.hasTodos() ? EXIT_TODOS : 0;
        Files.writeString(work.resolve(EXIT_CODE), code + "\n");
        return code;
    }

    /**
     * Whether this machine captures on the platform of the committed captures ({@link CapturePlatform}); else the run is
     * a FAIL that says so, and nothing is captured or compared.
     */
    private boolean samePlatform() throws IOException {
        String committed;
        try {
            committed = CapturePlatform.single(bdsData);
        } catch (IllegalStateException e) {
            report.check(Report.Status.FAIL, "Capture platform", e.getMessage());
            return false;
        }
        String mismatch = committed == null ? null : CapturePlatform.mismatch("The committed data (" + Layout.BDS + ")", committed, CapturePlatform.host());
        report.check(mismatch == null ? Report.Status.PASS : Report.Status.FAIL, "Capture platform",
            mismatch == null ? CapturePlatform.host() + (committed == null ? " (no committed captures yet)" : ", like the committed captures") : mismatch);
        return mismatch == null;
    }

    /** The command line of this run, without the options that only say where things go. */
    private String command(BdsDistribution distribution) {
        StringBuilder args = new StringBuilder("update --channel " + (preview ? "preview" : "release") + " --version " + distribution.version());
        for (String option : List.of("against", "verify-only", "name", "protocol", "experiments", "experiment-attribution")) {
            if (options.containsKey(option)) {
                String value = options.get(option);
                args.append(" --").append(option).append(value == null || value.equals("true") ? "" : " " + value);
            }
        }
        return "./gradlew :tools:run --args=\"" + args + "\"";
    }

    private void pipeline(Path cache, BdsDistribution distribution, Path work) throws Exception {
        Path server = distribution.extract(cache, !keepServer);
        report.header.put("Server", "`" + server + "`" + (keepServer ? " (kept)" : " (removed after the run)"));
        BdsDump dump;
        Runs runs = new Runs();
        runs.bdsVersion = distribution.version();
        runs.formatVersion = AddonGenerator.formatVersionFor(FixedAddons.FORMAT_VERSION, distribution.version());
        try {
            // 1. Script API dump
            Path dumpLog = work.resolve("bds-dump.log");
            ScriptApiDump.run(root, server, dumpLog, timeout, port);
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
            protocol = runs.protocol;

            // 3. Voxel shapes: the packet of the vanilla world, of the probe add-on and of the anchors
            if (runs.vanilla != null) {
                voxelShapes(distribution, server, work, runs);
            } else {
                report.check(Report.Status.SKIP, "Voxel shapes", "no capture of this build");
            }
        } finally {
            // With a capture, the spec capture runs on this server too (reuse-server) and removes it
            if (!keepServer && runs.protocol == null) {
                try {
                    BdsDistribution.deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }

        // 4. Compare and update
        scriptApi(distribution, dump, runs);
        inventory(cache, distribution);
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
        if (against != null) {
            // A history check leaves the repository's bundled data, constants and model alone
            if (runs.protocol != null) {
                specCapture(distribution, work, runs);
            }
            return;
        }
        constants(staging);
        staging.apply();
        // After the data is in place: the inference puts each capture into the window of its bundled version
        if (runs.protocol != null) {
            specCapture(distribution, work, runs);
        }
        // After the captures, which the white-box facts are checked against and compared with
        whitebox(cache, distribution);
        if (runs.voxelShapesWritten) {
            // After the data of the version is in place: the import reads the block names of the bundled versions
            ShapeImport.run(root);
        }
    }

    /**
     * Captures the voxel shapes ({@link ShapeProbe}): into {@code data/bds/<build>/voxel-shapes} when this run writes, else
     * into the work directory, and says whether they are the committed ones.
     */
    private void voxelShapes(BdsDistribution distribution, Path server, Path work, Runs runs) {
        Path target = buildDirectory.resolve(ShapeProbe.DIRECTORY);
        // A history check compares with the build it is checked against, apart from the version stamps
        Path committed = against != null ? bdsData.resolve(against).resolve(ShapeProbe.DIRECTORY) : target;
        Path captured = work.resolve(ShapeProbe.DIRECTORY);
        try {
            ShapeProbe.capture(captured, root, distribution.version(), server, work.resolve("voxel-shapes-run"), runs.reference, port, timeout);
            List<String> differing = new ArrayList<>();
            List<Path> files = BuildHistory.files(captured);
            for (Path file : files) {
                Path other = committed.resolve(file.getFileName());
                boolean same = Files.exists(other) && (against != null
                    ? BuildHistory.sameData(file, BuildHistory.formatVersion(captured), other, BuildHistory.formatVersion(committed))
                    : Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(other)));
                if (!same) {
                    differing.add(file.getFileName().toString());
                }
            }
            if (against != null) {
                // Files the earlier build has and this one does not (the probe writes some only when they say something)
                BuildHistory.files(committed).stream().map(f -> f.getFileName().toString())
                    .filter(name -> !Files.exists(captured.resolve(name))).forEach(name -> differing.add(name + " (not captured now)"));
            }
            compared(differing.isEmpty(), "Voxel shapes", differing.isEmpty() ? "`" + relative(committed) + "` has the same captures"
                + (against != null ? " (version stamps aside)" : "") : "`" + relative(committed) + "` is missing or differs: " + String.join(", ", differing));
            if (against != null) {
                for (Path file : files) {
                    stageCopy(target.resolve(file.getFileName()), file, "voxel shape capture " + file.getFileName());
                }
            } else if (!differing.isEmpty()) {
                for (String file : differing) {
                    stageCopy(committed.resolve(file), captured.resolve(file), "voxel shape capture " + file);
                }
                runs.voxelShapesWritten = !verifyOnly;
            }
        } catch (Exception e) {
            report.fail("Voxel shapes", e);
        }
    }

    /**
     * Regenerates the vanilla constants from the staged data (what the repository will have after this run), and lists
     * the constants added and deprecated. Staged with the data, so a data import never leaves stale constants.
     */
    private void constants(Staging staging) throws IOException {
        ConstantsGenerator.Result result;
        try {
            result = ConstantsGenerator.generate(staging.resources, ConstantsGenerator.names(root),
                ConstantsGenerator.readJson(root.resolve(ConstantsGenerator.HISTORY)), ConstantsGenerator.paletterVersion(root),
                ConstantsGenerator.Sources.read(staging.resources, root.resolve(ConstantsGenerator.BDS)));
        } catch (IllegalStateException e) {
            // A name collision or a state that changed its kind needs an entry in the name overrides of ConstantsGenerator
            report.check(Report.Status.FAIL, "Vanilla constants", e.getMessage());
            report.action("add a name override in ConstantsGenerator");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("From " + result.release() + (result.preview() == null ? "" : " and preview " + result.preview()) + ": " + result.counts());
        result.added().forEach(added -> lines.add("added `" + added + "`"));
        result.deprecated().forEach(deprecated -> lines.add("deprecated `" + deprecated + "`"));
        result.tableBlocks().forEach(block -> lines.add("lookup table (states are not a full product in a stride order): " + block));
        report.check(result.added().isEmpty() && result.deprecated().isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Vanilla constants",
            result.added().size() + " added, " + result.deprecated().size() + " deprecated, " + result.tableBlocks().size() + " blocks with a lookup table");
        report.evidence("Constants", lines);
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
        /** The format version of the add-on packs of this build: the model's, or the build's own if it is older. */
        String formatVersion;
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
        /** Whether this run replaced the voxel shape captures of its build: the bundled voxel shapes follow. */
        boolean voxelShapesWritten;
        final ExperimentRun experiments = new ExperimentRun();
    }

    /** What the experiment step found; see docs/data-pipeline.md, "Experiments". */
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
        /** Toggles start game reports for a world without any: part of vanilla, never attributed. */
        List<String> alwaysOn = List.of();
        Map<String, String> codes = new TreeMap<>();
        ExperimentAttribution.Diff all;
        Map<String, ExperimentAttribution.Diff> each = new TreeMap<>();
        Map<String, ExperimentContent> content = new TreeMap<>();
        /** Per toggle the recipes it adds, removes or changes, by section; only toggles that touch recipes. */
        Map<String, Map<String, List<String>>> recipes = new TreeMap<>();
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
            Capture.Reachable reachable = Capture.reachable(bds, address, 20);
            boolean raknet = reachable != null && !reachable.netherNet();
            if (reachable == null) {
                network = bds.lines(line -> line.contains("Accepting clients") || line.contains("supported, port")
                    || line.contains("NetherNet"));
            } else {
                runs.netherNet = reachable.netherNet();
                if (reachable.protocol() != 0) {
                    runs.protocol = reachable.protocol();
                    report.header.put("Protocol", runs.protocol + (raknet ? " (RakNet pong: " : " (NetherNet LAN discovery: ")
                        + reachable.status() + ")");
                } else {
                    runs.protocol = Integer.parseInt(options.getOrDefault("protocol", "0"));
                    if (runs.protocol == 0) {
                        throw new IOException("The server's status carries no protocol version; pass --protocol");
                    }
                    report.header.put("Protocol", runs.protocol + " (from --protocol)");
                }
                report.header.put("Transport", raknet ? "RakNet" : "NetherNet (HTTP signaling on server-port, WebRTC; "
                    + "self signed identity bound to the login chain)");
                runs.existing = against != null ? containingVersion(runs.protocol) : bundledName(runs.protocol);
                runs.reference = reference(runs.existing, dump, runs);
                Capture.Summary summary = capture(address, runs, work.resolve("vanilla"), null, "vanilla capture");
                runs.vanilla = work.resolve("vanilla");
                // Toggles start game reports for a world without any are part of vanilla (1.26.70 previews:
                // data_driven_vanilla_blocks_and_items), not toggles to attribute
                runs.experiments.alwaysOn = summary.enabledExperiments();
                alwaysOnToggles = runs.experiments.alwaysOn;
                if (!alwaysOnToggles.isEmpty()) {
                    report.check(Report.Status.INFO, "Experiments: always on", "start game of a world without experiments reports "
                        + alwaysOnToggles + ": treated as vanilla");
                }
                hashCheck("vanilla", summary);
            }
        }
        if (network != null) {
            report.check(Report.Status.FAIL, "Network capture", "BDS answers neither a RakNet ping nor NetherNet signaling on port " + port);
            report.evidence("Network lines of the server", List.of("```", String.join("\n", network), "```"));
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
            report.action("check the toggles: the add-on world used " + runs.experiments.current + " (" + runs.experiments.source + ")");
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
        Path creativePack = work.resolve("creative-pack");
        FixedAddons.writeCreative(creativePack, runs.formatVersion);
        freshWorld(server, CREATIVE_WORLD, creativePack);
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
        // A history check numbers against the bundled data version of its window, which may lack states of this build and
        // of the build it is checked against alike: the states themselves are compared through the Script API dump
        ordering.failures.forEach(failure -> {
            if (against != null) {
                report.check(Report.Status.INFO, "Block numbering", failure + " (against bundled " + runs.reference.version().name()
                    + "; block states are compared through the Script API dump)");
                report.notCompared.add("Block numbering: " + failure + " (against bundled " + runs.reference.version().name()
                    + "); the block states themselves are compared through the Script API dump");
            } else {
                report.check(Report.Status.FAIL, "Block numbering", failure);
            }
        });
        if (!ordering.withinBlockOrder.isEmpty()) {
            report.todo("Block palette: " + ordering.withinBlockOrder.size() + " creative states of generated blocks sit elsewhere "
                + "within their block than generated; fix the state order of those blocks before importing", "States",
                ordering.withinBlockOrder.stream().map(state -> "- " + state).toList());
        }
    }

    /**
     * Creates the add-on world (packs installed, experiments on) and returns the content log of a start with the
     * experiments enabled: the definitions BDS rejects. Leaves the server stopped.
     */
    private List<String> addonWorldContentLog(Path server, Path work, Map<String, String> properties, Runs runs) throws Exception {
        Path addon = work.resolve("capture-pack");
        FixedAddons.writeCapture(addon, runs.formatVersion);
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
        report.check(Report.Status.PASS, what, (summary.blockNetworkIdsHashed() ? "hashed" : "sequential") + " block ids; protocol "
            + summary.protocol() + (summary.nativeCodec() ? "" : ", decoded with the " + summary.codecProtocol() + " codec"));
        if (!summary.nativeCodec() && !codecReported) {
            // Once: every capture of the run decodes with the same codec
            codecReported = true;
            report.check(Report.Status.DIFF, "Protocol codec", "no codec for " + summary.protocol() + " in the protocol library, "
                + "captures decoded with the " + summary.codecProtocol() + " codec: update it and rerun");
        }
        return summary;
    }

    /** Whether the missing protocol codec is reported already. */
    private boolean codecReported;

    private void hashCheck(String label, Capture.Summary summary) {
        if (summary.unresolvedHashes() == 0) {
            report.check(Report.Status.PASS, "Block hashes (" + label + ")", "all " + summary.resolvedHashes()
                + " creative block ids are FNV-1a 32 hashes of palette states");
        } else if (against != null) {
            // A history check: the bundled data version of the window lacks states this build sends (and maybe the build it
            // is checked against too); the states themselves are compared through the Script API dump
            report.check(Report.Status.INFO, "Block hashes (" + label + ")", summary.unresolvedHashes() + " creative block ids match no "
                + "state of the reference palette (" + summary.resolvedHashes() + " do): this build has states the bundled data version "
                + "lacks; block states are compared through the Script API dump");
            report.notCompared.add("Block hashes (" + label + "): " + summary.unresolvedHashes() + " creative block ids match no state "
                + "of the bundled data version; the block states themselves are compared through the Script API dump");
        } else {
            report.check(Report.Status.FAIL, "Block hashes (" + label + ")", summary.unresolvedHashes() + " creative block ids match no "
                + "palette state (" + summary.resolvedHashes() + " do): the palette lacks states this version has");
            report.action("add those states to the palette (`creativeBlockIdsUnresolved` in capture.json)");
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
            // An always-on toggle has no log code
            long withCode = result.accepted.stream().filter(t -> !result.alwaysOn.contains(t)).count();
            boolean codesMatch = result.activeCodes != null && result.activeCodes.size() == withCode;
            compared(codesMatch, "Experiments: discovery",
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
        List<String> streamed = all.enabledExperiments().stream().filter(t -> !result.alwaysOn.contains(t)).toList();
        if (manual) {
            appliedCheck("--experiments", toggles, all);
        } else {
            result.source = "discovered";
            result.current = streamed;
            result.retired = toggles.stream().filter(t -> !streamed.contains(t) && !result.alwaysOn.contains(t)).toList();
            retiredToggles = result.retired;
            List<String> notAccepted = streamed.stream().filter(t -> !toggles.contains(t)).toList();
            boolean uiMatch = streamed.size() == result.uiKeys.size();
            compared(notAccepted.isEmpty() && uiMatch, "Experiments: current toggles",
                streamed.size() + " streamed in start game " + streamed + ", " + result.retired.size() + " retired (kept, not streamed); "
                    + result.uiKeys.size() + " `" + ExperimentDiscovery.UI_PREFIX + "*` UI keys in the binary"
                    + (uiMatch ? "" : ": a current toggle may be missing from the candidates; pass it with --experiments or add it to DEFAULT_EXPERIMENTS")
                    + (notAccepted.isEmpty() ? "" : "; streamed but not kept in level.dat: " + notAccepted));
        }
        List<String> packStack = all.packStackExperiments().stream().filter(e -> e.endsWith("=true"))
            .map(e -> e.substring(0, e.length() - 5)).filter(t -> !result.alwaysOn.contains(t)).sorted().toList();
        compared(packStack.equals(streamed), "Experiments: resource pack stack",
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
                        recipes(result, toggle, runs.vanilla, out);
                    }
                    result.unexplained = ExperimentAttribution.unexplained(result.all, result.each);
                } else if (result.current.size() == 1) {
                    result.content.put(result.current.getFirst(), result.all.content(creativeBefore));
                    recipes(result, result.current.getFirst(), runs.vanilla, work.resolve("experiments/all"));
                }
            } finally {
                // The dump world back without experiments
                LevelDat.setExperiments(server.resolve("worlds").resolve(BdsRunner.WORLD).resolve("level.dat"), List.of());
            }
        }
        result.millis = (System.nanoTime() - started) / 1_000_000;
    }

    private static void recipes(ExperimentRun result, String toggle, Path base, Path with) throws IOException {
        Map<String, List<String>> changes = ExperimentAttribution.recipes(base, with);
        if (!changes.isEmpty()) {
            result.recipes.put(toggle, changes);
        }
    }

    /** Whether start game reported exactly the requested toggles; retired ones are kept but never streamed. */
    private void appliedCheck(String what, List<String> requested, Capture.Summary summary) {
        List<String> echoed = summary.enabledExperiments().stream().filter(t -> !alwaysOnToggles.contains(t)).toList();
        List<String> retired = retiredToggles;
        List<String> expected = requested.stream().filter(t -> !retired.contains(t)).sorted().toList();
        boolean ok = echoed.equals(expected);
        report.check(ok ? Report.Status.PASS : Report.Status.FAIL, "Experiments applied (" + what + ")",
            ok ? "start game reports " + echoed : "requested " + expected + ", start game reports " + echoed
                + (requested.stream().anyMatch(t -> !echoed.contains(t)) ? " (BDS dropped or retired toggles)" : ""));
    }

    /** Retired toggles of this run: BDS keeps them in level.dat but never streams them. */
    private List<String> retiredToggles = List.of();
    /** Always-on toggles of this run (see {@link ExperimentRun#alwaysOn}). */
    private List<String> alwaysOnToggles = List.of();

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
        for (String toggle : result.alwaysOn) {
            lines.add("| `" + toggle + "` | always on (vanilla) | | | | | | |");
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

        if (!result.source.equals("discovered")) {
            return;
        }
        byte[] record = record(distribution, runs);
        if (against != null) {
            Path earlier = bdsData.resolve(against).resolve("experiments.json");
            boolean same = Files.exists(earlier) && BuildHistory.sameData(record, Files.readAllBytes(earlier));
            compared(same, "Experiment record vs " + against, same ? "same toggles and content (version stamps aside)"
                : "`" + root.relativize(earlier) + "` is missing or lists other toggles or content");
        }
        stage(bdsData.resolve(distribution.version()).resolve("experiments.json"), record, "experiment record");
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
            notCompared(same, "Experiment marks vs bundled " + runs.bundledName,
                same ? "identical" : "bundled " + new TreeSet<>(data.experiments().keySet()) + ", captured " + marks.keySet(),
                "the experiment content is compared build to build in the experiment record");
            Files.write(file, write(data.withExperiments(marks)));
        }
    }

    /** Which toggle each experiment named in the creator docs ({@code data/model/components.json}) is. */
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
        all.addAll(result.alwaysOn);
        all.stream().sorted().forEach(toggle -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("status", result.current.contains(toggle) ? "current" : result.alwaysOn.contains(toggle) ? "always-on" : "retired");
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
                Map<String, List<String>> recipes = result.recipes.get(toggle);
                if (recipes != null) {
                    JsonObject sections = new JsonObject();
                    recipes.forEach((section, entries) -> sections.add(section, array(entries)));
                    content.add("recipes", sections);
                }
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

    /** The Script API view against the committed dump of this build, or of the build before ({@link #baseline}). */
    private void scriptApi(BdsDistribution distribution, BdsDump dump, Runs runs) throws IOException {
        // A history check compares with the build it is checked against
        String baselineBuild = against != null ? against : baseline(distribution.version(), committedBuilds("dump.json.gz"));
        Path baselineFile = baselineBuild == null ? null : bdsData.resolve(baselineBuild).resolve("dump.json.gz");
        Path target = bdsData.resolve(distribution.version()).resolve("dump.json.gz");
        if (baselineFile == null || !Files.exists(baselineFile)) {
            compared(false, "Script API vs baseline", "no baseline dump in data/bds/" + (against != null ? against : "*") + "/dump.json.gz");
        } else {
            String baselineVersion = baselineBuild;
            BdsDump baseline = BdsDump.parse(readGzipText(baselineFile), baselineVersion);
            Diff diff = Diff.scriptApi(baseline, dump);
            compared(diff.identical(), "Script API vs baseline " + baselineVersion, diff.identical() ? "identical" : diff.count() + " differences");
            report.diff("Script API: BDS " + distribution.version() + " vs baseline " + baselineVersion, diff);
            if (runs.vanilla == null) {
                fallbackTodos(diff.lines("new blocks"), diff.lines("new items"), "Script API");
            }
        }
        stage(target, gzip(runs.dumpJson), "Script API baseline " + distribution.version());
    }

    /**
     * The inventory of the build's Linux binary ({@link BdsInventory}: block and item components, Script API members) and what
     * changed against the inventory of the previous build: new BDS properties show up here before anything reads them.
     */
    private void inventory(Path cache, BdsDistribution distribution) {
        try {
            JsonObject inventory = BdsInventory.read(root, distribution.version(), distribution.preview(), cache);
            if (inventory.getAsJsonObject("blockComponents").isEmpty()) {
                // The inventory reads RTTI type names; the Linux binaries of the 1.26.70 previews have none
                report.check(Report.Status.SKIP, "BDS inventory", "the Linux bedrock_server of this build has no RTTI type names "
                    + "(no Block*Description, no Scripting::Reflection bindings): nothing to inventory");
                return;
            }
            // A history check compares with the inventory of the build it is checked against
            String previousBuild = against != null ? against : baseline(distribution.version(), committedBuilds(BdsInventory.FILE));
            Path previous = previousBuild == null ? null : bdsData.resolve(previousBuild).resolve(BdsInventory.FILE);
            if (previous == null || !Files.exists(previous)) {
                report.check(Report.Status.INFO, "BDS inventory", against != null ? "not compared: " + against + " has no " + BdsInventory.FILE
                    : "no inventory of an older build to diff against");
                if (against != null) {
                    report.notCompared.add("BDS inventory: " + against + " has no " + BdsInventory.FILE);
                }
            } else {
                String before = previousBuild;
                List<String> diff = BdsInventory.diff(BdsInventory.parse(previous), inventory);
                // Only getters the behaviour dump reads need a decision; the rest of the inventory is for reference. In a
                // history check any difference is one between the two builds
                String detail = diff.isEmpty() ? "no new or removed properties" : diff.size() + " new or removed properties";
                if (against != null) {
                    compared(diff.isEmpty(), "BDS inventory vs " + before, detail);
                } else {
                    report.check(diff.isEmpty() ? Report.Status.PASS : Report.Status.INFO, "BDS inventory vs " + before, detail);
                }
                if (!diff.isEmpty()) {
                    report.evidence("New or removed properties", diff.stream().map(line -> "- `" + line + "`").toList());
                }
                List<String> added = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                for (String line : diff) {
                    String getter = behaviourGetter(line);
                    if (getter != null) {
                        (line.startsWith("+") ? added : removed).add(getter);
                    }
                }
                if (!added.isEmpty() || !removed.isEmpty()) {
                    compared(false, "Script API getters", (added.isEmpty() ? "" : added.size() + " new")
                        + (added.isEmpty() || removed.isEmpty() ? "" : ", ") + (removed.isEmpty() ? "" : removed.size() + " removed")
                        + " value getters of blocks, items or their components (the behaviour dump reads them)");
                    List<String> lines = new ArrayList<>();
                    added.forEach(getter -> lines.add("- new `" + getter + "`"));
                    removed.forEach(getter -> lines.add("- removed `" + getter + "`"));
                    report.action("give each a behaviour key or an ignored entry", "Getters", lines);
                }
            }
            stage(bdsData.resolve(distribution.version()).resolve(BdsInventory.FILE),
                BdsInventory.json(inventory).getBytes(StandardCharsets.UTF_8), "BDS inventory " + distribution.version());
        } catch (org.geysermc.paletter.tools.Http.NetworkException e) {
            if (e.status() != 0 && !org.geysermc.paletter.tools.Http.retried(e.status())) {
                // An answer a retry would not change (404 above all): the Linux build is not where it should be, which is a failure
                report.fail("BDS inventory", e);
                return;
            }
            // The Linux zip is only read for the inventory: without the network (or with a server error or rate limit that
            // outlasted the retries) there is nothing to inventory, nothing failed
            report.check(Report.Status.SKIP, "BDS inventory", "the Linux build could not be downloaded: " + e.getMessage());
        } catch (Exception e) {
            report.fail("BDS inventory", e);
        }
    }

    /**
     * What the code of the build's Linux binary can send ({@code whitebox}) and which of it no capture shows
     * ({@code whitebox-coverage}). Advisory: nothing else of the update depends on it, so whatever goes wrong here (an
     * exception, a failed self-check) is a FAIL row and the facts are not written. New gaps are listed under "Needs attention".
     */
    private void whitebox(Path cache, BdsDistribution distribution) {
        String build = distribution.version();
        try {
            WhiteboxRun run = WhiteboxRun.of(BdsDistribution.resolve(true, preview, build).zip(cache), root, build);
            if (!run.problems().isEmpty()) {
                report.check(Report.Status.FAIL, "White-box", "white-box unavailable for this build: " + String.join("; ", run.problems()));
                return;
            }
            Path data = bdsData.resolve(build);
            stage(data.resolve(Whitebox.FILE), Whitebox.json(run.facts()).getBytes(StandardCharsets.UTF_8), "white-box facts " + build);
            List<String> changes = run.changes().stream().filter(line -> line.startsWith("!")).toList();
            report.check(Report.Status.INFO, "White-box", run.changes().isEmpty() ? "no changes against the build before"
                : changes.size() + " changes of what the code sends, " + (run.changes().size() - changes.size()) + " of how it writes it");
            if (!changes.isEmpty()) {
                report.action("capture the components that changed again", "Changes", changes);
            }
            List<String> rewritten = run.changes().stream().filter(line -> line.startsWith("~")).toList();
            if (!rewritten.isEmpty()) {
                report.evidence("Writers whose code changed", rewritten);
            }
            if (run.coverage() == null) {
                report.check(Report.Status.SKIP, "White-box coverage", "no captures of this build");
                return;
            }
            stage(data.resolve(WhiteboxCoverage.FILE), run.coverage().getBytes(StandardCharsets.UTF_8), "white-box coverage " + build);
            report.check(Report.Status.INFO, "White-box coverage", run.gaps().size() + " things the code can send that no capture shows, "
                + run.newGaps().size() + " new");
            if (!run.newGaps().isEmpty()) {
                report.action("capture them with a spec variant, a format sweep entry or a probe add-on", "New gaps",
                    run.newGaps().stream().map(gap -> "- " + gap.subject() + ": " + gap.missing()).toList());
            }
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            // Also what a binary the analysis does not expect can run into: deep listings, a large image
            report.check(Report.Status.FAIL, "White-box", "white-box unavailable for this build: " + e);
        }
    }

    /** Script API classes whose getters the behaviour dump reads: the block, its permutation and type, the item stack. */
    private static final Set<String> BEHAVIOUR_TYPES = Set.of("Block", "BlockPermutation", "BlockType", "ItemStack");
    /** Component classes of runtime state, not of the block or item type or state: containers, recipes, stored entities. */
    private static final java.util.regex.Pattern RUNTIME_TYPES = java.util.regex.Pattern.compile("Recipe|Crafting|Inventory|Container"
        + "|EntityStorage|DynamicProperties|ItemActor");
    /**
     * Members that are no value of the type or state: native fields and ids, legacy {@code _010} bindings, live objects
     * (dimension, location, entities, players, items, blocks, permutations, the type object) and the getters that take an
     * argument (the inventory has no signatures, so these are named).
     */
    private static final java.util.regex.Pattern NOT_A_VALUE = java.util.regex.Pattern.compile("^m[A-Z]|^_?isValid|Ids?(\\(\\))?$"
        + "|_?V?010|Dimension|Location|Entit|Player|Container|ItemStack|Item\\(\\)$|Record|Context|Blocks|BlockParts|Permutation"
        + "|DynamicProperty|^(get)?Type$|^getState\\(|^getProperty|^getEnchantment\\(|^getDamageChance\\(|^getText\\(|^getRawText\\("
        + "|^getTextDyeColor\\(|^getInputSlotEnabled\\(");

    /**
     * The getter of a {@link BdsInventory#diff} line that the behaviour dump ({@code data/addons/behaviour-dump}) reads or
     * would read, e.g. {@code BlockPermutation.getAllStates()} for {@code + Script API ScriptModuleMinecraft
     * ScriptBlockPermutation getAllStates()}: a getter of {@link #BEHAVIOUR_TYPES} or of a block or item component that
     * returns a value of the type or state ({@link #RUNTIME_TYPES} and {@link #NOT_A_VALUE} are left out). A new one needs
     * a behaviour key or an ignored entry, or the key table generation fails.
     *
     * @return {@code Type.member}, or null for any other line
     */
    static String behaviourGetter(String line) {
        String[] parts = line.split(" ", 6);
        if (parts.length < 6 || !parts[1].equals("Script") || !parts[2].equals("API") || !parts[4].startsWith("Script")) {
            return null;
        }
        String type = parts[4].substring("Script".length());
        String member = parts[5];
        boolean component = (type.startsWith("Block") || type.startsWith("Item") || type.endsWith("ItemComponent"))
            && type.contains("Component") && !type.contains("CustomComponent") && !type.endsWith("Registry");
        String name = member.startsWith("get ") ? member.substring(4) : member;
        if (!(BEHAVIOUR_TYPES.contains(type) || component) || !member.startsWith("get") || RUNTIME_TYPES.matcher(type).find()
            || NOT_A_VALUE.matcher(name).find()) {
            return null;
        }
        return type + "." + name;
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
            report.evidence("Content that falls back to unknown (" + source + ")", lines);
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
        report.header.put("Bundled version", runs.existing != null ? name + " (existing, same protocol)" : name + " (new protocol)");
        runs.bundledName = name;
        // The capture in the bundled format, imported into a scratch directory
        Path scratch = staging.directory.resolveSibling("imported");
        Files.createDirectories(scratch);
        new Importer(scratch).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog);
        VanillaData captured = read(scratch.resolve(name + ".nbt"));
        runs.imported = scratch.resolve(name + ".nbt");
        Map<String, List<VariantNames.Entry>> variantNames = VariantNames.read(root.resolve(VariantNames.FILE));
        try {
            recipes(distribution, runs, captured);
        } catch (Exception e) {
            // The recipe data never blocks the import
            report.check(Report.Status.DIFF, "Recipes", "not compared: " + e);
        }
        // The recipe data of the capture, committed with the build's other vanilla captures
        for (String file : List.of(RecipeCapture.CRAFTING, RecipeCapture.TRIM)) {
            if (Files.exists(runs.vanilla.resolve(file))) {
                stageCopy(bdsData.resolve(distribution.version()).resolve("vanilla").resolve(file), runs.vanilla.resolve(file), "recipe capture " + file);
            }
        }

        if (runs.existing != null) {
            VanillaData bundled = read(resources.resolve(name + ".nbt"));
            Diff network = Diff.vanilla(bundled, captured, true, false);
            compared(network.identical(), "Network data vs bundled " + name, network.identical()
                ? "identical (items, runtime ids, components, data driven blocks, creative layout)" : network.count() + " differences");
            report.diff("Network data: BDS " + distribution.version() + " vs bundled " + name, network);
            if (!network.identical()) {
                report.action("same protocol, different data: decide whether to re-import " + name + " (`import --source "
                    + runs.vanilla + " --name " + name + " --protocol " + runs.protocol + " --bds-dump " + runs.dumpLog + "`)");
            }
            // Refresh what comes from the Script API (item tags, block defaults); keep the network data
            VanillaData refreshed = new VanillaData(bundled.version(), bundled.blockStates(), bundled.items(), bundled.dataDrivenBlocks(),
                bundled.creative(), captured.itemTags(), bundled.fallbacks(), captured.blockDefaults(), bundled.experiments(),
                captured.blockItems());
            Diff bds = Diff.vanilla(bundled, refreshed, false, true);
            // A history check compares the Script API (where these come from) with the earlier build instead
            notCompared(bds.identical(), "Item tags and block defaults vs bundled " + name, bds.identical() ? "identical" : bds.count() + " differences",
                "item tags and block defaults are compared build to build through the Script API dump");
            report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + name, bds);
            Files.write(staging.resources.resolve(name + ".nbt"), write(refreshed));
            updateVariantNames(variantNames, name, dump, refreshed);
        } else if (against != null) {
            // Older than every bundled data version: nothing to compare the network data with
            compared(false, "Network data", "protocol " + runs.protocol + " is older than every bundled data version; "
                + "this build needs a data version of its own to be served");
        } else {
            Importer.IndexEntry latest = Importer.readIndex(resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
            VanillaData previous = read(resources.resolve(latest.name() + ".nbt"));
            Diff diff = Diff.vanilla(previous, captured, true, true);
            report.check(Report.Status.DIFF, "New protocol " + runs.protocol, "not bundled yet; " + diff.count() + " differences to " + latest.name());
            report.diff("What changed: " + name + " (BDS " + distribution.version() + ") vs bundled " + latest.name(), diff);
            fallbackTodos(diff.lines("new blocks"), diff.lines("new items"), "network");
            dropPreviews(distribution, name, runs.protocol, staging, variantNames);
            new Importer(staging.resources).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog, preview);
            report.check(Report.Status.INFO, "Import", "imported " + name + " (" + runs.protocol + ")" + (preview ? " as a preview version" : ""));
            // Content older versions lack is listed above (INFO); there are no bundled fallbacks to check
            new Checker(staging.resources).problems(preview);
            updateVariantNames(variantNames, name, dump, captured);
        }
        stage(root.resolve(VariantNames.FILE), VariantNames.render(variantNames).getBytes(StandardCharsets.UTF_8), "item variant names");
    }

    /** The variant names of a version from the dump; an incomplete dump is a FAIL and leaves the names as they are. */
    private void updateVariantNames(Map<String, List<VariantNames.Entry>> variantNames, String name, BdsDump dump, VanillaData data) {
        try {
            VariantNames.update(variantNames, name, dump, data);
        } catch (IllegalStateException e) {
            report.check(Report.Status.FAIL, "Item variant names", e.getMessage() + "; the names of " + name + " stay as they are");
        }
    }

    /**
     * The recipe capture against the committed one of this build, or of the build before ({@link #baseline}), and the
     * assumptions the recipe data rests on (items and tags exist in the imported palette, results have their block state,
     * net ids are 1..n). Runs before the capture is committed, so it still sees the old one. Only ever INFO, PASS or DIFF:
     * it must not block an import.
     */
    private void recipes(BdsDistribution distribution, Runs runs, VanillaData captured) throws IOException {
        if (!Files.exists(runs.vanilla.resolve(RecipeCapture.CRAFTING))) {
            report.check(Report.Status.INFO, "Recipes", "no crafting data in the capture");
            return;
        }
        RecipeCapture.Recipes recipes = RecipeCapture.read(runs.vanilla);
        String previousBuild = baseline(distribution.version(), committedBuilds("vanilla/" + RecipeCapture.CRAFTING));
        if (previousBuild == null) {
            report.check(Report.Status.INFO, "Recipes", "no committed recipe capture to compare with");
        } else {
            Diff diff = Diff.recipes(RecipeCapture.read(bdsData.resolve(previousBuild).resolve("vanilla")), recipes);
            String against = previousBuild.equals(distribution.version()) ? "the committed capture of " + previousBuild : previousBuild;
            report.check(diff.identical() ? Report.Status.PASS : Report.Status.DIFF, "Recipes vs " + against,
                diff.identical() ? "identical" : diff.count() + " differences");
            report.diff("Recipes: BDS " + distribution.version() + " vs " + against, diff);
        }
        Set<String> items = new HashSet<>();
        captured.items().forEach(item -> items.add(item.identifier()));
        assumption("Recipe items exist in the palette", RecipeData.missingItems(recipes, items));
        assumption("Recipe tags have members", RecipeData.emptyTags(recipes, captured.itemTags()));
        assumption("Recipe results have their block state", RecipeData.missingBlockStates(recipes));
        assumption("Recipe net ids are 1..n", RecipeData.netIdProblems(recipes));
    }

    private void assumption(String name, List<String> problems) {
        report.check(problems.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, name, problems.isEmpty() ? "holds" : problems.size() + " problems");
        if (!problems.isEmpty()) {
            report.evidence("Problems", problems.stream().map(problem -> "- " + problem).toList());
        }
    }

    /**
     * Only the newest preview is bundled, and only until its release: importing a version drops every bundled preview of
     * the same name or an older protocol, with its BDS captures ({@code data/bds/<build>} of that name, but not this build).
     */
    private void dropPreviews(BdsDistribution distribution, String name, int protocol, Staging staging,
                              Map<String, List<VariantNames.Entry>> variantNames) throws IOException {
        for (Importer.IndexEntry entry : Importer.readIndex(staging.resources)) {
            boolean sameName = entry.name().equals(name);
            // A preview imported again just replaces its own data
            if (!entry.preview() || (preview && sameName) || (!sameName && entry.protocol() > protocol)) {
                continue;
            }
            Importer.remove(staging.resources, entry.name());
            variantNames.remove(entry.name());
            report.check(Report.Status.INFO, "Import", "dropped preview " + entry.name() + " (" + entry.protocol() + "): "
                + (preview ? "a newer preview replaces it" : "its release is imported"));
            try (Stream<Path> dirs = Files.list(bdsData)) {
                for (Path dir : dirs.filter(d -> d.getFileName().toString().matches("\\d+\\..*")).toList()) {
                    String build = dir.getFileName().toString();
                    if (!build.equals(distribution.version()) && versionName(build).equals(entry.name())) {
                        report.repoChanges.add((verifyOnly ? "would " : "") + "remove `" + root.relativize(dir).toString().replace('\\', '/')
                            + "` (captures of the dropped preview)");
                        if (!verifyOnly) {
                            BdsDistribution.deleteRecursively(dir);
                        }
                    }
                }
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
     * creative order probe (docs/wire-notes.md). A difference means BDS changed where it puts custom content.
     */
    private void creativeOrder(Runs runs) throws IOException {
        if (against != null && runs.addon != null) {
            addonCreative(runs);
        }
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
            CreativeOrderCheck.Result result = CreativeOrderCheck.check(runs.protocol,
                directory.resolve("creative_items.json"), custom.resolve("custom_item_registry.nbt"), custom.resolve("custom_block_properties.nbt"));
            if (result.matches()) {
                report.check(Report.Status.PASS, "Creative order (" + capture[0] + ")", "CreativeOrdering.BDS reproduces the "
                    + "creative inventory BDS sent (" + result.customStacks() + " custom stacks)");
            } else if (against != null && capture[0].equals("add-on")) {
                // The add-on world runs every experiment, whose vanilla stacks the bundled layout may lack; a history check
                // compares this creative inventory with the earlier build's instead (addonCreative)
                report.check(Report.Status.INFO, "Creative order (" + capture[0] + ")", "BDS places custom content differently "
                    + "from CreativeOrdering.BDS on the bundled layout: " + result.differences().size() + "+ differences; the "
                    + "creative inventory is compared with " + against + "'s");
                report.notCompared.add("Creative order (" + capture[0] + "): CreativeOrdering.BDS on the bundled layout places custom "
                    + "content differently; the add-on world's creative inventory itself is compared build to build");
            } else {
                compared(false, "Creative order (" + capture[0] + ")", "BDS places custom content differently "
                    + "from CreativeOrdering.BDS: " + result.differences().size() + "+ differences");
                List<String> lines = new ArrayList<>();
                result.differences().forEach(difference -> lines.add("- `" + difference.replace("`", "'") + "`"));
                report.evidence("Differences", lines);
                report.action("update CreativeOrdering.BDS and the committed probe captures (docs/wire-notes.md)");
            }
        }
    }

    /**
     * History check: the creative inventory of the add-on world (vanilla with every experiment, and the add-on's content)
     * against the earlier build's, kept as {@code addon/creative_items.json.gz}.
     */
    private void addonCreative(Runs runs) throws IOException {
        byte[] creative = gzip(JsonParser.parseString(Files.readString(runs.addon.resolve("creative_items.json"))).toString());
        stage(buildDirectory.resolve("addon/creative_items.json.gz"), creative, "add-on creative inventory");
        Path earlier = bdsData.resolve(against).resolve("addon/creative_items.json.gz");
        if (!Files.exists(earlier)) {
            report.check(Report.Status.INFO, "Add-on creative inventory", "not compared: " + against + " has no `addon/creative_items.json.gz`");
            report.notCompared.add("Add-on creative inventory: " + against + " has no `addon/creative_items.json.gz`");
            return;
        }
        compared(BuildHistory.sameData(creative, Files.readAllBytes(earlier)), "Add-on creative inventory",
            "the add-on world's creative inventory against " + against + "'s");
    }

    private void contentLog(Runs runs) throws IOException {
        if (runs.addonContentLog == null) {
            return;
        }
        Path file = root.resolve(Layout.CAPTURE_ADDON + "/content-log.txt");
        String current = String.join("\n", runs.addonContentLog) + "\n";
        if (against != null) {
            // A kept build keeps its log with its add-on capture; the one in data/addons/capture is the newest build's
            stage(buildDirectory.resolve("addon/content-log.txt"), current.getBytes(StandardCharsets.UTF_8), "add-on content log");
            file = bdsData.resolve(against).resolve("addon/content-log.txt");
            if (!Files.exists(file)) {
                report.check(Report.Status.INFO, "Add-on content log", "not compared: " + against + " has no add-on content log "
                    + "(what BDS rejects shows in the add-on capture, compared below)");
                report.notCompared.add("Add-on content log: " + against + " has none; what BDS rejects shows in the add-on capture, compared");
                return;
            }
        }
        String committed = Files.exists(file) ? Files.readString(file).replace("\r\n", "\n") : "";
        Set<String> before = new TreeSet<>(committed.lines().filter(l -> !l.isBlank()).toList());
        Set<String> after = new TreeSet<>(runs.addonContentLog);
        List<String> lines = new ArrayList<>();
        Diff.difference(after, before).forEach(line -> lines.add("- new: `" + line.replace("`", "'") + "`"));
        Diff.difference(before, after).forEach(line -> lines.add("- gone: `" + line.replace("`", "'") + "`"));
        compared(lines.isEmpty(), "Add-on content log",
            lines.isEmpty() ? runs.addonContentLog.size() + " known rejections/warnings, unchanged"
                : lines.size() + " changes in what BDS rejects or warns about in data/addons/capture");
        if (!lines.isEmpty()) {
            report.evidence("Content log changes", lines);
        }
        if (against == null && !olderThanNewestBundled(runs)) {
            // The committed log is the newest build's: a backfill of an older build does not replace it
            stage(file, current.getBytes(StandardCharsets.UTF_8), "add-on content log");
        }
    }

    /** Component wire shapes: the new add-on and vanilla captures against the committed ones. */
    private void shapes(BdsDistribution distribution, Runs runs) throws Exception {
        // A history check: the add-on capture of the build it is checked against, or of the newest build before it
        String previousBuild = baseline(against != null ? against : distribution.version(), committedBuilds("addon/custom_item_registry.nbt"));
        Path previous = previousBuild == null ? null : bdsData.resolve(previousBuild).resolve("addon");
        Path target = bdsData.resolve(distribution.version()).resolve("addon");
        List<String> files = List.of("custom_block_properties.nbt", "custom_item_registry.nbt");
        WireShapes captured = WireShapes.ofCapture(runs.customCapture);

        if (previous != null) {
            WireShapes earlier = WireShapes.ofCapture(previous);
            if (previousBuild.equals(distribution.version())) {
                // This build's own committed capture: a difference is BDS sending it differently now, not a new wire format
                String what = "add-on components vs the committed capture of " + previousBuild;
                Diff shapes = Diff.shapes(earlier.components(), captured.components());
                report.check(shapes.identical() ? Report.Status.PASS : Report.Status.DIFF, "Component shapes", what + ": "
                    + (shapes.identical() ? "identical" : shapes.count() + " differences; re-capture this build and review the diff"));
                report.diff("Component shapes: " + what, shapes);
                reportValues(Diff.values(earlier.values(), captured.values()), what);
            } else {
                report(Diff.shapes(earlier.components(), captured.components()), "add-on components vs " + previousBuild);
                reportValues(Diff.values(earlier.values(), captured.values()), "add-on components vs " + previousBuild);
            }
        }
        // Vanilla data driven items and blocks: the reference version against the capture
        if (runs.imported != null) {
            WireShapes reference = WireShapes.ofPalette(resources.resolve(runs.referenceName + ".nbt"));
            WireShapes imported = WireShapes.ofPalette(runs.imported);
            report(Diff.shapes(reference.components(), imported.components()), "vanilla components vs bundled " + runs.referenceName);
            reportValues(Diff.values(reference.values(), imported.values()), "vanilla components vs bundled " + runs.referenceName);
        }
        // Coverage: everything captured must be something the component model can produce
        Path model = root.resolve(Layout.MODEL);
        JsonObject mappings = JsonParser.parseString(Files.readString(model.resolve("mappings.json"))).getAsJsonObject();
        Model components = Fixtures.read(model).apply(Model.read(model.resolve("components.json")));
        Map<String, List<String>> uncovered = WireShapes.classify(captured.uncovered(mappings), mappings, components);
        reportUncovered(uncovered);

        // A history check compares with the add-on capture of the build it is checked against (or the newest older one)
        Path committed = against != null ? previous : target;
        boolean same = committed != null && Files.isDirectory(committed);
        for (String file : files) {
            same &= Files.exists(committed.resolve(file))
                && Nbt.readGzip(committed.resolve(file)).equals(Nbt.readGzip(runs.customCapture.resolve(file)));
        }
        String where = committed == null ? "no add-on capture" : "`" + relative(committed) + "`";
        compared(same, "Add-on capture vs " + relative(bdsData), same ? where + " has the same NBT" : where + " is missing or differs");
        if (against != null) {
            for (String file : files) {
                stageCopy(target.resolve(file), runs.customCapture.resolve(file), "component capture " + file);
            }
        } else if (!same) {
            for (String file : files) {
                stageCopy(target.resolve(file), runs.customCapture.resolve(file), "component capture " + file);
            }
            report.check(Report.Status.INFO, "Component model", "new add-on capture staged; run the model checks (checkMappings, checkComponentApi) and review the diff");
        }
    }

    /**
     * The captured wire paths the mappings do not cover, by reason ({@link WireShapes#classify}). An unclassified path is a
     * wire change and a TODO: the model or the classification has to learn it.
     */
    private void reportUncovered(Map<String, List<String>> uncovered) {
        List<String> unclassified = uncovered.getOrDefault(WireShapes.UNCLASSIFIED, List.of());
        report.check(unclassified.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Spec coverage", unclassified.isEmpty()
            ? "every captured wire path is in " + Layout.MODEL + "/mappings.json or has a known reason (listed below)"
            : unclassified.size() + " captured wire paths are not in the mappings and have no known reason");
        unclassified.forEach(path -> report.wireChanges.add("not covered by " + Layout.MODEL + "/mappings.json: " + path));
        List<String> lines = new ArrayList<>();
        uncovered.forEach((reason, paths) -> {
            lines.add("- " + reason + ":");
            paths.forEach(path -> lines.add("  - `" + path + "`"));
        });
        if (!lines.isEmpty()) {
            report.evidence("Wire paths the model does not cover", lines);
        }
        if (!unclassified.isEmpty()) {
            report.action("map them in " + Layout.MODEL + "/mappings.json or give WireShapes.classify a reason");
        }
    }

    /**
     * The spec capture of this build (every documented component field, varied: the add-on is generated for the build's
     * format version) and the mapping inference over all captures. The capture is compared with the newest earlier one
     * value by value ({@link SpecDiff}): a changed value (an axis that is mirrored, a unit, an enum id, bit packing) with the
     * same keys and types is a DIFF as well as a changed shape. Verify-only runs write the capture into the work directory
     * and infer nothing.
     */
    private void specCapture(BdsDistribution distribution, Path work, Runs runs) throws Exception {
        // A history check captures into the work directory; keep() copies the capture when the build is kept
        boolean write = !verifyOnly && against == null;
        Path out = write ? bdsData.resolve(distribution.version()).resolve("spec") : work.resolve("spec");
        Map<String, String> specOptions = new LinkedHashMap<>(options);
        specOptions.put("version", distribution.version());
        specOptions.put("reuse-server", "true");
        specOptions.put("port", String.valueOf(port + 10));
        specOptions.put("protocol", String.valueOf(runs.protocol));
        specOptions.put("out", out.toString());
        // A history check compares with the build it is checked against
        String previousBuild = against != null ? against : baseline(distribution.version(), committedBuilds("spec/capture.json"));
        boolean own = distribution.version().equals(previousBuild);
        Path previous = previousBuild == null ? null : bdsData.resolve(previousBuild).resolve("spec");
        if (own && write) {
            // The capture replaces the committed one: keep that to compare with
            Path committed = work.resolve("spec-committed");
            BdsDistribution.deleteRecursively(committed);
            copyRecursively(previous, committed);
            previous = committed;
        }
        try {
            // Only the files whose bytes changed are repository changes: a capture identical to the committed one is none
            if ((write ? track(report, root, List.of(out), "spec capture", () -> new SpecCapture(root, specOptions).run())
                : new SpecCapture(root, specOptions).run()) != 0) {
                report.check(Report.Status.FAIL, "Spec capture", "failed");
                return;
            }
        } catch (Exception e) {
            report.fail("Spec capture", e);
            return;
        }
        report.check(Report.Status.INFO, "Spec capture", "`" + (write ? root.relativize(out) : out) + "`");
        SpecDiff.Pairing pairing = previous == null ? null : SpecDiff.pairing(previous, out);
        if (pairing != null && !pairing.same()) {
            // The values and acceptance only cover the variants both were given alike: in a history check that would
            // call two captures of different add-ons identical
            if (against != null) {
                report.check(Report.Status.FAIL, "Spec capture variants", previousBuild + ": " + pairing.mismatch());
                return;
            }
            report.check(Report.Status.INFO, "Spec capture variants", previousBuild + ": " + pairing.mismatch()
                + "; the comparison below covers the " + pairing.variants() + " variants alike only");
        }
        if (previous != null) {
            List<SpecDiff.Change> changes = SpecDiff.compare(previous, out);
            String earlier = own ? "the committed capture of " + previousBuild : previousBuild;
            Map<String, List<SpecDiff.Change>> byComponent = new java.util.TreeMap<>();
            changes.forEach(change -> byComponent.computeIfAbsent(change.component(), k -> new ArrayList<>()).add(change));
            compared(changes.isEmpty(), "Spec capture values",
                changes.isEmpty() ? "every one of the " + pairing.variants() + " variants both were given alike sent as in " + earlier
                    : changes.size() + " leaves in " + byComponent.size() + " components differ from " + earlier + ", variant by variant ("
                    + pairing.variants() + " variants compared)");
            List<String> lines = new ArrayList<>();
            byComponent.forEach((component, list) -> {
                long values = list.stream().filter(SpecDiff.Change::valueChange).count();
                // Against this build's own capture BDS answers differently now: no new protocol window to check
                String how = own ? "BDS sends it differently from the committed capture of this build: re-capture and review"
                    : transformOf(component) != null ? "its hand transform `Transforms." + transformOf(component) + "` needs a protocol check"
                    : "the inferred mapping gets a protocol window (regenerate with :tools:inferMappings and :tools:generateComponentApi)";
                if (!own) {
                    report.wireChanges.add("spec capture vs " + earlier + ": " + component + ": " + list.size() + " leaves differ (" + values
                        + " with another value); " + how);
                }
                lines.add("- `" + component + "`: " + how);
                list.stream().limit(6).forEach(change -> lines.add("  - " + change));
                if (list.size() > 6) {
                    lines.add("  - ... " + (list.size() - 6) + " more");
                }
            });
            if (!lines.isEmpty()) {
                report.evidence("Components", lines);
                report.action(own ? "re-capture this build and review these components" : "review the protocol windows of these components");
            }
            // What BDS accepts and logs: a variant both builds were given that one accepts and the other rejects, or
            // that logs other lines
            List<String> acceptance = SpecDiff.acceptance(previous, out);
            compared(acceptance.isEmpty(), "Spec capture acceptance", acceptance.isEmpty()
                ? "every variant accepted or rejected, with the same content log, as in " + earlier
                : acceptance.size() + " variants accepted, rejected or logged differently than in " + earlier);
            if (!acceptance.isEmpty()) {
                List<String> variants = new ArrayList<>();
                acceptance.stream().limit(200).forEach(line -> variants.add("- `" + line.replace("`", "'") + "`"));
                if (acceptance.size() > 200) {
                    variants.add("- ... " + (acceptance.size() - 200) + " more");
                }
                report.evidence("Variants", variants);
            }
        }
        if (write) {
            MappingInference inference = new MappingInference(root);
            Path model = root.resolve(Layout.MODEL);
            track(report, root, List.of(model.resolve("mappings.json"), model.resolve("coverage.md"), model.resolve("bds-feedback.json")),
                "inferred from the spec captures", () -> {
                    inference.run(false);
                    return null;
                });
            List<String> bits = inference.unexplainedBits();
            report.check(bits.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Residual bits", bits.isEmpty()
                ? "every bit the captures set is explained by a field or the format sweep" : bits.size() + " leaves have bits nothing explains");
            if (!bits.isEmpty()) {
                report.action("explain them by a field or the format sweep", "Leaves", bits.stream().map(finding -> "- " + finding).toList());
            }
            List<String> linked = inference.linkedFields();
            report.check(linked.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Linked fields", linked.isEmpty()
                ? "no field's evidence was decided by another field" : linked.size() + " fields need a capture that varies them with another field");
            if (!linked.isEmpty()) {
                report.action("capture again: the add-on generator links them from bds-feedback.json", "Fields", linked.stream().map(field -> "- " + field).toList());
            }
            report.check(Report.Status.INFO, "Component model", "mappings inferred; run :tools:generateComponentApi and the conformance suite (:tools:test) for the new protocol");
        } else {
            report.check(Report.Status.INFO, "Component model", "mappings not inferred (" + (against != null ? "history check" : "verify only") + ")");
        }
    }

    /** Whether the build of this run is older than the newest bundled version (a backfill, not an update). */
    private boolean olderThanNewestBundled(Runs runs) throws IOException {
        if (runs.protocol == null) {
            return false;
        }
        return Importer.readIndex(resources).stream().anyMatch(entry -> entry.protocol() > runs.protocol && !entry.preview());
    }

    /**
     * The committed build a capture of {@code build} is compared with: its own when it has one, so an already bundled
     * build is verified against what was committed for it; else the newest older build that has one, so a build not
     * bundled yet shows what changed since (and only then do differences hint at new protocol windows).
     *
     * @param committed the builds with such a capture
     * @return the build, or null if no build qualifies
     */
    static String baseline(String build, Collection<String> committed) {
        if (committed.contains(build)) {
            return build;
        }
        return committed.stream().filter(other -> Versions.compare(other, build) < 0).max(Versions.ORDER).orElse(null);
    }

    /** The builds in {@code data/bds} that have {@code relative}; a release run leaves out the bundled preview's. */
    private Set<String> committedBuilds(String relative) throws IOException {
        Set<String> builds = new TreeSet<>();
        if (!Files.isDirectory(bdsData)) {
            return builds;
        }
        Set<String> skip = previewBuilds();
        try (Stream<Path> dirs = Files.list(bdsData)) {
            dirs.filter(dir -> Files.exists(dir.resolve(relative))).map(dir -> dir.getFileName().toString())
                .filter(build -> !skip.contains(build)).forEach(builds::add);
        }
        return builds;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
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

    /** The hand transform of a component, if {@link HandTransforms} names one (component level or for a field). */
    private static String transformOf(String component) {
        return HandTransforms.BY_PATH.entrySet().stream().filter(e -> e.getKey().startsWith(component + ":")).map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }

    /** A value that came or went in a component both sides send: the shape is the same, what BDS puts in it is not. */
    private void reportValues(Diff diff, String what) {
        List<String> changes = diff.lines(Diff.VALUE_CHANGES);
        compared(changes.isEmpty(), "Component values", what + ": " + (changes.isEmpty() ? "identical" : changes.size() + " paths with other values"));
        if (!changes.isEmpty()) {
            report.action("review the component model and the hand transforms");
            report.evidence("Paths with other values", changes.stream().map(c -> "- `" + c.replace("`", "'") + "`").toList());
        }
    }

    private void report(Diff diff, String what) {
        if (diff.identical()) {
            report.check(Report.Status.PASS, "Component shapes", what + ": identical");
            return;
        }
        compared(false, "Component shapes", what + ": "
            + diff.lines(Diff.WIRE_CHANGES).size() + " wire format changes, " + diff.lines(Diff.NEW_COMPONENTS).size() + " new components");
        diff.lines(Diff.WIRE_CHANGES).forEach(change -> report.wireChanges.add(what + ": " + change));
        List<String> lines = new ArrayList<>();
        diff.lines(Diff.WIRE_CHANGES).forEach(change -> lines.add("- wire format change `" + change.replace("`", "'") + "`"));
        diff.lines(Diff.NEW_COMPONENTS).forEach(c -> lines.add("- new component `" + c + "`"));
        report.evidence("Shape changes", lines);
        if (!diff.lines(Diff.WIRE_CHANGES).isEmpty()) {
            report.action("review the component model and the generated component types");
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
            // What the run removed (a dropped preview)
            try (Stream<Path> files = Files.list(Update.this.resources)) {
                for (Path file : files.filter(f -> !Files.exists(resources.resolve(f.getFileName()))).sorted().toList()) {
                    report.repoChanges.add((verifyOnly ? "would " : "") + "remove `" + root.relativize(file).toString().replace('\\', '/') + "` (bundled data)");
                    if (!verifyOnly) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    void stage(Path target, byte[] content, String what) throws IOException {
        if (against != null) {
            // A history check writes only the captures of its build, and only when the build is kept (keep())
            Path normalized = target.toAbsolutePath().normalize();
            if (!normalized.startsWith(buildDirectory) || normalized.equals(buildDirectory)) {
                throw new IllegalStateException("A history check writes only into " + relative(buildDirectory) + ", not " + what
                    + " to " + normalized);
            }
            pending.put(normalized, content);
            return;
        }
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

    /**
     * Runs {@code action}, which writes into the repository itself, and adds a repository change for every file under
     * {@code paths} (files or directories) that it added, updated or removed. A file rewritten with the same bytes is no change.
     */
    static <T> T track(Report report, Path root, List<Path> paths, String what, Callable<T> action) throws Exception {
        Map<String, String> before = digests(root, paths);
        T result;
        try {
            result = action.call();
        } catch (Exception | Error e) {
            // Also what a failed action wrote; a failure to read it back never hides the action's own
            try {
                recordChanges(report, root, paths, what, before);
            } catch (IOException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        recordChanges(report, root, paths, what, before);
        return result;
    }

    private static void recordChanges(Report report, Path root, List<Path> paths, String what, Map<String, String> before) throws IOException {
        Map<String, String> after = digests(root, paths);
        Set<String> files = new TreeSet<>(before.keySet());
        files.addAll(after.keySet());
        for (String file : files) {
            String old = before.get(file);
            String now = after.get(file);
            if (!Objects.equals(old, now)) {
                report.repoChanges.add((old == null ? "add" : now == null ? "remove" : "update") + " `" + file + "` (" + what + ")");
            }
        }
    }

    /** The SHA-256 of every regular file under {@code paths}, by repository path; a missing path has none. */
    private static Map<String, String> digests(Path root, List<Path> paths) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                    digests.put(root.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
                }
            }
        }
        return digests;
    }

    /**
     * A check that compares this build with committed data, or with what BDS itself says elsewhere. In a history check,
     * any difference (any {@link Report.Status#DIFF}) is a change against the build it is checked against: the build is
     * kept.
     */
    private void compared(boolean same, String name, String detail) {
        report.check(same ? Report.Status.PASS : Report.Status.DIFF, name, detail);
    }

    /**
     * A history check: a check against the bundled data version of the window rather than the build compared with
     * (an INFO, never a change), listed in the report as not compared. Outside a history check it is a {@link #compared}
     * check.
     *
     * @param instead how the same thing is compared build to build, if it is
     */
    private void notCompared(boolean same, String name, String detail, String instead) {
        if (against == null) {
            compared(same, name, detail);
            return;
        }
        report.check(same ? Report.Status.PASS : Report.Status.INFO, name, detail);
        if (!same) {
            report.notCompared.add(name + ": " + detail + (instead == null ? "" : "; " + instead));
        }
    }

    /** Writes the captures of a kept build (history check) into {@code data/bds/<build>}. */
    void keep(Path work) throws IOException {
        BdsDistribution.deleteRecursively(buildDirectory);
        for (Map.Entry<Path, byte[]> file : pending.entrySet()) {
            Files.createDirectories(file.getKey().getParent());
            Files.write(file.getKey(), file.getValue());
        }
        Path spec = work.resolve("spec");
        if (Files.isDirectory(spec)) {
            Path target = buildDirectory.resolve("spec");
            Files.createDirectories(target);
            for (Path file : BuildHistory.files(spec)) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        report.repoChanges.add("add `" + root.relativize(buildDirectory).toString().replace('\\', '/') + "` (captures of this build)");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    /** A path of the repository as the report shows it (forward slashes on every platform). */
    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * The bundled release whose window holds a protocol: the newest one not newer than it (a hotfix with a protocol bump
     * is served the data version of its release), null if every bundled version is newer.
     */
    private String containingVersion(int protocol) throws IOException {
        String name = null;
        for (Importer.IndexEntry entry : Importer.readIndex(resources)) {
            if (!entry.preview() && entry.protocol() <= protocol) {
                name = entry.name();
            }
        }
        return name;
    }

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
        states.sort(Comparator.comparing((NbtMap state) -> BlockRegistry.nameHash(BlockStates.name(state)), Long::compareUnsigned));
        if (!generated.isEmpty() || !removed.isEmpty()) {
            report.check(Report.Status.INFO, "Reference palette", latest.name() + " adjusted with the Script API: "
                + (generated.isEmpty() ? "" : "generated " + String.join(", ", generated)) + (removed.isEmpty() ? "" : "; removed " + removed));
            if (!generated.isEmpty()) {
                report.todo("Block palette: the states of " + generated.size() + " blocks were generated from the Script API; their order "
                    + "within each block is inferred (Update#keyOrder) and only creative stacks verify it", "Generated blocks",
                    generated.stream().map(block -> "- " + block).toList());
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
    public static String versionName(String bdsVersion) {
        String[] parts = bdsVersion.split("[.-]");
        int offset = parts[0].equals("1") ? 1 : 0;
        return parts[offset] + "." + (Integer.parseInt(parts[offset + 1]) / 10 * 10);
    }

    private Path latestDump() throws IOException {
        if (!Files.isDirectory(bdsData)) {
            return null;
        }
        Set<String> skip = previewBuilds();
        try (Stream<Path> dirs = Files.list(bdsData)) {
            return dirs.filter(d -> !skip.contains(d.getFileName().toString())).map(d -> d.resolve("dump.json.gz")).filter(Files::exists)
                .max(Comparator.comparing(f -> f.getParent().getFileName().toString(), Versions.ORDER)).orElse(null);
        }
    }

    /**
     * The builds in {@code data/bds} of the bundled preview (their version name is marked {@code preview} in
     * {@code versions.txt}). A release run does not compare against them; a preview run does (none are skipped).
     */
    private Set<String> previewBuilds() throws IOException {
        if (preview || !Files.isDirectory(bdsData)) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>();
        Importer.readIndex(resources).stream().filter(Importer.IndexEntry::preview).forEach(entry -> names.add(entry.name()));
        try (Stream<Path> dirs = Files.list(bdsData)) {
            return dirs.map(d -> d.getFileName().toString()).filter(build -> build.matches("\\d+\\..*") && names.contains(versionName(build)))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    /** The newest committed add-on capture directory ({@code <bdsData>/<version>/addon}), null if there is none. */
    public static Path latestAddonCapture(Path bdsData) throws IOException {
        return latestAddonCapture(bdsData, Set.of());
    }

    private static Path latestAddonCapture(Path bdsData, Set<String> skip) throws IOException {
        if (!Files.isDirectory(bdsData)) {
            return null;
        }
        try (Stream<Path> files = Files.list(bdsData)) {
            return files.filter(f -> !skip.contains(f.getFileName().toString()) && Files.exists(f.resolve("addon/custom_item_registry.nbt")))
                .max(Comparator.comparing(f -> f.getFileName().toString(), Versions.ORDER)).map(f -> f.resolve("addon")).orElse(null);
        }
    }

    private static VanillaData read(Path file) throws IOException {
        return BundledFiles.read(file);
    }

    /** A version file (format 2): the properties are not in it, {@code properties.nbt} keeps them. */
    private static byte[] write(VanillaData data) throws IOException {
        return BundledFiles.bytes(data);
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
