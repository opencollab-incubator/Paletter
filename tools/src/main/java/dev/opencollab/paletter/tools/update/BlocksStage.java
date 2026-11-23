package dev.opencollab.paletter.tools.update;

import dev.opencollab.paletter.tools.blocks.BlockGenerator;
import dev.opencollab.paletter.tools.codegen.GeneratedSources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The vanilla block definitions, regenerated from the staged data: an imported or refreshed palette becomes generated
 * code. The version files keep their block states until {@link #slim} at the very end of the run: the stages after
 * {@code Staging#apply} (the spec capture, the shape import) read the bundled data in this process, whose compiled
 * definitions do not know a palette that is new or changed in this run.
 */
final class BlocksStage {
    private final UpdateContext ctx;
    private Map<String, byte[]> versionFiles = Map.of();

    BlocksStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /** Generates from {@code resources} (the staged copy of the bundled data) and stages the sources. */
    void blocks(Path resources) {
        BlockGenerator.Result result;
        try {
            result = BlockGenerator.generate(ctx.root, resources);
        } catch (Exception e) {
            // A palette the definitions cannot say, or anything else: the message says what; the data stays as staged
            ctx.report.check(Report.Status.FAIL, "Block definitions", e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        try {
            List<String> changes = GeneratedSources.sync(ctx.root, result.sources(), List.of(BlockGenerator.DIRECTORY), BlockGenerator.GENERATOR, true);
            ctx.report.check(changes.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Block definitions",
                changes.isEmpty() ? "the generated block definitions are unchanged" : changes.size() + " generated files change");
            result.inferred().forEach((version, blocks) -> {
                if (!blocks.isEmpty() && changes.stream().anyMatch(change -> change.contains("BlockChanges" + version.replaceAll("[^A-Za-z0-9]", "_") + "."))) {
                    ctx.report.decision("Block definitions: the state order of " + blocks.size() + " blocks of " + version + " is inferred, not observed "
                        + "(BDS sends no palette, and no second source has the version); check it against CloudburstMC/Data once it has the version",
                        "Inferred state order", blocks.stream().map(block -> "- " + block).toList());
                }
            });
            for (Path stale : GeneratedSources.stale(ctx.root, result.sources(), List.of(BlockGenerator.DIRECTORY), BlockGenerator.GENERATOR)) {
                ctx.report.repoChanges.add((ctx.verifyOnly ? "would " : "") + "remove `" + ctx.root.relativize(stale).toString().replace('\\', '/')
                    + "` (block definitions)");
                if (!ctx.verifyOnly) {
                    Files.delete(stale);
                }
            }
            for (Map.Entry<String, String> file : result.sources().entrySet()) {
                ctx.stage(ctx.root.resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8), "block definitions");
            }
            versionFiles = result.versionFiles();
        } catch (Exception e) {
            ctx.report.check(Report.Status.FAIL, "Block definitions", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The last step of the run: the version files without the block states, which the staged definitions give. */
    void slim() {
        try {
            for (Map.Entry<String, byte[]> file : versionFiles.entrySet()) {
                ctx.stage(ctx.resources.resolve(file.getKey()), file.getValue(), "bundled data without block states");
            }
        } catch (IOException e) {
            ctx.report.check(Report.Status.FAIL, "Block definitions", "the version files keep their block states: " + e);
        }
    }
}
