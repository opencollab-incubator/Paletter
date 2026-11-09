package org.geysermc.paletter.item;

import org.geysermc.paletter.internal.item.ResolvedItem;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.paletter.Ids;
import org.geysermc.paletter.Origin;
import org.geysermc.paletter.Palette;
import org.geysermc.paletter.PaletteLayer;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPatchTest {
    static final Paletter PALETTER = Paletter.bundled();

    private static PaletteLayer ruby() {
        return PaletteLayer.builder("session")
            .item(CustomItem.builder("server:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS)).build())
            .build();
    }

    private static ItemData data(ResolvedItem resolved) {
        return ItemData.builder().definition(resolved.definition()).damage(resolved.damage()).count(1)
            .blockDefinition(resolved.block()).build();
    }

    @Test
    void sessionLayerIsAPatchOverTheBase() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(ruby());
        ItemRegistry items = session.items();

        assertEquals(1, items.ownSize());
        assertTrue(base.items().ownSize() > 1900);
        RegisteredItem ruby = items.item("server:ruby");
        assertNotNull(ruby);

        // Merged once, in runtime id order: the base's definitions (the same instances), then the new one
        List<RegisteredItem> expected = new ArrayList<>(base.items().definitions());
        expected.add(ruby);
        assertEquals(expected, items.definitions());
        assertSame(items.definitions(), items.definitions());
        for (int i = 0; i < expected.size() - 1; i++) {
            assertSame(base.items().definitions().get(i), items.definitions().get(i));
        }
        assertEquals(expected, items.toPacket().getItems());

        assertSame(base.items().item("minecraft:apple"), items.item("minecraft:apple"));
        assertSame(ruby, items.getDefinition(ruby.getRuntimeId()));
        assertTrue(items.isRegistered(ruby));
        assertTrue(items.isRegistered(base.items().item("minecraft:apple")));
        assertNull(base.items().item("server:ruby"));
        assertFalse(base.items().isRegistered(ruby));

        assertEquals(Origin.custom("session"), ruby.origin());
        assertTrue(ruby.isCustom());
        assertEquals("server:ruby", ruby.key().identifier());
        assertNotNull(ruby.customItem());
        assertTrue(base.items().item("minecraft:apple").origin().isVanilla());
        assertTrue(base.items().item("minecraft:apple").components().isEmpty());
    }

    @Test
    void reusedCanonicalTableResolvesLikeTheBase() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(ruby());
        int baseSize = Ids.itemCount(base);
        assertEquals(baseSize + 1, Ids.itemCount(session));
        for (int i = 0; i < baseSize; i++) {
            ResolvedItem a = Ids.itemAt(base, i);
            ResolvedItem b = Ids.itemAt(session, i);
            assertSame(a, b);
            if (a != null) {
                assertEquals(Ids.match(base, data(a)), Ids.match(session, data(a)), String.valueOf(a));
            }
        }
        ResolvedItem ruby = Ids.itemAt(session, baseSize);
        assertSame(session.items().item("server:ruby"), ruby.definition());
        assertEquals(baseSize, Ids.match(session, data(ruby)));
    }

    @Test
    void overrideShadowsTheBaseOnlyInThePatch() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.components(MaxStackSize.of(1)))
            .build());
        RegisteredItem original = base.items().item("minecraft:cake");
        RegisteredItem overridden = session.items().item("minecraft:cake");
        assertNotSame(original, overridden);
        assertEquals(1, session.items().ownSize());
        assertEquals(original.getRuntimeId(), overridden.getRuntimeId());
        assertSame(overridden, session.items().getDefinition(original.getRuntimeId()));
        assertSame(original, base.items().getDefinition(original.getRuntimeId()));
        assertTrue(session.items().isRegistered(overridden));
        assertFalse(session.items().isRegistered(original));
        // The override keeps the origin of the item, and records what it set
        assertTrue(overridden.origin().isVanilla());
        assertEquals(1, overridden.components().size());
        assertEquals(base.items().definitions().size(), session.items().definitions().size());
        assertTrue(session.items().definitions().contains(overridden));
        assertTrue(session.items().definitions().stream().noneMatch(i -> i == original));
        assertSame(base.items().item("minecraft:apple"), session.items().item("minecraft:apple"));
        // The table is built fully: stacks of the cake resolve to the override
        for (int i = 0; i < Ids.itemCount(base); i++) {
            ResolvedItem resolved = Ids.itemAt(session, i);
            if (resolved != null && resolved.definition().getIdentifier().equals("minecraft:cake")) {
                assertSame(overridden, resolved.definition());
            }
        }
    }
}
