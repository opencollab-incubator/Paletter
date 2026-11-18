package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.Layout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.internal.data.ExperimentContent;
import dev.opencollab.paletter.tools.data.BundledFiles;
import dev.opencollab.paletter.internal.data.VanillaData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The "Experiments" report section, the committed experiment record and the experiment marks of the bundled version. */
final class ExperimentReportStage {
    private final UpdateContext ctx;

    ExperimentReportStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The "Experiments" report section, the committed record {@code data/bds/<bds version>/experiments.json} and the
     * experiment marks of the bundled version.
     */
    void experimentsReport(BdsDistribution distribution, Runs runs, Staging staging) throws IOException {
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
            ctx.report.check(Report.Status.INFO, "Experiments: interactions", result.unexplained.size() + " changes only appear with several toggles together");
        }
        docsExperiments(result, lines);
        ctx.report.section("Experiments", lines);

        if (!result.source.equals("discovered")) {
            return;
        }
        byte[] record = record(distribution, runs);
        if (ctx.against != null) {
            Path earlier = ctx.bdsData.resolve(ctx.against).resolve("experiments.json");
            boolean same = Files.exists(earlier) && BuildHistory.sameExperimentRecord(record, Files.readAllBytes(earlier));
            ctx.compared(same, "Experiment record vs " + ctx.against, same ? "same toggles and content (version stamps aside)"
                : "`" + ctx.root.relativize(earlier) + "` is missing or lists other toggles or content");
        }
        ctx.stage(ctx.bdsData.resolve(distribution.version()).resolve("experiments.json"), record, "experiment record");
        if (runs.bundledName != null && result.attribution.equals("each")) {
            Path file = staging.resources.resolve(runs.bundledName + ".nbt");
            VanillaData data = BundledFiles.read(file);
            Map<String, ExperimentContent> marks = new TreeMap<>();
            result.content.forEach((toggle, content) -> {
                if (!content.isEmpty()) {
                    marks.put(toggle, content);
                }
            });
            boolean same = new TreeMap<>(data.experiments()).equals(marks);
            ctx.notCompared(same, "Experiment marks vs bundled " + runs.bundledName,
                same ? "identical" : "bundled " + new TreeSet<>(data.experiments().keySet()) + ", captured " + marks.keySet(),
                "the experiment content is compared build to build in the experiment record");
            Files.write(file, BundledFiles.bytes(data.withExperiments(marks)));
        }
    }

    /** Which toggle each experiment named in the creator docs ({@code data/model/components.json}) is. */
    private void docsExperiments(ExperimentRun result, List<String> lines) throws IOException {
        Path model = ctx.root.resolve(Layout.MODEL + "/components.json");
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
        ctx.report.check(unknown.isEmpty() ? Report.Status.PASS : Report.Status.INFO, "Experiments: creator docs",
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
}
