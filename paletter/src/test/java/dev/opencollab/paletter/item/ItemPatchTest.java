package dev.opencollab.paletter.item;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.Ids;
import dev.opencollab.paletter.Origin;
import dev.opencollab.paletter.Palette;
import dev.opencollab.paletter.PaletteLayer;
import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.internal.access.Access;
import dev.opencollab.paletter.internal.item.ItemRegistry;
import dev.opencollab.paletter.item.component.MaxStackSize;
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
            .item(CustomItem.builder("server:ruby").menuCategory(CreativeCategory.ITEMS).build())
            .build();
    }

    private static ItemData data(ResolvedItem resolved) {
        return ItemData.builder().definition(resolved.definition()).damage(resolved.aux()).count(1)
            .blockDefinition(resolved.block()).build();
    }

    @Test
    void sessionLayerIsAPatchOverTheBase() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(ruby());
        ItemRegistry items = Access.palette().items(session);

        assertEquals(1, items.ownSize());
        assertTrue(Access.palette().items(base).ownSize() > 1900);
        RegisteredItem ruby = items.getDefinition("server:ruby");
        assertNotNull(ruby);

        // Merged once, in runtime id order: the base's definitions (the same instances), then the new one
        List<RegisteredItem> expected = new ArrayList<>(Access.palette().items(base).definitions());
        expected.add(ruby);
        assertEquals(expected, items.definitions());
        assertSame(items.definitions(), items.definitions());
        for (int i = 0; i < expected.size() - 1; i++) {
            assertSame(Access.palette().items(base).definitions().get(i), items.definitions().get(i));
        }
        assertEquals(expected, items.toPacket().getItems());

        assertSame(Access.palette().items(base).getDefinition("minecraft:apple"), items.getDefinition("minecraft:apple"));
        assertSame(ruby, items.getDefinition(ruby.getRuntimeId()));
        assertTrue(items.isRegistered(ruby));
        assertTrue(items.isRegistered(Access.palette().items(base).getDefinition("minecraft:apple")));
        assertNull(Access.palette().items(base).getDefinition("server:ruby"));
        assertFalse(Access.palette().items(base).isRegistered(ruby));

        assertEquals(Origin.custom("session"), ruby.origin());
        assertTrue(ruby.isCustom());
        assertEquals("server:ruby", ruby.type().identifier());
        assertNotNull(ruby.customItem());
        assertTrue(Access.palette().items(base).getDefinition("minecraft:apple").origin().isVanilla());
        assertTrue(Access.palette().items(base).getDefinition("minecraft:apple").components().isEmpty());
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
        assertSame(Access.palette().items(session).getDefinition("server:ruby"), ruby.definition());
        assertEquals(baseSize, Ids.match(session, data(ruby)));
    }

    @Test
    void overrideShadowsTheBaseOnlyInThePatch() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(1)))
            .build());
        RegisteredItem original = Access.palette().items(base).getDefinition("minecraft:cake");
        RegisteredItem overridden = Access.palette().items(session).getDefinition("minecraft:cake");
        assertNotSame(original, overridden);
        assertEquals(1, Access.palette().items(session).ownSize());
        assertEquals(original.getRuntimeId(), overridden.getRuntimeId());
        assertSame(overridden, Access.palette().items(session).getDefinition(original.getRuntimeId()));
        assertSame(original, Access.palette().items(base).getDefinition(original.getRuntimeId()));
        assertTrue(Access.palette().items(session).isRegistered(overridden));
        assertFalse(Access.palette().items(session).isRegistered(original));
        // The override keeps the origin of the item, and records what it set
        assertTrue(overridden.origin().isVanilla());
        assertEquals(1, overridden.components().size());
        assertEquals(Access.palette().items(base).definitions().size(), Access.palette().items(session).definitions().size());
        assertTrue(Access.palette().items(session).definitions().contains(overridden));
        assertTrue(Access.palette().items(session).definitions().stream().noneMatch(i -> i == original));
        assertSame(Access.palette().items(base).getDefinition("minecraft:apple"), Access.palette().items(session).getDefinition("minecraft:apple"));
        // The table is built fully: stacks of the cake resolve to the override
        for (int i = 0; i < Ids.itemCount(base); i++) {
            ResolvedItem resolved = Ids.itemAt(session, i);
            if (resolved != null && resolved.definition().getIdentifier().equals("minecraft:cake")) {
                assertSame(overridden, resolved.definition());
            }
        }
    }
}
