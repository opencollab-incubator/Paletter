package org.geysermc.paletter;

import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.ResolvedItem;
import com.sun.management.ThreadMXBean;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.data.DataVersion;
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
        BedrockBlockState stone = PALETTER.vanilla(PALETTER.latest()).blockState(BlockStates.of("minecraft:stone"), Lookup.RENAMED);
        assertSame(BedrockBlocks.STONE.defaultState(), stone);
        assertSame(stone, PALETTER.vanilla(PALETTER.latest()).blockState(BlockStates.of("minecraft:stone", Map.of()), Lookup.RENAMED));
        assertNull(PALETTER.vanilla(PALETTER.latest()).blockState(BlockStates.of("minecraft:not_a_block"), Lookup.RENAMED));
        assertNotNull(PALETTER.vanilla(PALETTER.latest()).explainUnknown(BlockStates.of("minecraft:not_a_block"), Lookup.RENAMED));
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        assertSame(stone, palette.blockState(BlockStates.of("minecraft:stone"), Lookup.RENAMED));
        assertEquals(palette.blockDefinition(stone).getRuntimeId(), palette.runtimeId(stone));
    }

    @Test
    void itemsResolveToHandles() {
        BedrockItemVariant apple = PALETTER.vanilla(PALETTER.latest()).itemVariant("minecraft:apple", 0, Lookup.RENAMED);
        assertSame(BedrockItems.APPLE.variant(), apple);
        BedrockItemVariant potion = PALETTER.vanilla(PALETTER.latest()).itemVariant("minecraft:potion", 5, Lookup.RENAMED);
        assertSame(BedrockItems.POTION.variant(5), potion);
        assertNull(PALETTER.vanilla(PALETTER.latest()).itemVariant("minecraft:potion", 9999, Lookup.RENAMED));
        assertNotNull(PALETTER.vanilla(PALETTER.latest()).explainUnknown("minecraft:potion", 9999, Lookup.RENAMED));
        assertNull(PALETTER.vanilla(PALETTER.latest()).itemVariant("minecraft:not_an_item", 0, Lookup.RENAMED));
    }

    @Test
    void customContentHasHandlesInItsPalette() {
        CustomBlock lamp = lamp();
        CustomItem ruby = ruby();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp).item(ruby).build());
        BedrockBlockType type = lamp.type();
        for (BedrockBlockState state : type.states()) {
            assertSame(state, palette.blockState(state.toNbt(), Lookup.RENAMED));
        }
        assertNull(PALETTER.vanilla(PALETTER.latest()).blockState(type.defaultState().toNbt(), Lookup.RENAMED));
        BedrockItemVariant handle = palette.itemVariant("example:ruby", 0, Lookup.RENAMED);
        assertNotNull(handle);
        assertSame(ruby.type().variant(), handle, "the palette hands back the item's own handle");
        assertSame(handle, palette.itemVariant("example:ruby", 0, Lookup.RENAMED));
        assertNotNull(palette.itemDefinition(handle.type()));
        assertTrue(palette.itemVariants().contains(handle));
        assertTrue(palette.itemVariants().contains(BedrockItems.APPLE.variant()));
        assertNull(PALETTER.vanilla(PALETTER.latest()).itemVariant("example:ruby", 0, Lookup.RENAMED));
    }

    @Test
    void decodeReturnsTheStack() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        var apple = palette.encode(BedrockItemStack.of(BedrockItems.APPLE, 3));
        BedrockItemStack decoded = palette.decode(apple);
        assertNotNull(decoded);
        assertSame(BedrockItems.APPLE.variant(), decoded.variant());
        assertEquals(3, decoded.count());
        assertNull(palette.decode(org.cloudburstmc.protocol.bedrock.data.inventory.ItemData.AIR));
    }

    /** The hot path of translators: a handle to its runtime id is a field read and an array load. */
    @Test
    void handleToRuntimeIdDoesNotAllocate() {
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp()).build());
        List<BedrockBlockState> states = new java.util.ArrayList<>(BedrockBlockType.vanillaTypes().stream()
            .flatMap(type -> type.states().stream()).toList());
        states.addAll(lamp().type().states());
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

    @Test
    void resolveGivesWhatEncodeSends() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.vanilla(version);
            for (BedrockItemVariant variant : List.of(BedrockItems.APPLE.variant(), BedrockItems.POTION.variant(5), BedrockItems.OAK_PLANKS.variant())) {
                ResolvedItem resolved = palette.resolve(variant);
                ItemData sent = palette.encode(BedrockItemStack.of(variant));
                if (resolved == null) {
                    assertSame(ItemData.AIR, sent, version.toString());
                    continue;
                }
                assertSame(resolved.definition(), sent.getDefinition(), version.toString());
                assertEquals(resolved.damage(), sent.getDamage(), version.toString());
                assertSame(resolved.block(), sent.getBlockDefinition(), version.toString());
                assertEquals(resolved.fallback(), !variant.equals(resolved.variant()), version.toString());
            }
        }
    }

    @Test
    void resolveOfVanillaVariantsDoesNotAllocate() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion());
        BedrockItemVariant[] variants = palette.itemVariants().toArray(BedrockItemVariant[]::new);
        long sum = 0;
        for (int warmup = 0; warmup < 30; warmup++) {
            for (BedrockItemVariant variant : variants) {
                sum += palette.resolve(variant).damage();
            }
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int round = 0; round < 20; round++) {
            for (BedrockItemVariant variant : variants) {
                sum += palette.resolve(variant).damage();
            }
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated < 4096, allocated + " bytes allocated by " + (20L * variants.length) + " resolutions (checksum " + sum + ")");
    }
}
