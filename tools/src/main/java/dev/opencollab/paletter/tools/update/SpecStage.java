package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.bds.BdsDistribution;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.data.CreativeOrderCheck;
import dev.opencollab.paletter.tools.Layout;
import dev.opencollab.paletter.tools.model.addon.AddonGenerator;
import dev.opencollab.paletter.tools.model.addon.AddonWriter;
import dev.opencollab.paletter.tools.model.infer.MappingInference;
import dev.opencollab.paletter.tools.model.infer.SpecDiff;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.opencollab.paletter.tools.codegen.component.Gating;
import dev.opencollab.paletter.tools.codegen.component.HandTransforms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The spec capture of the build (with the format sweep) and the mapping inference over all captures. */
final class SpecStage {
    private final UpdateContext ctx;
    private final CaptureStage captures;

    SpecStage(UpdateContext ctx, CaptureStage captures) {
        this.ctx = ctx;
        this.captures = captures;
    }

    /**
     * Reports captures of different add-ons. The values and acceptance only cover the variants both were given alike: in a
     * history check that would call two captures of different add-ons identical, so it fails. A recapture's captures are
     * of the new add-on by design, compared with the committed ones of the old: only information.
     *
     * @return whether the comparison goes on
     */
    boolean variantsAlike(SpecDiff.Pairing pairing, String previousBuild) {
        if (ctx.against != null && ctx.captureOnly == null) {
            ctx.report.check(Report.Status.FAIL, "Spec capture variants", previousBuild + ": " + pairing.mismatch());
            return false;
        }
        ctx.report.check(Report.Status.INFO, "Spec capture variants", previousBuild + ": " + pairing.mismatch()
            + "; the comparison below covers the " + pairing.variants() + " variants alike only");
        return true;
    }

