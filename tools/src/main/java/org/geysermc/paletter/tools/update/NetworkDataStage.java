package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.VariantNames;
import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.bds.BdsDump;
import org.geysermc.paletter.tools.client.GiveCapture;
import org.geysermc.paletter.tools.data.Checker;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.data.Importer;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.tools.data.BundledFiles;
import org.geysermc.paletter.internal.data.VanillaData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The network data of the capture against the bundled version: the import, the variant names, the previews it drops. */
final class NetworkDataStage {
    private final UpdateContext ctx;
    private final RecipeStage recipes;

    NetworkDataStage(UpdateContext ctx) {
        this.ctx = ctx;
        this.recipes = new RecipeStage(ctx);
    }

    /**
     * Commits what {@code /give} gave per block ({@link GiveCapture}) with the build's vanilla captures. Only ever INFO: a block
     * without an answer is unverified, a different item is evidence for {@code Palette#itemOf}, and a failure here does not fail the run.
     */
    private void stageBlockItems(BdsDistribution distribution, Runs runs) {
        Path file = runs.vanilla.resolve(GiveCapture.FILE);
        try {
            if (!Files.exists(file)) {
                ctx.report.check(Report.Status.INFO, "Block items (/give)", "no " + GiveCapture.FILE + " in the capture");
                return;
            }
            List<GiveCapture.Entry> entries = GiveCapture.read(file);
            Map<String, String> expected = entries.stream().collect(java.util.stream.Collectors.toMap(GiveCapture.Entry::block, GiveCapture.Entry::item));
            GiveCapture.Check check = GiveCapture.check(entries, expected::get);
            ctx.report.check(Report.Status.INFO, "Block items (/give)", check.matched() + " of " + entries.size() + " blocks gave the item the data says, "
                + check.mismatches().size() + " another, " + check.unverified().size() + " unverified (no answer)");
            if (!check.mismatches().isEmpty()) {
                ctx.report.evidence("Blocks that gave another item than the data says", check.mismatches().stream().map(line -> "- " + line).toList());
            }
            ctx.stageCopy(ctx.bdsData.resolve(distribution.version()).resolve("vanilla").resolve(GiveCapture.FILE), file, "block items " + GiveCapture.FILE);
        } catch (Exception e) {
            ctx.report.check(Report.Status.INFO, "Block items (/give)", "not recorded: " + e);
        }
    }

    void networkData(BdsDistribution distribution, BdsDump dump, Runs runs, Staging staging) throws Exception {
        String name = runs.existing != null ? runs.existing : ctx.options.getOrDefault("name", Update.versionName(distribution.version()));
        ctx.report.header.put("Bundled version", runs.existing != null ? name + " (existing, same protocol)" : name + " (new protocol)");
        runs.bundledName = name;
        // The capture in the bundled format, imported into a scratch directory
        Path scratch = staging.directory.resolveSibling("imported");
        Files.createDirectories(scratch);
        new Importer(scratch).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog);
        VanillaData captured = BundledFiles.read(scratch.resolve(name + ".nbt"));
        runs.imported = scratch.resolve(name + ".nbt");
        Map<String, List<VariantNames.Entry>> variantNames = staging.variantNames;
        try {
            recipes.recipes(distribution, runs, captured);
            recipes.stageRecipes(distribution, runs);
            recipes.stageProbe(distribution, runs, scratch.resolveSibling("recipe-probe"));
        } catch (Exception e) {
            ctx.report.fail("Recipes", e);
        }
        stageBlockItems(distribution, runs);

