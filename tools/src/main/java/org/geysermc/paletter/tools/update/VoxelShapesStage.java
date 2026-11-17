package org.geysermc.paletter.tools.update;

import org.geysermc.paletter.tools.bds.BdsDistribution;
import org.geysermc.paletter.tools.shape.ShapeProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The voxel shapes of the build ({@link ShapeProbe}): captured, compared and staged. */
final class VoxelShapesStage {
    private final UpdateContext ctx;

    VoxelShapesStage(UpdateContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Captures the voxel shapes ({@link ShapeProbe}): into {@code data/bds/<build>/voxel-shapes} when this run writes, else
     * into the work directory, and says whether they are the committed ones.
     */
    void voxelShapes(BdsDistribution distribution, Path server, Path work, Runs runs) {
        Path target = ctx.buildDirectory.resolve(ShapeProbe.DIRECTORY);
        // A history check compares with the build it is checked against, apart from the version stamps
        Path committed = ctx.against != null ? ctx.bdsData.resolve(ctx.against).resolve(ShapeProbe.DIRECTORY) : target;
        Path captured = work.resolve(ShapeProbe.DIRECTORY);
        try {
            ShapeProbe.capture(captured, ctx.root, distribution.version(), server, work.resolve("voxel-shapes-run"), runs.reference, ctx.port, ctx.timeout);
            List<String> differing = new ArrayList<>();
            List<Path> files = BuildHistory.files(captured);
            for (Path file : files) {
                Path other = committed.resolve(file.getFileName());
                boolean same = Files.exists(other) && (ctx.against != null
                    ? BuildHistory.sameData(file, BuildHistory.formatVersion(captured), other, BuildHistory.formatVersion(committed))
                    : Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(other)));
                if (!same) {
                    differing.add(file.getFileName().toString());
                }
            }
            if (ctx.against != null) {
                // Files the earlier build has and this one does not (the probe writes some only when they say something)
                BuildHistory.files(committed).stream().map(f -> f.getFileName().toString())
                    .filter(name -> !Files.exists(captured.resolve(name))).forEach(name -> differing.add(name + " (not captured now)"));
            }
            ctx.compared(differing.isEmpty(), "Voxel shapes", differing.isEmpty() ? "`" + ctx.relative(committed) + "` has the same captures"
                + (ctx.against != null ? " (version stamps aside)" : "") : "`" + ctx.relative(committed) + "` is missing or differs: " + String.join(", ", differing));
            if (ctx.against != null) {
                for (Path file : files) {
                    ctx.stageCopy(target.resolve(file.getFileName()), file, "voxel shape capture " + file.getFileName());
                }
            } else if (!differing.isEmpty()) {
                for (String file : differing) {
                    ctx.stageCopy(committed.resolve(file), captured.resolve(file), "voxel shape capture " + file);
                }
                runs.voxelShapesWritten = !ctx.verifyOnly;
            }
        } catch (Exception e) {
            ctx.report.fail("Voxel shapes", e);
        }
    }
}
