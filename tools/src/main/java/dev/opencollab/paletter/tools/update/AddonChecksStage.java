package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.data.CreativeOrderCheck;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The creative inventory of the creative order probe against Paletter's ordering and, in a history check, the committed one. */
final class AddonChecksStage {
    /** Where a build keeps the creative inventory of the creative order probe. */
    private static final String PROBE_INVENTORY = "creative-probe/creative_items.json.gz";

    private final UpdateContext ctx;

    AddonChecksStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Paletter's default creative ordering ({@code CreativeOrdering.BDS}) against what BDS sent for the creative order probe
     * (docs/wire-notes.md). A difference means BDS changed where it puts custom content. The block map order of the spec
     * add-on is checked with the spec capture ({@link SpecStage}).
     */
    void creativeOrder(Runs runs) throws IOException {
        if (ctx.against != null && runs.creative != null) {
            probeCreative(runs);
        }
        if (runs.existing == null) {
            ctx.report.check(Report.Status.INFO, "Creative order", "skipped: protocol " + runs.protocol + " is not bundled, "
                + "so Paletter has no vanilla layout of this version to place custom content in");
            return;
        }
        if (runs.creative == null) {
            ctx.report.check(Report.Status.FAIL, "Creative order (creative order probe)", "no capture");
            return;
        }
        Path custom = runs.creative.resolve("custom");
        CreativeOrderCheck.Result result = CreativeOrderCheck.check(runs.protocol, ctx.resources,
            runs.creative.resolve("creative_items.json"), custom.resolve("custom_item_registry.nbt"), custom.resolve("custom_block_properties.nbt"));
        if (result.skipped() != null) {
            ctx.report.check(Report.Status.SKIP, "Creative order (creative order probe)", result.skipped());
        } else if (result.matches()) {
            ctx.report.check(Report.Status.PASS, "Creative order (creative order probe)", "CreativeOrdering.BDS reproduces the "
                + "creative inventory BDS sent (" + result.customStacks() + " custom stacks)");
        } else {
            ctx.compared(false, "Creative order (creative order probe)", "BDS places custom content differently "
                + "from CreativeOrdering.BDS: " + result.differences().size() + "+ differences");
            List<String> lines = new ArrayList<>();
            result.differences().forEach(difference -> lines.add("- `" + difference.replace("`", "'") + "`"));
            ctx.report.evidence("Differences", lines);
            ctx.report.action("update CreativeOrdering.BDS and the committed probe captures (docs/wire-notes.md)");
        }
    }

    /** History check: the creative inventory of the creative order probe against the earlier build's, kept as {@value #PROBE_INVENTORY}. */
    private void probeCreative(Runs runs) throws IOException {
        byte[] creative = UpdateContext.gzip(JsonParser.parseString(Files.readString(runs.creative.resolve("creative_items.json"))).toString());
        ctx.stage(ctx.buildDirectory.resolve(PROBE_INVENTORY), creative, "creative order probe inventory");
        Path earlier = ctx.bdsData.resolve(ctx.against).resolve(PROBE_INVENTORY);
        if (!Files.exists(earlier)) {
            ctx.report.check(Report.Status.INFO, "Creative order probe inventory", "not compared: " + ctx.against + " has no `" + PROBE_INVENTORY + "`");
            ctx.report.notCompared.add("Creative order probe inventory: " + ctx.against + " has no `" + PROBE_INVENTORY + "`");
            return;
        }
        ctx.compared(BuildHistory.sameData(creative, Files.readAllBytes(earlier)), "Creative order probe inventory",
            "the creative order probe's creative inventory against " + ctx.against + "'s");
    }
}
