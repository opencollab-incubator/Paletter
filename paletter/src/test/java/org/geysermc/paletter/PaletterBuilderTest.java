package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativeOrdering;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.geysermc.paletter.internal.data.VanillaData;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global policy of {@link Paletter#builder()}.
 */
class PaletterBuilderTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final int OLDEST = 2168;

    private static List<String> stacks(Palette palette) {
        List<String> stacks = new ArrayList<>();
        palette.creativeLayout().forEachStack(stack -> stacks.add(stack.type().identifier() + ":" + stack.variant().aux()));
        return stacks;
    }

    @Test
    void defaultsEqualTheBundledPalettes() {
        Paletter built = Paletter.builder().build();
        assertEquals(PALETTER.versions(), built.versions());
        for (DataVersion version : PALETTER.versions()) {
            Palette expected = PALETTER.palette(version.protocolVersion());
            Palette actual = built.palette(version.protocolVersion());
            assertEquals(BlockNetworkIds.SEQUENTIAL, actual.blockNetworkIds());
            assertEquals(expected.blocks().size(), actual.blocks().size(), version.toString());
            assertEquals(expected.items().definitions().size(), actual.items().definitions().size(), version.toString());
            assertEquals(stacks(expected), stacks(actual), version.toString());
            assertEquals(expected.blocks().defaultFallback(), actual.blocks().defaultFallback());
            assertEquals(expected.items().defaultFallback(), actual.items().defaultFallback());
        }
    }

    @Test
    void paletteIsCompiledOncePerVersion() {
        Paletter built = Paletter.builder().build();
        int protocol = PALETTER.latest().protocolVersion();
        assertSame(built.palette(protocol), built.palette(protocol));
        assertSame(built.palette(protocol), built.palette(protocol, BlockNetworkIds.SEQUENTIAL, List.of()));
        assertSame(PALETTER.palette(OLDEST), PALETTER.palette(OLDEST));
    }

    @Test
    void globalLayersApplyToEveryVersionInOrder() {
        PaletteLayer first = PaletteLayer.builder("first")
            .item(CustomItem.builder("test:first").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build()).build();
        PaletteLayer second = PaletteLayer.builder("second")
            .item(CustomItem.builder("test:second").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build()).build();
        Paletter paletter = Paletter.builder().layer(first).layer(second).build();
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            assertEquals(List.of(first, second), palette.layers(), version.toString());
            assertNotNull(palette.items().getDefinition("test:first"));
            assertNotNull(palette.items().getDefinition("test:second"));
            assertTrue(palette.items().getDefinition("test:first").getRuntimeId() < palette.items().getDefinition("test:second").getRuntimeId());
        }
        assertNull(PALETTER.palette(OLDEST).items().getDefinition("test:first"));
    }

    @Test
    void fallbacksApplyPerVersion() {
        String oldest = PALETTER.version(OLDEST).name();
        String newest = PALETTER.latest().name();
        Fallbacks fallbacks = Fallbacks.builder()
            .item(oldest, "minecraft:poplar_boat", new ItemFallback("minecraft:stick", true))
            .item(newest, "minecraft:poplar_boat", new ItemFallback("minecraft:apple", true))
            .build();
        assertEquals(Map.of("minecraft:poplar_boat", new ItemFallback("minecraft:stick", true)), fallbacks.items(PALETTER.version(OLDEST)));
        assertEquals(Map.of("minecraft:poplar_boat", new ItemFallback("minecraft:apple", true)), fallbacks.items(PALETTER.latest()));
        assertEquals(ItemFallback.UNKNOWN, fallbacks.defaultItem());
        assertNull(Fallbacks.NONE.defaultItem());
        assertNull(Fallbacks.NONE.defaultBlock());

        Paletter paletter = Paletter.builder().fallbacks(fallbacks).build();
        assertEquals("minecraft:stick", paletter.palette(OLDEST).items().resolve(ItemVariant.of("minecraft:poplar_boat")).definition().getIdentifier());
        assertEquals("minecraft:apple", paletter.palette(PALETTER.latest().protocolVersion()).items().resolve(ItemVariant.of("minecraft:poplar_boat")).definition().getIdentifier());
    }

    @Test
    void vanillaContentRemovesItems() {
        Paletter paletter = Paletter.builder().vanilla(vanilla -> vanilla.remove(BedrockItems.APPLE)).build();
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.palette(version.protocolVersion());
            assertNull(palette.items().getDefinition("minecraft:apple"), version.toString());
            assertFalse(stacks(palette).stream().anyMatch(stack -> stack.startsWith("minecraft:apple:")), version.toString());
            assertNotNull(palette.items().getDefinition("minecraft:golden_apple"));
            // Resolves through the fallback policy
            var resolved = palette.items().resolve(ItemVariant.of("minecraft:apple"));
            assertNotNull(resolved);
            assertTrue(resolved.fallback());
            assertEquals("minecraft:unknown", resolved.definition().getIdentifier());
        }
        assertNotNull(PALETTER.palette(OLDEST).items().getDefinition("minecraft:apple"));

        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).vanilla(vanilla -> vanilla.remove(BedrockItems.APPLE)).build();
        assertNull(none.palette(OLDEST).items().resolve(ItemVariant.of("minecraft:apple")));
    }

    /** The bundled data without experiment marks, plus marks of our own. */
    private static PaletteDataSource experimental() {
        PaletteDataSource bundled = PaletteDataSource.bundled();
        return new PaletteDataSource() {
            @Override
            public List<DataVersion> versions() {
                return bundled.versions();
            }

            @Override
            public VanillaData load(DataVersion version) throws IOException {
                return bundled.load(version).withExperiments(Map.of(
                    "test_toggle", new ExperimentContent(List.of("minecraft:apple"), List.of(), List.of()),
                    "other_toggle", new ExperimentContent(List.of("minecraft:golden_apple", "minecraft:carrot"), List.of(), List.of()),
                    "shared_toggle", new ExperimentContent(List.of("minecraft:carrot"), List.of(), List.of())));
            }

            @Override
            public org.geysermc.paletter.internal.data.Renames renames() {
                return bundled.renames();
            }
        };
    }

    @Test
    void experimentsRemoveItemsOfToggles() {
        // All enabled by default: nothing is removed
        Palette all = Paletter.builder().data(experimental()).build().palette(OLDEST);
        assertNotNull(all.items().getDefinition("minecraft:apple"));
        assertNotNull(all.items().getDefinition("minecraft:golden_apple"));

        Palette some = Paletter.builder().data(experimental()).vanilla(vanilla -> vanilla.experiments("test_toggle", "shared_toggle"))
            .build().palette(OLDEST);
        assertNotNull(some.items().getDefinition("minecraft:apple"));
        assertNull(some.items().getDefinition("minecraft:golden_apple"));
        // Gated by two toggles, one of them enabled
        assertNotNull(some.items().getDefinition("minecraft:carrot"));

        Palette none = Paletter.builder().data(experimental()).vanilla(vanilla -> vanilla.experiments()).build().palette(OLDEST);
        assertNull(none.items().getDefinition("minecraft:apple"));
        assertNull(none.items().getDefinition("minecraft:golden_apple"));
        assertNull(none.items().getDefinition("minecraft:carrot"));
        assertNotNull(none.items().getDefinition("minecraft:stick"));
    }

    @Test
    void reportsAreDeliveredOncePerCompiledPalette() {
        AtomicInteger reports = new AtomicInteger();
        Paletter paletter = Paletter.builder().reports(report -> reports.incrementAndGet())
            .layer(PaletteLayer.builder("global").build()).build();
        assertEquals(0, reports.get());
        paletter.palette(OLDEST);
        paletter.palette(OLDEST);
        assertEquals(1, reports.get());
        paletter.palette(PALETTER.latest().protocolVersion());
        assertEquals(2, reports.get());
    }

    @Test
    void versionsCanBeFiltered() {
        DataVersion newest = PALETTER.latest();
        Paletter paletter = Paletter.builder().versions(version -> version.equals(newest)).build();
        assertEquals(List.of(newest), paletter.versions());
        assertEquals(newest, paletter.latest());
        assertThrows(IllegalArgumentException.class, () -> paletter.palette(OLDEST));
        assertEquals(newest, paletter.palette(newest.protocolVersion()).version());
    }

    @Test
    void customBlocksInASessionLayerNeedHashedIds() {
        PaletteLayer session = PaletteLayer.builder("session").block(CustomBlock.builder("test:block").build()).build();
        int protocol = PALETTER.latest().protocolVersion();

        Palette sequential = Paletter.builder().build().palette(protocol);
        PaletteException e = assertThrows(PaletteException.class, () -> sequential.extend(session));
        assertTrue(e.getMessage().startsWith("Custom blocks in a session layer need BlockNetworkIds.HASHED"), e.getMessage());

        Palette hashed = Paletter.builder().blockNetworkIds(BlockNetworkIds.HASHED).build().palette(protocol);
        Palette extended = hashed.extend(session);
        assertEquals(1, extended.blocks().customBlocks().size());
        assertEquals(BlockNetworkIds.HASHED, extended.blockNetworkIds());

        // Global layers are long lived: custom blocks are fine in sequential mode
        Paletter global = Paletter.builder().layer(session).build();
        assertEquals(1, global.palette(protocol).blocks().customBlocks().size());
    }

    @Test
    void creativeOrderingIsGlobal() {
        PaletteLayer layer = PaletteLayer.builder("blocks").block(CustomBlock.builder("test:block_menu_only").menuCategory(
            org.geysermc.paletter.block.component.MenuCategory.builder()
                .category(org.geysermc.paletter.block.component.MenuCategory.Category.CONSTRUCTION).build()).build()).build();
        int protocol = PALETTER.latest().protocolVersion();
        Palette bds = Paletter.builder().blockNetworkIds(BlockNetworkIds.HASHED).layer(layer).build().palette(protocol);
        Palette append = Paletter.builder().blockNetworkIds(BlockNetworkIds.HASHED).creativeOrdering(CreativeOrdering.APPEND).layer(layer).build()
            .palette(protocol);
        assertTrue(stacks(bds).contains("test:block_menu_only:0"));
        assertFalse(stacks(append).contains("test:block_menu_only:0"));
    }
}
