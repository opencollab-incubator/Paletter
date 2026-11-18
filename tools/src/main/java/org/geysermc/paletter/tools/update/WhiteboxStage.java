package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.whitebox.Whitebox;
import org.geysermc.paletter.tools.whitebox.WhiteboxCoverage;
import org.geysermc.paletter.tools.whitebox.WhiteboxRun;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** What the code of the build's Linux binary can send, and which of it no capture shows. */
final class WhiteboxStage {
    private final UpdateContext ctx;

    WhiteboxStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * What the code of the build's Linux binary can send ({@code whitebox}) and which of it no capture shows
     * ({@code whitebox-coverage}). Advisory: nothing else of the update depends on it, so whatever goes wrong here (an
     * exception, a failed self-check) is a FAIL row and the facts are not written. New gaps are listed under "Needs attention".
     */
    void whitebox(Path cache, BdsDistribution distribution) {
        String build = distribution.version();
        try {
            WhiteboxRun run = WhiteboxRun.of(BdsDistribution.resolve(true, ctx.preview, build).zip(cache), ctx.root, build);
            if (!run.problems().isEmpty()) {
                ctx.report.check(Report.Status.FAIL, "White-box", "white-box unavailable for this build: " + String.join("; ", run.problems()));
                return;
            }
            Path data = ctx.bdsData.resolve(build);
            ctx.stage(data.resolve(Whitebox.FILE), Whitebox.json(run.facts()).getBytes(StandardCharsets.UTF_8), "white-box facts " + build);
            List<String> changes = run.changes().stream().filter(line -> line.startsWith("!")).toList();
            ctx.report.check(Report.Status.INFO, "White-box", run.changes().isEmpty() ? "no changes against the build before"
                : changes.size() + " changes of what the code sends, " + (run.changes().size() - changes.size()) + " of how it writes it");
            if (!changes.isEmpty()) {
                ctx.report.action("capture the components that changed again", "Changes", changes);
            }
            List<String> rewritten = run.changes().stream().filter(line -> line.startsWith("~")).toList();
            if (!rewritten.isEmpty()) {
                ctx.report.evidence("Writers whose code changed", rewritten);
            }
            if (run.coverage() == null) {
                ctx.report.check(Report.Status.SKIP, "White-box coverage", "no captures of this build");
                return;
            }
            ctx.stage(data.resolve(WhiteboxCoverage.FILE), run.coverage().getBytes(StandardCharsets.UTF_8), "white-box coverage " + build);
            ctx.report.check(Report.Status.INFO, "White-box coverage", run.gaps().size() + " things the code can send that no capture shows, "
                + run.newGaps().size() + " new");
            if (!run.newGaps().isEmpty()) {
                ctx.report.action("capture them with a spec variant, a format sweep entry or a probe add-on", "New gaps",
                    run.newGaps().stream().map(gap -> "- " + gap.subject() + ": " + gap.missing()).toList());
            }
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            // Also what a binary the analysis does not expect can run into: deep listings, a large image
            ctx.report.check(Report.Status.FAIL, "White-box", "white-box unavailable for this build: " + e);
        }
    }
}
