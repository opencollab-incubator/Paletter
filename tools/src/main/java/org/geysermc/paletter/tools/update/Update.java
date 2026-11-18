package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.bds.ScriptApiDump;
import org.geysermc.paletter.tools.bds.CapturePlatform;
import org.geysermc.paletter.tools.Layout;
import org.geysermc.paletter.tools.model.addon.AddonGenerator;
import org.geysermc.paletter.tools.model.addon.FixedAddons;
import org.geysermc.paletter.tools.shape.ShapeImport;
import org.geysermc.paletter.tools.client.Capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code update}: pulls the newest BDS of a channel, runs the Script API dump and the network captures, compares the
 * result with the committed data and either updates the repository or (with {@code --verify-only}) only reports.
 * See docs/data-pipeline.md, "Updating for a new BDS build".
 */
public final class Update {
    private final UpdateContext ctx;
    /** The build this run captures ({@link #begin}). */
    private BdsDistribution distribution;
    private Integer protocol;

    /** The report of an update, in {@code data/bds/<build>/}. */
    public static final String REPORT = "update-report.md";
    /** The exit code of an update that wrote everything but left decisions (the report's TODO list). */
    public static final int EXIT_TODOS = 2;
    /** The file in the work directory that holds the exit code of the run, as plain text. */
    public static final String EXIT_CODE = "exit-code";

    public Update(Path root, Map<String, String> options) {
        this.ctx = new UpdateContext(root, options);
    }

    /** What the stages of the run share; for the tests of a single stage. */
    UpdateContext context() {
        return ctx;
    }

    /** The report of the run ({@link #begin}). */
    Report report() {
        return ctx.report;
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
        return !ctx.report.changes().isEmpty();
    }

    /** What a history check could not compare with the other build: the report's "Not compared" list. */
    public List<String> notCompared() {
        return List.copyOf(ctx.report.notCompared);
    }

    /** Whether a step of the run failed. */
    public boolean failed() {
        return ctx.report.failed();
    }

    /** @return the process exit code */
    public int run() throws Exception {
        Path cache = ctx.options.containsKey("cache") ? Path.of(ctx.options.get("cache")) : BdsDistribution.defaultCache();
        BdsDistribution distribution = BdsDistribution.resolve(ctx.preview, ctx.options.get("version"));
        begin(distribution);
        Path work = distribution.runDirectory(cache, "");
        BdsDistribution.deleteRecursively(work);
        Files.createDirectories(work);
        ctx.report.header.put("Work directory", "`" + work + "` (logs, captures)");

        try {
            if (samePlatform()) {
                pipeline(cache, distribution, work);
            }
        } catch (Exception e) {
            ctx.report.fail("pipeline", e);
        }
        if (ctx.against == null && ctx.captureOnly == null && !ctx.verifyOnly && !ctx.report.failed()) {
            // New captures of a bundled build change what the record says per data version
            UpdateContext.track(ctx.report, ctx.root, List.of(ctx.resources.resolve(BuildTable.RESOURCE), ctx.bdsData.resolve(BuildTable.TABLE)), "build table",
                () -> BuildTable.run(ctx.root, false));
        }
        return finish(work);
    }

    /** Starts the report of a run of {@code distribution} and checks where it may write. */
    void begin(BdsDistribution distribution) {
        ctx.report = new Report(ctx.against != null ? "Paletter build check: BDS " + distribution.version() + " against " + ctx.against
            : "Paletter data " + (ctx.verifyOnly ? "verification" : "update") + ": BDS " + distribution.version() + (ctx.preview ? " (preview)" : ""));
        ctx.report.against = ctx.against;
        ctx.buildDirectory = BdsDistribution.inside(ctx.bdsData, distribution.version());
        if (ctx.against != null && !Files.isDirectory(BdsDistribution.inside(ctx.bdsData, ctx.against))) {
            throw new IllegalArgumentException("--against " + ctx.against + ": no captures in " + ctx.relative(ctx.bdsData.resolve(ctx.against)));
        }
        if (ctx.against != null && !ctx.verifyOnly && ctx.captureOnly == null && Files.exists(ctx.buildDirectory)) {
            throw new IllegalArgumentException(ctx.root.relativize(ctx.buildDirectory) + " exists: a history check only adds builds (use --verify-only)");
        }
        ctx.report.header.put("Date", LocalDate.now().toString());
        ctx.report.header.put("Mode", ctx.captureOnly != null ? "capture only" + (ctx.against != null ? " (compared with " + ctx.against + ")" : "")
            + ": captures go to a directory of the recapture job, nothing is written into the repository" : ctx.against != null ? "history check against " + ctx.against + (ctx.verifyOnly ? " (nothing written)"
            : " (captures written to data/bds/" + distribution.version() + " only if they differ)") : ctx.verifyOnly ? "verify only (nothing written)" : "update");
        ctx.report.header.put("BDS", distribution.version() + " " + distribution.platform() + " (" + distribution.url() + ")");
        this.distribution = distribution;
    }

