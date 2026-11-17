package org.geysermc.paletter.tools.shape;

import org.geysermc.paletter.tools.bds.CapturePlatform;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.block.VoxelShape;
import org.geysermc.paletter.internal.shape.VoxelGrid;
import org.geysermc.paletter.internal.shape.VoxelShapeStore;
import org.geysermc.paletter.tools.client.VoxelShapeCapture;
import org.geysermc.paletter.tools.codegen.component.ApiBinder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voxel shape conformance suite, once per capture ({@code data/bds/<build>/voxel-shapes}): Paletter must send the
 * packet BDS sent, the same shapes with the same ids, the same names in the same order and the same count of custom
 * shapes, for
 * <ul>
 *     <li>the vanilla world, and the one with the experiment where that differs;</li>
 *     <li>the world with the probe add-on: its shape files registered with {@code PaletteLayer.Builder#voxelShape} in
 *     the order of their file names, its block files bound to the generated API ({@link ApiBinder});</li>
 *     <li>the world with the anchors, the same way.</li>
 * </ul>
 * The shapes BDS refused are exactly those {@code voxelShape} refuses, and every shape file BDS ships gives the shape
 * BDS sends under its name. No capture and no variant is left out.
 */
class VoxelShapeConformanceTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @TestFactory
    List<DynamicTest> everyCapture() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        // Captures of different BDS platforms are never compared: fails with the captures per platform if data/bds mixes them
        CapturePlatform.single(ROOT.resolve("data/bds"));
        List<ShapeImport.Captured> captures = ShapeImport.captures(ROOT);
        assertFalse(captures.isEmpty(), "no voxel shape captures under data/bds");
        for (ShapeImport.Captured capture : captures) {
            String build = "BDS " + capture.bdsVersion() + ": ";
            tests.add(DynamicTest.dynamicTest(build + "vanilla", () -> same(capture.packet("vanilla"), palette(capture, false, null))));
            if (capture.has("experiment.json")) {
                tests.add(DynamicTest.dynamicTest(build + "vanilla with the experiment", () -> same(capture.packet("experiment"), palette(capture, true, null))));
            }
            tests.add(DynamicTest.dynamicTest(build + "shape files of BDS", () -> shapeFiles(capture)));
            tests.add(DynamicTest.dynamicTest(build + "add-on", () -> same(capture.packet("addon"), palette(capture, true, addon(capture)))));
            if (capture.has("addon-plain.json")) {
                tests.add(DynamicTest.dynamicTest(build + "add-on without the experiment",
                    () -> same(capture.packet("addon-plain"), palette(capture, false, addon(capture)))));
            }
            tests.add(DynamicTest.dynamicTest(build + "anchors", () -> same(anchorPacket(capture, true), palette(capture, true, anchors(capture)))));
            if (capture.has("anchors-plain.json.gz")) {
                tests.add(DynamicTest.dynamicTest(build + "anchors without the experiment",
                    () -> same(anchorPacket(capture, false), palette(capture, false, anchors(capture)))));
            }
        }
        return tests;
    }

    private static VoxelShapesPacket anchorPacket(ShapeImport.Captured capture, boolean experiment) throws IOException {
        return VoxelShapeCapture.packet(capture.anchors(experiment).getAsJsonObject("packet"));
    }

    private static Palette palette(ShapeImport.Captured capture, boolean experiment, PaletteLayer layer) {
        Paletter.Builder builder = Paletter.builder();
        if (experiment) {
            builder.experiments(VoxelShapeStore.EXPERIMENT);
        }
        if (layer != null) {
            builder.layer(layer);
        }
        return builder.build().palette(capture.protocol());
    }

    private static void same(VoxelShapesPacket expected, Palette palette) {
        VoxelShapesPacket actual = palette.voxelShapesPacket();
        for (int i = 0; i < Math.min(expected.getShapes().size(), actual.getShapes().size()); i++) {
            assertEquals(expected.getShapes().get(i), actual.getShapes().get(i), "shape " + i);
        }
        assertEquals(expected.getShapes().size(), actual.getShapes().size(), "number of shapes");
        assertEquals(new ArrayList<>(expected.getNameMap().entrySet()), new ArrayList<>(actual.getNameMap().entrySet()), "names, in wire order");
        assertEquals(expected.getCustomShapeCount(), actual.getCustomShapeCount(), "custom shape count");
    }

    /** Every shape file of BDS's own packs gives the shape BDS sends under its name. */
    private static void shapeFiles(ShapeImport.Captured capture) throws IOException {
        JsonObject packs = JsonParser.parseString(Files.readString(capture.directory().resolve("vanilla-shapes.json")))
            .getAsJsonObject().getAsJsonObject("packs");
        VoxelShapesPacket sent = capture.packet(capture.has("experiment.json") ? "experiment" : "vanilla");
        int checked = 0;
        for (String pack : packs.keySet()) {
            for (Map.Entry<String, JsonElement> shape : packs.getAsJsonObject(pack).entrySet()) {
                Integer id = sent.getNameMap().get(shape.getKey());
                assertNotNull(id, "BDS did not send " + shape.getKey() + " of the pack " + pack);
                assertEquals(sent.getShapes().get(id), VoxelGrid.of(shape(shape.getValue())).toWire(), shape.getKey());
                checked++;
            }
        }
        // All names but minecraft:empty and minecraft:unit_cube, which BDS has without a file
        assertEquals(sent.getNameMap().size() - 2, checked, "named shapes with a file");
    }

    /** The boxes of a shape, {@code [[minX, minY, minZ, maxX, maxY, maxZ], ...]} in pixels, as a shape in blocks. */
    private static BlockShape shape(JsonElement boxes) {
        float[] coordinates = new float[6 * boxes.getAsJsonArray().size()];
        int at = 0;
        for (JsonElement box : boxes.getAsJsonArray()) {
            for (JsonElement coordinate : box.getAsJsonArray()) {
                coordinates[at++] = coordinate.getAsFloat() / 16;
            }
        }
        return BlockShape.of(coordinates);
    }

    /** The layer of the probe add-on, from the files the capture's build was given. */
    private static PaletteLayer addon(ShapeImport.Captured capture) throws IOException {
        JsonObject files = JsonParser.parseString(Files.readString(capture.directory().resolve("addon-variants.json")))
            .getAsJsonObject().getAsJsonObject("files");
        Map<String, JsonObject> byPath = new LinkedHashMap<>();
        files.entrySet().forEach(file -> byPath.put(file.getKey(), file.getValue().getAsJsonObject()));
        TreeSet<String> refused = new TreeSet<>();
        PaletteLayer layer = layer(byPath, refused);
        // The shapes BDS refused, by the file its content log names
        TreeSet<String> refusedByBds = new TreeSet<>();
        for (String line : Files.readAllLines(capture.directory().resolve("content-log.txt"))) {
            if (line.startsWith("ERROR [VoxelShape] shapes/")) {
                refusedByBds.add(line.substring("ERROR [VoxelShape] ".length(), line.indexOf(" | ")));
            }
        }
        assertEquals(refusedByBds, refused, "the shape files BDS " + capture.bdsVersion() + " refused");
        return layer;
    }

    private static PaletteLayer anchors(ShapeImport.Captured capture) throws IOException {
        List<ShapeAddon.Anchor> anchors = new ArrayList<>();
        for (JsonElement element : capture.anchors(true).getAsJsonArray("anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            float[] box = new float[6];
            for (int i = 0; i < 6; i++) {
                box[i] = anchor.getAsJsonArray("box").get(i).getAsFloat();
            }
            anchors.add(new ShapeAddon.Anchor(anchor.get("block").getAsString().substring(ShapeAddon.NAMESPACE.length() + 1),
                anchor.has("before") ? anchor.get("before").getAsString() : null,
                new ShapeAddon.Box(box[0], box[1], box[2], box[3], box[4], box[5])));
        }
        TreeSet<String> refused = new TreeSet<>();
        PaletteLayer layer = layer(ShapeAddon.anchorFiles(anchors, "1.26.0"), refused);
        assertTrue(refused.isEmpty(), "anchors refused: " + refused);
        return layer;
    }

    /**
     * A layer of the files of a pack: the blocks, and the shapes in the order BDS loads them in. That is the order in
     * which the file system lists the directory: the captures are of the Windows build, where NTFS lists names by their
     * upper case form ({@code 1A_ANCHOR} before {@code 1_ANCHOR}, the other way round than the plain names).
     *
     * @param refused receives the paths of the shape files {@code voxelShape} refuses
     */
    private static PaletteLayer layer(Map<String, JsonObject> files, TreeSet<String> refused) {
        PaletteLayer.Builder layer = PaletteLayer.builder("probe");
        Map<String, JsonObject> listed = new TreeMap<>(Comparator.comparing((String path) -> path.toUpperCase(Locale.ROOT)));
        listed.putAll(files);
        assertEquals(files.size(), listed.size(), "files that differ in case only");
        listed.forEach((path, json) -> {
            if (path.startsWith("shapes/")) {
                JsonObject shape = json.getAsJsonObject("minecraft:voxel_shape");
                List<Float> coordinates = new ArrayList<>();
                for (JsonElement box : shape.getAsJsonObject("shape").getAsJsonArray("boxes")) {
                    for (String corner : List.of("min", "max")) {
                        box.getAsJsonObject().getAsJsonArray(corner).forEach(value -> coordinates.add(value.getAsFloat() / 16));
                    }
                }
                float[] boxes = new float[coordinates.size()];
                for (int i = 0; i < boxes.length; i++) {
                    boxes[i] = coordinates.get(i);
                }
                try {
                    String identifier = shape.getAsJsonObject("description").get("identifier").getAsString();
                    layer.voxelShape(identifier, VoxelShape.ofBoxes(identifier, boxes));
                } catch (IllegalArgumentException e) {
                    refused.add(path);
                }
            } else {
                layer.block(ApiBinder.block(json));
            }
        });
        return layer.build();
    }
}
