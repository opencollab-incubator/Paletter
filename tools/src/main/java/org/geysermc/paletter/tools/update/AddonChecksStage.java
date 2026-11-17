package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.CreativeOrderCheck;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.Importer;
import org.geysermc.paletter.tools.Layout;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The creative inventory and the content log of the add-on and the creative order probe against the committed ones. */
final class AddonChecksStage {
    private final UpdateContext ctx;

    AddonChecksStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Paletter's default creative ordering ({@code CreativeOrdering.BDS}) against what BDS sent for the add-on and the
     * creative order probe (docs/wire-notes.md). A difference means BDS changed where it puts custom content.
     */
    void creativeOrder(Runs runs) throws IOException {
        if (ctx.against != null && runs.addon != null) {
            addonCreative(runs);
        }
        if (runs.existing == null) {
            ctx.report.check(Report.Status.INFO, "Creative order", "skipped: protocol " + runs.protocol + " is not bundled, "
                + "so Paletter has no vanilla layout of this version to place custom content in");
            return;
        }
        String[][] captures = {{"add-on", runs.addon == null ? null : runs.addon.toString(), runs.customCapture == null ? null : runs.customCapture.toString()},
            {"creative order probe", runs.creative == null ? null : runs.creative.toString(), runs.creative == null ? null : runs.creative.resolve("custom").toString()}};
        for (String[] capture : captures) {
            if (capture[1] == null) {
                ctx.report.check(Report.Status.FAIL, "Creative order (" + capture[0] + ")", "no capture");
                continue;
            }
            Path directory = Path.of(capture[1]);
            Path custom = Path.of(capture[2]);
            CreativeOrderCheck.Result result = CreativeOrderCheck.check(runs.protocol, ctx.resources,
                directory.resolve("creative_items.json"), custom.resolve("custom_item_registry.nbt"), custom.resolve("custom_block_properties.nbt"));
            if (result.skipped() != null) {
                ctx.report.check(Report.Status.SKIP, "Creative order (" + capture[0] + ")", result.skipped());
            } else if (result.matches()) {
                ctx.report.check(Report.Status.PASS, "Creative order (" + capture[0] + ")", "CreativeOrdering.BDS reproduces the "
                    + "creative inventory BDS sent (" + result.customStacks() + " custom stacks)");
            } else if (ctx.against != null && capture[0].equals("add-on")) {
                // The add-on world runs every experiment, whose vanilla stacks the bundled layout may lack; a history check
                // compares this creative inventory with the earlier build's instead (addonCreative)
                ctx.report.check(Report.Status.INFO, "Creative order (" + capture[0] + ")", "BDS places custom content differently "
                    + "from CreativeOrdering.BDS on the bundled layout: " + result.differences().size() + "+ differences; the "
                    + "creative inventory is compared with " + ctx.against + "'s");
                ctx.report.notCompared.add("Creative order (" + capture[0] + "): CreativeOrdering.BDS on the bundled layout places custom "
                    + "content differently; the add-on world's creative inventory itself is compared build to build");
            } else {
                ctx.compared(false, "Creative order (" + capture[0] + ")", "BDS places custom content differently "
                    + "from CreativeOrdering.BDS: " + result.differences().size() + "+ differences");
                List<String> lines = new ArrayList<>();
                result.differences().forEach(difference -> lines.add("- `" + difference.replace("`", "'") + "`"));
                ctx.report.evidence("Differences", lines);
                ctx.report.action("update CreativeOrdering.BDS and the committed probe captures (docs/wire-notes.md)");
            }
        }
    }

    /**
     * History check: the creative inventory of the add-on world (vanilla with every experiment, and the add-on's content)
     * against the earlier build's, kept as {@code addon/creative_items.json.gz}.
     */
    private void addonCreative(Runs runs) throws IOException {
        byte[] creative = UpdateContext.gzip(JsonParser.parseString(Files.readString(runs.addon.resolve("creative_items.json"))).toString());
        ctx.stage(ctx.buildDirectory.resolve("addon/creative_items.json.gz"), creative, "add-on creative inventory");
        Path earlier = ctx.bdsData.resolve(ctx.against).resolve("addon/creative_items.json.gz");
        if (!Files.exists(earlier)) {
            ctx.report.check(Report.Status.INFO, "Add-on creative inventory", "not compared: " + ctx.against + " has no `addon/creative_items.json.gz`");
            ctx.report.notCompared.add("Add-on creative inventory: " + ctx.against + " has no `addon/creative_items.json.gz`");
            return;
        }
        ctx.compared(BuildHistory.sameData(creative, Files.readAllBytes(earlier)), "Add-on creative inventory",
            "the add-on world's creative inventory against " + ctx.against + "'s");
    }

    /** Add-on definitions BDS rejects (content log errors and warnings) against the committed list. */
    void contentLog(Runs runs) throws IOException {
        if (runs.addonContentLog == null) {
            return;
        }
        Path file = ctx.root.resolve(Layout.CAPTURE_ADDON + "/content-log.txt");
        String current = String.join("\n", runs.addonContentLog) + "\n";
        if (ctx.against != null) {
            // A kept build keeps its log with its add-on capture; the one in data/addons/capture is the newest build's
            ctx.stage(ctx.buildDirectory.resolve("addon/content-log.txt"), current.getBytes(StandardCharsets.UTF_8), "add-on content log");
            file = ctx.bdsData.resolve(ctx.against).resolve("addon/content-log.txt");
            if (!Files.exists(file)) {
                ctx.report.check(Report.Status.INFO, "Add-on content log", "not compared: " + ctx.against + " has no add-on content log "
                    + "(what BDS rejects shows in the add-on capture, compared below)");
                ctx.report.notCompared.add("Add-on content log: " + ctx.against + " has none; what BDS rejects shows in the add-on capture, compared");
                return;
            }
        }
        String committed = Files.exists(file) ? Files.readString(file).replace("\r\n", "\n") : "";
        Set<String> before = new TreeSet<>(committed.lines().filter(l -> !l.isBlank()).toList());
        Set<String> after = new TreeSet<>(runs.addonContentLog);
        List<String> lines = new ArrayList<>();
        Diff.difference(after, before).forEach(line -> lines.add("- new: `" + line.replace("`", "'") + "`"));
        Diff.difference(before, after).forEach(line -> lines.add("- gone: `" + line.replace("`", "'") + "`"));
        ctx.compared(lines.isEmpty(), "Add-on content log",
            lines.isEmpty() ? runs.addonContentLog.size() + " known rejections/warnings, unchanged"
                : lines.size() + " changes in what BDS rejects or warns about in data/addons/capture");
        if (!lines.isEmpty()) {
            ctx.report.evidence("Content log changes", lines);
        }
        if (ctx.against == null && !olderThanNewestBundled(runs)) {
            // The committed log is the newest build's: a backfill of an older build does not replace it
            ctx.stage(file, current.getBytes(StandardCharsets.UTF_8), "add-on content log");
        }
    }

    /** Whether the build of this run is older than the newest bundled version (a backfill, not an update). */
    private boolean olderThanNewestBundled(Runs runs) throws IOException {
        if (runs.protocol == null) {
            return false;
        }
        return Importer.readIndex(ctx.resources).stream().anyMatch(entry -> entry.protocol() > runs.protocol && !entry.preview());
    }
}
