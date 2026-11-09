package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The throwing and the non-throwing lookup of a block state that a palette may not have. */
class PaletteLookupTest {
    private static final Paletter HASHED = Palettes.paletter();
    private static final CustomBlock LAMP = CustomBlock.builder("lookup:lamp")
        .property(BlockProperty.bool("lookup:lit"))
        .component(Geometry.of("minecraft:geometry.full_block"))
        .build();

    @Test
    void aStateThatIsNotInThePaletteThrowsOrFallsBack() {
        Palette base = HASHED.palette(HASHED.latest().protocolVersion());
        BedrockBlockState lamp = LAMP.type().defaultState();
        assertThrows(IllegalArgumentException.class, () -> base.runtimeId(lamp));
        assertThrows(IllegalArgumentException.class, () -> base.block(lamp));
        int air = base.runtimeId(BedrockBlocks.AIR.defaultState());
        assertEquals(air, base.runtimeIdOrDefault(lamp, air));
        assertEquals(-1, base.runtimeIdOrDefault(lamp, -1));
        assertNull(base.blockOrNull(lamp));
    }

    @Test
    void aStateInThePaletteGivesTheSameAsTheThrowingVariants() {
        Palette base = HASHED.palette(HASHED.latest().protocolVersion());
        Palette session = base.extend(PaletteLayer.builder("session").block(LAMP).build());
        BedrockBlockState lamp = LAMP.type().defaultState();
        assertEquals(session.runtimeId(lamp), session.runtimeIdOrDefault(lamp, -1));
        RegisteredBlockState block = session.blockOrNull(lamp);
        assertNotNull(block);
        assertSame(session.block(lamp), block);
        BedrockBlockState stone = BedrockBlocks.STONE.defaultState();
        assertEquals(base.runtimeId(stone), base.runtimeIdOrDefault(stone, -1));
        assertSame(base.block(stone), base.blockOrNull(stone));
    }
}
