package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.bds.BdsDump;
import dev.opencollab.paletter.tools.bds.ScriptModules;
import dev.opencollab.paletter.tools.bds.BdsDocGen;
import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.model.Schema;
import dev.opencollab.paletter.tools.model.SchemaExtract;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The Script API dump and the BDS schema against the committed ones. */
final class ScriptApiStage {
    private final UpdateContext ctx;

    ScriptApiStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /** The Script API view against the committed dump of this build, or of the build before ({@link #baseline}). */
    void scriptApi(BdsDistribution distribution, BdsDump dump, Runs runs) throws IOException {
        // A history check compares with the build it is checked against
        String baselineBuild = ctx.against != null ? ctx.against : UpdateContext.baseline(distribution.version(), ctx.committedBuilds("dump.json.gz"));
        Path baselineFile = baselineBuild == null ? null : ctx.bdsData.resolve(baselineBuild).resolve("dump.json.gz");
        Path target = ctx.bdsData.resolve(distribution.version()).resolve("dump.json.gz");
        if (baselineFile == null || !Files.exists(baselineFile)) {
            ctx.compared(false, "Script API vs baseline", "no baseline dump in data/bds/" + (ctx.against != null ? ctx.against : "*") + "/dump.json.gz");
        } else {
            String baselineVersion = baselineBuild;
            BdsDump baseline = BdsDump.parse(UpdateContext.readGzipText(baselineFile), baselineVersion);
            Diff diff = Diff.scriptApi(baseline, dump);
            ctx.compared(diff.identical(), "Script API vs baseline " + baselineVersion, diff.identical() ? "identical" : diff.count() + " differences");
            ctx.report.diff("Script API: BDS " + distribution.version() + " vs baseline " + baselineVersion, diff);
            valueOrder(baseline, dump);
            if (runs.vanilla == null) {
                FallbackTodos.list(ctx, diff.lines("new blocks"), diff.lines("new items"), "Script API");
            }
        }
        ctx.stage(target, UpdateContext.gzip(runs.dumpJson), "Script API baseline " + distribution.version());
    }

    /** A warning, never a failure: BDS lists a state type's values in another order than in the baseline build. */
    private void valueOrder(BdsDump baseline, BdsDump dump) {
        if (baseline.stateTypes().isEmpty()) {
            ctx.report.check(Report.Status.SKIP, "Script API value order", "the baseline dump " + baseline.version()
                + " has its values sorted; compared from the next recapture on");
            return;
        }
        List<String> differences = baseline.valueOrderDifferences(dump);
        if (differences.isEmpty()) {
            ctx.report.check(Report.Status.PASS, "Script API value order", "the state types list their values in the baseline's order");
        } else {
            ctx.report.check(Report.Status.INFO, "Script API value order", "warning: " + differences.size() + " state types list their values in "
                + "another order than in " + baseline.version() + "; generated blocks may need their value order checked");
            ctx.report.evidence("State types (baseline vs this build)", differences);
        }
    }

    /**
     * The Script API module versions of the build's docgen output, staged as {@code script-modules.json} and kept for the behaviour
     * dump of this run. No version in the docs is a failed check (the beta add-on cannot run without its module); no docs at all
     * is the failed "BDS schema" row of {@link #schema}, not a second one.
     */
    void scriptModules(BdsDistribution distribution, Path docs, Runs runs) throws IOException {
        String build = distribution.version();
        if (docs == null) {
            // The one FAIL of the missing docgen output is the "BDS schema" row of schema()
            return;
        }
        try {
            runs.scriptModules = ScriptModules.parse(docs);
            ctx.stage(ScriptModules.file(ctx.bdsData, build), ScriptModules.render(build, runs.scriptModules).getBytes(StandardCharsets.UTF_8),
                "Script API modules " + build);
            ctx.report.check(Report.Status.PASS, "Script API modules", "BDS " + build + " has @minecraft/server " + runs.scriptModules);
        } catch (IOException e) {
            ctx.report.check(Report.Status.FAIL, "Script API modules", e.getMessage());
            ctx.report.action("the behaviour dump needs data/bds/" + build + "/" + ScriptModules.FILE + ": fix the parser or write it by hand");
        }
    }

