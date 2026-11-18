package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.data.VariantNames;
import dev.opencollab.paletter.tools.codegen.constants.ConstantsGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The vanilla constants, regenerated from the staged data. */
final class ConstantsStage {
    private final UpdateContext ctx;

    ConstantsStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Regenerates the vanilla constants from the staged data (what the repository will have after this run), and lists
     * the constants added and deprecated. Staged with the data, so a data import never leaves stale constants.
     */
    void constants(Staging staging) throws IOException {
        ConstantsGenerator.Result result;
        try {
            result = generateConstants(ctx.root, staging.resources, staging.variantNames);
        } catch (IllegalStateException e) {
            // The message says what to do (a name override, a script-dump, a split in renames.txt)
            ctx.report.check(Report.Status.FAIL, "Vanilla constants", e.getMessage());
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("From " + result.release() + (result.preview() == null ? "" : " and preview " + result.preview()) + ": " + result.counts());
        result.added().forEach(added -> lines.add("added `" + added + "`"));
        result.deprecated().forEach(deprecated -> lines.add("deprecated `" + deprecated + "`"));
        result.tableBlocks().forEach(block -> lines.add("lookup table (states are not a full product in a stride order): " + block));
        ctx.report.check(result.added().isEmpty() && result.deprecated().isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Vanilla constants",
            result.added().size() + " added, " + result.deprecated().size() + " deprecated, " + result.tableBlocks().size() + " blocks with a lookup table");
        ctx.report.evidence("Constants", lines);
        for (Path stale : dev.opencollab.paletter.tools.codegen.constants.Main.stale(ctx.root, result.files())) {
            ctx.report.repoChanges.add((ctx.verifyOnly ? "would " : "") + "remove `" + ctx.root.relativize(stale).toString().replace('\\', '/') + "` (constants)");
            if (!ctx.verifyOnly) {
                Files.delete(stale);
            }
        }
        for (Map.Entry<String, String> file : result.files().entrySet()) {
            ctx.stage(ctx.root.resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8), "vanilla constants");
        }
    }

    /**
     * The vanilla constants of the bundled data in {@code resources} with the variant names {@code variantNames}, the
     * history and the captures of {@code root}.
     */
    static ConstantsGenerator.Result generateConstants(Path root, Path resources, Map<String, List<VariantNames.Entry>> variantNames) throws IOException {
        return ConstantsGenerator.generate(resources, ConstantsGenerator.names(root), ConstantsGenerator.readJson(root.resolve(ConstantsGenerator.HISTORY)),
            ConstantsGenerator.paletterVersion(root), ConstantsGenerator.Sources.read(resources, root.resolve(ConstantsGenerator.BDS), variantNames));
    }
}
