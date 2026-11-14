package org.geysermc.paletter.block;

import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Palettes;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voxel shape API. That the packet is the one BDS sends is the job of the conformance test of the tools
 * ({@code VoxelShapeConformanceTest}); this is about what a consumer sees.
 */
class VoxelShapesTest {
    private static final BlockShape SLAB = BlockShape.of(0, 0, 0, 1, 0.5f, 1);
    /** A post on a plate: its faces are new shapes. */
    private static final BlockShape POST = BlockShape.of(0, 0, 0, 1, 2 / 16f, 1, 6 / 16f, 2 / 16f, 7 / 16f, 9 / 16f, 1, 10 / 16f);

    private static CustomBlock block(String identifier, String cullingShape, Transformation transformation) {
        CustomBlock.Builder block = CustomBlock.builder(identifier)
            .component(Geometry.builder().identifier("geometry.example").cullingShape(cullingShape).build())
            .component(MaterialInstances.builder().all(MaterialInstances.Instance.builder().texture("lamp").build()).build());
        if (transformation != null) {
            block.component(transformation);
        }
        return block.build();
    }

    @Test
    void everyBundledVersionHasItsVanillaShapes() {
        Paletter paletter = Paletter.bundled();
        for (DataVersion version : paletter.versions()) {
            VoxelShapes shapes = paletter.vanilla(version).voxelShapes();
            assertNotNull(shapes, version.toString());
            assertEquals(BlockShape.EMPTY, shapes.get("minecraft:empty"), version.toString());
            assertEquals(BlockShape.FULL_CUBE, shapes.get("minecraft:unit_cube"), version.toString());
            assertNull(shapes.get("example:none"));
            VoxelShapesPacket packet = paletter.vanilla(version).voxelShapesPacket();
            assertEquals(shapes.size(), packet.getShapes().size());
            assertEquals(shapes.names(), packet.getNameMap().keySet());
            assertEquals(0, packet.getCustomShapeCount(), version.toString());
        }
        // Since 26.40 BDS has its vanilla shapes without an experiment
        assertEquals(BlockShape.of(0, 0, 0, 1, 10 / 16f, 1), paletter.palette(2225).voxelShapes().get("minecraft:box_16x10x16"));
    }

    @Test
    void theExperimentChangesTheShapesOf2630Only() {
        Paletter plain = Paletter.bundled();
        Paletter experiment = Paletter.builder().vanilla(vanilla -> vanilla.experiments("voxel_shapes")).build();
        assertEquals(2, plain.palette(1001).voxelShapes().size());
        assertTrue(experiment.palette(1001).voxelShapes().size() > 2);
        assertEquals(List.of("voxel_shapes"), experiment.palette(1001).experiments());
        // Later versions have nothing behind the toggle
        assertEquals(plain.palette(2225).voxelShapes().names(), experiment.palette(2225).voxelShapes().names());
        assertEquals(List.of(), experiment.palette(2225).experiments());
    }

    @Test
    void aLayerDefinesShapesAndBlocksNameThem() {
        PaletteLayer layer = PaletteLayer.builder("shapes")
            .voxelShape("example:slab", SLAB)
            .voxelShape("example:post", POST)
            .block(block("example:post", "example:post", null))
            .build();
        Palette vanilla = Palettes.palette(2225);
        Palette palette = Palettes.palette(2225, layer);
        VoxelShapes shapes = palette.voxelShapes();
        assertEquals(SLAB, shapes.get("example:slab"));
        assertTrue(shapes.names().containsAll(List.of("example:slab", "example:post", "minecraft:anvil")));
        // The slab is the vanilla minecraft:box_16x8x16: one shape, two names. The post is new, and so is the shape of the
        // upper face of the block that names it (the post); its other faces are the unit cube and minecraft:box_16x2x16
        VoxelShapesPacket packet = palette.voxelShapesPacket();
        assertEquals(packet.getNameMap().get("minecraft:box_16x8x16"), packet.getNameMap().get("example:slab"));
        assertEquals(2, packet.getCustomShapeCount());
        assertEquals(vanilla.voxelShapes().size() + 2, shapes.size());
        // Vanilla shapes keep their ids
        vanilla.voxelShapesPacket().getNameMap().forEach((name, id) -> assertEquals(id, packet.getNameMap().get(name), name));
        assertTrue(palette.report().errors().isEmpty() && palette.report().warnings().isEmpty(), palette.report().toString());
    }

    @Test
    void aTurnedBlockGetsATurnedShape() {
        BlockShape corner = BlockShape.of(0, 0, 0, 3 / 16f, 5 / 16f, 7 / 16f);
        PaletteLayer layer = PaletteLayer.builder("turned")
            .voxelShape("example:corner", corner)
            .block(block("example:turned", "example:corner", Transformation.builder().rotation(0, 90, 0).build()))
            .build();
        VoxelShapesPacket packet = Palettes.palette(2225, layer).voxelShapesPacket();
        // What BDS sends for it (capture of the probe add-on, turn_y): (x, z) -> (z, 1 - x)
        boolean turned = packet.getShapes().stream().anyMatch(shape -> shape.getXCoordinates().equals(List.of(0f, 7 / 16f))
            && shape.getYCoordinates().equals(List.of(0f, 5 / 16f)) && shape.getZCoordinates().equals(List.of(13 / 16f, 1f)));
        assertTrue(turned, "the corner turned around y");
    }

    @Test
    void aBlockNamingAnUnknownShapeIsReported() {
        PaletteLayer layer = PaletteLayer.builder("unknown").block(block("example:lost", "example:nowhere", null)).build();
        Palette palette = Palettes.palette(2225, layer);
        assertEquals(Palettes.palette(2225).voxelShapes().size(), palette.voxelShapes().size());
        assertTrue(palette.report().warnings().stream().anyMatch(entry -> entry.toString().contains("example:nowhere")), palette.report().toString());
    }

    @Test
    void whatBdsRefusesIsRefused() {
        PaletteLayer.Builder layer = PaletteLayer.builder("refused");
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("minecraft:mine", SLAB));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:none", BlockShape.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:flat", BlockShape.of(0, 0, 0, 1, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:inverted", BlockShape.of(1, 1, 1, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:too_far", BlockShape.of(0, 0, 0, 1, 31 / 16f, 1)));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:outside", BlockShape.of(0, 1, 0, 1, 30 / 16f, 1)));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:wide", BlockShape.of(-14 / 16f, 0, 0, 18 / 16f, 1, 1)));
        layer.voxelShape("example:beyond", BlockShape.of(-14 / 16f, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:beyond", SLAB));
        assertEquals(List.of("example:beyond"), List.copyOf(layer.build().voxelShapes().keySet()));
    }

    @Test
    void aSessionLayerExtendsTheShapesAndOthersShareThem() {
        Palette base = Palettes.palette(2225);
        Palette untouched = base.extend(PaletteLayer.builder("nothing").build());
        assertSame(base.voxelShapes(), untouched.voxelShapes());
        Palette extended = base.extend(PaletteLayer.builder("session").voxelShape("example:post", POST).build());
        assertEquals(base.voxelShapes().size() + 1, extended.voxelShapes().size());
        assertEquals(1, extended.voxelShapesPacket().getCustomShapeCount());
    }
}
