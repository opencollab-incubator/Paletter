package dev.opencollab.paletter.tools.update;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.tools.BuildNumber;
import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsServer;
import dev.opencollab.paletter.tools.bds.ScriptModules;
import dev.opencollab.paletter.tools.behaviour.BehaviourDump;
import dev.opencollab.paletter.tools.behaviour.BehaviourProbe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The captures that were separate commands: the behaviour dump ({@code behaviour-dump}), the behaviour probe
 * ({@code behaviour-probe}) and the Education Edition capture ({@code education-capture}), on the server the other captures
 * ran on. They take six more starts of BDS (the dump two, the probe one, the education world three) and are staged like the
 * other captures, only when they differ from the committed ones of the build.
 * <p>
 * Their output is the input of {@code behaviour import} and of the constants generator, which the job that gathers a recapture
 * runs, so a step that fails is a FAIL. Only the {@link #OPTIONAL} probes are a skipped row with a TODO (the committed file
 * stays); the report header lists them.
 */
final class BehaviourStage {
    /**
     * The steps whose failure is a SKIP: the behaviour probe, whose measurements {@code behaviour import} reads from one reference
     * build ({@code data/bds/1.26.63.1/behaviour-probe}); a build without its own probe loses nothing the import needs.
     */
    static final Set<String> OPTIONAL = Set.of("Behaviour probe");
    static final String DUMP = BehaviourDump.FILE;
    static final String PROBE_DIRECTORY = "behaviour-probe";
    static final String PROBE_CAPTURE = PROBE_DIRECTORY + "/capture.json";
    static final String PROBE_LOG = PROBE_DIRECTORY + "/probe.log";
    static final String EDUCATION = "education.json";

    /** A step of the stage: its captures are compared and staged by the step itself. */
    interface Step {
        void run() throws Exception;
    }

    private final UpdateContext ctx;

    BehaviourStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Runs the three captures on {@code server}, which stays extracted. The packs they installed are removed again: the spec
     * capture that follows reads every pack of the server as BDS's own.
     */
    void behaviour(BdsDistribution distribution, Path server, Path work, Runs runs) {
        String build = distribution.version();
        ctx.report.header.put("Optional probes", String.join(", ", new java.util.TreeSet<>(OPTIONAL)) + (ctx.preview ? ", Behaviour dump (a preview)" : "")
            + " (a failure is a skipped row, the committed file stays)");
        try {
            step("Behaviour dump", () -> dump(build, server, work, runs));
            step("Behaviour probe", () -> probe(build, server, work));
            if (runs.protocol == null) {
                ctx.report.check(Report.Status.FAIL, "Education capture", "no network capture of this build");
            } else {
                step("Education capture", () -> education(build, server, work.resolve("education"), runs));
            }
        } finally {
            for (String pack : new String[] {BehaviourDump.PACK, BehaviourProbe.PACK}) {
                try {
                    BdsDistribution.deleteRecursively(server.resolve("behavior_packs").resolve(pack));
                } catch (IOException e) {
                    System.out.println("Could not remove the pack " + pack + ": " + e);
                }
            }
        }
    }

