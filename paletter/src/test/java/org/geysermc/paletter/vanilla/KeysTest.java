package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.Ids;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.Forwarding;
import org.geysermc.paletter.internal.data.Renames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void ofReturnsTheConstants() {
        assertSame(BedrockItems.APPLE, BedrockItemType.of("minecraft:apple"));
        assertFalse(BedrockItemType.of("minecraft:apple").isCustom());
    }

    @Test
    void customTypesAreInterned() {
        BedrockItemType first = BedrockItemType.of("test:thing");
        assertSame(first, BedrockItemType.of("test:thing"));
        assertTrue(first.isCustom());
        assertNotEquals(first, BedrockItemType.of("test:other"));
        assertEquals(first.hashCode(), BedrockItemType.of("test:thing").hashCode());
    }

    @Test
    void variantsOfKnownTypesAreTheKnownInstances() {
        assertSame(BedrockItems.APPLE.base(), BedrockItemVariant.of("minecraft:apple", 0));
        assertSame(BedrockItems.APPLE.base(), BedrockItemVariant.of(BedrockItems.APPLE, 0));
        for (BedrockItemVariant known : BedrockItems.POTION.variants()) {
            assertSame(known, BedrockItemVariant.of(BedrockItems.POTION, known.aux()));
        }
        BedrockItemVariant unknown = BedrockItemVariant.of("test:thing", 7);
        assertEquals(7, unknown.aux());
        assertSame(BedrockItemType.of("test:thing"), unknown.type());
        assertEquals(unknown, BedrockItemVariant.of("test:thing", 7));
    }

    @Test
    void renamesForward() {
        Renames renames = Renames.parse(List.of("item test:old_apple minecraft:apple"));
        assertEquals("minecraft:apple", renames.item("test:old_apple"));
        CanonicalItems items = CanonicalItems.of(PALETTER.vanillaData(PALETTER.latest()), renames);
        Forwarding forwarding = items.forward(item("test:old_apple", 0));
        assertEquals(Forwarding.Kind.RENAMED, forwarding.kind());
        assertEquals(BedrockItems.APPLE.base().canonicalIn(items), forwarding.canonical());
    }

    @Test
    void splitsForward() {
        Renames renames = Renames.parse(List.of("item minecraft:apple@5 minecraft:golden_apple", "item test:legacy@2 minecraft:apple@0"));
        Renames.Split split = renames.split("minecraft:apple", 5);
        assertEquals(new Renames.Split("minecraft:golden_apple", 0), split);
        assertNull(renames.split("minecraft:apple", 4));
        assertNull(renames.item("minecraft:apple"));

        CanonicalItems items = CanonicalItems.of(PALETTER.vanillaData(PALETTER.latest()), renames);
        Forwarding forwarding = items.forward(item("minecraft:apple", 5));
        assertEquals(Forwarding.Kind.RENAMED, forwarding.kind());
        assertEquals(BedrockItems.GOLDEN_APPLE.base().canonicalIn(items), forwarding.canonical());
        Forwarding other = items.forward(item("test:legacy", 2));
        assertEquals(BedrockItems.APPLE.base().canonicalIn(items), other.canonical());
    }

    private static org.geysermc.paletter.internal.item.ItemVariant item(String id, int damage) {
        return org.geysermc.paletter.internal.item.ItemVariant.of(id, damage);
    }
}