    /**
     * The spec capture of this build (every documented component field, varied: the add-on is generated for the build's
     * format version) and the mapping inference over all captures. The capture is compared with the newest earlier one
     * value by value ({@link SpecDiff}): a changed value (an axis that is mirrored, a unit, an enum id, bit packing) with the
     * same keys and types is a DIFF as well as a changed shape. Verify-only runs write the capture into the work directory
     * and infer nothing.
     */
    void specCapture(BdsDistribution distribution, Path work, Runs runs) throws Exception {
        // A history check captures into the work directory; keep() copies the capture when the build is kept
        boolean write = !ctx.verifyOnly && ctx.against == null && ctx.captureOnly == null;
        Path out = write ? ctx.bdsData.resolve(distribution.version()).resolve("spec") : work.resolve("spec");
        Map<String, String> specOptions = new LinkedHashMap<>(ctx.options);
        specOptions.put("version", distribution.version());
        specOptions.put("reuse-server", "true");
        specOptions.put("port", String.valueOf(ctx.port + 10));
        specOptions.put("protocol", String.valueOf(runs.protocol));
        specOptions.put("out", out.toString());
        // The format sweep: what BDS accepts at older formats of the components it rejects now, on the same server
        if (!ctx.verifyOnly && ctx.against == null) {
            specOptions.put("with-format-sweep", "true");
            // And a world without experiments, for the gating the creator docs claim (not essential: a missing file is "unverified")
            specOptions.put("with-gating", "true");
        }
        // The spec add-on world has this build's current toggles on, so it also covers experimental content
        specOptions.put("experiments", String.join(",", runs.experiments.current));
        // A history check compares with the build it is checked against
        String previousBuild = ctx.against != null ? ctx.against : UpdateContext.baseline(distribution.version(), ctx.committedBuilds("spec/capture.json"));
        boolean own = distribution.version().equals(previousBuild);
        Path previous = previousBuild == null ? null : ctx.bdsData.resolve(previousBuild).resolve("spec");
        if (own && write) {
            // The capture replaces the committed one: keep that to compare with
            Path committed = work.resolve("spec-committed");
            BdsDistribution.deleteRecursively(committed);
            copyRecursively(previous, committed);
            previous = committed;
        }
        SpecCapture spec = new SpecCapture(ctx.root, specOptions, runs.schema);
        try {
            // Only the files whose bytes changed are repository changes: a capture identical to the committed one is none
            if ((write ? UpdateContext.track(ctx.report, ctx.root, List.of(out, out.resolveSibling("spec-format"), out.resolveSibling("spec-gating")), "spec capture", spec::run)
                : spec.run()) != 0) {
                ctx.report.check(Report.Status.FAIL, "Spec capture", "failed");
                return;
            }
        } catch (Exception e) {
            ctx.report.fail("Spec capture", e);
            return;
        }
        ctx.report.check(Report.Status.INFO, "Spec capture", "`" + (write ? ctx.root.relativize(out) : out) + "`");
        List<String> unattributed = spec.unattributedLines();
        if (!unattributed.isEmpty()) {
            // The capture is written and the data goes on: a human classifies the lines
            ctx.report.check(Report.Status.FAIL, "Spec capture content log", unattributed.size() + " content log line(s) name no variant of the add-on and are no known phrasing");
            ctx.report.evidence("Lines", unattributed.stream().map(line -> "- `" + line.replace("`", "'") + "`").toList());
            ctx.report.action("classify these lines in `SpecCapture.UNATTRIBUTED` or the variant matcher");
        }
        cutValues(out);
        gating(out.resolveSibling("spec-gating"), runs);
        specChecks(out, spec.summary(), runs);
        SpecDiff.Pairing pairing = previous == null ? null : SpecDiff.pairing(previous, out);
        if (pairing != null && !pairing.same() && !variantsAlike(pairing, previousBuild)) {
            return;
        }
        if (previous != null) {
            List<SpecDiff.Change> changes = SpecDiff.compare(previous, out);
            String earlier = own ? "the committed capture of " + previousBuild : previousBuild;
            Map<String, List<SpecDiff.Change>> byComponent = new java.util.TreeMap<>();
            changes.forEach(change -> byComponent.computeIfAbsent(change.component(), k -> new ArrayList<>()).add(change));
            ctx.compared(changes.isEmpty(), "Spec capture values",
                changes.isEmpty() ? "every one of the " + pairing.variants() + " variants both were given alike sent as in " + earlier
                    : changes.size() + " leaves in " + byComponent.size() + " components differ from " + earlier + ", variant by variant ("
                    + pairing.variants() + " variants compared)");
            List<String> lines = new ArrayList<>();
            byComponent.forEach((component, list) -> {
                long values = list.stream().filter(SpecDiff.Change::valueChange).count();
                // Against this build's own capture BDS answers differently now: no new protocol window to check
                String how = own ? "BDS sends it differently from the committed capture of this build: re-capture and review"
                    : transformOf(component) != null ? "its hand transform `Transforms." + transformOf(component) + "` needs a protocol check"
                    : "the inferred mapping gets a protocol window (regenerate with :tools:inferMappings and :tools:generateComponentApi)";
                if (!own) {
                    ctx.report.wireChanges.add("spec capture vs " + earlier + ": " + component + ": " + list.size() + " leaves differ (" + values
                        + " with another value); " + how);
                }
                lines.add("- `" + component + "`: " + how);
                list.stream().limit(6).forEach(change -> lines.add("  - " + change));
                if (list.size() > 6) {
                    lines.add("  - ... " + (list.size() - 6) + " more");
                }
            });
            if (!lines.isEmpty()) {
                ctx.report.evidence("Components", lines);
                ctx.report.action(own ? "re-capture this build and review these components" : "review the protocol windows of these components");
            }
            // What BDS accepts and logs: a variant both builds were given that one accepts and the other rejects, or
            // that logs other lines
            List<String> acceptance = SpecDiff.acceptance(previous, out);
            ctx.compared(acceptance.isEmpty(), "Spec capture acceptance", acceptance.isEmpty()
                ? "every variant accepted or rejected, with the same content log, as in " + earlier
                : acceptance.size() + " variants accepted, rejected or logged differently than in " + earlier);
            if (!acceptance.isEmpty()) {
                List<String> variants = new ArrayList<>();
                acceptance.stream().limit(200).forEach(line -> variants.add("- `" + line.replace("`", "'") + "`"));
                if (acceptance.size() > 200) {
                    variants.add("- ... " + (acceptance.size() - 200) + " more");
                }
                ctx.report.evidence("Variants", variants);
            }
        }
        if (write) {
            MappingInference inference = new MappingInference(ctx.root);
            Path model = ctx.root.resolve(Layout.MODEL);
            UpdateContext.track(ctx.report, ctx.root, List.of(model.resolve("mappings.json"), model.resolve("coverage.md"), model.resolve("bds-feedback.json")),
                "inferred from the spec captures", () -> {
                    inference.run(false);
                    return null;
                });
            List<String> bits = inference.unexplainedBits();
            ctx.report.check(bits.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Residual bits", bits.isEmpty()
                ? "every bit the captures set is explained by a field or the format sweep" : bits.size() + " leaves have bits nothing explains");
            if (!bits.isEmpty()) {
                ctx.report.action("explain them by a field or the format sweep", "Leaves", bits.stream().map(finding -> "- " + finding).toList());
            }
            List<String> linked = inference.linkedFields();
            ctx.report.check(linked.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Linked fields", linked.isEmpty()
                ? "no field's evidence was decided by another field" : linked.size() + " fields need a capture that varies them with another field");
            if (!linked.isEmpty()) {
                ctx.report.action("capture again: the add-on generator links them from bds-feedback.json", "Fields", linked.stream().map(field -> "- " + field).toList());
            }
            buildGaps(ctx.report, BuildTable.gaps(ctx.root));
            ctx.report.check(Report.Status.INFO, "Component model", "mappings inferred; run :tools:generateComponentApi and the conformance suite (:tools:test) for the new protocol");
        } else {
            ctx.report.check(Report.Status.INFO, "Component model", "mappings not inferred (" + (ctx.captureOnly != null ? "capture only" : ctx.against != null ? "history check" : "verify only") + ")");
        }
    }

