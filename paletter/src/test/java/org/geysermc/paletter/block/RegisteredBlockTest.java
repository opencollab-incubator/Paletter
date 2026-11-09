package org.geysermc.paletter.block;

import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.Palettes;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return Palettes.palette(PALETTER.latest().protocolVersion(), mode, PaletteLayer.builder("content").block(lamp()).build());
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
            assertSame(wheat, blocks.block(state));
            assertTrue(wheat.components(state).isEmpty());
            assertNotNull(state.key());
            assertEquals(state.getState(), state.key().toNbt());
        }
        assertEquals(wheat.identifier(), wheat.key().identifier());
    }

    @Test
    void everyStateBelongsToABlockOfTheRegistry() {
        for (BlockNetworkIds mode : BlockNetworkIds.values()) {
            BlockRegistry blocks = Palettes.palette(PALETTER.latest().protocolVersion(), mode).blocks();
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
                assertSame(block, blocks.block(state));
                assertTrue(state.isCustom());
                assertNotNull(state.key());
                assertEquals(state.getState(), state.key().toNbt());
                boolean lit = state.states().getByte("example:lit") == 1 && state.states().getString("example:color").equals("green");
                assertEquals(lit, block.components(state).stream().anyMatch(LightEmission.class::isInstance), state.toString());
                assertTrue(block.components(state).stream().anyMatch(Geometry.class::isInstance));
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
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), BlockNetworkIds.HASHED, PaletteLayer.builder("x").block(block).build());
        RegisteredBlock glow = palette.blocks().block("example:glow");
        for (RegisteredBlockState state : glow.states()) {
            assertEquals(1, glow.components(state).size());
            LightEmission emission = (LightEmission) glow.components(state).getFirst();
            assertEquals(state.states().getByte("example:on") == 1 ? 15 : 3, emission.value());
        }
    }

    @Test
    void sharedHashedStatesAreNeverAskedForAWrongVersionBlock() {
        // A hashed state is the same instance in every version, so only the palette can say which block it is in
        Paletter paletter = Paletter.builder().blockNetworkIds(BlockNetworkIds.HASHED).build();
        for (org.geysermc.paletter.data.DataVersion version : paletter.versions()) {
            BlockRegistry blocks = paletter.palette(version.protocolVersion()).blocks();
            for (RegisteredBlock block : blocks.blocks()) {
                for (RegisteredBlockState state : block.states()) {
                    assertSame(block, blocks.block(state), version + " " + state);
                }
            }
        }
        BlockRegistry oldest = paletter.palette(paletter.versions().getFirst().protocolVersion()).blocks();
        BlockRegistry latest = paletter.palette(paletter.latest().protocolVersion()).blocks();
        int shared = 0;
        for (RegisteredBlockState state : oldest.block("minecraft:stone").states()) {
            RegisteredBlockState same = latest.exact(state.getState());
            if (same != null) {
                shared++;
                assertSame(state, same);
                assertEquals(latest.block("minecraft:stone").states(), latest.block(same).states());
                assertEquals(oldest.block("minecraft:stone").states(), oldest.block(state).states());
            }
        }
        assertTrue(shared > 0);
        // A state of another palette is not asked about
        RegisteredBlockState onlyInLatest = latest.blocks().stream().flatMap(block -> block.states().stream())
            .filter(state -> oldest.exact(state.getState()) == null).findFirst().orElse(null);
        if (onlyInLatest != null) {
            assertThrows(IllegalArgumentException.class, () -> oldest.block(onlyInLatest));
        }
    }

    @Test
    void aPatchesBlocksCarryTheirOwnPropertyDataBecauseTheBlockIdsShift() {
        CustomBlock zebra = CustomBlock.builder("example:zebra").property(BlockProperty.bool("example:on")).build();
        Palette base = Palettes.palette(PALETTER.latest().protocolVersion(), BlockNetworkIds.HASHED,
            PaletteLayer.builder("content").block(lamp()).build());
        // Block ids follow the identifiers in descending order: zebra comes before the lamp, so the lamp's id moves
        Palette patched = base.extend(PaletteLayer.builder("session").block(zebra).build());

        RegisteredBlock before = base.blocks().block("example:lamp");
        RegisteredBlock after = patched.blocks().block("example:lamp");
        assertNotEquals(before.propertyData(), after.propertyData());
        for (BlockPropertyData data : patched.blocks().blockProperties()) {
            if (data.getName().startsWith("example:")) {
                assertEquals(data.getProperties(), patched.blocks().block(data.getName()).propertyData(), data.getName());
            }
        }
        for (BlockPropertyData data : base.blocks().blockProperties()) {
            assertEquals(data.getProperties(), base.blocks().block(data.getName()).propertyData());
        }
        // The states are the same instances: only the block is new
        assertEquals(before.states(), after.states());
        assertEquals(1, patched.blocks().blocks().stream().filter(block -> block.identifier().equals("example:lamp")).count());
        assertSame(after, patched.blocks().block(after.states().getFirst()));
    }
}
