package dev.opencollab.paletter.vanilla;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.internal.data.Renames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByIdentifierTest {
    @Test
    void byIdentifierFindsTheConstantsAndMintsNothing() {
        assertSame(BedrockItems.APPLE, BedrockItemType.byIdentifier("minecraft:apple"));
        assertSame(BedrockBlocks.STONE, BedrockBlockType.byIdentifier("minecraft:stone"));
        assertNull(BedrockItemType.byIdentifier("nobody:item"));
        assertNull(BedrockBlockType.byIdentifier("nobody:block"));
        assertThrows(NullPointerException.class, () -> BedrockItemType.byIdentifier(null));
        assertThrows(NullPointerException.class, () -> BedrockBlockType.byIdentifier(null));
    }

    @Test
    void variantByIdentifierFindsConstantsAndMintsNothing() {
        assertEquals(BedrockItems.APPLE.variant(0), BedrockItemVariant.byIdentifier("minecraft:apple", 0));
        assertNull(BedrockItemVariant.byIdentifier("nobody:item", 3));
        assertThrows(NullPointerException.class, () -> BedrockItemVariant.byIdentifier(null, 0));
    }

    /** The bundled data has no renames yet, so the rename step is checked with renames given to the package private overload. */
    @Test
    void byIdentifierAppliesRenamesOfItemsAndBlocks() {
        Renames renames = Renames.parse(List.of("item minecraft:old_apple minecraft:apple", "block minecraft:old_stone minecraft:stone",
            "item minecraft:old_nothing nobody:item"));
        assertSame(BedrockItems.APPLE, BedrockItemType.byIdentifier("minecraft:old_apple", renames));
        assertSame(BedrockBlocks.STONE, BedrockBlockType.byIdentifier("minecraft:old_stone", renames));
        assertSame(BedrockItems.APPLE, BedrockItemType.byIdentifier("minecraft:apple", renames));
        // A rename to something the data lacks does not make the old name known
        assertNull(BedrockItemType.byIdentifier("minecraft:old_nothing", renames));
        assertNull(BedrockItemType.byIdentifier("minecraft:old_apple", Renames.NONE));
        assertNull(BedrockBlockType.byIdentifier("minecraft:old_stone", Renames.NONE));
    }

    @Test
    void typesMadeForUnknownIdentifiersAreCustom() {
        assertTrue(BedrockItemType.fromIdentifier("nobody:item").isCustom());
        assertTrue(BedrockBlockType.fromIdentifier("nobody:block").isCustom());
        assertFalse(BedrockItemType.fromIdentifier("minecraft:apple").isCustom());
        assertFalse(BedrockBlockType.fromIdentifier("minecraft:stone").isCustom());
    }

    @Test
    void paletterResolvesTypesAgainstItsNewestData() {
        Paletter paletter = Paletter.bundled();
        assertSame(BedrockItems.APPLE, paletter.itemType("minecraft:apple"));
        assertSame(BedrockBlocks.STONE, paletter.blockType("minecraft:stone"));
        assertNull(paletter.itemType("nobody:item"));
        assertNull(paletter.blockType("nobody:block"));
        assertThrows(NullPointerException.class, () -> paletter.itemType(null));
        assertThrows(NullPointerException.class, () -> paletter.blockType(null));
    }
}
