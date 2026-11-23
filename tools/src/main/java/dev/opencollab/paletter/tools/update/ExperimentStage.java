package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.BdsRunner;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.client.LevelDat;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The experiment toggles of a BDS: discovery, and the attribution of what each toggle adds. */
final class ExperimentStage {
    /** Above this many current toggles, only one capture with all of them is made (no per toggle attribution). */
    static final int EACH_LIMIT = 12;
    private static final String EXPERIMENT_WORLD = "paletter_experiments";

    private final UpdateContext ctx;
    private final CaptureStage captures;

    ExperimentStage(UpdateContext ctx, CaptureStage captures) {
        this.ctx = ctx;
        this.captures = captures;
    }

    /**
     * Discovers the experiment toggles of this BDS (candidates from the binary, BDS's level.dat round trip as the
     * oracle, start game for which are current), checks that BDS applies them, and attributes content: a capture and a
     * Script API dump with all toggles and with each current toggle alone, against the vanilla ones without any.
     * Leaves the server stopped.
     */
    void experiments(Path server, Path work, InetSocketAddress address, BdsDump dump, Runs runs,
                             Map<String, String> properties) throws Exception {
        ExperimentRun result = runs.experiments;
        long started = System.nanoTime();
        Path levelDat = server.resolve("worlds").resolve(EXPERIMENT_WORLD).resolve("level.dat");
        captures.freshWorld(server, EXPERIMENT_WORLD, null);
        properties.put("level-name", EXPERIMENT_WORLD);
        properties.put("block-network-ids-are-hashes", "true");
        BdsServer.configure(server, properties);
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-experiments-create.log"));
            bds.awaitStarted(ctx.timeout);
        }

