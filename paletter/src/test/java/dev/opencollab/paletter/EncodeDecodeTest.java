package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.block.BlockStates;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.ItemFallback;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        BedrockItemStack stack = BedrockItemStack.builder(BedrockItems.DIAMOND_SWORD.base()).nbt(tag).build();
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
    void adventureFieldsAndAuxRoundTrip() {
        Palette palette = latest();
        BedrockItemStack stack = BedrockItemStack.builder(BedrockItems.DIAMOND_PICKAXE.base()).aux(3).count(2)
            .canPlace("minecraft:stone").canBreak("minecraft:dirt", "minecraft:grass_block").blockingTicks(40).build();
        ItemData data = palette.encode(stack);
        assertEquals(3, data.getDamage());
        assertArrayEquals(new String[] {"minecraft:stone"}, data.getCanPlace());
        assertArrayEquals(new String[] {"minecraft:dirt", "minecraft:grass_block"}, data.getCanBreak());
        assertEquals(40, data.getBlockingTicks());
        assertEquals(stack, palette.decode(data));
        assertEquals(stack, stack.toBuilder().build());
        // A named variant takes no other aux
        assertThrows(IllegalArgumentException.class, () -> BedrockItemStack.builder(BedrockItems.POTION.variant(5)).aux(6).build());
    }

    @Test
    void customStacksRoundTrip() {
        CustomItem ruby = CustomItem.builder("example:ruby").menuCategory(CreativeCategory.ITEMS).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").item(ruby).build());
        BedrockItemStack stack = BedrockItemStack.of(ruby.type().base(), 7);
        ItemData data = palette.encode(stack);
        assertEquals("example:ruby", data.getDefinition().getIdentifier());
        assertEquals(stack, palette.decode(data));
        // Not in this palette: the default fallback, as for every other unknown item
        assertEquals("minecraft:unknown", latest().encode(stack).getDefinition().getIdentifier());
    }

    @Test
    void aBlockStateOfTheStackIsKeptOnlyIfItDiffersFromTheItemsBlock() {
        Palette palette = latest();
        BedrockItemVariant log = Access.vanilla().itemVariant("minecraft:oak_log", 0);
        BedrockItemStack plain = BedrockItemStack.of(log);
        ItemData plainData = palette.encode(plain);
        assertNotNull(plainData.getBlockDefinition());
        assertNull(palette.decode(plainData).blockState(), "the default block is not a block state of the stack");

        BedrockBlockState sideways = PALETTER.vanilla(PALETTER.latest()).blockState(BlockStates.of("minecraft:oak_log", Map.of("pillar_axis", "x")), Lookup.RENAMED);
        assertNotNull(sideways);
        ItemData data = palette.encode(plain.withBlockState(sideways));
        assertEquals(Access.vanilla().canonicalId(sideways, palette.blocks().canonical()), palette.blocks().canonical(data.getBlockDefinition()));
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
            .itemType(oldest, BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.STICK.base()).forced())
            .build();
        Paletter paletter = Paletter.builder().fallbacks(fallbacks).build();
        Palette palette = paletter.palette(OLDEST);
        BedrockItemStack boat = BedrockItemStack.of(Access.vanilla().itemVariant("minecraft:poplar_boat", 0), 2);
        ItemData data = palette.encode(boat);
        assertEquals("minecraft:stick", data.getDefinition().getIdentifier());
        assertEquals(2, data.getCount());
        // Newer versions have the boat itself
        assertEquals("minecraft:poplar_boat", paletter.palette(PALETTER.latest().protocolVersion()).encode(boat).getDefinition().getIdentifier());
    }

    @Test
    void nothingAtAllEncodesToAirAndDecodesToNull() {
        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).removeItemTypes(BedrockItems.APPLE).build();
        assertEquals(ItemData.AIR, none.palette(OLDEST).encode(BedrockItemStack.of(BedrockItems.APPLE.base())));
        assertNull(latest().decode(ItemData.AIR));
    }
}
