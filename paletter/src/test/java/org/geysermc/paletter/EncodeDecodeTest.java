package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.vanilla.BedrockBlockState;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stacks cross the protocol boundary with {@link Palette#encode} and {@link Palette#decode}. */
class EncodeDecodeTest {
    private static final int OLDEST = 2168;
    private static final Paletter PALETTER = Paletter.bundled();

    private static Palette latest() {
        return PALETTER.palette(PALETTER.latest().protocolVersion());
    }

    @Test
    void vanillaStacksRoundTrip() {
        Palette palette = latest();
        NbtMap tag = NbtMap.builder().putString("custom", "value").build();
        BedrockItemStack stack = new BedrockItemStack(BedrockItems.DIAMOND_SWORD.base(), 1, tag, null);
        ItemData data = palette.encode(stack);
        assertEquals("minecraft:diamond_sword", data.getDefinition().getIdentifier());
        assertEquals(tag, data.getTag());
        BedrockItemStack decoded = palette.decode(data);
        assertEquals(stack, decoded);

        BedrockItemStack apples = BedrockItemStack.of(BedrockItems.APPLE.base(), 12);
        assertEquals(12, palette.encode(apples).getCount());
        assertEquals(apples, palette.decode(palette.encode(apples)));
    }

    @Test
    void auxVariantsRoundTrip() {
        Palette palette = latest();
        BedrockItemVariant potion = BedrockItems.POTION.variant(5);
        ItemData data = palette.encode(BedrockItemStack.of(potion));
        assertEquals(5, data.getDamage());
        BedrockItemStack decoded = palette.decode(data);
        assertNotNull(decoded);
        assertSame(potion, decoded.variant());
        assertEquals(5, decoded.variant().aux());
    }

    @Test
    void customStacksRoundTrip() {
        CustomItem ruby = CustomItem.builder("example:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build();
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").item(ruby).build());
        BedrockItemStack stack = BedrockItemStack.of(ruby.type().base(), 7);
        ItemData data = palette.encode(stack);
        assertEquals("example:ruby", data.getDefinition().getIdentifier());
        assertEquals(stack, palette.decode(data));
        // Not in this palette: nothing to send
        assertEquals(ItemData.AIR, latest().encode(stack));
    }

    @Test
    void aBlockStateOfTheStackIsKeptOnlyIfItDiffersFromTheItemsBlock() {
        Palette palette = latest();
        BedrockItemVariant log = BedrockItemVariant.of("minecraft:oak_log", 0);
        BedrockItemStack plain = BedrockItemStack.of(log);
        ItemData plainData = palette.encode(plain);
        assertNotNull(plainData.getBlockDefinition());
        assertNull(palette.decode(plainData).blockState(), "the default block is not a block state of the stack");

        BedrockBlockState sideways = PALETTER.block(BlockStates.of("minecraft:oak_log", Map.of("pillar_axis", "x")));
        assertNotNull(sideways);
        ItemData data = palette.encode(plain.withBlockState(sideways));
        assertEquals(sideways.canonicalIn(palette.blocks().canonical()), palette.blocks().canonical(data.getBlockDefinition()));
        assertTrue(data.getBlockDefinition().getRuntimeId() != plainData.getBlockDefinition().getRuntimeId());
        BedrockItemStack decoded = palette.decode(data);
        assertNotNull(decoded);
        assertSame(log, decoded.variant());
        assertSame(sideways, decoded.blockState());
    }

    @Test
    void fallbacksApplyWhenEncodingForAnOlderVersion() {
        DataVersion oldest = PALETTER.version(OLDEST);
        Fallbacks fallbacks = Fallbacks.builder()
            .item(oldest, BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.STICK).forced())
            .build();
        Paletter paletter = Paletter.builder().fallbacks(fallbacks).build();
        Palette palette = paletter.palette(OLDEST);
        BedrockItemStack boat = BedrockItemStack.of(BedrockItemVariant.of("minecraft:poplar_boat", 0), 2);
        ItemData data = palette.encode(boat);
        assertEquals("minecraft:stick", data.getDefinition().getIdentifier());
        assertEquals(2, data.getCount());
        // Newer versions have the boat itself
        assertEquals("minecraft:poplar_boat", paletter.palette(PALETTER.latest().protocolVersion()).encode(boat).getDefinition().getIdentifier());
    }

    @Test
    void nothingAtAllEncodesToAirAndDecodesToNull() {
        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).vanilla(vanilla -> vanilla.remove(BedrockItems.APPLE)).build();
        assertEquals(ItemData.AIR, none.palette(OLDEST).encode(BedrockItemStack.of(BedrockItems.APPLE.base())));
        assertNull(latest().decode(ItemData.AIR));
    }
}