    /**
     * The schema of the build ({@code schema.json}: the facts of BDS's own JSON schemas, written by its documentation generator)
     * and what changed against the schema of the previous build. No docs (one FAIL row) or a schema layout the extract cannot read
     * is a FAIL: that is how a change of BDS's documentation generator shows, and the add-on of this build would otherwise be
     * generated from an older schema without anyone being told. Components it could not read are a DIFF, a keyword it does not
     * know an INFO with an action.
     *
     * @return the schema extracted from this run's docs, which this run's add-ons use instead of the committed file (it is staged,
     *         or only written to a capture-only run's output); null if none was extracted
     */
    Schema schema(Path cache, BdsDistribution distribution, Path docs) {
        String build = distribution.version();
        try {
            if (docs == null) {
                ctx.report.check(Report.Status.FAIL, "BDS schema", "BDS's documentation generator wrote no docs (see bds-docgen.log)");
                ctx.report.action("run the documentation generator by hand for this build (see bds-docgen.log)");
                return null;
            }
            Schema previous = Schema.newest(ctx.bdsData, build);
            JsonObject extract = SchemaExtract.extract(BdsDocGen.keep(docs, cache, build), build);
            Schema schema = Schema.of(extract);
            if (previous == Schema.NONE) {
                ctx.report.check(Report.Status.INFO, "BDS schema", schema.summary() + "; no schema of an older build to diff against");
            } else {
                List<String> diff = Schema.diff(previous, schema);
                ctx.report.check(diff.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "BDS schema vs " + previous.build(),
                    schema.summary() + (diff.isEmpty() ? ", no changes" : ", " + diff.size() + " changes"));
                if (!diff.isEmpty()) {
                    ctx.report.evidence("Schema changes", diff.stream().map(line -> "- `" + line + "`").toList());
                }
            }
            if (!schema.skipped().isEmpty()) {
                ctx.report.check(Report.Status.DIFF, "BDS schema components skipped", schema.skipped().size() + " components could not be read and are not in schema.json");
                ctx.report.evidence("Schema components skipped", schema.skipped().stream().map(line -> "- " + line).toList());
            }
            if (!schema.unhandledKeywords().isEmpty()) {
                // Data in, a human decides: a keyword that only documents something changes no constraint
                ctx.report.check(Report.Status.INFO, "BDS schema keywords", "the extract does not read " + schema.unhandledKeywords());
                ctx.report.action("check whether the BDS schema keywords " + schema.unhandledKeywords()
                    + " carry constraints; if so teach SchemaExtract what they mean, else list them as known and ignored");
            }
            // What the content log said that the schema contradicts: the add-on tests both until a capture settles it
            Path feedback = ctx.root.resolve(Layout.MODEL).resolve("bds-feedback.json");
            List<String> disputes = Files.exists(feedback)
                ? schema.disputes(JsonParser.parseString(Files.readString(feedback, StandardCharsets.UTF_8)).getAsJsonObject()) : List.of();
            ctx.report.check(disputes.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "BDS schema vs content log",
                disputes.isEmpty() ? "no limit or value the log and the schema disagree on" : disputes.size() + " disputes, the add-on tests both");
            if (!disputes.isEmpty()) {
                ctx.report.evidence("Schema and content log disagree", disputes.stream().map(line -> "- " + line).toList());
            }
            ctx.stage(ctx.bdsData.resolve(build).resolve(Schema.FILE), SchemaExtract.render(extract).getBytes(StandardCharsets.UTF_8), "BDS schema " + build);
            return schema;
        } catch (Exception e) {
            ctx.report.fail("BDS schema", e);
            return null;
        }
    }
}
