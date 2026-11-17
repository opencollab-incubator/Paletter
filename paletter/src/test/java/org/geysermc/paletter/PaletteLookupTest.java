package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.CanonicalBlocks;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The lookup of a block state that a palette may not have, and the way back to a key. */
class PaletteLookupTest {
    private static final Paletter HASHED = Palettes.paletter();
    private static final CustomBlock LAMP = CustomBlock.builder("lookup:lamp")
        .property(BlockProperty.bool("lookup:lit"))
        .component(Geometry.of("minecraft:geometry.full_block"))
        .build();

    @Test
    void aStateThatIsNotInThePaletteGivesNullOrTheDefault() {
        Palette base = HASHED.palette(HASHED.latest().protocolVersion());
        BedrockBlockState lamp = LAMP.type().defaultState();
        assertNull(base.blockDefinition(lamp));
        assertNull(base.blockBehaviour(lamp));
        assertNull(base.blockBehaviour(LAMP.type()));
        int air = base.runtimeId(BedrockBlocks.AIR.defaultState(), -1);
        assertEquals(air, base.runtimeId(lamp, air));
        assertEquals(-1, base.runtimeId(lamp, -1));
    }

    @Test
    void everyVanillaStateIsInEveryVersion() {
        CanonicalBlocks canonical = HASHED.canonicalBlocks();
        for (DataVersion version : HASHED.versions()) {
            Palette palette = HASHED.vanilla(version);
            for (int id = 0; id < canonical.vanillaSize(); id++) {
                assertNotNull(palette.blockDefinition(canonical.handle(id)), version + " " + canonical.state(id));
            }
        }
    }

    @Test
    void aStateInThePaletteIsFoundWhateverTheDefault() {
        Palette base = HASHED.palette(HASHED.latest().protocolVersion());
        Palette session = base.extend(PaletteLayer.builder("session").block(LAMP).build());
        BedrockBlockState lamp = LAMP.type().defaultState();
        RegisteredBlockState block = session.blockDefinition(lamp);
        assertNotNull(block);
        assertEquals(block.getRuntimeId(), session.runtimeId(lamp, -1));
        assertEquals(block.getRuntimeId(), session.runtimeId(lamp, 0));
        BedrockBlockState stone = BedrockBlocks.STONE.defaultState();
        assertEquals(base.runtimeId(stone, -1), base.runtimeId(stone, 0));
    }

    @Test
    void blockStateLooksUpADefinitionOfAnotherPaletteByRuntimeId() {
        Palette base = HASHED.palette(HASHED.latest().protocolVersion());
        Palette session = base.extend(PaletteLayer.builder("session").block(LAMP).build());
        BedrockBlockState lamp = LAMP.type().defaultState();
        RegisteredBlockState lampBlock = session.blockDefinition(lamp);
        assertSame(lamp, session.blockState(lampBlock));
        assertSame(lampBlock, session.blockDefinition(session.blockState(lampBlock)));

        // Vanilla blocks are shared between the palettes of a Paletter
        BedrockBlockState stone = BedrockBlocks.STONE.defaultState();
        assertSame(stone, session.blockState(base.blockDefinition(stone)));
        assertSame(stone, base.blockState(session.blockDefinition(stone)));

        // A palette without the custom block has no block with its runtime id
        assertNull(base.blockState(lampBlock));

        // An equal block of another layer has the same runtime id: the key is the one this palette knows
        CustomBlock otherLamp = CustomBlock.builder("lookup:lamp")
            .property(BlockProperty.bool("lookup:lit"))
            .component(Geometry.of("minecraft:geometry.full_block"))
            .build();
        Palette other = base.extend(PaletteLayer.builder("other").block(otherLamp).build());
        assertEquals(lampBlock.getRuntimeId(), other.runtimeId(otherLamp.type().defaultState(), -1));
        assertSame(otherLamp.type().defaultState(), other.blockState(lampBlock));
        assertNotSame(lamp, other.blockState(lampBlock));
    }
}
