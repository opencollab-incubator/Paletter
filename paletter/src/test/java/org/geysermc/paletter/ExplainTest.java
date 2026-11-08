package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockResolution;
import org.geysermc.paletter.block.BlockStates;
import org.geysermc.paletter.item.ItemVariant;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void exactBlock() {
        Palette palette = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        BlockExplanation explanation = palette.explain(BlockStates.of("minecraft:stone"));
        int canonical = PALETTER.canonicalBlock(BlockStates.of("minecraft:stone"));
        assertEquals(canonical, explanation.canonical());
        assertEquals(BlockResolution.Kind.EXACT, explanation.kind());
        assertEquals(palette.runtimeId(canonical), explanation.runtimeId());
        assertEquals(explanation.runtimeId(), explanation.hashedId());
        String text = explanation.toString();
        assertTrue(text.contains("minecraft:stone"), text);
        assertTrue(text.contains("exact"), text);
        assertTrue(text.contains("canonical id: " + canonical), text);
    }

    @Test
    void oldVersionShowsFallbackChain() {
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst(), BlockNetworkIds.SEQUENTIAL);
        Palette newest = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.SEQUENTIAL);
        boolean found = false;
        for (int i = 0; i < newest.canonicalBlockCount() && !found; i++) {
            BlockExplanation explanation = oldest.explain(newest.canonicalBlocks().state(i));
            if (explanation.kind() == BlockResolution.Kind.FALLBACK || explanation.kind() == BlockResolution.Kind.DEFAULT) {
                found = true;
                assertEquals(i, explanation.canonical());
                assertEquals(oldest.runtimeId(i), explanation.runtimeId());
                assertNotNull(explanation.sent());
                assertTrue(explanation.toString().contains(explanation.kind().name().toLowerCase()));
                if (explanation.kind() == BlockResolution.Kind.FALLBACK) {
                    assertEquals(2, explanation.chain().size());
                    assertTrue(explanation.toString().contains(" -> "));
                }
            }
        }
        assertTrue(found, "the oldest version lacks no block of the newest");
    }

    @Test
    void items() {
        Palette palette = PALETTER.vanilla(PALETTER.latest(), BlockNetworkIds.HASHED);
        ItemExplanation apple = palette.explain(BedrockItems.APPLE.base());
        assertEquals(palette.canonicalItem(BedrockItems.APPLE), apple.canonical());
        assertEquals(BlockResolution.Kind.EXACT, apple.kind());
        assertEquals("minecraft:apple", apple.item());
        assertNull(apple.block());
        assertNotNull(apple.creative());

        ItemExplanation stone = palette.explain(ItemVariant.of("minecraft:stone"));
        assertNotNull(stone.block());
        assertTrue(stone.toString().contains("block:"), stone.toString());

        ItemExplanation unknown = palette.explain(ItemVariant.of("minecraft:potion", 9999));
        assertEquals(-1, unknown.canonical());
        assertNull(unknown.creative());
        assertTrue(unknown.toString().contains("not in the creative inventory"), unknown.toString());
        assertFalse(apple.toString().isEmpty());
    }
}
