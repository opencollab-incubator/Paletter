package dev.opencollab.paletter;

import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.Wearable;
import dev.opencollab.paletter.vanilla.BedrockBlocks;
import dev.opencollab.paletter.vanilla.BedrockStates;
import dev.opencollab.paletter.vanilla.state.CardinalDirection;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Bedrock defines is open (docs/api.md, "Evolution"): a documented string value is an open value class that takes a
 * value Paletter does not know, and a block state's value can be read without its enum.
 */
class OpenValuesTest {
    @Test
    void knownValuesAreTheirConstants() {
        assertSame(Wearable.Slot.SLOT_ARMOR_HEAD, Wearable.Slot.of("slot.armor.head"));
        assertTrue(Wearable.Slot.SLOT_ARMOR_HEAD.isKnown());
        assertTrue(Wearable.Slot.known().contains(Wearable.Slot.SLOT_WEAPON_OFFHAND));
        assertEquals("slot.armor.head", Wearable.Slot.SLOT_ARMOR_HEAD.value());
    }

    @Test
    void anUnknownValueIsAValueByItsString() {
        Wearable.Slot tail = Wearable.Slot.of("slot.armor.tail");
        assertFalse(tail.isKnown());
        assertEquals(tail, Wearable.Slot.of("slot.armor.tail"));
        assertNotSame(tail, Wearable.Slot.of("slot.armor.tail"));
        assertEquals(tail.hashCode(), Wearable.Slot.of("slot.armor.tail").hashCode());
        assertFalse(Wearable.Slot.known().contains(tail));
    }

    @Test
    void anUnknownValueIsSentAndWhatItDecidesIsLeftOutWithAWarning() {
        CustomItem item = CustomItem.builder("example:tail").component(Icon.of("tail"))
            .component(Wearable.builder().slot(Wearable.Slot.of("slot.armor.tail")).build())
            .menuCategory(CreativeCategory.EQUIPMENT).build();
        Palette palette = Palettes.palette(Paletter.bundled().latest().protocolVersion(), PaletteLayer.builder("open").item(item).build());
        NbtMap components = palette.items().getDefinition("example:tail").getComponentData().getCompound("components");
        assertEquals("slot.armor.tail", components.getCompound("minecraft:wearable").getString("slot"));
        assertTrue(palette.report().warnings().stream().anyMatch(w -> w.toString().contains("slot.armor.tail")), palette.report().warnings().toString());
    }

    @Test
    void rawReadsAStateWithoutItsEnumAndNeverThrows() {
        var door = BedrockBlocks.WOODEN_DOOR.state().with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH).build();
        assertEquals("north", door.raw(BedrockStates.CARDINAL_DIRECTION));
        assertEquals(false, door.raw(BedrockStates.OPEN_BIT));
        assertNull(BedrockBlocks.STONE.defaultState().raw(BedrockStates.CARDINAL_DIRECTION), "a state the block does not have");
    }
}
