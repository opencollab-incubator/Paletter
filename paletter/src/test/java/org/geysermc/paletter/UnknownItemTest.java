package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.internal.item.ItemVariant;
import org.geysermc.paletter.item.BedrockItemStack;
import org.geysermc.paletter.item.ItemFallback;
import org.geysermc.paletter.item.ItemRegistry;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.geysermc.paletter.vanilla.BedrockItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.geysermc.paletter.block.BlockRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item the palette cannot send takes the same stand-in whichever way it is asked for: the variants of the newest data it
 * lacks, and the ones nobody registered. The aux value of a stack survives decoding. Caches never grow with what callers ask.
 */
class UnknownItemTest {
    private static final int OLDEST = 2168;
    private static final Paletter PALETTER = Paletter.bundled();

    private static Palette latest() {
        return PALETTER.palette(PALETTER.latest().protocolVersion());
    }

    /** A canonical item of the newest version that the oldest lacks. */
    private static BedrockItemVariant missingInOldest(Palette oldest) {
        for (BedrockItemVariant variant : latest().itemVariants()) {
            if (oldest.item(variant) == null) {
                return variant;
            }
        }
        throw new AssertionError("fixture: the oldest version lacks items of the newest");
    }

    @Test
    void unknownAndMissingItemsAreSentAsTheSameStandIn() {
        Palette oldest = PALETTER.palette(OLDEST);
        BedrockItemVariant missing = missingInOldest(oldest);
        BedrockItemVariant nobody = BedrockItemVariant.of("nobody:item", 0);
        ItemData fromMissing = oldest.encode(BedrockItemStack.of(missing, 3));
        ItemData fromNobody = oldest.encode(BedrockItemStack.of(nobody, 3));
        assertEquals("minecraft:unknown", fromMissing.getDefinition().getIdentifier());
        assertSame(fromMissing.getDefinition(), fromNobody.getDefinition());
        assertEquals(3, fromNobody.getCount());
        assertTrue(oldest.isFallback(nobody));
        assertEquals(BedrockItems.UNKNOWN.base(), oldest.fallbackOf(nobody));
        assertEquals(oldest.fallbackOf(missing), oldest.fallbackOf(nobody));
        // The newest version has no item of that name either
        assertEquals("minecraft:unknown", latest().encode(BedrockItemStack.of(nobody)).getDefinition().getIdentifier());
    }

    @Test
    void theConfiguredDefaultFallbackIsUsedEverywhere() {
        Paletter stick = Paletter.builder().fallbacks(Fallbacks.builder().defaultItem(ItemFallback.of(BedrockItems.STICK)).build()).build();
        Palette oldest = stick.palette(OLDEST);
        BedrockItemVariant missing = missingInOldest(oldest);
        BedrockItemVariant nobody = BedrockItemVariant.of("nobody:item", 7);
        assertEquals("minecraft:stick", oldest.encode(BedrockItemStack.of(missing)).getDefinition().getIdentifier());
        assertEquals("minecraft:stick", oldest.encode(BedrockItemStack.of(nobody)).getDefinition().getIdentifier());
        assertEquals(BedrockItems.STICK.base(), oldest.fallbackOf(nobody));
    }

    @Test
    void withoutADefaultFallbackBothPathsAreAir() {
        Paletter none = Paletter.builder().fallbacks(Fallbacks.NONE).build();
        Palette oldest = none.palette(OLDEST);
        BedrockItemVariant missing = missingInOldest(oldest);
        BedrockItemVariant nobody = BedrockItemVariant.of("nobody:item", 0);
        assertEquals(ItemData.AIR, oldest.encode(BedrockItemStack.of(missing)));
        assertEquals(ItemData.AIR, oldest.encode(BedrockItemStack.of(nobody)));
        assertFalse(oldest.isFallback(nobody));
        assertNull(oldest.fallbackOf(nobody));
    }

    @Test
    void decodeKeepsTheDamageOfAnItemWithoutThatVariant() {
        Palette palette = latest();
        BedrockItemVariant sword = BedrockItems.DIAMOND_SWORD.base();
        ItemData worn = palette.encode(BedrockItemStack.of(sword, 2)).toBuilder().damage(17).build();
        BedrockItemStack decoded = palette.decode(worn);
        assertNotNull(decoded);
        assertEquals(BedrockItems.DIAMOND_SWORD, decoded.type());
        assertEquals(17, decoded.variant().aux(), "the damage must not be lost");
        assertEquals(2, decoded.count());
        // And it goes back out as it came
        ItemData again = palette.encode(decoded);
        assertEquals(17, again.getDamage());
        assertSame(worn.getDefinition(), again.getDefinition());
        assertTrue(palette.sameStack(worn, again));

        // A listed variant is still the known instance, an unlisted aux of an item with variants keeps its aux
        BedrockItemVariant potion = BedrockItems.POTION.variant(5);
        assertSame(potion, palette.decode(palette.encode(BedrockItemStack.of(potion))).variant());
        ItemData odd = palette.encode(BedrockItemStack.of(BedrockItems.POTION.base())).toBuilder().damage(9999).build();
        BedrockItemStack oddStack = palette.decode(odd);
        assertNotNull(oddStack);
        assertEquals(BedrockItems.POTION, oddStack.type());
        assertEquals(9999, oddStack.variant().aux());
        // The base variant itself decodes to itself
        assertSame(sword, palette.decode(palette.encode(BedrockItemStack.of(sword))).variant());
    }

    private static int size(Object registry, String field) throws ReflectiveOperationException {
        Field f = registry.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(registry)).size();
    }

    @Test
    void resolvingWhatCallersChooseDoesNotGrowCaches() throws ReflectiveOperationException {
        Palette palette = latest();
        BlockRegistry blocks = palette.blocks();
        ItemRegistry items = palette.items();
        // Build the canonical tables, which cache what they resolve
        for (int id = 0; id < blocks.canonical().size(); id++) {
            blocks.block(id);
        }
        int cached = size(blocks, "resolutions");
        assertTrue(cached <= blocks.canonical().size(), "bounded by the canonical states");

        for (int i = 0; i < 5_000; i++) {
            // Misses, and states that adapt (a wrong value of a known block) but are not canonical
            assertNull(blocks.resolve(NbtMap.builder().putString("name", "chosen:block_" + i).putCompound("states", NbtMap.EMPTY).build()).block());
            blocks.resolve(NbtMap.builder().putString("name", "minecraft:oak_log")
                .putCompound("states", NbtMap.builder().putString("pillar_axis", "bogus_" + i).putInt("extra", i).build()).build());
            assertNotNull(blocks.resolve(NbtMap.builder().putString("name", "minecraft:oak_log")
                .putCompound("states", NbtMap.builder().putString("pillar_axis", "bogus_" + i).build()).build()).block());
            // Items: arbitrary identifiers and damage values, with and without a block state
            items.resolve(ItemVariant.of("chosen:item_" + i, i));
            items.resolve(ItemVariant.of("minecraft:oak_log", i));
            items.resolve(new ItemVariant("minecraft:oak_log", 0, NbtMap.builder().putString("name", "chosen:block_" + i)
                .putCompound("states", NbtMap.EMPTY).build()));
            palette.encode(BedrockItemStack.of(BedrockItemType.of("chosen:stack_" + i).variantOf(i)));
        }
        assertEquals(cached, size(blocks, "resolutions"), "what a caller asks is not kept");
    }
}
