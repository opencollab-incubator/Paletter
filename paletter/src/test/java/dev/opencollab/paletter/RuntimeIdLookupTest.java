package dev.opencollab.paletter;

import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime id lookups on {@link Palette}: for ids a server stores itself. */
class RuntimeIdLookupTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final Palette PALETTE = Palettes.palette(PALETTER.latest().protocolVersion(),
        PaletteLayer.builder("content").item(CustomContentTest.ruby()).block(CustomContentTest.lamp()).build());

    private static void assertBlockRoundTrip(RegisteredBlockState block) {
        int id = block.getRuntimeId();
        BedrockBlockState key = PALETTE.blockState(id);
        assertNotNull(key);
        assertSame(block, PALETTE.blockDefinition(id));
        assertEquals(id, PALETTE.blockDefinition(key).getRuntimeId());
        assertEquals(id, PALETTE.runtimeId(key));
    }

    @Test
    void vanillaBlockRoundTrips() {
        assertBlockRoundTrip(PALETTE.blocks().air());
    }

    @Test
    void customBlockRoundTrips() {
        RegisteredBlockState custom = null;
        for (int i = 0; i < PALETTE.blocks().size(); i++) {
            RegisteredBlockState block = PALETTE.blocks().byIndex(i);
            if (block.isCustom() && block.identifier().equals("example:lamp")) {
                custom = block;
                break;
            }
        }
        assertNotNull(custom);
        assertBlockRoundTrip(custom);
    }

    @Test
    void unknownBlockIdIsNull() {
        assertNull(PALETTE.blockState(1));
        assertNull(PALETTE.blockDefinition(1));
    }

    @Test
    void itemsRoundTrip() {
        for (String identifier : new String[]{"minecraft:apple", "example:ruby", "example:lamp"}) {
            RegisteredItem item = PALETTE.itemDefinition(identifier);
            assertNotNull(item, identifier);
            assertSame(item, PALETTE.itemDefinition(item.getRuntimeId()), identifier);
        }
        assertTrue(PALETTE.itemDefinition("example:lamp").getRuntimeId() < 0, "plain custom block item");
        assertNull(PALETTE.itemDefinition(Integer.MAX_VALUE));
    }
}
