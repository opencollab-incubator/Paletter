package org.geysermc.paletter.block;

import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.LightEmission;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredBlockTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock lamp() {
        return CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .property(BlockProperty.strings("example:color", "red", "green"))
            .component(Geometry.of("minecraft:geometry.full_block"))
            .permutation("q.block_state('example:lit') == 1 && q.block_state('example:color') == 'green'", LightEmission.of(15))
            .build();
    }

    private static Palette palette(BlockNetworkIds mode) {
        return PALETTER.palette(PALETTER.latest().protocolVersion(), mode, PaletteLayer.builder("content").block(lamp()).build());
    }

    @Test
    void vanillaBlocksDeriveTheirProperties() {
        BlockRegistry blocks = PALETTER.vanilla(PALETTER.latest()).blocks();
        RegisteredBlock wheat = blocks.block("minecraft:wheat");
        assertNotNull(wheat);
        assertEquals("minecraft:wheat", wheat.identifier());
        assertEquals(Origin.VANILLA, wheat.origin());
        assertTrue(wheat.components().isEmpty());
        assertNull(wheat.customBlock());
        assertEquals(1, wheat.stateProperties().size());
        BlockProperty<?> growth = wheat.stateProperties().getFirst();
        assertEquals("growth", growth.name());
        assertEquals(BlockProperty.Type.INTEGER, growth.type());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), growth.values());
        assertEquals(8, wheat.states().size());
        assertSame(blocks.defaultState("minecraft:wheat"), wheat.defaultState());
        for (RegisteredBlockState state : wheat.states()) {
            assertSame(wheat, state.block());
            assertEquals(Origin.VANILLA, state.origin());
            assertTrue(state.components().isEmpty());
            assertNotNull(state.key());
            assertEquals(state.getState(), state.key().toNbt());
        }
        assertEquals(wheat.identifier(), wheat.key().identifier());
    }

    @Test
    void everyStateBelongsToABlockOfTheRegistry() {
        for (BlockNetworkIds mode : BlockNetworkIds.values()) {
            BlockRegistry blocks = PALETTER.palette(PALETTER.latest().protocolVersion(), mode).blocks();
            int states = 0;
            for (RegisteredBlock block : blocks.blocks()) {
                assertSame(block, blocks.block(block.identifier()));
                states += block.states().size();
                assertFalse(block.states().isEmpty());
            }
            assertEquals(blocks.size(), states);
        }
    }

    @Test
    void propertyDataIsThereForDataDrivenBlocksOnly() {
        BlockRegistry blocks = PALETTER.vanilla(PALETTER.latest()).blocks();
        Set<String> dataDriven = new HashSet<>();
        for (BlockPropertyData data : blocks.blockProperties()) {
            dataDriven.add(data.getName());
            assertEquals(data.getProperties(), blocks.block(data.getName()).propertyData());
        }
        for (RegisteredBlock block : blocks.blocks()) {
            assertEquals(dataDriven.contains(block.identifier()), block.propertyData() != null, block.identifier());
        }
    }

    @Test
    void customBlocksCarryTheirComponentsPermutationsAndOrigin() {
        for (BlockNetworkIds mode : BlockNetworkIds.values()) {
            BlockRegistry blocks = palette(mode).blocks();
            RegisteredBlock block = blocks.block("example:lamp");
            assertNotNull(block);
            assertEquals(Origin.custom("content"), block.origin());
            assertEquals(lamp(), block.customBlock());
            assertEquals(lamp().components(), block.components());
            assertEquals(List.of("example:lit", "example:color"), block.stateProperties().stream().map(BlockProperty::name).toList());
            assertEquals(4, block.states().size());
            assertEquals("example:lamp", block.propertyData() == null ? null : blocks.blockProperties().stream()
                .filter(data -> data.getProperties().equals(block.propertyData())).findFirst().orElseThrow().getName());
            assertSame(block.states().getFirst(), block.defaultState());
            assertEquals(block.key(), lamp().type());

            for (RegisteredBlockState state : block.states()) {
                assertSame(block, state.block());
                assertTrue(state.isCustom());
                assertEquals(Origin.custom("content"), state.origin());
                assertNotNull(state.key());
                assertEquals(state.getState(), state.key().toNbt());
                boolean lit = state.states().getByte("example:lit") == 1 && state.states().getString("example:color").equals("green");
                assertEquals(lit, state.components().stream().anyMatch(LightEmission.class::isInstance), state.toString());
                assertTrue(state.components().stream().anyMatch(Geometry.class::isInstance));
            }
        }
    }

    @Test
    void permutationComponentsReplaceBaseComponentsOfTheSameName() {
        CustomBlock block = CustomBlock.builder("example:glow")
            .property(BlockProperty.bool("example:on"))
            .component(LightEmission.of(3))
            .permutation("q.block_state('example:on')", LightEmission.of(15))
            .build();
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), BlockNetworkIds.HASHED, PaletteLayer.builder("x").block(block).build());
        for (RegisteredBlockState state : palette.blocks().block("example:glow").states()) {
            assertEquals(1, state.components().size());
            LightEmission emission = (LightEmission) state.components().getFirst();
            assertEquals(state.states().getByte("example:on") == 1 ? 15 : 3, emission.value());
        }
    }
}
