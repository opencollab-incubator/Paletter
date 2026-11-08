package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.block.PaletteBlock;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which block an item is sent with comes from the bundled block to item relation.
 */
class BlockOfTest {
    private final Paletter paletter = Paletter.bundled();

    @Test
    void everyVersionHasTheRelation() {
        for (DataVersion version : paletter.versions()) {
            assertFalse(paletter.vanillaData(version).blockItems().isEmpty(), version.toString());
        }
    }

    @Test
    void itemsNamedDifferentlyFromTheirBlockAreFound() {
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.vanilla(version);
            // doors, signs and crops: the item is not named like the block
            assertEquals("minecraft:acacia_door", block(palette, "minecraft:acacia_door"));
            assertEquals("minecraft:acacia_standing_sign", block(palette, "minecraft:acacia_sign"));
            assertEquals("minecraft:carrots", block(palette, "minecraft:carrot"));
            // a slab item places the slab, never the double slab
            assertEquals("minecraft:acacia_slab", block(palette, "minecraft:acacia_slab"));
            assertNull(palette.blockOf(ItemVariant.of("minecraft:acacia_double_slab")));
            // items that place nothing
            assertNull(palette.blockOf(ItemVariant.of("minecraft:stick")));
            assertNull(palette.blockOf(ItemVariant.of("minecraft:diamond_sword")));
        }
    }

    @Test
    void aBlockStateOfTheVariantWins() {
        Palette palette = paletter.vanilla(paletter.latest());
        PaletteBlock log = palette.blockOf(ItemVariant.of("minecraft:oak_log"));
        assertNotNull(log);
        PaletteBlock sideways = palette.blockOf(ItemVariant.of("minecraft:oak_log")
            .withBlockState(BlockStates.of("minecraft:oak_log", Map.of("pillar_axis", "x"))));
        assertNotNull(sideways);
        assertEquals("x", sideways.states().getString("pillar_axis"));
    }

    @Test
    void blockOfIsVersionIndependent() {
        BedrockBlockState block = paletter.blockOf(ItemVariant.of("minecraft:acacia_sign"));
        assertNotNull(block);
        assertEquals("minecraft:acacia_standing_sign", block.type().identifier());
        assertNull(paletter.blockOf(ItemVariant.of("minecraft:stick")));
        assertNull(paletter.blockOf(ItemVariant.of("minecraft:not_an_item")));
    }

    private static String block(Palette palette, String item) {
        PaletteBlock block = palette.blockOf(ItemVariant.of(item));
        return block == null ? null : block.identifier();
    }
}
