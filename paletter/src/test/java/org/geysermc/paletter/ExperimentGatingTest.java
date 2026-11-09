package org.geysermc.paletter;

import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.paletter.block.BlockFallback;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.internal.data.Renames;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(gated.isFallback(BedrockItems.BLACK_CUSHION.base()));
        assertEquals(BedrockItems.STICK.base(), gated.fallbackOf(BedrockItems.BLACK_CUSHION.base()));
        assertNull(gated.item(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(), gated.experiments());

        Palette regular = paletter.palette(MIDDLE);
        assertFalse(regular.isFallback(BedrockItems.BLACK_CUSHION.base()));
        assertNotNull(regular.item(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(), regular.experiments());

        // Without a fallback of its own the gated item is the default fallback, like content the version lacks
        assertEquals(BedrockItems.UNKNOWN.base(), PALETTER.palette(OLDEST).fallbackOf(BedrockItems.BLACK_CUSHION.base()));
    }

    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void enablingTheToggleMakesTheContentPresentAndIsSent() {
        Fallbacks flat = Fallbacks.builder().item(BedrockItems.BLACK_CUSHION, ItemFallback.of(BedrockItems.STICK)).build();
        Paletter paletter = Paletter.builder().fallbacks(flat).vanilla(vanilla -> vanilla.experiments(TOGGLE)).build();

        Palette enabled = paletter.palette(OLDEST);
        assertFalse(enabled.isFallback(BedrockItems.BLACK_CUSHION.base()));
        assertNotNull(enabled.item(BedrockItems.BLACK_CUSHION));
        assertEquals(List.of(TOGGLE), enabled.experiments());
        assertEquals(TOGGLE, enabled.experimentOf(BedrockItems.BLACK_CUSHION));
        assertEquals(TOGGLE, enabled.experimentOf(enabled.item(BedrockItems.BLACK_CUSHION)));
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
        assertTrue(lines.getFirst().matches("26\\.40: \\d+ items, \\d+ blocks behind `" + TOGGLE + "` use fallbacks; enable with vanilla\\(v -> v\\.experiments\\(\"" + TOGGLE + "\"\\)\\)"),
            lines.getFirst());
        assertTrue(report.entries().stream().anyMatch(e -> e.level() == PaletteReport.Level.INFO && e.message().contains(TOGGLE)));

        Paletter enabled = Paletter.builder().vanilla(vanilla -> vanilla.experiments(TOGGLE)).build();
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
        };
    }

    @Test
    void aGatedBlockResolvesThroughItsFallback() {
        BedrockBlockState glass = BedrockBlocks.GLASS.state().build();
        Fallbacks flat = Fallbacks.builder().block(BedrockBlocks.GLASS, BlockFallback.of(BedrockBlocks.STONE)).build();

        Palette missing = Paletter.builder().data(gatedBlock()).fallbacks(flat).build().palette(OLDEST);
        assertEquals("minecraft:stone", missing.blocks().byRuntimeId(missing.runtimeId(glass)).identifier());
        assertEquals("minecraft:stone", missing.explain(glass).chain().getLast());
        assertEquals(BlockExplanation.Kind.FALLBACK, missing.explain(glass).kind());
        // Without a fallback of its own: the default fallback
        Palette unknown = Paletter.builder().data(gatedBlock()).build().palette(OLDEST);
        assertEquals("minecraft:unknown", unknown.blocks().byRuntimeId(unknown.runtimeId(glass)).identifier());

        Palette present = Paletter.builder().data(gatedBlock()).fallbacks(flat).vanilla(vanilla -> vanilla.experiments("block_toggle")).build().palette(OLDEST);
        assertEquals("minecraft:glass", present.blocks().byRuntimeId(present.runtimeId(glass)).identifier());
        assertEquals("block_toggle", present.experimentOf(BedrockBlocks.GLASS));
        assertEquals(List.of("block_toggle"), present.experiments());
        assertNull(present.experimentOf(BedrockBlocks.STONE));
    }
}
