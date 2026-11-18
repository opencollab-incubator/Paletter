package dev.opencollab.paletter;

import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.BlockFallback;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.internal.data.ExperimentContent;
import dev.opencollab.paletter.internal.data.Renames;
import dev.opencollab.paletter.internal.data.VanillaData;
import dev.opencollab.paletter.internal.shape.VoxelShapeStore;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content a version has only behind an experiment toggle counts as missing unless the toggle is enabled, so a flat
 * (non-forced) fallback applies to it, and only in the versions that gate it.
 */
class ExperimentGatingTest {
    private static final int OLDEST = 2168;
    private static final int MIDDLE = 2193;
    private static final String TOGGLE = "y_2026_drop_3";

    /** 26.40 has the cushions behind y_2026_drop_3, 26.50 has them as regular content (see data/bds/1.26.45.1). */
    @Test
    void aFlatFallbackAppliesWhereTheItemIsGatedOnly() {
        Fallbacks flat = Fallbacks.builder().item(BedrockItems.BLACK_CUSHION, ItemFallback.of(BedrockItems.STICK)).build();
        Paletter paletter = Paletter.builder().fallbacks(flat).build();

        Palette gated = paletter.palette(OLDEST);
        assertTrue(gated.resolve(BedrockItems.BLACK_CUSHION).fallback());
        assertEquals(BedrockItems.STICK.variant(), gated.resolve(BedrockItems.BLACK_CUSHION).variant());
        assertNull(gated.itemDefinition(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(), gated.experiments());

        Palette regular = paletter.palette(MIDDLE);
        assertFalse(regular.resolve(BedrockItems.BLACK_CUSHION).fallback());
        assertNotNull(regular.itemDefinition(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(), regular.experiments());

        // Without a fallback of its own the gated item is the default fallback, like content the version lacks
        assertEquals(BedrockItems.UNKNOWN.variant(), PALETTER.palette(OLDEST).resolve(BedrockItems.BLACK_CUSHION).variant());
    }

    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void lookupsSeeWhatThePaletteHas() {
        // The items behind a disabled toggle
        String cushion = BedrockItems.BLACK_CUSHION.identifier();
        Palette gated = Paletter.builder().build().palette(OLDEST);
        for (Lookup lookup : Lookup.values()) {
            assertNull(gated.itemVariant(cushion, 0, lookup));
        }
        assertTrue(gated.explainUnknown(cushion, 0, Lookup.EXACT).contains("disabled experiment " + TOGGLE));
        Palette all = Paletter.builder().allExperiments().build().palette(OLDEST);
        assertNotNull(all.itemVariant(cushion, 0, Lookup.EXACT));

        // The blocks behind a disabled toggle, in any version that has some
        Paletter plain = Paletter.builder().build();
        Paletter everything = Paletter.builder().allExperiments().build();
        boolean found = false;
        for (DataVersion version : plain.versions()) {
            for (String block : plain.vanilla(version).gating().missingBlocks()) {
                NbtMap state = plain.vanillaData(version).blockStates().stream()
                    .filter(candidate -> BlockStates.name(candidate).equals(block)).findFirst().orElseThrow();
                for (Lookup lookup : Lookup.values()) {
                    assertNull(plain.vanilla(version).blockState(state, lookup), block);
                }
                assertTrue(plain.vanilla(version).explainUnknown(state, Lookup.EXACT).contains("disabled experiment"), block);
                assertNotNull(everything.vanilla(version).blockState(state, Lookup.EXACT), block);
                found = true;
            }
        }
        assertTrue(found, "some version gates blocks behind a toggle");
    }

    @Test
    void removedItemsAreExplained() {
        Palette palette = Paletter.builder().removeItems(BedrockItems.STICK).build()
            .palette(MIDDLE);
        assertNull(palette.itemVariant("minecraft:stick", 0, Lookup.EXACT));
        assertTrue(palette.explainUnknown("minecraft:stick", 0, Lookup.EXACT).contains("Paletter.Builder.removeItems"));
    }

    @Test
    void allExperimentsEnablesTheVersionsToggleButNotVoxelShapes() {
        Paletter paletter = Paletter.builder().allExperiments().build();

        Palette all = paletter.palette(OLDEST);
        assertNotNull(all.itemDefinition(BedrockItems.BLACK_CUSHION));
        assertTrue(all.experiments().contains(TOGGLE));
        assertFalse(all.experiments().contains(VoxelShapeStore.EXPERIMENT));
        assertEquals(List.of(), paletter.palette(1001).experiments());
    }

    @Test
    void aVersionThatIsNotServedIsRejected() {
        DataVersion first = Paletter.builder().build().versions().getFirst();
        DataVersion other = new DataVersion("0.0", 1);
        PaletteException unknown = assertThrows(PaletteException.class, () -> Paletter.builder().experiments(other, TOGGLE).build());
        assertTrue(unknown.getMessage().contains(other.toString()), unknown.getMessage());
        // A version the predicate leaves out is not served either
        Paletter.Builder narrowed = Paletter.builder().versions(version -> version.equals(first));
        assertThrows(PaletteException.class, () -> narrowed.experiments(Paletter.builder().build().latest(), TOGGLE).build());
    }

    @Test
    void everyExperimentsCallAdds() {
        assertEquals(List.of(TOGGLE), Paletter.builder().experiments("unrelated").experiments(TOGGLE).build().palette(OLDEST).experiments());
        assertEquals(List.of(TOGGLE), Paletter.builder().experiments(TOGGLE).experiments("unrelated").build().palette(OLDEST).experiments());
        Paletter.Builder both = Paletter.builder().experiments("unrelated");
        both.experiments(Paletter.builder().build().version(OLDEST), TOGGLE);
        assertEquals(List.of(TOGGLE), both.build().palette(OLDEST).experiments());
    }

    @Test
    void allExperimentsAddsToExperimentsAndOnlyNamingTheVoxelShapesEnablesThem() {
        assertEquals(List.of(), Paletter.builder().allExperiments().build().palette(1001).experiments());
        assertEquals(List.of(VoxelShapeStore.EXPERIMENT),
            Paletter.builder().allExperiments().experiments(VoxelShapeStore.EXPERIMENT).build().palette(1001).experiments());
        Palette both = Paletter.builder().experiments(VoxelShapeStore.EXPERIMENT).allExperiments().build().palette(OLDEST);
        assertTrue(both.experiments().contains(TOGGLE));
    }

    @Test
    void enablingTheToggleMakesTheContentPresentAndIsSent() {
        Fallbacks flat = Fallbacks.builder().item(BedrockItems.BLACK_CUSHION, ItemFallback.of(BedrockItems.STICK)).build();
        Paletter paletter = Paletter.builder().fallbacks(flat).experiments(TOGGLE).build();

        Palette enabled = paletter.palette(OLDEST);
        assertFalse(enabled.resolve(BedrockItems.BLACK_CUSHION).fallback());
        assertNotNull(enabled.itemDefinition(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(TOGGLE), enabled.experiments());
        assertEquals(TOGGLE, enabled.experimentOf(BedrockItems.BLACK_CUSHION));
        assertNull(enabled.experimentOf(BedrockItems.STICK));

        StartGamePacket start = new StartGamePacket();
        enabled.applyTo(start);
        enabled.applyTo(start);
        assertEquals(List.of(new ExperimentData(TOGGLE, true)), start.getExperiments());
        ResourcePackStackPacket stack = new ResourcePackStackPacket();
        enabled.applyTo(stack);
        assertEquals(List.of(new ExperimentData(TOGGLE, true)), stack.getExperiments());

        // A version that does not gate anything behind it has nothing to enable
        Palette regular = paletter.palette(MIDDLE);
        assertEquals(List.of(), regular.experiments());
        StartGamePacket other = new StartGamePacket();
        regular.applyTo(other);
        assertEquals(List.of(), other.getExperiments());

        // An extension keeps it
        assertEquals(List.of(TOGGLE), enabled.extend(PaletteLayer.builder("test:ext").build()).experiments());
    }

    @Test
    void theReportNamesEveryToggleLeftDisabled() {
        PaletteReport report = PALETTER.palette(OLDEST).report();
        List<String> lines = report.entries().stream().filter(e -> e.source().equals("vanilla")).map(PaletteReport.Entry::message).toList();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().matches("26\\.40: \\d+ items, \\d+ blocks behind `" + TOGGLE + "` use fallbacks; enable with experiments\\(\"" + TOGGLE + "\"\\)"),
            lines.getFirst());
        assertTrue(report.entries().stream().anyMatch(e -> e.level() == PaletteReport.Level.INFO && e.message().contains(TOGGLE)));

        Paletter enabled = Paletter.builder().experiments(TOGGLE).build();
        assertTrue(enabled.palette(OLDEST).report().entries().stream().noneMatch(e -> e.message().contains(TOGGLE)));
        assertTrue(PALETTER.palette(MIDDLE).report().entries().stream().noneMatch(e -> e.message().contains("behind")));
    }

    /** The bundled data of 26.40 with a block of the palette gated, as a data driven block would be. */
    private static PaletteDataSource gatedBlock() {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        return new PaletteDataSource() {
            @Override
            public List<DataVersion> versions() {
                return bundled.versions();
            }

            @Override
            public VanillaData load(DataVersion version) throws IOException {
                return bundled.load(version).withExperiments(Map.of(
                    "block_toggle", new ExperimentContent(List.of(), List.of("minecraft:glass"), List.of())));
            }

            @Override
            public Renames renames() {
                return bundled.renames();
            }

            @Override
            public VoxelShapeStore voxelShapes() {
                return bundled.voxelShapes();
            }
        };
    }

    @Test
    void aGatedBlockResolvesThroughItsFallback() {
        BedrockBlockState glass = BedrockBlocks.GLASS.state().build();
        Fallbacks flat = Fallbacks.builder().block(BedrockBlocks.GLASS, BlockFallback.of(BedrockBlocks.STONE)).build();

        Palette missing = Paletter.builder().data(gatedBlock()).fallbacks(flat).build().palette(OLDEST);
        assertEquals("minecraft:stone", missing.blocks().byRuntimeId(missing.runtimeId(glass)).identifier());
        assertEquals("minecraft:stone", missing.explain(glass.toNbt()).chain().getLast());
        assertEquals(BlockExplanation.Kind.FALLBACK, missing.explain(glass.toNbt()).kind());
        // Without a fallback of its own: the default fallback
        Palette unknown = Paletter.builder().data(gatedBlock()).build().palette(OLDEST);
        assertEquals("minecraft:unknown", unknown.blocks().byRuntimeId(unknown.runtimeId(glass)).identifier());

        Palette present = Paletter.builder().data(gatedBlock()).fallbacks(flat).experiments("block_toggle").build().palette(OLDEST);
        assertEquals("minecraft:glass", present.blocks().byRuntimeId(present.runtimeId(glass)).identifier());
        assertEquals("block_toggle", present.experimentOf(BedrockBlocks.GLASS));
        assertEquals(List.of("block_toggle"), present.experiments());
        assertNull(present.experimentOf(BedrockBlocks.STONE));
    }
}