    /** Notes the protocols, keeps a history check's captures when they differ and writes the report. */
    private int finish(Path work) throws IOException {
        if (ctx.against != null && protocol != null) {
            // Protocols are version stamps to the comparison, but builds of the same data under two protocols are worth saying
            Integer againstProtocol = BuildHistory.protocol(ctx.bdsData.resolve(ctx.against));
            if (againstProtocol != null && againstProtocol.intValue() != protocol) {
                ctx.report.againstNotes.add("this build speaks protocol " + protocol + ", " + ctx.against + " protocol " + againstProtocol
                    + "; the comparison leaves protocols aside");
            }
        }

        // A history check keeps the captures of a build only if they differ from the build it was compared with
        boolean kept = ctx.captureOnly != null ? !ctx.report.failed() : ctx.against != null && !ctx.verifyOnly && !ctx.report.failed() && changed();
        if (kept) {
            ctx.keep(work);
        }
        // An update keeps its report with the data of its build, so a later run can be diffed against it; an update that
        // changed no data file keeps the committed report, so the report is never the only change. A history check keeps
        // its report with the captures of a kept build
        Path reportFile = ctx.options.containsKey("report") ? Path.of(ctx.options.get("report"))
            : ctx.captureOnly != null ? work.resolve("report.md")
            : ctx.against != null ? (kept ? ctx.buildDirectory.resolve(REPORT) : work.resolve("report.md"))
            : ctx.verifyOnly || ctx.report.repoChanges.isEmpty() ? work.resolve("report.md") : ctx.bdsData.resolve(distribution.version()).resolve(REPORT);
        ctx.report.generatedBy = command(distribution);
        ctx.report.write(reportFile);
        if (!reportFile.startsWith(work)) {
            ctx.report.write(work.resolve("report.md"));
        }
        System.out.println("Report: " + reportFile.toAbsolutePath());
        if (ctx.captureOnly != null) {
            // Differences from the committed captures are the point of a recapture, never a failure; decisions are the gathering job's
            int code = ctx.report.failed() ? 1 : 0;
            Files.writeString(work.resolve(EXIT_CODE), code + "\n");
            System.out.println(ctx.report.failed() ? "FAILED" : "CAPTURED into " + ctx.captureOnly);
            return code;
        }
        if (ctx.against != null) {
            System.out.println(ctx.report.failed() ? "FAILED" : changed() ? "DIFFERS FROM " + ctx.against + (kept ? " (kept)" : "") : "IDENTICAL TO " + ctx.against);
            return ctx.report.failed() || (ctx.verifyOnly && changed()) ? 1 : 0;
        }
        System.out.println(ctx.report.failed() ? "FAILED" : ctx.report.differs() ? (ctx.verifyOnly ? "DIFFERENCES (verify failed)" : "UPDATED") : "IDENTICAL");
        return exitCode(ctx.report, ctx.verifyOnly, work);
    }

    /**
     * Whether this machine captures on the platform of the committed captures ({@link CapturePlatform}); else the run is
     * a FAIL that says so, and nothing is captured or compared.
     */
    private boolean samePlatform() throws IOException {
        String committed;
        try {
            committed = CapturePlatform.single(ctx.bdsData);
        } catch (IllegalStateException e) {
            ctx.report.check(Report.Status.FAIL, "Capture platform", e.getMessage());
            return false;
        }
        String mismatch = committed == null ? null : CapturePlatform.mismatch("The committed data (" + Layout.BDS + ")", committed, CapturePlatform.host());
        ctx.report.check(mismatch == null ? Report.Status.PASS : Report.Status.FAIL, "Capture platform",
            mismatch == null ? CapturePlatform.host() + (committed == null ? " (no committed captures yet)" : ", like the committed captures") : mismatch);
        return mismatch == null;
    }

    /** The command line of this run, without the options that only say where things go. */
    private String command(BdsDistribution distribution) {
        StringBuilder args = new StringBuilder("update --channel " + (ctx.preview ? "preview" : "release") + " --version " + distribution.version());
        for (String option : List.of("against", "verify-only", "name", "protocol", "experiments", "experiment-attribution")) {
            if (ctx.options.containsKey(option)) {
                String value = ctx.options.get(option);
                args.append(" --").append(option).append(value == null || value.equals("true") ? "" : " " + value);
            }
        }
        return "./gradlew :tools:run --args=\"" + args + "\"";
    }

