package org.geysermc.paletter.internal.shape;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.SerializableVoxelShape;
import org.geysermc.paletter.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of {@link VoxelGrid}, {@link ShapeTransformation} and the name order on examples; the expected values are
 * what BDS 1.26.63.1 sent (data/bds/1.26.63.1/voxel-shapes), which the conformance test of the tools checks in full.
 */
class VoxelGridTest {
    private static float[] pixels(float... pixels) {
        float[] blocks = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            blocks[i] = pixels[i] / 16;
        }
        return blocks;
    }

    private static List<Short> shorts(int... values) {
        List<Short> list = new ArrayList<>();
        for (int value : values) {
            list.add((short) value);
        }
        return list;
    }

    @Test
    void theAnvilIsSentAsBdsSendsIt() {
        // behavior_packs/vanilla shapes: minecraft:anvil
        SerializableVoxelShape anvil = VoxelGrid.ofBoxes(pixels(2, 0, 2, 14, 4, 14, 4, 4, 3, 12, 5, 13, 6, 5, 4, 10, 16, 12, 3, 10, 0, 6, 16, 16,
            6, 10, 0, 13, 16, 4, 6, 10, 12, 13, 16, 16, 10, 10, 4, 13, 16, 12)).toWire();
        assertEquals(List.of(0.125f, 0.1875f, 0.25f, 0.375f, 0.625f, 0.75f, 0.8125f, 0.875f), anvil.getXCoordinates());
        assertEquals(List.of(0f, 0.25f, 0.3125f, 0.625f, 1f), anvil.getYCoordinates());
        assertEquals(List.of(0f, 0.125f, 0.1875f, 0.25f, 0.75f, 0.8125f, 0.875f, 1f), anvil.getZCoordinates());
        assertEquals(7, anvil.getCells().getXSize());
        assertEquals(4, anvil.getCells().getYSize());
        assertEquals(7, anvil.getCells().getZSize());
        assertEquals(shorts(62, 0, 0, 224, 3, 0, 254, 62, 14, 224, 239, 227, 32, 254, 62, 14, 224, 239, 3, 0, 254, 62, 0, 0, 0),
            anvil.getCells().getStorage());
        assertEquals(VoxelGrid.ofBoxes(pixels(2, 0, 2, 14, 4, 14, 4, 4, 3, 12, 5, 13, 6, 5, 4, 10, 16, 12, 3, 10, 0, 6, 16, 16,
            6, 10, 0, 13, 16, 4, 6, 10, 12, 13, 16, 16, 10, 10, 4, 13, 16, 12)), VoxelGrid.ofWire(anvil));
    }

    @Test
    void theEmptyShapeHasTheCoordinateZeroAndNoCells() {
        SerializableVoxelShape empty = VoxelGrid.EMPTY.toWire();
        assertEquals(List.of(0f), empty.getXCoordinates());
        assertEquals(0, empty.getCells().getXSize());
        assertTrue(empty.getCells().getStorage().isEmpty());
        // Boxes without volume are no shape
        assertSame(VoxelGrid.EMPTY, VoxelGrid.ofBoxes(0, 0, 0, 1, 0, 1));
        assertSame(VoxelGrid.EMPTY, VoxelGrid.ofBoxes());
    }

    @Test
    void boxesThatAreOneBoxAreMerged() {
        VoxelGrid cube = VoxelGrid.ofBoxes(0, 0, 0, 1, 1, 1);
        // The probe's split_full, nested and twice
        assertEquals(cube, VoxelGrid.ofBoxes(0, 0, 0, 0.5f, 1, 1, 0.5f, 0, 0, 1, 1, 1));
        assertEquals(cube, VoxelGrid.ofBoxes(0, 0, 0, 1, 1, 1, 0.25f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f));
        assertEquals(cube, VoxelGrid.ofBoxes(0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1));
        // The order of the boxes does not matter
        assertEquals(VoxelGrid.ofBoxes(pixels(6, 0, 6, 10, 16, 10, 0, 0, 0, 16, 2, 16)), VoxelGrid.ofBoxes(pixels(0, 0, 0, 16, 2, 16, 6, 0, 6, 10, 16, 10)));
        // What does not merge stays: two cells of the three of an L
        assertEquals(shorts(7), VoxelGrid.ofBoxes(pixels(0, 0, 0, 8, 8, 8, 8, 0, 0, 16, 8, 8, 0, 8, 0, 8, 16, 8)).toWire().getCells().getStorage());
    }

    @Test
    void aFaceIsWhatReachesItFromInside() {
        // The probe's apart: a cube in the lower north west corner and one in the upper south east corner
        VoxelGrid[] faces = VoxelGrid.ofBoxes(pixels(0, 0, 0, 4, 4, 4, 12, 12, 12, 16, 16, 16)).faces();
        assertEquals(VoxelGrid.ofBoxes(pixels(0, 0, 0, 4, 16, 4)), faces[0]);
        assertEquals(VoxelGrid.ofBoxes(pixels(12, 0, 12, 16, 16, 16)), faces[1]);
        assertEquals(VoxelGrid.ofBoxes(pixels(0, 0, 0, 4, 4, 16)), faces[2]);
        assertEquals(VoxelGrid.ofBoxes(pixels(12, 12, 0, 16, 16, 16)), faces[3]);
        assertEquals(VoxelGrid.ofBoxes(pixels(0, 0, 0, 16, 4, 4)), faces[4]);
        assertEquals(VoxelGrid.ofBoxes(pixels(0, 12, 12, 16, 16, 16)), faces[5]);
        // The probe's below: the box under the block ends at the lower face and is no part of it
        assertEquals(VoxelGrid.ofBoxes(pixels(0, 0, 0, 5.5f, 16, 11)),
            VoxelGrid.ofBoxes(pixels(0, -8, 0, 16, 0, 16, 0, 0, 0, 5.5f, 16, 11)).faces()[0]);
        // The probe's beyond_all: a box through the face is part of it, as wide as it is
        assertEquals(VoxelGrid.ofBoxes(pixels(-7, 0, -7, 23, 16, 23)), VoxelGrid.ofBoxes(pixels(-7, -7, -7, 23, 23, 23)).faces()[1]);
        assertSame(VoxelGrid.EMPTY, VoxelGrid.ofBoxes(pixels(4, 4, 4, 12, 12, 12)).faces()[3]);
    }

    private static ShapeTransformation transformation(Object... wire) {
        var map = NbtMap.builder();
        for (int i = 0; i < wire.length; i += 2) {
            map.put((String) wire[i], wire[i + 1]);
        }
        return ShapeTransformation.of(map.build());
    }

    private static void assertBox(VoxelGrid grid, float... expected) {
        assertArrayEquals(expected, grid.cellBoxes(), grid.toString());
    }

    @Test
    void transformationsAsBdsComputesThem() {
        // turn_x, turn_y, turn_z, turn_xyz of the probe: whole pixels
        assertBox(VoxelGrid.ofBoxes(pixels(0, 0, 0, 5, 7, 9)).transformed(transformation("RX", 1)), pixels(0, 7, 0, 5, 16, 7));
        assertBox(VoxelGrid.ofBoxes(pixels(0, 0, 0, 3, 5, 7)).transformed(transformation("RY", 1)), pixels(0, 0, 13, 7, 5, 16));
        assertBox(VoxelGrid.ofBoxes(pixels(0, 0, 0, 7, 9, 11)).transformed(transformation("RZ", 1)), pixels(7, 0, 0, 16, 7, 11));
        assertBox(VoxelGrid.ofBoxes(pixels(0, 0, 0, 4, 6, 10)).transformed(transformation("RX", 1, "RY", 2, "RZ", 3)), pixels(6, 0, 10, 16, 4, 16));
        // odd_scaled: every axis goes through (v - 0.5) * s + 0.5, also the one with the factor 1
        VoxelGrid odd = VoxelGrid.ofBoxes(3.1f / 16, 0.2f / 16, 0.3f / 16, 6.3f / 16, 5.1f / 16, 7.7f / 16);
        assertBox(odd.transformed(transformation("SX", 0.3f, "SY", 0.7f, "SZ", 1f)), 0.40812498f, 0.15875f, 0.018750012f, 0.468125f, 0.37312502f, 0.48125f);
        // odd_turn_y: a turn takes coordinates as they are, or as 1 - v
        VoxelGrid turned = VoxelGrid.ofBoxes(1.3000001f / 16, 0.2f / 16, 0.3f / 16, 4.5f / 16, 5.1f / 16, 7.7f / 16);
        assertBox(turned.transformed(transformation("RY", 1)), 0.01875f, 0.0125f, 0.71875f, 0.48125f, 0.31875f, 0.91875f);
        // pivot_turn: around a pivot the turned axes go through (v - c) + c'
        VoxelGrid pivot = VoxelGrid.ofBoxes(4.9f / 16, 0.2f / 16, 0.3f / 16, 8.1f / 16, 5.1f / 16, 7.7f / 16);
        assertBox(pivot.transformed(transformation("RY", 1, "RXP", 0.25f, "RZP", 0.125f)), 0.14375001f, 0.0125f, 0.86875f, 0.60625f, 0.31875f, 1.06875f);
        // odd_moved
        VoxelGrid moved = VoxelGrid.ofBoxes(3.7f / 16, 0.2f / 16, 0.3f / 16, 6.9f / 16, 5.1f / 16, 7.7f / 16);
        assertBox(moved.transformed(transformation("TX", 0.1f, "TY", -0.3f, "TZ", 0.2f)), 0.33125f, -0.28750002f, 0.21875f, 0.53125f, 0.018749982f, 0.68125f);
        assertSame(odd, odd.transformed(ShapeTransformation.IDENTITY));
    }

    @Test
    void theNamesComeInTheOrderOfBdsHashMap() {
        // The first names BDS 1.26.63.1 sends for its vanilla shapes, of the first eleven it registers
        List<String> inserted = List.of("minecraft:empty", "minecraft:unit_cube", "minecraft:anvil", "minecraft:bell_attachment_ceiling",
            "minecraft:bell_attachment_double_wall", "minecraft:bell_attachment_floor", "minecraft:bell_attachment_single_wall",
            "minecraft:big_dripleaf", "minecraft:big_dripleaf_tilt_full", "minecraft:big_dripleaf_tilt_partial", "minecraft:box_16x10x16");
        List<String> order = VoxelShapeCompiler.hashMapOrder(inserted);
        assertEquals(inserted.size(), order.size());
        assertTrue(order.containsAll(inserted));
        // Eleven names are in 64 buckets, and the names of a bucket are neighbours
        List<Long> buckets = new ArrayList<>();
        for (String name : order) {
            long bucket = BlockRegistry.nameHash(name) & 63;
            assertTrue(buckets.isEmpty() || buckets.getLast() == bucket || !buckets.contains(bucket), "the bucket of " + name + " is split");
            buckets.add(bucket);
        }
        // Up to eight names nothing grows: a new name goes to the end, or in front of the names of its bucket
        assertEquals(List.of("minecraft:empty", "minecraft:unit_cube"), VoxelShapeCompiler.hashMapOrder(inserted.subList(0, 2)));
        assertEquals(order, VoxelShapeCompiler.hashMapOrder(inserted));
    }
}