    /**
     * What the spec add-on world shows beyond the mappings: its creative block ids resolve to palette states (custom blocks
     * with trait states), start game reports the requested toggles, every captured wire path is in the mappings or has a known
     * reason, and BDS's block ids and block map order are Paletter's. What BDS rejects or accepts is compared with the earlier
     * capture ({@link SpecDiff#acceptance}).
     */
    private void specChecks(Path capture, Capture.Summary summary, Runs runs) throws Exception {
        captures.hashCheck("spec add-on", summary);
        captures.appliedCheck(runs.experiments, "spec add-on world", runs.experiments.current, summary);
        new ShapesStage(ctx).coverage(capture);
        if (runs.existing != null) {
            // The engine packs follow the main pack in the world's pack list (SpecCapture#freshWorld), one block each
            CreativeOrderCheck.Result order = CreativeOrderCheck.blockOrder(runs.protocol, ctx.resources,
                capture.resolve("custom_item_registry.nbt"), capture.resolve("custom_block_properties.nbt"),
                AddonWriter.enginePacks().stream().map(AddonWriter.EnginePack::identifier).toList());
            if (order.skipped() != null) {
                ctx.report.check(Report.Status.SKIP, "Block map order (spec add-on)", order.skipped());
            } else {
                // A history check numbers against the bundled data of its window; the capture's blocks are compared build to build
                ctx.notCompared(order.matches(), "Block map order (spec add-on)", order.matches()
                    ? "block ids and CreativeOrdering.BDS's block order are BDS's for " + order.customStacks() + " custom blocks"
                    : "BDS numbers or orders custom blocks differently: " + String.join("; ", order.differences()),
                    "the spec capture's block ids and order are compared with the earlier build's");
                if (!order.matches() && ctx.against == null) {
                    ctx.report.action("update BlockIdOrder and CreativeOrdering.BDS (BDS changed how it numbers or orders custom blocks; docs/wire-notes.md)");
                }
            }
        }
    }

