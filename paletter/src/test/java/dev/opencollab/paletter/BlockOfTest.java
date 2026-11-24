package dev.opencollab.paletter;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
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
    void itemsThatPlaceABlockButHaveNoneOfTheirOwnAreSentWithoutOne() {
        for (DataVersion version : paletter.versions()) {
            Palette palette = paletter.vanilla(version);
            // doors, signs and crops place a block, but BDS sends them without one (their captured creative stacks have none)
            for (String item : List.of("minecraft:acacia_door", "minecraft:acacia_sign", "minecraft:carrot")) {
                assertNull(palette.resolve(Access.vanilla().itemVariant(item, 0)).block(), item);
                assertNotNull(paletter.vanillaData(version).blockItems().values().stream().filter(item::equals).findAny().orElse(null), item);
            }
            // a slab item places the slab, never the double slab
            assertEquals("minecraft:acacia_slab", block(palette, "minecraft:acacia_slab"));
            assertNull(palette.resolve(Access.vanilla().itemVariant("minecraft:acacia_double_slab", 0)).block());
            // items that place nothing
            assertNull(palette.resolve(Access.vanilla().itemVariant("minecraft:stick", 0)).block());
            assertNull(palette.resolve(Access.vanilla().itemVariant("minecraft:diamond_sword", 0)).block());
        }
    }

    @Test
    void aBlockStateOfTheStackWins() {
        Palette palette = paletter.vanilla(paletter.latest());
        RegisteredBlockState log = palette.resolve(Access.vanilla().itemVariant("minecraft:oak_log", 0)).block();
        assertNotNull(log);
        BedrockBlockState sideways = paletter.vanilla(paletter.latest()).blockState(BlockStates.of("minecraft:oak_log", Map.of("pillar_axis", "x")), Lookup.RENAMED);
        ItemData data = palette.encode(BedrockItemStack.of(Access.vanilla().itemVariant("minecraft:oak_log", 0)).withBlockState(sideways));
        assertNotNull(data.getBlockDefinition());
        assertEquals("x", palette.blocks().byRuntimeId(data.getBlockDefinition().getRuntimeId()).states().getString("pillar_axis"));
    }

    @Test
    void blockOfIsVersionIndependent() {
        BedrockBlockState block = paletter.vanilla(paletter.latest()).blockOf(Access.vanilla().itemVariant("minecraft:acacia_slab", 0));
        assertNotNull(block);
        assertEquals("minecraft:acacia_slab", block.type().identifier());
        for (DataVersion version : paletter.versions()) {
            assertEquals(block.type().identifier(), block(paletter.vanilla(version), "minecraft:acacia_slab"), version.toString());
        }
        assertNull(paletter.vanilla(paletter.latest()).blockOf(Access.vanilla().itemVariant("minecraft:acacia_sign", 0)));
        assertNull(paletter.vanilla(paletter.latest()).blockOf(Access.vanilla().itemVariant("minecraft:stick", 0)));
    }

    /**
     * Command blocks and jigsaws: a creative state of the item wins over the block's default state (per the precedence
     * of {@link dev.opencollab.paletter.internal.item.ItemRegistry#blockOf}). The bundled creative data has no stack of them (they are
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
                RegisteredBlockState block = palette.resolve(Access.vanilla().itemVariant(id, 0)).block();
                assertNotNull(block, version + " " + id);
                NbtMap expected = creative.isEmpty() ? palette.blocks().defaultState(id).getState() : creative.getFirst();
                assertEquals(BlockStates.normalize(expected), BlockStates.normalize(block.getState()), version + " " + id);
            }
        }
    }

    private static String block(Palette palette, String item) {
        RegisteredBlockState block = palette.resolve(Access.vanilla().itemVariant(item, 0)).block();
        return block == null ? null : block.identifier();
    }
}
