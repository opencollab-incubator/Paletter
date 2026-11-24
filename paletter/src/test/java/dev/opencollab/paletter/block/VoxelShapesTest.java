package dev.opencollab.paletter.block;

import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteException;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Palettes;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.MaterialInstances;
import dev.opencollab.paletter.block.component.Transformation;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.data.DataLoader;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final VoxelShape SLAB = VoxelShape.ofBoxes("example:slab", 0, 0, 0, 1, 0.5f, 1);
    /** A post on a plate: its faces are new shapes. */
    private static final VoxelShape POST = VoxelShape.ofBoxes("example:post", 0, 0, 0, 1, 2 / 16f, 1, 6 / 16f, 2 / 16f, 7 / 16f, 9 / 16f, 1, 10 / 16f);

    private static CustomBlock block(String identifier, String cullingShape, Transformation transformation) {
        CustomBlock.Builder block = CustomBlock.builder(identifier)
            .component(Geometry.builder().identifier("geometry.example").cullingShape(cullingShape).build())
            .component(MaterialInstances.builder().all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("lamp").build())).build());
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
            assertEquals(VoxelShape.EMPTY, shapes.get("minecraft:empty"), version.toString());
            assertEquals(VoxelShape.ofBoxes("example:cube", 0, 0, 0, 1, 1, 1), shapes.get("minecraft:unit_cube"), version.toString());
            assertNull(shapes.get("example:none"));
            VoxelShapesPacket packet = paletter.vanilla(version).voxelShapesPacket();
            assertEquals(shapes.size(), packet.getShapes().size());
            assertEquals(shapes.names(), packet.getNameMap().keySet());
            assertEquals(0, packet.getCustomShapeCount(), version.toString());
        }
        // Since 26.40 BDS has its vanilla shapes without an experiment
        assertEquals(VoxelShape.ofBoxes("example:box", 0, 0, 0, 1, 10 / 16f, 1), paletter.palette(2225).voxelShapes().get("minecraft:box_16x10x16"));
    }

    @Test
    void theExperimentChangesTheShapesOf2630Only() {
        Paletter plain = Paletter.bundled();
        Paletter experiment = Paletter.builder().experiments("voxel_shapes").build();
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
        assertTrue(palette.report().warnings().isEmpty(), palette.report().toString());
    }

    @Test
    void aTurnedBlockGetsATurnedShape() {
        VoxelShape corner = VoxelShape.ofBoxes("example:corner", 0, 0, 0, 3 / 16f, 5 / 16f, 7 / 16f);
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
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("minecraft:mine", 0, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:none"));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:flat", 0, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:inverted", 1, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:too_far", 0, 0, 0, 1, 31 / 16f, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:outside", 0, 1, 0, 1, 30 / 16f, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelShape.ofBoxes("example:wide", -14 / 16f, 0, 0, 18 / 16f, 1, 1));
        layer.voxelShape("example:beyond", VoxelShape.ofBoxes("example:beyond", -14 / 16f, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> layer.voxelShape("example:beyond", SLAB));
        assertEquals(List.of("example:beyond"), List.copyOf(layer.build().voxelShapes().keySet()));
    }

    @Test
    void theShapeIsItsGrid() {
        // An L: the lower slab and one upper half; minimal, so three planes on x and y and two on z
        VoxelShape shape = VoxelShape.ofBoxes("example:l", 0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0, 0.5f, 1, 1);
        assertArrayEquals(new float[] {0, 0.5f, 1}, shape.xCoordinates());
        assertArrayEquals(new float[] {0, 0.5f, 1}, shape.yCoordinates());
        assertArrayEquals(new float[] {0, 1}, shape.zCoordinates());
        assertEquals(2, shape.xSize());
        assertEquals(2, shape.ySize());
        assertEquals(1, shape.zSize());
        assertTrue(shape.cell(0, 0, 0) && shape.cell(1, 0, 0) && shape.cell(0, 1, 0));
        assertFalse(shape.cell(1, 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.cell(2, 0, 0));
        assertEquals(3, shape.boxes().size());
        assertEquals(shape, VoxelShape.ofBoxes("example:other", BlockShape.of(0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0, 0.5f, 1, 1)));
        assertEquals(shape.hashCode(), VoxelShape.ofBoxes("example:other", 0, 0, 0, 1, 0.5f, 1, 0, 0.5f, 0, 0.5f, 1, 1).hashCode());
        // The caller cannot change the shape
        shape.xCoordinates()[1] = 7;
        assertEquals(0.5f, shape.xCoordinates()[1]);
        assertTrue(VoxelShape.EMPTY.isEmpty());
        assertArrayEquals(new float[] {0}, VoxelShape.EMPTY.xCoordinates());
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

    @Test
    void aVersionWithoutVanillaShapesFailsWhenItsPaletteIsBuilt() {
        DataLoader bundled = Access.data().loader(PaletteDataSource.bundled());
        DataVersion missing = bundled.versions().getFirst();
        // The bundled shapes, less those of one version
        List<VoxelShapeStore.Registry> registries = new ArrayList<>();
        for (VoxelShapeStore.Registry registry : bundled.voxelShapes().registries()) {
            List<String> versions = new ArrayList<>(registry.versions());
            versions.remove(missing.name());
            registries.add(new VoxelShapeStore.Registry(versions, registry.experiment(), registry.shapes()));
        }
        VoxelShapeStore store = new VoxelShapeStore(registries);
        PaletteDataSource source = Access.data().source(new DataLoader() {
            @Override
            public List<DataVersion> versions() {
                return bundled.versions();
            }

            @Override
            public VanillaData load(DataVersion version) throws IOException {
                return bundled.load(version);
            }

            @Override
            public VoxelShapeStore voxelShapes() {
                return store;
            }
        });
        Paletter without = Paletter.builder().data(source).versions(version -> !version.equals(missing)).build();
        assertNotNull(without.palette(bundled.versions().get(1).protocolVersion()).voxelShapesPacket());
        PaletteException e = assertThrows(PaletteException.class, () -> Paletter.builder().data(source).build());
        assertTrue(e.getMessage().contains("voxel shapes for " + missing), e.getMessage());
    }
}
