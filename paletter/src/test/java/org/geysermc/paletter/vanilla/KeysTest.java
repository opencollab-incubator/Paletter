package org.geysermc.paletter.vanilla;

import org.geysermc.paletter.Ids;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.internal.CanonicalItems;
import org.geysermc.paletter.internal.Forwarding;
import org.geysermc.paletter.internal.data.Renames;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysTest {
    private static final Paletter PALETTER = Paletter.bundled();

    @Test
    void ofReturnsTheConstants() {
        assertSame(BedrockItems.APPLE, BedrockItemType.fromIdentifier("minecraft:apple"));
        assertFalse(BedrockItemType.fromIdentifier("minecraft:apple").isCustom());
    }

    @Test
    void ofAllocatesNothingForAKnownIdentifier() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        BedrockItemType[] keep = new BedrockItemType[1];
        for (int i = 0; i < 10_000; i++) {
            keep[0] = BedrockItemType.fromIdentifier("minecraft:apple");
        }
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10_000; i++) {
            keep[0] = BedrockItemType.fromIdentifier("minecraft:apple");
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertSame(BedrockItems.APPLE, keep[0]);
        assertTrue(allocated < 10_000, allocated + " bytes for 10000 calls");
    }

    @Test
    void customTypesAreEqualByIdentifier() {
        BedrockItemType first = BedrockItemType.fromIdentifier("test:thing");
        // Not interned: nothing is kept for identifiers the data does not know
        assertNotSame(first, BedrockItemType.fromIdentifier("test:thing"));
        assertEquals(first, BedrockItemType.fromIdentifier("test:thing"));
        assertTrue(first.isCustom());
        assertNotEquals(first, BedrockItemType.fromIdentifier("test:other"));
        assertEquals(first.hashCode(), BedrockItemType.fromIdentifier("test:thing").hashCode());
    }

    @Test
    void variantsOfKnownTypesAreTheKnownInstances() {
        assertSame(BedrockItems.APPLE.variant(), BedrockItemVariant.fromIdentifier("minecraft:apple", 0));
        assertSame(BedrockItems.APPLE.variant(), BedrockItems.APPLE.variant(0));
        for (BedrockItemVariant known : BedrockItems.POTION.variants()) {
            assertSame(known, BedrockItems.POTION.variant(known.aux()));
        }
        BedrockItemVariant unknown = BedrockItemVariant.fromIdentifier("test:thing", 7);
        assertEquals(7, unknown.aux());
        assertEquals(BedrockItemType.fromIdentifier("test:thing"), unknown.type());
        assertEquals(unknown, BedrockItemVariant.fromIdentifier("test:thing", 7));
    }

    @Test
    void renamesForward() {
        Renames renames = Renames.parse(List.of("item test:old_apple minecraft:apple"));
        assertEquals("minecraft:apple", renames.item("test:old_apple"));
        CanonicalItems items = CanonicalItems.of(PALETTER.vanillaData(PALETTER.latest()), renames);
        Forwarding forwarding = items.forward(item("test:old_apple", 0));
        assertEquals(Forwarding.Kind.RENAMED, forwarding.kind());
        assertEquals(BedrockItems.APPLE.variant().canonicalIn(items), forwarding.canonical());
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
        assertEquals(BedrockItems.GOLDEN_APPLE.variant().canonicalIn(items), forwarding.canonical());
        Forwarding other = items.forward(item("test:legacy", 2));
        assertEquals(BedrockItems.APPLE.variant().canonicalIn(items), other.canonical());
    }

    private static org.geysermc.paletter.internal.item.ItemVariant item(String id, int damage) {
        return org.geysermc.paletter.internal.item.ItemVariant.of(id, damage);
    }
}
