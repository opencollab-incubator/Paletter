package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.item.BedrockItemStack;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stacks are the same only if everything but the net id is: {@link Palette#sameStack}, {@code ItemData#equals}, {@code BedrockItemStack#equals}. */
class SameStackTest {
    private static final Paletter HASHED = Palettes.paletter();
    private static final NbtMap ENCHANTED = NbtMap.builder().putInt("Damage", 4).build();
    private static final NbtMap RENAMED = NbtMap.builder().putString("name", "sword").build();

    private static Palette latest() {
        return HASHED.palette(HASHED.latest().protocolVersion());
    }

    private static BedrockItemStack sword(int count, NbtMap nbt) {
        return BedrockItemStack.builder(BedrockItems.DIAMOND_SWORD.base()).count(count).nbt(nbt).build();
    }

    @Test
    void equalStacksMatchWhateverTheInstance() {
        Palette palette = latest();
        ItemData a = palette.encode(sword(2, ENCHANTED));
        ItemData b = palette.encode(sword(2, NbtMap.builder().putInt("Damage", 4).build()));
        assertNotSame(a, b);
        assertEquals(a, b);
        assertTrue(palette.sameStack(a, b));
        assertEquals(sword(2, ENCHANTED), sword(2, NbtMap.builder().putInt("Damage", 4).build()));
        // The net id is no part of a stack
        assertTrue(palette.sameStack(a, b.toBuilder().usingNetId(true).netId(9).build()));
        // Air is air
        assertTrue(palette.sameStack(ItemData.AIR, ItemData.AIR));
    }

    @Test
    void stacksThatDifferOnlyInCountDoNotMatch() {
        Palette palette = latest();
        ItemData one = palette.encode(sword(1, ENCHANTED));
        ItemData two = palette.encode(sword(2, ENCHANTED));
        assertNotEquals(one, two);
        assertFalse(palette.sameStack(one, two));
        assertNotEquals(sword(1, ENCHANTED), sword(2, ENCHANTED));
    }

    @Test
    void stacksThatDifferOnlyInNbtDoNotMatch() {
        Palette palette = latest();
        ItemData enchanted = palette.encode(sword(1, ENCHANTED));
        ItemData renamed = palette.encode(sword(1, RENAMED));
        ItemData plain = palette.encode(sword(1, null));
        assertNotEquals(enchanted, renamed);
        assertNotEquals(enchanted, plain);
        assertFalse(palette.sameStack(enchanted, renamed));
        assertFalse(palette.sameStack(enchanted, plain));
        assertFalse(palette.sameStack(plain, enchanted));
        assertNotEquals(sword(1, ENCHANTED), sword(1, RENAMED));
        assertNotEquals(sword(1, ENCHANTED), sword(1, null));
        // No tag and an empty one are the same
        assertTrue(palette.sameStack(plain, plain.toBuilder().tag(NbtMap.EMPTY).build()));
        assertEquals(sword(1, null), sword(1, NbtMap.EMPTY));
    }

    @Test
    void everyOtherFieldCounts() {
        Palette palette = latest();
        ItemData base = palette.encode(sword(1, null));
        assertFalse(palette.sameStack(base, base.toBuilder().damage(3).build()));
        assertFalse(palette.sameStack(base, base.toBuilder().blockingTicks(5).build()));
        assertFalse(palette.sameStack(base, base.toBuilder().canPlace("minecraft:stone").build()));
        assertFalse(palette.sameStack(base, base.toBuilder().canBreak("minecraft:stone").build()));
        assertTrue(palette.sameStack(base.toBuilder().canPlace("minecraft:stone").build(), base.toBuilder().canPlace("minecraft:stone").build()));
        assertFalse(palette.sameStack(base, palette.encode(BedrockItemStack.of(BedrockItems.IRON_SWORD.base()))));
        assertFalse(palette.sameStack(base, ItemData.AIR));
    }

    @Test
    void theBlockOfTheStackCounts() {
        Palette palette = latest();
        BedrockItemStack log = BedrockItemStack.of(BedrockItems.OAK_LOG.base());
        BedrockBlockState sideways = Paletter.bundled().vanilla(Paletter.bundled().latest()).blockState(dev.opencollab.paletter.internal.block.BlockStates.of("minecraft:oak_log",
            java.util.Map.of("pillar_axis", "x")), Lookup.RENAMED);
        assertNotNull(sideways);
        ItemData plain = palette.encode(log);
        ItemData placing = palette.encode(log.withBlockState(sideways));
        assertTrue(palette.sameStack(plain, palette.encode(log)));
        assertFalse(palette.sameStack(plain, placing));
        assertFalse(palette.sameStack(plain, plain.toBuilder().blockDefinition(null).build()));
        // Cloudburst's equals does not look at the block
        assertEquals(plain, placing);
    }

    @Test
    void definitionsOfOtherPalettesAreMappedToThisOne() {
        Palette base = latest();
        PaletteLayer layer = PaletteLayer.builder("override")
            .item(CustomItem.builder("same:ruby").menuCategory(CreativeCategory.ITEMS).build())
            .overrideItem("minecraft:diamond_sword", item -> item.modifyComponents(MaxStackSize.of(1)))
            .build();
        Palette session = base.extend(layer);
        ItemData fromBase = base.encode(sword(1, null));
        ItemData fromSession = session.encode(sword(1, null));
        RegisteredItem baseSword = base.items().getDefinition("minecraft:diamond_sword");
        RegisteredItem sessionSword = session.items().getDefinition("minecraft:diamond_sword");
        assertNotSame(baseSword, sessionSword);
        assertNotSame(fromBase.getDefinition(), fromSession.getDefinition());
        // Not equal for Cloudburst (another definition), the same stack for the palette that knows both
        assertNotEquals(fromBase, fromSession);
        assertTrue(session.sameStack(fromBase, fromSession));
        assertTrue(session.sameStack(fromSession, fromBase));
        assertFalse(session.sameStack(fromBase, session.encode(sword(2, null))));

        // A definition nobody registered, but with the identity of an entry, is that entry; any other is not
        ItemData foreign = fromSession.toBuilder().definition(new SimpleItemDefinition(baseSword.getIdentifier(), baseSword.getRuntimeId(), false)).build();
        assertTrue(session.sameStack(foreign, fromSession));
        ItemData wrongName = fromSession.toBuilder().definition(new SimpleItemDefinition("minecraft:other", baseSword.getRuntimeId(), false)).build();
        assertFalse(session.sameStack(wrongName, fromSession));
        ItemData unknown = fromSession.toBuilder().definition(new SimpleItemDefinition("nobody:item", 31000, false)).build();
        assertFalse(session.sameStack(unknown, fromSession));
        assertTrue(session.sameStack(unknown, unknown.toBuilder().build()));
    }
}
