package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.data.Diff;
import org.geysermc.paletter.tools.client.RecipeCapture;
import org.geysermc.paletter.tools.data.RecipeData;
import org.geysermc.paletter.internal.data.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The recipe capture against the committed one, and the assumptions the recipe data rests on. */
final class RecipeStage {
    private final UpdateContext ctx;

    RecipeStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The recipe capture against the committed one of this build, or of the build before ({@link #baseline}), and the
     * assumptions the recipe data rests on (items and tags exist in the imported palette, results have their block state,
     * net ids are 1..n). Runs before the capture is committed, so it still sees the old one. A missing or unreadable
     * capture is a FAIL; the assumptions are only ever INFO.
     */
    void recipes(BdsDistribution distribution, Runs runs, VanillaData captured) throws IOException {
        if (!requireRecipeCapture(ctx.report, runs.vanilla)) {
            return;
        }
        RecipeCapture.Recipes recipes = RecipeCapture.read(runs.vanilla);
        // A history check compares with the build it is checked against
        String previousBuild = ctx.against != null ? ctx.against : UpdateContext.baseline(distribution.version(), ctx.committedBuilds("vanilla/" + RecipeCapture.CRAFTING));
        Path previous = previousBuild == null ? null : ctx.bdsData.resolve(previousBuild).resolve("vanilla");
        if (previous == null || !Files.exists(previous.resolve(RecipeCapture.CRAFTING))) {
            ctx.report.check(Report.Status.INFO, "Recipes", ctx.against != null ? "not compared: " + ctx.against + " has no " + RecipeCapture.CRAFTING
                : "no committed recipe capture to compare with");
            if (ctx.against != null) {
                ctx.report.notCompared.add("Recipes: " + ctx.against + " has no " + RecipeCapture.CRAFTING);
            }
        } else {
            Diff diff = Diff.recipes(RecipeCapture.read(previous), recipes);
            String compared = previousBuild.equals(distribution.version()) ? "the committed capture of " + previousBuild : previousBuild;
            ctx.report.check(diff.identical() ? Report.Status.PASS : Report.Status.DIFF, "Recipes vs " + compared,
                diff.identical() ? "identical" : diff.count() + " differences");
            ctx.report.diff("Recipes: BDS " + distribution.version() + " vs " + compared, diff);
        }
        Set<String> items = new HashSet<>();
        captured.items().forEach(item -> items.add(item.identifier()));
        assumption("Recipe items exist in the palette", RecipeData.missingItems(recipes, items));
        assumption("Recipe tags have members", RecipeData.emptyTags(recipes, captured.itemTags()));
        assumption("Recipe results have their block state", RecipeData.missingBlockStates(recipes));
        assumption("Recipe net ids are 1..n", RecipeData.netIdProblems(recipes));
    }


    /** A broken assumption is information about the recipe data, never a difference of the build. */
    private void assumption(String name, List<String> problems) {
        ctx.report.check(problems.isEmpty() ? Report.Status.PASS : Report.Status.INFO, name, problems.isEmpty() ? "holds" : problems.size() + " problems");
        if (!problems.isEmpty()) {
            ctx.report.evidence("Problems", problems.stream().map(problem -> "- " + problem).toList());
            if (ctx.against != null) {
                ctx.report.notCompared.add(name + ": " + problems.size() + " problems");
            }
        }
    }

    /**
     * Commits the recipe files of the capture with the build's other vanilla captures, but only when they differ from the
     * committed ones: the UUIDs of shaped and shapeless recipes are random per BDS start, so the bytes change on every run
     * ({@link Diff#recipes} compares them normalised).
     */
    void stageRecipes(BdsDistribution distribution, Runs runs) throws IOException {
        if (!Files.exists(runs.vanilla.resolve(RecipeCapture.CRAFTING))) {
            return;
        }
        Path committed = ctx.bdsData.resolve(distribution.version()).resolve("vanilla");
        if (!recipesChanged(committed, runs.vanilla)) {
            return;
        }
        for (String file : List.of(RecipeCapture.CRAFTING, RecipeCapture.TRIM)) {
            if (Files.exists(runs.vanilla.resolve(file))) {
                ctx.stageCopy(committed.resolve(file), runs.vanilla.resolve(file), "recipe capture " + file);
            }
        }
    }

    /** A capture without recipe data is a failure of the run (exit code 1), not something to compare. */
    static boolean requireRecipeCapture(Report report, Path vanilla) {
        if (Files.exists(vanilla.resolve(RecipeCapture.CRAFTING))) {
            return true;
        }
        report.check(Report.Status.FAIL, "Recipes", "no " + RecipeCapture.CRAFTING + " in the capture");
        return false;
    }

    /** Whether a capture's recipes differ from the committed ones (normalised), or none are committed. */
    static boolean recipesChanged(Path committed, Path captured) throws IOException {
        return !Files.exists(committed.resolve(RecipeCapture.CRAFTING))
            || !Diff.recipes(RecipeCapture.read(committed), RecipeCapture.read(captured)).identical();
    }
}
