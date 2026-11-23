package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.data.Diff;
import dev.opencollab.paletter.tools.data.WireShapes;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.model.Fixtures;
import dev.opencollab.paletter.tools.model.Model;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Component wire shapes: the vanilla data driven content against the bundled data, and the spec capture's coverage by the mappings. */
final class WireShapesStage {
    private final UpdateContext ctx;

    WireShapesStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /** Component wire shapes of the vanilla data driven items and blocks: the capture against the bundled reference version. */
    void vanillaShapes(Runs runs) throws Exception {
        WireShapes reference = WireShapes.ofPalette(ctx.resources.resolve(runs.referenceName + ".nbt"));
        WireShapes imported = WireShapes.ofPalette(runs.imported);
        report(Diff.shapes(reference.components(), imported.components()), "vanilla components vs bundled " + runs.referenceName);
        reportValues(Diff.values(reference.values(), imported.values()), "vanilla components vs bundled " + runs.referenceName);
    }

    /** Coverage: everything the spec capture sent must be something the component model can produce. */
    void coverage(Path capture) throws Exception {
        Path model = ctx.root.resolve(Layout.MODEL);
        JsonObject mappings = JsonParser.parseString(Files.readString(model.resolve("mappings.json"))).getAsJsonObject();
        Model components = Fixtures.read(model).apply(Model.read(model.resolve("components.json")));
        reportUncovered(WireShapes.classify(WireShapes.ofCapture(capture).uncovered(mappings), mappings, components));
    }

    /**
     * The captured wire paths the mappings do not cover, by reason ({@link WireShapes#classify}). An unclassified path is a
     * wire change and a TODO: the model or the classification has to learn it.
     */
    private void reportUncovered(Map<String, List<String>> uncovered) {
        List<String> unclassified = uncovered.getOrDefault(WireShapes.UNCLASSIFIED, List.of());
        ctx.report.check(unclassified.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Spec coverage", unclassified.isEmpty()
            ? "every captured wire path is in " + Layout.MODEL + "/mappings.json or has a known reason (listed below)"
            : unclassified.size() + " captured wire paths are not in the mappings and have no known reason");
        unclassified.forEach(path -> ctx.report.wireChanges.add("not covered by " + Layout.MODEL + "/mappings.json: " + path));
        List<String> lines = new ArrayList<>();
        uncovered.forEach((reason, paths) -> {
            lines.add("- " + reason + ":");
            paths.forEach(path -> lines.add("  - `" + path + "`"));
        });
        if (!lines.isEmpty()) {
            ctx.report.evidence("Wire paths the model does not cover", lines);
        }
        if (!unclassified.isEmpty()) {
            ctx.report.action("map them in " + Layout.MODEL + "/mappings.json or give WireShapes.classify a reason");
        }
    }

    /** A value that came or went in a component both sides send: the shape is the same, what BDS puts in it is not. */
    private void reportValues(Diff diff, String what) {
        List<String> changes = diff.lines(Diff.VALUE_CHANGES);
        ctx.compared(changes.isEmpty(), "Component values", what + ": " + (changes.isEmpty() ? "identical" : changes.size() + " paths with other values"));
        if (!changes.isEmpty()) {
            ctx.report.action("review the component model and the hand transforms");
            ctx.report.evidence("Paths with other values", changes.stream().map(c -> "- `" + c.replace("`", "'") + "`").toList());
        }
    }

    private void report(Diff diff, String what) {
        if (diff.identical()) {
            ctx.report.check(Report.Status.PASS, "Component shapes", what + ": identical");
            return;
        }
        ctx.compared(false, "Component shapes", what + ": "
            + diff.lines(Diff.WIRE_CHANGES).size() + " wire format changes, " + diff.lines(Diff.NEW_COMPONENTS).size() + " new components");
        diff.lines(Diff.WIRE_CHANGES).forEach(change -> ctx.report.wireChanges.add(what + ": " + change));
        List<String> lines = new ArrayList<>();
        diff.lines(Diff.WIRE_CHANGES).forEach(change -> lines.add("- wire format change `" + change.replace("`", "'") + "`"));
        diff.lines(Diff.NEW_COMPONENTS).forEach(c -> lines.add("- new component `" + c + "`"));
        ctx.report.evidence("Shape changes", lines);
        if (!diff.lines(Diff.WIRE_CHANGES).isEmpty()) {
            ctx.report.action("review the component model and the generated component types");
        }
    }
}
