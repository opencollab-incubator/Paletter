package dev.opencollab.paletter.tools.shape;

import dev.opencollab.paletter.internal.shape.VoxelGrid;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import dev.opencollab.paletter.tools.blocks.CompiledBlocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voxel shape generator on a small store: the names and boxes of the constants, the registries, and that the
 * generated code, compiled and run, gives the store again.
 */
class ShapeCodeTest {
    private static final VoxelGrid SLAB = VoxelGrid.ofBoxes(0, 0, 0, 1, 0.5f, 1);
    /** An L of two boxes: three cells of a 2x2x1 grid. */
    private static final VoxelGrid STEP = VoxelGrid.ofBoxes(0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0.5f, 1, 1, 1);
    private static final VoxelGrid THIRD = VoxelGrid.ofBoxes(0, 0, 0, 1 / 3f, 1, 1);

    private static VoxelShapeStore store() {
        return new VoxelShapeStore(List.of(
            new VoxelShapeStore.Registry(List.of("1.1"), false, List.of(new VoxelShapeStore.Entry(VoxelGrid.EMPTY, "minecraft:empty", null, null),
                new VoxelShapeStore.Entry(SLAB, null, "test:slab", "test:slab"))),
            new VoxelShapeStore.Registry(List.of("1.2", "1.3"), false, List.of(new VoxelShapeStore.Entry(SLAB, null, "test:slab", "test:slab"),
                new VoxelShapeStore.Entry(STEP, null, "test:stairs", "test:stairs_2"), new VoxelShapeStore.Entry(THIRD, null, "test:third", "test:third"))),
            new VoxelShapeStore.Registry(List.of("1.2"), true, List.of(new VoxelShapeStore.Entry(STEP, "minecraft:step", null, null)))));
    }

    private static String file(Map<String, String> files, String name) {
        return files.get(ShapeCode.DIRECTORY + "/" + name + ".java");
    }

    @Test
    void eachShapeIsOneConstantOfItsBoxes() {
        Map<String, String> files = ShapeCode.generate(store());
        String table = file(files, "VoxelShapeTable");
        assertTrue(table.contains("static final VoxelGrid EMPTY = VoxelGrid.ofBoxes();"), table);
        assertTrue(table.contains("[0, 0, 0, 16, 8, 16] (in pixels).") && table.contains("BOX_0_0_0_16_8_16 = VoxelGrid.ofBoxes(0.0f, 0.0f, 0.0f, 1.0f, 0.5f, 1.0f);"),
            table);
        assertTrue(table.contains("[0, 0, 0, 16, 8, 16], [0, 8, 8, 16, 16, 16] (in pixels).") && table.contains(" SHAPE_TEST_STAIRS = "), table);
        assertTrue(table.contains(" SHAPE_TEST_THIRD = "), "a box off whole pixels is named after its block: " + table);
        String registry = file(files, "VoxelShapes1_2");
        assertTrue(registry.contains("s.block(BOX_0_0_0_16_8_16, \"test:slab\");") && registry.contains("s.block(SHAPE_TEST_STAIRS, \"test:stairs\", \"test:stairs_2\");"),
            registry);
        assertTrue(file(files, "VoxelShapes1_2Experiment").contains("s.named(\"minecraft:step\", SHAPE_TEST_STAIRS);"));
    }

    @Test
    void theBoxesOfAGridGiveItAgain() {
        assertArrayEquals(new float[] {0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0.5f, 1, 1, 1}, ShapeCode.boxes(STEP));
        assertArrayEquals(new float[0], ShapeCode.boxes(VoxelGrid.EMPTY));
    }

    @Test
    void theGeneratedCodeGivesTheStoreAgain(@TempDir Path directory) throws Exception {
        try (URLClassLoader loader = CompiledBlocks.compile(ShapeCode.generate(store()), directory, ShapeCode.PACKAGE)) {
            VoxelShapeStore loaded = (VoxelShapeStore) loader.loadClass(ShapeCode.PACKAGE + ".VoxelShapeData").getMethod("load").invoke(null);
            assertEquals(store().registries(), loaded.registries());
        }
    }
}
