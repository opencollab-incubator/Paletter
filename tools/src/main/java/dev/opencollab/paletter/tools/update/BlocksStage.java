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
 * code, and its version file is staged without the block states. Staged with the data, so a data import never leaves a
 * palette that only a version file has.
 */
final class BlocksStage {
    private final UpdateContext ctx;

    BlocksStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    void blocks(Staging staging) throws IOException {
        BlockGenerator.Result result;
        try {
            result = BlockGenerator.generate(ctx.root, staging.resources);
        } catch (IllegalStateException e) {
            // A palette the definitions cannot say: what it does is in the message
            ctx.report.check(Report.Status.FAIL, "Block definitions", e.getMessage());
            return;
        }
        List<String> changes = GeneratedSources.sync(ctx.root, result.sources(), List.of(BlockGenerator.DIRECTORY), BlockGenerator.GENERATOR, true);
        ctx.report.check(changes.isEmpty() ? Report.Status.PASS : Report.Status.DIFF, "Block definitions",
            changes.isEmpty() ? "the generated block definitions are unchanged" : changes.size() + " generated files change");
        for (Path stale : GeneratedSources.stale(ctx.root, result.sources(), List.of(BlockGenerator.DIRECTORY), BlockGenerator.GENERATOR)) {
            ctx.report.repoChanges.add((ctx.verifyOnly ? "would " : "") + "remove `" + ctx.root.relativize(stale).toString().replace('\\', '/') + "` (block definitions)");
            if (!ctx.verifyOnly) {
                Files.delete(stale);
            }
        }
        for (Map.Entry<String, String> file : result.sources().entrySet()) {
            ctx.stage(ctx.root.resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8), "block definitions");
        }
        // Into the staged copy: Staging#apply brings the version files to the repository
        for (Map.Entry<String, byte[]> file : result.versionFiles().entrySet()) {
            Files.write(staging.resources.resolve(Path.of(file.getKey()).getFileName().toString()), file.getValue());
        }
    }
}
