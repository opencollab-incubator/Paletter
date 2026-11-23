package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.tools.codegen.ClassSize;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated block definitions and voxel shapes stay well inside the JVM's limits ({@link ClassSize}), so data that
 * grows fails here and not when the classes load. Read from the compiled classes.
 */
class GeneratedBlocksLimitsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    private static List<String> classes(String directory) throws IOException {
        try (Stream<Path> files = Files.list(ROOT.resolve(directory))) {
            return files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".java")).map(f -> f.replace(".java", "")).sorted().toList();
        }
    }

    @Test
    void everyGeneratedBlockClassIsWellInsideTheLimits() throws Exception {
        List<String> classes = classes(BlockCode.DIRECTORY);
        assertTrue(classes.size() > 3, classes.toString());
        assertEquals(List.of(), ClassSize.over(Class.forName(BlockCode.PACKAGE + ".BlockData"), classes), "lower Chunks.METHOD_STATEMENTS");
    }

    @Test
    void everyGeneratedShapeClassIsWellInsideTheLimits() throws Exception {
        String directory = "paletter/src/generated/java/dev/opencollab/paletter/internal/vanilla/shapes";
        List<String> classes = classes(directory);
        assertTrue(classes.size() > 3, classes.toString());
        assertEquals(List.of(), ClassSize.over(Class.forName("dev.opencollab.paletter.internal.vanilla.shapes.VoxelShapeData"), classes),
            "lower ShapeCode.CLASS_FLOATS or Chunks.METHOD_STATEMENTS");
    }
}
