package org.geysermc.paletter;

import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.internal.block.BlockStates;
import org.geysermc.paletter.internal.item.ItemVariant;
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
        Palette palette = Palettes.paletter(BlockNetworkIds.HASHED).vanilla(PALETTER.latest());
        BlockExplanation explanation = palette.explain(BlockStates.of("minecraft:stone"));
        int canonical = Ids.block(PALETTER, BlockStates.of("minecraft:stone"));
        assertNotNull(explanation.handle());
        assertEquals(BlockExplanation.Kind.EXACT, explanation.kind());
        assertEquals(Ids.runtimeId(palette, canonical), explanation.runtimeId());
        assertEquals(explanation.runtimeId(), explanation.hashedId());
        String text = explanation.toString();
        assertTrue(text.contains("minecraft:stone"), text);
        assertTrue(text.contains("exact"), text);
        assertTrue(text.contains("handle:       minecraft:stone"), text);
    }

    @Test
    void oldVersionShowsFallbackChain() {
        Palette oldest = PALETTER.vanilla(PALETTER.versions().getFirst());
        Palette newest = Palettes.paletter(BlockNetworkIds.SEQUENTIAL).vanilla(PALETTER.latest());
        boolean found = false;
        for (int i = 0; i < Ids.blockCount(newest) && !found; i++) {
            BlockExplanation explanation = oldest.explain(Ids.canonicalBlocks(newest).state(i));
            if (explanation.kind() == BlockExplanation.Kind.FALLBACK || explanation.kind() == BlockExplanation.Kind.DEFAULT) {
                found = true;
                assertNotNull(explanation.handle());
                assertEquals(Ids.runtimeId(oldest, i), explanation.runtimeId());
                assertNotNull(explanation.sent());
                assertTrue(explanation.toString().contains(explanation.kind().name().toLowerCase()));
                if (explanation.kind() == BlockExplanation.Kind.FALLBACK) {
                    assertEquals(2, explanation.chain().size());
                    assertTrue(explanation.toString().contains(" -> "));
                }
            }
        }
        assertTrue(found, "the oldest version lacks no block of the newest");
    }

    @Test
    void items() {
        Palette palette = Palettes.paletter(BlockNetworkIds.HASHED).vanilla(PALETTER.latest());
        ItemExplanation apple = palette.explain(BedrockItems.APPLE.base());
        assertNotNull(apple.handle());
        assertEquals(ItemExplanation.Kind.EXACT, apple.kind());
        assertEquals("minecraft:apple", apple.item());
        assertNull(apple.block());
        assertTrue(apple.creative());

        ItemExplanation stone = palette.explain("minecraft:stone", 0);
        assertNotNull(stone.block());
        assertTrue(stone.toString().contains("block:"), stone.toString());

        ItemExplanation unknown = palette.explain("minecraft:potion", 9999);
        assertNull(unknown.handle());
        assertFalse(unknown.creative());
        assertTrue(unknown.toString().contains("not in the creative inventory"), unknown.toString());
        assertFalse(apple.toString().isEmpty());
    }
}