    private void pipeline(Path cache, BdsDistribution distribution, Path work) throws Exception {
        Path server = distribution.extract(cache, !ctx.keepServer);
        ctx.report.header.put("Server", "`" + server + "`" + (ctx.keepServer ? " (kept)" : " (removed after the run)"));
        ScriptApiStage scriptApi = new ScriptApiStage(ctx);
        AddonChecksStage addonChecks = new AddonChecksStage(ctx);
        CaptureStage captures = new CaptureStage(ctx);
        BdsDump dump;
        Runs runs = new Runs();
        runs.bdsVersion = distribution.version();
        runs.formatVersion = AddonGenerator.formatVersionFor(FixedAddons.FORMAT_VERSION, distribution.version());
        try {
            // 1. Script API dump
            Path dumpLog = work.resolve("bds-dump.log");
            Path docs = ScriptApiDump.run(ctx.root, server, dumpLog, ctx.timeout, ctx.port);
            scriptApi.scriptModules(distribution, docs, runs);
            runs.schema = scriptApi.schema(cache, distribution, docs);
            String[] json = BdsDump.json(dumpLog);
            dump = BdsDump.parse(json[1], json[0]);
            Files.writeString(work.resolve("bds-dump.json"), json[1]);
            runs.dumpLog = dumpLog;
            runs.dumpJson = BdsDump.canonicalJson(json[1]);
            ctx.report.check(dump.errors().isEmpty() ? Report.Status.PASS : Report.Status.FAIL, "Script API dump",
                "BDS reports " + json[0] + "; " + dump.items().size() + " items, " + dump.blockDefaults().size() + " blocks, "
                    + dump.itemTags().size() + " item tags" + (dump.errors().isEmpty() ? "" : "; addon errors: " + dump.errors()));

            // 2. Network captures (vanilla, experiments and the creative order probe, hashed and sequential block ids)
            captures.captures(server, work, dump, runs);
            protocol = runs.protocol;
            if (runs.codecProtocol != null && runs.protocol != null) {
                // The captures were decoded with another protocol's codec: BDS's own packet schemas say whether that is safe
                ProtocolSchemaCheck.report(ctx.report, cache.resolve("docgen"),runs.codecProtocol, runs.protocol);
            }

            // 3. Voxel shapes: the packet of the vanilla world, of the probe add-on and of the anchors
            if (runs.vanilla != null) {
                new VoxelShapesStage(ctx).voxelShapes(distribution, server, work, runs);
            } else {
                ctx.report.check(Report.Status.SKIP, "Voxel shapes", "no capture of this build");
            }

            // 4. Behaviour dump, behaviour probe and Education Edition capture, on this server (before the spec capture removes it)
            new BehaviourStage(ctx).behaviour(distribution, server, work, runs);
        } finally {
            // With a capture, the spec capture runs on this server too (reuse-server) and removes it
            if (!ctx.keepServer && runs.protocol == null) {
                try {
                    BdsDistribution.deleteRecursively(server);
                } catch (IOException e) {
                    System.out.println("Could not remove " + server + ": " + e);
                }
            }
        }

        // 5. Compare and update
        scriptApi.scriptApi(distribution, dump, runs);
        new InventoryStage(ctx).inventory(cache, distribution);
        Staging staging = new Staging(ctx, work.resolve("staging"));
        NetworkDataStage networkData = new NetworkDataStage(ctx);
        if (runs.vanilla != null) {
            networkData.networkData(distribution, dump, runs, staging);
        } else {
            networkData.missingNetworkData(distribution, dump);
        }
        new ExperimentReportStage(ctx).experimentsReport(distribution, runs, staging);
        addonChecks.creativeOrder(runs);
        if (runs.imported != null) {
            new ShapesStage(ctx).vanillaShapes(runs);
        }
        SpecStage spec = new SpecStage(ctx, captures);
        if (ctx.against != null || ctx.captureOnly != null) {
            // A history check (and the capture half of a recapture) leaves the repository's bundled data, constants and model alone
            if (runs.protocol != null) {
                spec.specCapture(distribution, work, runs);
            }
            return;
        }
        new ConstantsStage(ctx).constants(staging);
        staging.apply();
        // After the data is in place: the inference puts each capture into the window of its bundled version
        if (runs.protocol != null) {
            spec.specCapture(distribution, work, runs);
        }
        // After the captures, which the white-box facts are checked against and compared with
        new WhiteboxStage(ctx).whitebox(cache, distribution);
        if (runs.voxelShapesWritten) {
            // After the data of the version is in place: the import reads the block names of the bundled versions
            ShapeImport.run(ctx.root);
        }
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

    /** {@code 1.26.70.23} -> {@code 26.70}, {@code 1.26.63.1} -> {@code 26.60}: hotfixes share their release's name. */
    public static String versionName(String bdsVersion) {
        String[] parts = bdsVersion.split("[.-]");
        int offset = parts[0].equals("1") ? 1 : 0;
        return parts[offset] + "." + (Integer.parseInt(parts[offset + 1]) / 10 * 10);
    }
}