        // Candidates: the binary (identifiers, '_' suffixes, imm64 immediates), the experimental packs and the ids of the
        // committed records
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
            candidates.addAll(ExperimentDiscovery.recorded(ctx.bdsData));
            result.candidates = candidates.size();
            LevelDat.setExperiments(levelDat, candidates);
            try (BdsServer bds = new BdsServer(server, false)) {
                bds.start(work.resolve("bds-experiments-discover.log"));
                bds.awaitStarted(ctx.timeout);
                result.activeCodes = ExperimentDiscovery.activeCodes(bds.lines(line -> line.contains("Experiment(s) active")));
            }
            result.accepted = LevelDat.enabled(LevelDat.experiments(levelDat));
            if (result.accepted.isEmpty()) {
                throw new IOException("BDS kept none of " + candidates.size() + " candidate toggles in level.dat");
            }
            // An always-on toggle has no log code
            long withCode = result.accepted.stream().filter(t -> !result.alwaysOn.contains(t)).count();
            boolean codesMatch = result.activeCodes != null && result.activeCodes.size() == withCode;
            ctx.compared(codesMatch, "Experiments: discovery",
                result.candidates + " candidates (binary " + BdsServer.executable(server).getFileName() + ", experimental "
                    + "packs, committed records); BDS kept " + result.accepted.size() + " toggles in level.dat"
                    + (codesMatch ? " and logged as many active" : "; it logged " + (result.activeCodes == null ? "no" : result.activeCodes.size())
                    + " active (Experiment(s) active line)"));
            toggles = result.accepted;
        }

        // All toggles at once: start game tells which are current (streamed); the rest are retired
        LevelDat.setExperiments(levelDat, toggles);
        Capture.Summary all;
        try (BdsServer bds = new BdsServer(server, false)) {
            bds.start(work.resolve("bds-experiments-all.log"));
            bds.awaitStarted(ctx.timeout);
            all = captures.capture(address, runs, work.resolve("experiments/all"), null, "experiments capture (all toggles)");
        }
        List<String> streamed = all.enabledExperiments().stream().filter(t -> !result.alwaysOn.contains(t)).toList();
        if (manual) {
            captures.appliedCheck(result, "--experiments", toggles, all);
        } else {
            result.source = "discovered";
            result.current = streamed;
            result.retired = toggles.stream().filter(t -> !streamed.contains(t) && !result.alwaysOn.contains(t)).toList();
            List<String> notAccepted = streamed.stream().filter(t -> !toggles.contains(t)).toList();
            boolean uiMatch = streamed.size() == result.uiKeys.size();
            ctx.compared(notAccepted.isEmpty() && uiMatch, "Experiments: current toggles",
                streamed.size() + " streamed in start game " + streamed + ", " + result.retired.size() + " retired (kept, not streamed); "
                    + result.uiKeys.size() + " `" + ExperimentDiscovery.UI_PREFIX + "*` UI keys in the binary"
                    + (uiMatch ? "" : ": a current toggle may be missing from the candidates; pass it with --experiments")
                    + (notAccepted.isEmpty() ? "" : "; streamed but not kept in level.dat: " + notAccepted));
        }
        List<String> packStack = all.packStackExperiments().stream().filter(e -> e.endsWith("=true"))
            .map(e -> e.substring(0, e.length() - 5)).filter(t -> !result.alwaysOn.contains(t)).sorted().toList();
        ctx.compared(packStack.equals(streamed), "Experiments: resource pack stack",
            packStack.equals(streamed) ? "lists the same toggles as start game" : "lists " + packStack + ", start game " + streamed);
        result.uiKeyOf = ExperimentDiscovery.matchUiKeys(result.current, result.uiKeys);

        // Attribution
        String mode = ctx.options.attribution().text();
        if (mode.equals("each") && result.current.size() > EACH_LIMIT) {
            ctx.report.check(Report.Status.INFO, "Experiments: attribution", result.current.size() + " current toggles (more than "
                + EACH_LIMIT + "): only all together");
            mode = "all";
        }
        result.attribution = mode;
        if (!mode.equals("none") && runs.vanilla != null) {
            CapturedData base = CapturedData.read(runs.vanilla);
            Set<String> creativeBefore = ExperimentAttribution.creativeItems(base);
            BdsRunner runner = new BdsRunner(ctx.root.resolve(Layout.DUMP_ADDON));
            Path dumpLog = work.resolve("experiments/bds-dump-all.log");
            try {
                runner.rerun(server, dumpLog, ctx.timeout, ctx.port, toggles);
                result.all = ExperimentAttribution.diff(base, CapturedData.read(work.resolve("experiments/all")), dump, BdsDump.read(dumpLog));
                if (mode.equals("each")) {
                    for (String toggle : result.current) {
                        LevelDat.setExperiments(levelDat, List.of(toggle));
                        // The dump reruns switch server.properties to their own world
                        BdsServer.configure(server, properties);
                        Path out = work.resolve("experiments").resolve(toggle);
                        try (BdsServer bds = new BdsServer(server, false)) {
                            bds.start(work.resolve("experiments/bds-" + toggle + ".log"));
                            bds.awaitStarted(ctx.timeout);
                            List<String> codes = ExperimentDiscovery.activeCodes(bds.lines(line -> line.contains("Experiment(s) active")));
                            if (codes != null && codes.size() == 1) {
                                result.codes.put(toggle, codes.getFirst());
                            }
                            captures.appliedCheck(result, toggle, List.of(toggle), captures.capture(address, runs, out, null, "experiments capture (" + toggle + ")"));
                        }
                        Path log = work.resolve("experiments/bds-dump-" + toggle + ".log");
                        runner.rerun(server, log, ctx.timeout, ctx.port, List.of(toggle));
                        ExperimentAttribution.Diff diff = ExperimentAttribution.diff(base, CapturedData.read(out), dump, BdsDump.read(log));
                        result.each.put(toggle, diff);
                        result.content.put(toggle, diff.content(creativeBefore));
                        recipes(result, toggle, runs.vanilla, out);
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

    /** The recipes of a toggle; a failure is one INFO row, the rest of the attribution goes on. */
    private void recipes(ExperimentRun result, String toggle, Path base, Path with) {
        try {
            Map<String, List<String>> changes = ExperimentAttribution.recipes(base, with);
            if (!changes.isEmpty()) {
                result.recipes.put(toggle, changes);
            }
        } catch (Exception e) {
            ctx.report.check(Report.Status.INFO, "Experiments: recipes of " + toggle, "not attributed: " + e);
        }
    }
}
