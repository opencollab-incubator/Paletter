package org.geysermc.paletter;

import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.internal.item.ResolvedItem;
import com.sun.management.ThreadMXBean;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public lookups return handles, never ids. */
class HandleTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static CustomBlock lamp() {
        return CustomBlock.builder("example:lamp").property(BlockProperty.bool("example:lit")).build();
    }

    private static CustomItem ruby() {
        return CustomItem.builder("example:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build();
    }

    @Test
    void blocksResolveToInternedHandles() {
        BedrockBlockState stone = PALETTER.block(BlockStates.of("minecraft:stone"));
        assertSame(BedrockBlocks.STONE.defaultState(), stone);
        assertSame(stone, PALETTER.block(BlockStates.of("minecraft:stone", Map.of())));
        assertNull(PALETTER.block(BlockStates.of("minecraft:not_a_block")));
        assertNotNull(PALETTER.explainUnknown(BlockStates.of("minecraft:not_a_block")));
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertSame(stone, palette.block(BlockStates.of("minecraft:stone")));
        assertEquals(palette.block(stone).getRuntimeId(), palette.runtimeId(stone));
    }

    @Test
    void itemsResolveToHandles() {
        BedrockItemVariant apple = PALETTER.item("minecraft:apple", 0);
        assertSame(BedrockItems.APPLE.base(), apple);
        BedrockItemVariant potion = PALETTER.item("minecraft:potion", 5);
        assertSame(BedrockItems.POTION.variant(5), potion);
        assertNull(PALETTER.item("minecraft:potion", 9999));
        assertNotNull(PALETTER.explainUnknown("minecraft:potion", 9999));
        assertNull(PALETTER.item("minecraft:not_an_item", 0));
    }

    @Test
    void customContentHasHandlesInItsPalette() {
        CustomBlock lamp = lamp();
        CustomItem ruby = ruby();
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp).item(ruby).build());
        BedrockBlockType type = BedrockBlockType.of(lamp);
        for (BedrockBlockState state : type.states()) {
            assertSame(state, palette.block(state.toNbt()));
        }
        assertNull(PALETTER.block(type.defaultState().toNbt()));
        BedrockItemVariant handle = palette.item("example:ruby", 0);
        assertNotNull(handle);
        assertEquals(ruby.type().base(), handle);
        assertSame(handle, palette.item("example:ruby", 0), "custom handles are created once");
        assertNotNull(palette.item(handle));
        assertTrue(palette.itemVariants().contains(handle));
        assertTrue(palette.itemVariants().contains(BedrockItems.APPLE.base()));
        assertNull(PALETTER.item("example:ruby", 0));
    }

    @Test
    void decodeReturnsTheStack() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        var apple = palette.encode(BedrockItemStack.of(BedrockItems.APPLE.base(), 3));
        BedrockItemStack decoded = palette.decode(apple);
        assertNotNull(decoded);
        assertSame(BedrockItems.APPLE.base(), decoded.variant());
        assertEquals(3, decoded.count());
        assertNull(palette.decode(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR));
    }

    @Test
    void blockOfIsAHandle() {
        BedrockBlockState sign = PALETTER.blockOf(BedrockItemVariant.of("minecraft:acacia_sign", 0));
        assertNotNull(sign);
        assertSame(sign, PALETTER.block(sign.toNbt()));
    }

    /** The hot path of translators: a handle to its runtime id is a field read and an array load. */
    @Test
    void handleToRuntimeIdDoesNotAllocate() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp()).build());
        List<BedrockBlockState> states = new java.util.ArrayList<>(BedrockBlockType.vanillaTypes().stream()
            .flatMap(type -> type.states().stream()).toList());
        states.addAll(BedrockBlockType.of(lamp()).states());
        BedrockBlockState[] handles = states.toArray(BedrockBlockState[]::new);
        long sum = 0;
        for (int warmup = 0; warmup < 30; warmup++) {
            for (BedrockBlockState handle : handles) {
                sum += palette.runtimeId(handle);
            }
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int round = 0; round < 20; round++) {
            for (BedrockBlockState handle : handles) {
                sum += palette.runtimeId(handle);
            }
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + (20L * handles.length) + " lookups (checksum " + sum + ")");
    }
}