    void step(String name, Step step) {
        try {
            // BDS now and then hangs while it starts (each step starts it): once more before the step fails
            BdsServer.retryOnHang(() -> {
                step.run();
                return null;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            skipped(name, e);
        } catch (Exception e) {
            skipped(name, e);
        }
    }

    /**
     * The behaviour dump of a preview is optional too: BDS 1.26.70.24 and 1.26.70.26 exit without a word in its world sample (after
     * about 1840 of 2144 samples, ten seconds in, around minecraft:straw_bed; the cause is unverified), and the bundled preview has
     * no behaviour dump to lose. TODO: find what ends BDS there and drop this exception.
     */
    private boolean optional(String name) {
        return OPTIONAL.contains(name) || (ctx.preview && name.equals("Behaviour dump"));
    }

    private void skipped(String name, Exception error) {
        if (optional(name)) {
            ctx.report.check(Report.Status.SKIP, name, "failed (optional), the committed data stays: " + error);
        } else {
            ctx.report.fail(name, error);
        }
        ctx.report.action("run `" + name.toLowerCase(Locale.ROOT).replace(' ', '-') + "` by hand for this build (see the log in the work directory)");
    }

    /** Whether a committed file says the same as what this run captured. */
    interface Comparison {
        boolean same(Path committed) throws IOException;
    }

    private void dump(String build, Path server, Path work, Runs runs) throws Exception {
        Path log = work.resolve("behaviour-dump.log");
        // This run's docgen output, else the committed file of the build; without either the run fails
        List<String> modules = runs.scriptModules != null ? runs.scriptModules : ScriptModules.versions(ctx.bdsData, build);
        BehaviourDump.run(ctx.root, build, ctx.preview, ScriptModules.betaVersion(modules), server, null, ctx.port, ctx.timeout, log);
        BehaviourDump.Rendered rendered = BehaviourDump.render(log);
        requireBuild(build, ctx.preview, rendered.version());
        JsonObject captured = BehaviourDump.read(rendered.gzip());
        compareAndStage(build, "Behaviour dump", DUMP, rendered.summary(),
            committed -> BehaviourDump.sameContent(BehaviourDump.read(committed), captured), rendered.gzip());
    }

    private void probe(String build, Path server, Path work) throws Exception {
        Path log = work.resolve("behaviour-probe.log");
        BehaviourProbe.run(ctx.root, build, ctx.preview, server, null, ctx.port, ctx.timeout, log);
        BehaviourProbe.Measured measured = BehaviourProbe.fromConsole(ctx.root, log);
        requireBuild(build, ctx.preview, measured.version());
        boolean changed = compareAndStage(build, "Behaviour probe", PROBE_CAPTURE, "Build ID " + measured.build(),
            committed -> BehaviourProbe.sameCapture(Files.readString(committed), measured.capture()), measured.capture().getBytes(StandardCharsets.UTF_8));
        // The log carries timestamps: it follows the capture, so an unchanged measurement is no change
        if (changed) {
            ctx.stage(ctx.buildDirectory.resolve(PROBE_LOG), measured.log().getBytes(StandardCharsets.UTF_8), "behaviour probe log " + build);
        }
    }

    private void education(String build, Path server, Path out, Runs runs) throws Exception {
        Files.createDirectories(out);
        VanillaData reference = runs.reference != null ? runs.reference : EducationProbe.reference(ctx.root, null, runs.protocol);
        // The first start only creates the world: the protocol and the transport are the ones of the network capture
        EducationProbe.createWorld(server, ctx.port, out, ctx.timeout);
        EducationProbe.Result result = EducationProbe.compare(server, ctx.port, runs.protocol, reference, runs.netherNet, build, out, ctx.timeout);
        String json = EducationProbe.render(result, build, runs.protocol);
        compareAndStage(build, "Education capture", EDUCATION, result.items().size() + " items, " + result.blocks().size() + " blocks",
            committed -> EducationProbe.sameContent(Files.readString(committed), json), json.getBytes(StandardCharsets.UTF_8));
    }

    static void requireBuild(String build, boolean preview, String reported) throws IOException {
        if (!BuildNumber.parse(build).reported(preview).equals(reported)) {
            throw new IOException("the add-on ran on BDS " + reported + ", not " + build);
        }
    }

    /**
     * Compares the capture with the committed one of the build (or, for a build without one, the newest earlier build; in a
     * history check the build it is checked against) and stages it unless the build's own committed capture says the same.
     *
     * @param relative the file in {@code data/bds/<build>}
     * @return whether the capture was staged
     */
    boolean compareAndStage(String build, String name, String relative, String what, Comparison same, byte[] content) throws IOException {
        String from = ctx.against != null ? ctx.against : UpdateContext.baseline(build, ctx.committedBuilds(relative));
        Path baseline = from == null ? null : ctx.bdsData.resolve(from).resolve(relative);
        Path own = ctx.buildDirectory.resolve(relative);
        boolean unchanged;
        if (baseline == null || !Files.exists(baseline)) {
            ctx.report.check(Report.Status.INFO, name, what + "; no committed capture to compare with");
            unchanged = false;
        } else {
            boolean alike = same.same(baseline);
            ctx.compared(alike, name, what + "; " + (alike ? "same as" : "differs from") + " the committed capture of " + from);
            unchanged = from.equals(build) ? alike : Files.exists(own) && same.same(own);
        }
        if (!unchanged) {
            ctx.stage(own, content, name.toLowerCase(Locale.ROOT) + " " + build);
        }
        return !unchanged;
    }
}
