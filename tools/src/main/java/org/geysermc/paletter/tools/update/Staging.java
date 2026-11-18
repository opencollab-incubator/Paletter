package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.data.VariantNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A copy of the bundled data that the stages change; {@link #apply} compares it with (or copies it into) the repository at the end. */
final class Staging {
    private final UpdateContext ctx;
    final Path directory;
    final Path resources;
    /** The item variant names the run stages: the constants are generated from these, also in a verify-only run. */
    final Map<String, List<VariantNames.Entry>> variantNames;

    Staging(UpdateContext ctx, Path directory) throws IOException {
        this.ctx = ctx;
        this.directory = directory;
        this.resources = directory.resolve("resources");
        this.variantNames = VariantNames.read(ctx.root.resolve(VariantNames.FILE));
        Files.createDirectories(resources);
        try (Stream<Path> files = Files.list(ctx.resources)) {
            for (Path file : files.toList()) {
                Files.copy(file, resources.resolve(file.getFileName()));
            }
        }
    }

    void apply() throws IOException {
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.sorted().toList()) {
                ctx.stageCopy(ctx.resources.resolve(file.getFileName()), file, "bundled data");
            }
        }
        // What the run removed (a dropped preview)
        try (Stream<Path> files = Files.list(ctx.resources)) {
            for (Path file : files.filter(f -> !Files.exists(resources.resolve(f.getFileName()))).sorted().toList()) {
                ctx.report.repoChanges.add((ctx.verifyOnly ? "would " : "") + "remove `" + ctx.root.relativize(file).toString().replace('\\', '/') + "` (bundled data)");
                if (!ctx.verifyOnly) {
                    Files.delete(file);
                }
            }
        }
    }
}
