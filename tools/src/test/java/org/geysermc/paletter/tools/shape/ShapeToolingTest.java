package org.geysermc.paletter.tools.shape;

import com.google.gson.JsonObject;
import org.geysermc.paletter.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The voxel shape tooling itself: the reader of BDS's shape files, the probe add-on and the bundled data. */
class ShapeToolingTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));

    /** A compiled shape file as BDS 1.26.63.1 ships them: {@code minecraft:box_16x10x16} of its vanilla pack. */
    private static byte[] compiled(String identifier, float... boxes) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {0x7f, 'M', 'C', 'B', 1, 0, 21, 0, 110, 0, 0, 0});
        for (String text : List.of("minecraft:voxel_shape", identifier)) {
            bytes.write(text.length());
            bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
        bytes.write(boxes.length / 6);
        ByteBuffer floats = ByteBuffer.allocate(4 * boxes.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : boxes) {
            floats.putFloat(value);
        }
        bytes.writeBytes(floats.array());
        return bytes.toByteArray();
    }

    @Test
    void readsCompiledAndJsonShapeFiles() throws IOException {
        Map.Entry<String, List<ShapeAddon.Box>> compiled = ShapeFiles.read(compiled("minecraft:box_16x10x16", 0, 0, 0, 16, 10, 16));
        assertEquals("minecraft:box_16x10x16", compiled.getKey());
        assertEquals(List.of(new ShapeAddon.Box(0, 0, 0, 16, 10, 16)), compiled.getValue());

        JsonObject json = ShapeAddon.shapeFile("half", List.of(new ShapeAddon.Box(0, 0, 0, 16, 8, 16), new ShapeAddon.Box(1.5f, 8, 0, 16, 9, 16)), "1.26.60");
        Map.Entry<String, List<ShapeAddon.Box>> plain = ShapeFiles.read(json.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("paletter:half", plain.getKey());
        assertEquals(List.of(new ShapeAddon.Box(0, 0, 0, 16, 8, 16), new ShapeAddon.Box(1.5f, 8, 0, 16, 9, 16)), plain.getValue());
    }

    @Test
    void aCompiledFileWithBytesLeftIsRefused() {
        byte[] file = compiled("minecraft:x", 0, 0, 0, 16, 10, 16);
        byte[] longer = java.util.Arrays.copyOf(file, file.length + 3);
        assertThrows(IOException.class, () -> ShapeFiles.read(longer));
    }

    @Test
    void readsAnArchive() throws IOException {
        byte[] first = compiled("minecraft:a", 0, 0, 0, 16, 16, 16);
        byte[] second = compiled("minecraft:b", 0, 0, 0, 8, 8, 8, 8, 8, 8, 16, 16, 16);
        ByteBuffer archive = ByteBuffer.allocate(16 + 2 * 256 + first.length + second.length).order(ByteOrder.LITTLE_ENDIAN);
        archive.putLong(0x267052a0b125277dL).putInt(2).putInt(1);
        int offset = 0;
        for (byte[] file : List.of(first, second)) {
            int entry = archive.position();
            archive.put((byte) 6).put("a.json".getBytes(StandardCharsets.UTF_8));
            archive.position(entry + 248).putInt(offset).putInt(file.length);
            offset += file.length;
        }
        archive.put(first).put(second);
        List<byte[]> files = ShapeFiles.archive(archive.array());
        assertEquals(2, files.size());
        assertEquals("minecraft:a", ShapeFiles.read(files.get(0)).getKey());
        assertEquals(2, ShapeFiles.read(files.get(1)).getValue().size());
    }

    @Test
    void everyVariantHasAShapeFileAndEveryBlockNamesAShapeOrNone() {
        Map<String, JsonObject> files = ShapeAddon.files("1.26.60");
        long shapes = files.keySet().stream().filter(path -> path.startsWith("shapes/")).count();
        assertEquals(ShapeAddon.variants().size(), shapes);
        for (Map.Entry<String, JsonObject> file : files.entrySet()) {
            assertEquals("1.26.60", file.getValue().get("format_version").getAsString(), file.getKey());
            if (file.getKey().startsWith("blocks/")) {
                JsonObject block = file.getValue().getAsJsonObject("minecraft:block");
                assertTrue(block.getAsJsonObject("description").get("identifier").getAsString().startsWith("paletter:"), file.getKey());
                assertNotNull(block.getAsJsonObject("components").get("minecraft:geometry"), file.getKey());
            }
        }
        // The shapes whose files sort the other way round than their identifiers
        assertTrue(files.containsKey("shapes/zz_order.json") && files.containsKey("shapes/aa_order.json"));
    }

    @Test
    void anAnchorSortsJustBeforeItsBlock() {
        List<String> blocks = List.of("minecraft:stone", "minecraft:dirt", "minecraft:anvil", "minecraft:oak_log", "minecraft:glass");
        List<ShapeAddon.Anchor> anchors = ShapeAddon.anchors(blocks);
        // Five blocks, far apart: an anchor before each and one after the last
        assertEquals(6, anchors.size());
        long previous = 0;
        for (int i = 0; i < anchors.size(); i++) {
            ShapeAddon.Anchor anchor = anchors.get(i);
            long hash = BlockRegistry.nameHash("paletter:" + anchor.block());
            assertTrue(i == 0 || Long.compareUnsigned(previous, hash) < 0, "anchors in hash order");
            if (anchor.before() != null) {
                long block = BlockRegistry.nameHash(anchor.before());
                assertTrue(Long.compareUnsigned(hash, block) < 0, anchor.block() + " before " + anchor.before());
                assertTrue(i == 0 || Long.compareUnsigned(previous, block) < 0, "the block before " + anchor.before() + " comes first");
                previous = block;
            } else {
                assertEquals(anchors.size() - 1, i, "only the last anchor follows every block");
            }
        }
        // No two anchors have the same shape
        assertEquals(anchors.size(), anchors.stream().map(ShapeAddon.Anchor::box).distinct().count());
        assertEquals(anchors, ShapeAddon.anchors(blocks), "the same anchors every time");
    }

    @Test
    void blocksWithNeighbouringHashesShareAnAnchor() {
        // Names that differ in their last character have hashes that differ in their last byte: no name fits between
        List<ShapeAddon.Anchor> anchors = ShapeAddon.anchors(List.of("minecraft:element_10", "minecraft:element_11"));
        assertEquals(2, anchors.size());
        assertNotNull(anchors.get(0).before());
        assertEquals(null, anchors.get(1).before());
    }

    @Test
    void theBundledVoxelShapesAreWhatTheCapturesGive() throws IOException {
        assertTrue(ShapeImport.check(ROOT), "voxel_shapes.nbt is stale: run ./gradlew :tools:run --args=\"voxel-shapes import\"");
    }
}
