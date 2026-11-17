package org.geysermc.paletter;

import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BDS numbers custom blocks in descending order of their file names, compared in upper case (Windows BDS), so '_' sorts
 * after letters: a:b_x gets the lowest id, although as plain text it is the last of the three.
 */
class BlockIdOrderTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static int blockId(Palette palette, String identifier) {
        return palette.blocks().blockProperties().stream().filter(property -> property.getName().equals(identifier)).findFirst().orElseThrow()
            .getProperties().getCompound("vanilla_block_data").getInt("block_id");
    }

    @Test
    void underscoreSortsAfterLettersInUpperCase() {
        PaletteLayer.Builder layer = PaletteLayer.builder("order");
        for (String identifier : new String[] {"a:bx", "a:by", "a:b_x"}) {
            layer.block(CustomBlock.builder(identifier).menuCategory(CreativeCategory.CONSTRUCTION).build());
        }
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer.build());

        assertEquals(10000, blockId(palette, "a:b_x"));
        assertEquals(10001, blockId(palette, "a:by"));
        assertEquals(10002, blockId(palette, "a:bx"));
    }
}