        if (runs.existing != null) {
            VanillaData bundled = BundledFiles.read(ctx.resources.resolve(name + ".nbt"));
            Diff network = Diff.vanilla(bundled, captured, true, false);
            ctx.compared(network.identical(), "Network data vs bundled " + name, network.identical()
                ? "identical (items, runtime ids, components, data driven blocks, creative layout)" : network.count() + " differences");
            ctx.report.diff("Network data: BDS " + distribution.version() + " vs bundled " + name, network);
            if (!network.identical()) {
                ctx.report.action("same protocol, different data: decide whether to re-import " + name + " (`import --source "
                    + runs.vanilla + " --name " + name + " --protocol " + runs.protocol + " --bds-dump " + runs.dumpLog + "`)");
            }
            // Refresh what comes from the Script API (item tags, block defaults); keep the network data
            VanillaData refreshed = new VanillaData(bundled.version(), bundled.blockStates(), bundled.items(), bundled.dataDrivenBlocks(),
                bundled.creative(), captured.itemTags(), bundled.fallbacks(), captured.blockDefaults(), bundled.experiments(),
                captured.blockItems());
            Diff bds = Diff.vanilla(bundled, refreshed, false, true);
            // A history check compares the Script API (where these come from) with the earlier build instead
            ctx.notCompared(bds.identical(), "Item tags and block defaults vs bundled " + name, bds.identical() ? "identical" : bds.count() + " differences",
                "item tags and block defaults are compared build to build through the Script API dump");
            ctx.report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + name, bds);
            Files.write(staging.resources.resolve(name + ".nbt"), BundledFiles.bytes(refreshed));
            updateVariantNames(variantNames, name, dump, refreshed);
        } else if (ctx.against != null) {
            // Older than every bundled data version: nothing to compare the network data with
            ctx.compared(false, "Network data", "protocol " + runs.protocol + " is older than every bundled data version; "
                + "this build needs a data version of its own to be served");
        } else {
            Importer.IndexEntry latest = Importer.readIndex(ctx.resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
            VanillaData previous = BundledFiles.read(ctx.resources.resolve(latest.name() + ".nbt"));
            Diff diff = Diff.vanilla(previous, captured, true, true);
            ctx.report.check(Report.Status.DIFF, "New protocol " + runs.protocol, "not bundled yet; " + diff.count() + " differences to " + latest.name());
            ctx.report.diff("What changed: " + name + " (BDS " + distribution.version() + ") vs bundled " + latest.name(), diff);
            FallbackTodos.list(ctx, diff.lines("new blocks"), diff.lines("new items"), "network");
            if (ctx.captureOnly == null) {
                dropPreviews(distribution, name, runs.protocol, staging, variantNames);
            }
            new Importer(staging.resources).run(runs.vanilla, name, runs.protocol, null, runs.dumpLog, ctx.preview);
            ctx.report.check(Report.Status.INFO, "Import", "imported " + name + " (" + runs.protocol + ")" + (ctx.preview ? " as a preview version" : ""));
            // Content older versions lack is listed above (INFO); there are no bundled fallbacks to check
            new Checker(staging.resources).problems(ctx.preview);
            updateVariantNames(variantNames, name, dump, captured);
        }
        stageVariantNames(variantNames);
    }

    /** The variant names go to the repository; a history check stages only into its build's directory and keeps none. */
    void stageVariantNames(Map<String, List<VariantNames.Entry>> variantNames) throws IOException {
        if (ctx.against == null) {
            ctx.stage(ctx.root.resolve(VariantNames.FILE), VariantNames.render(variantNames).getBytes(StandardCharsets.UTF_8), "item variant names");
        }
    }

    /** The variant names of a version from the dump; an incomplete dump is a FAIL and leaves the names as they are. */
    private void updateVariantNames(Map<String, List<VariantNames.Entry>> variantNames, String name, BdsDump dump, VanillaData data) {
        try {
            VariantNames.update(variantNames, name, dump, data);
        } catch (IllegalStateException e) {
            ctx.report.check(Report.Status.FAIL, "Item variant names", e.getMessage() + "; the names of " + name + " stay as they are");
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
            if (!entry.preview() || (ctx.preview && sameName) || (!sameName && entry.protocol() > protocol)) {
                continue;
            }
            Importer.remove(staging.resources, entry.name());
            variantNames.remove(entry.name());
            ctx.report.check(Report.Status.INFO, "Import", "dropped preview " + entry.name() + " (" + entry.protocol() + "): "
                + (ctx.preview ? "a newer preview replaces it" : "its release is imported"));
            try (Stream<Path> dirs = Files.list(ctx.bdsData)) {
                for (Path dir : dirs.filter(d -> d.getFileName().toString().matches("\\d+\\..*")).toList()) {
                    String build = dir.getFileName().toString();
                    if (!build.equals(distribution.version()) && Update.versionName(build).equals(entry.name())) {
                        ctx.report.repoChanges.add((ctx.verifyOnly ? "would " : "") + "remove `" + ctx.root.relativize(dir).toString().replace('\\', '/')
                            + "` (captures of the dropped preview)");
                        if (!ctx.verifyOnly) {
                            BdsDistribution.deleteRecursively(dir);
                        }
                    }
                }
            }
        }
    }

    void missingNetworkData(BdsDistribution distribution, BdsDump dump) throws IOException {
        Importer.IndexEntry latest = Importer.readIndex(ctx.resources).stream().filter(e -> !e.preview()).reduce((a, b) -> b).orElseThrow();
        ctx.report.header.put("Bundled version", Update.versionName(distribution.version()) + " (protocol unknown: no network capture)");
        VanillaData previous = BundledFiles.read(ctx.resources.resolve(latest.name() + ".nbt"));
        // Item tags and block defaults are Script API data, so they can still be compared with the bundled newest release
        Map<String, NbtMap> defaults = Importer.defaults(dump, previous.blockStates());
        VanillaData fromDump = new VanillaData(previous.version(), previous.blockStates(), previous.items(), previous.dataDrivenBlocks(),
            previous.creative(), dump.itemTags(), previous.fallbacks(), defaults, previous.experiments(),
            Importer.blockItems(dump, previous.blockStates(), previous.items()));
        Diff bds = Diff.vanilla(previous, fromDump, false, true);
        ctx.report.diff("Item tags and block defaults: BDS " + distribution.version() + " vs bundled " + latest.name(), bds);
        ctx.report.check(Report.Status.SKIP, "Network data", "no capture; nothing imported");
    }
}
