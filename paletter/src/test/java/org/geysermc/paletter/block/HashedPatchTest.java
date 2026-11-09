package org.geysermc.paletter.block;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.data.DataVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashedPatchTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock block(String identifier, int values) {
        return CustomBlock.builder(identifier)
            .property(BlockProperty.range("example:level", 0, values - 1))
            .component(Geometry.of("minecraft:geometry.full_block"))
            .build();
    }

    private static final CustomBlock LAMP = block("example:lamp", 4);
    // Sort before and after the lamp by identifier, so the block ids of the others shift
    private static final CustomBlock ZEBRA = block("example:zebra", 2);
    private static final CustomBlock APPLE = block("example:apple", 3);

    @Test
    void aPatchHoldsOnlyTheAddedStatesAndSharesTheParent() {
        for (DataVersion version : PALETTER.versions()) {
            int protocol = version.protocolVersion();
            BlockRegistry vanilla = PALETTER.palette(protocol, BlockNetworkIds.HASHED).blocks();
            Palette patched = PALETTER.palette(protocol, BlockNetworkIds.HASHED, PaletteLayer.builder("content").block(LAMP).build());
            BlockRegistry blocks = patched.blocks();

            assertEquals(vanilla.size() + 4, blocks.size(), version.toString());
            assertEquals(4, blocks.ownStateCount(), version.toString());
            assertEquals(vanilla.size(), vanilla.ownStateCount());
            for (int i = 0; i < vanilla.size(); i++) {
                RegisteredBlockState parent = vanilla.byIndex(i);
                assertSame(parent, blocks.byIndex(i));
                assertSame(parent, blocks.getDefinition(parent.getRuntimeId()));
                assertSame(parent, blocks.exact(parent.getState()));
                assertEquals(i, blocks.indexOf(parent));
            }
            for (int i = vanilla.size(); i < blocks.size(); i++) {
                RegisteredBlockState own = blocks.byIndex(i);
                assertTrue(own.isCustom());
                assertEquals(i, blocks.indexOf(own));
                assertSame(own, blocks.byRuntimeId(own.getRuntimeId()));
                assertEquals(BlockNetworkIds.hash(own.getState()), own.getRuntimeId());
                assertSame(own, blocks.resolve(own.getState()).block());
            }
            assertEquals(-1, vanilla.indexOf(blocks.byIndex(vanilla.size())));
            assertSame(vanilla.block("minecraft:stone"), blocks.block("minecraft:stone"));
            assertSame(vanilla.defaultState("minecraft:stone"), blocks.defaultState("minecraft:stone"));
            assertEquals(vanilla.states("minecraft:stone"), blocks.states("minecraft:stone"));
            assertNotNull(blocks.block("example:lamp"));
            assertEquals(vanilla.blocks().size() + 1, blocks.blocks().size());
            assertSame(vanilla.air(), blocks.air());
        }
    }

    @Test
    void canonicalTablesReuseTheParentsPages() {
        DataVersion latest = PALETTER.latest();
        BlockRegistry vanilla = PALETTER.palette(latest.protocolVersion(), BlockNetworkIds.HASHED).blocks();
        BlockRegistry blocks = PALETTER.palette(latest.protocolVersion(), BlockNetworkIds.HASHED,
            PaletteLayer.builder("content").block(LAMP).build()).blocks();
        for (int id = 0; id < vanilla.canonical().size(); id++) {
            assertSame(vanilla.block(id), blocks.block(id), "canonical " + id);
            assertEquals(vanilla.runtimeId(id), blocks.runtimeId(id));
        }
        for (int id = vanilla.canonical().size(); id < blocks.canonical().size(); id++) {
            assertTrue(blocks.block(id).isCustom());
            assertEquals(id, blocks.canonical(blocks.block(id)));
        }
        assertEquals(vanilla.missingCanonical(), blocks.missingCanonical());
    }

    @Test
    void stackedPatchesGiveWhatOneLayerWithAllBlocksGives() {
        for (DataVersion version : PALETTER.versions()) {
            int protocol = version.protocolVersion();
            BlockRegistry stacked = PALETTER.palette(protocol, BlockNetworkIds.HASHED,
                PaletteLayer.builder("a").block(LAMP).build(),
                PaletteLayer.builder("b").block(ZEBRA).block(APPLE).build()).blocks();
            BlockRegistry single = PALETTER.palette(protocol, BlockNetworkIds.HASHED,
                PaletteLayer.builder("all").block(LAMP).block(ZEBRA).block(APPLE).build()).blocks();

            assertEquals(single.size(), stacked.size());
            assertEquals(single.blockProperties(), stacked.blockProperties(), version.toString());
            assertEquals(single.customBlocks().size(), stacked.customBlocks().size());
            for (int i = 0; i < single.size(); i++) {
                RegisteredBlockState expected = single.byIndex(i);
                RegisteredBlockState actual = stacked.byRuntimeId(expected.getRuntimeId());
                assertNotNull(actual, expected.toString());
                assertEquals(expected.getState(), actual.getState());
                assertEquals(expected.getRuntimeId(), actual.getRuntimeId());
            }
            // Vanilla definitions are the very same objects
            for (RegisteredBlockState state : single.block("minecraft:stone").states()) {
                assertSame(state, stacked.exact(state.getState()));
            }
            assertEquals("a", stacked.block("example:lamp").origin().layer());
            assertEquals("b", stacked.block("example:zebra").origin().layer());
            // BDS numbers the property list by identifier, descending: zebra, lamp, apple
            List<String> custom = stacked.blockProperties().stream().map(data -> data.getName()).filter(name -> name.startsWith("example:")).toList();
            assertEquals(List.of("example:zebra", "example:lamp", "example:apple"), custom);
        }
    }

    @Test
    void collisionsAreRejected() {
        int protocol = PALETTER.latest().protocolVersion();
        assertThrows(Exception.class, () -> PALETTER.palette(protocol, BlockNetworkIds.HASHED,
            PaletteLayer.builder("a").block(LAMP).build(), PaletteLayer.builder("b").block(LAMP).build()));
        assertThrows(Exception.class, () -> PALETTER.palette(protocol, BlockNetworkIds.HASHED,
            PaletteLayer.builder("a").block(block("minecraft:stone", 2)).build()));
    }

    @Test
    void sequentialPalettesAreStillRebuilt() {
        int protocol = PALETTER.latest().protocolVersion();
        BlockRegistry blocks = PALETTER.palette(protocol, BlockNetworkIds.SEQUENTIAL, PaletteLayer.builder("content").block(LAMP).build()).blocks();
        assertEquals(blocks.size(), blocks.ownStateCount());
        NbtMap state = blocks.block("example:lamp").states().getFirst().getState();
        assertSame(blocks.exact(state), blocks.block("example:lamp").states().getFirst());
    }
}