    /**
     * What BDS does with the experiment-gated components in a world without experiments. Not essential: no file only leaves
     * {@code @BedrockExperiment} unverified. Components BDS rejects with the experiments on too are listed, not removed.
     */
    private void gating(Path dir, Runs runs) {
        Path file = dir.resolve("gating.json");
        if (!Files.exists(file)) {
            ctx.report.check(Report.Status.INFO, "Experiment gating", "not captured: @BedrockExperiment stays unverified against this build");
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> gated = new ArrayList<>();
            List<String> open = new ArrayList<>();
            List<String> never = new ArrayList<>();
            json.getAsJsonObject("components").entrySet().forEach(e -> {
                JsonObject value = e.getValue().getAsJsonObject();
                String without = value.get("without").getAsString();
                String with = value.has("with") ? value.get("with").getAsString() : null;
                (Gating.REJECTED.equals(with) ? never : without.equals(Gating.ACCEPTED) ? open : gated).add("- `" + e.getKey() + "`: " + without
                    + " without experiments" + (with == null ? "" : ", " + with + " with them"));
            });
            List<String> echoed = new ArrayList<>(json.getAsJsonArray("experiments").asList().stream().map(JsonElement::getAsString)
                .filter(toggle -> toggle.endsWith("=true")).map(toggle -> toggle.substring(0, toggle.length() - 5)).toList());
            echoed.removeAll(runs.experiments.alwaysOn);
            boolean clean = echoed.isEmpty();
            ctx.report.check(clean ? Report.Status.INFO : Report.Status.DIFF, "Experiment gating", gated.size() + " components BDS does not take without experiments, "
                + open.size() + " it takes without them, " + never.size() + " it rejects even with them"
                + (clean ? "" : "; the world was not experiment-free (start game reports " + echoed + "): discard the capture"));
            ctx.report.evidence("Experiment gating", Stream.of(gated, open, never).flatMap(List::stream).toList());
            if (!never.isEmpty()) {
                ctx.report.action("components BDS rejects even with the experiments on (kept, only reported)", "Components", never);
            }
        } catch (IOException | RuntimeException e) {
            ctx.report.check(Report.Status.INFO, "Experiment gating", "gating.json not readable: " + e);
        }
    }

    /** Says which values of the add-on the generator cut at {@code MAX_VALUES} (the capture's {@code valuesCut}): no variant has them. */
    private void cutValues(Path capture) {
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(capture.resolve("capture.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            if (meta.has("valuesCut")) {
                List<String> cut = meta.getAsJsonArray("valuesCut").asList().stream().map(e -> "- " + e.getAsString()).toList();
                ctx.report.check(Report.Status.INFO, "Add-on values cut", cut.size() + " fields have more values than the add-on varies (AddonGenerator.MAX_VALUES)");
                ctx.report.evidence("Values the add-on does not vary", cut);
            }
        } catch (IOException | RuntimeException e) {
            ctx.report.check(Report.Status.SKIP, "Add-on values cut", "capture.json not readable: " + e);
        }
    }

    /** The hand transform of a component, if {@link HandTransforms} names one (component level or for a field). */
    private static String transformOf(String component) {
        return HandTransforms.BY_PATH.entrySet().stream().filter(e -> e.getKey().startsWith(component + ":")).map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The check that every build sent the data of its data version: a build that did not needs a data version of its own. */
    static void buildGaps(Report report, List<String> gaps) {
        report.check(gaps.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Builds within a data version", gaps.isEmpty()
            ? "no build sent other data than the build chosen for its data version" : gaps.size() + " builds need a data version of their own");
        if (!gaps.isEmpty()) {
            report.action("import a data version for each build (decided by hand)", "Builds", gaps.stream().map(gap -> "- " + gap).toList());
        }
    }
}
