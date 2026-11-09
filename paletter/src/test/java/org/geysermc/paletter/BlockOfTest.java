package org.geysermc.paletter;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.item.ItemVariant;
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
            assertNull(palette.blockOf(BedrockItemVariant.of("minecraft:acacia_double_slab", 0)));
            // items that place nothing
            assertNull(palette.blockOf(BedrockItemVariant.of("minecraft:stick", 0)));
            assertNull(palette.blockOf(BedrockItemVariant.of("minecraft:diamond_sword", 0)));
        }
    }

    @Test
    void aBlockStateOfTheStackWins() {
        Palette palette = paletter.vanilla(paletter.latest());
        RegisteredBlockState log = palette.blockOf(BedrockItemVariant.of("minecraft:oak_log", 0));
        assertNotNull(log);
        BedrockBlockState sideways = paletter.block(BlockStates.of("minecraft:oak_log", Map.of("pillar_axis", "x")));
        ItemData data = palette.encode(BedrockItemStack.of(BedrockItemVariant.of("minecraft:oak_log", 0)).withBlockState(sideways));
        assertNotNull(data.getBlockDefinition());
        assertEquals("x", palette.blocks().byRuntimeId(data.getBlockDefinition().getRuntimeId()).states().getString("pillar_axis"));
    }

    @Test
    void blockOfIsVersionIndependent() {
        BedrockBlockState block = paletter.blockOf(BedrockItemVariant.of("minecraft:acacia_sign", 0));
        assertNotNull(block);
        assertEquals("minecraft:acacia_standing_sign", block.type().identifier());
        assertNull(paletter.blockOf(BedrockItemVariant.of("minecraft:stick", 0)));
        assertNull(paletter.blockOf(BedrockItemVariant.of("minecraft:not_an_item", 0)));
    }

    /**
     * Command blocks and jigsaws: a creative state of the item wins over the block's default state (per the precedence
     * of {@link org.geysermc.paletter.item.ItemRegistry#blockOf}). The bundled creative data has no stack of them (they are
     * operator items), so the default state is what is sent.
     */
    @Test
    void commandBlocksAndJigsawUseTheirCreativeStateElseTheDefault() {
        for (var version : paletter.versions()) {
            var data = paletter.vanillaData(version);
            Palette palette = paletter.palette(version.protocolVersion());
            for (String id : List.of("minecraft:command_block", "minecraft:repeating_command_block",
                "minecraft:chain_command_block", "minecraft:jigsaw")) {
                List<NbtMap> creative = new ArrayList<>();
                data.creative().forEachStack(stack -> {
                    if (stack.identifier().equals(id) && stack.blockState() != null) {
                        creative.add(stack.blockState());
                    }
                });
                RegisteredBlockState block = palette.blockOf(BedrockItemVariant.of(id, 0));
                assertNotNull(block, version + " " + id);
                NbtMap expected = creative.isEmpty() ? palette.blocks().defaultState(id).getState() : creative.getFirst();
                assertEquals(BlockStates.normalize(expected), BlockStates.normalize(block.getState()), version + " " + id);
            }
        }
    }

    private static String block(Palette palette, String item) {
        RegisteredBlockState block = palette.blockOf(BedrockItemVariant.of(item, 0));
        return block == null ? null : block.identifier();
    }
}
