package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.RegisteredBlock;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.block.component.BlockTags;
import dev.opencollab.paletter.block.component.LightEmission;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.property.Properties;
import dev.opencollab.paletter.property.PropertyKind;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesTest {
    static final Paletter PALETTER = Paletter.bundled();

    private static CustomItem ruby() {
        return CustomItem.builder("server:ruby").menuCategory(CreativeCategory.ITEMS)
            .components(MaxStackSize.of(16)).build();
    }

    @Test
    void blockPropertiesCheckTheirValues() {
        assertEquals(List.of(true, false), BlockProperty.bools("example:b", List.of(true, false)).values());
        assertEquals(List.of(3, 1), BlockProperty.ints("example:i", List.of(3, 1)).values());
        assertEquals(BlockProperty.strings("example:s", "a", "b"), BlockProperty.strings("example:s", List.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> BlockProperty.ints("example:i", List.of()));
        assertThrows(IllegalArgumentException.class, () -> BlockProperty.ints("example:i", 1, 1));
        assertThrows(NullPointerException.class, () -> BlockProperty.strings("example:s", java.util.Arrays.asList("a", null)));
    }

    @Test
    void permutationsAreValuesAndRefuseTags() {
        CustomBlock.Permutation lit = CustomBlock.Permutation.of("q.block_state('example:on')", LightEmission.of(15), MaxLight.DAMPENING);
        assertEquals(lit, CustomBlock.Permutation.of("q.block_state('example:on')", List.of(MaxLight.DAMPENING, LightEmission.of(15))),
            "components are one per name: their order does not matter");
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.Permutation.of("q.block_state('example:on')",
            BlockTags.builder().value(List.of("example:tag")).build()));
    }

    private static final class MaxLight {
        static final dev.opencollab.paletter.block.component.LightDampening DAMPENING = dev.opencollab.paletter.block.component.LightDampening.of(15);
    }

    @Test
    void aVanillaItemsPropertiesAreVersioned() {
        // red_shrub only exists from 26.50 on: its components are in the store for those versions only
        BedrockItemType shrub = PALETTER.vanilla(PALETTER.latest()).itemType("minecraft:red_shrub", Lookup.EXACT);
        assertNotNull(shrub);
        DataVersion older = PALETTER.versions().getFirst();
        DataVersion newer = PALETTER.latest();
        assertNotEquals(older, newer);
        assertTrue(PALETTER.properties(shrub, older).isEmpty());
        Properties current = PALETTER.properties(shrub, newer);
        assertFalse(current.isEmpty());
        RegisteredItem item = PALETTER.vanilla(newer).items().getDefinition("minecraft:red_shrub");
        assertNotNull(item);
        assertEquals(current, item.properties());
        assertNull(PALETTER.vanilla(older).items().getDefinition("minecraft:red_shrub"));
    }

    @Test
    void paletterAndPaletteAgree() {
        DataVersion version = PALETTER.latest();
        var palette = PALETTER.vanilla(version);
        int checked = 0;
        for (RegisteredItem item : palette.items().definitions()) {
            assertEquals(PALETTER.properties(item.type(), version), item.properties(), item.getIdentifier());
            checked += item.properties().isEmpty() ? 0 : 1;
        }
        assertTrue(checked > 50);
        for (RegisteredBlock block : palette.blocks().blocks()) {
            assertEquals(PALETTER.properties(block.type(), version), block.properties(), block.identifier());
            if (block.properties().get(PropertyKind.WIRE, "permutations") == null) {
                assertSame(block.properties(), block.properties(block.states().getFirst()));
            }
        }
    }

    @Test
    void customItemsCarryTheirComponents() {
        Palette palette = PALETTER.vanilla(PALETTER.latest()).extend(PaletteLayer.builder("server").item(ruby()).build());
        RegisteredItem item = palette.items().getDefinition("server:ruby");
        assertNotNull(item);
        assertTrue(item.properties().get(PropertyKind.COMPONENT, "minecraft:max_stack_size") != null);
        assertEquals(Properties.EMPTY, PALETTER.properties(BedrockItemType.fromIdentifier("server:ruby"), PALETTER.latest()));
    }

    @Test
    void overridesEditCustomItemsOfEarlierLayers() {
        Palette base = PALETTER.vanilla(PALETTER.latest()).extend(PaletteLayer.builder("server").item(ruby()).build());
        RegisteredItem before = base.items().getDefinition("server:ruby");
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("server:ruby", item -> item.modifyComponents(MaxStackSize.of(1))).build());
        RegisteredItem after = session.items().getDefinition("server:ruby");
        assertNotNull(after);
        assertNotEquals(before.properties(), after.properties());
        assertEquals((byte) 1, after.properties().component("minecraft:max_stack_size").getByte("value"));
        assertEquals((byte) 16, before.properties().component("minecraft:max_stack_size").getByte("value"));
    }

    @Test
    void overridesEditVanillaItemsTheSame() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.modifyComponents(MaxStackSize.of(3))).build());
        assertEquals((byte) 3, session.items().getDefinition("minecraft:cake").properties().component("minecraft:max_stack_size").getByte("value"));
        assertNotEquals(base.items().getDefinition("minecraft:cake").properties(), session.items().getDefinition("minecraft:cake").properties());
    }

    @Test
    void blockStatesAddThePermutationComponentsThatApply() {
        CustomBlock block = CustomBlock.builder("example:glow")
            .property(BlockProperty.bool("example:on"))
            .component(LightEmission.of(3))
            .permutation("q.block_state('example:on')", LightEmission.of(15))
            .build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("x").block(block).build());
        RegisteredBlock registered = palette.blocks().block("example:glow");
        assertNotNull(registered);
        assertNotNull(registered.properties().component("minecraft:light_emission"));
        assertEquals(Properties.EMPTY, PALETTER.properties(block.type(), PALETTER.latest()));
        for (RegisteredBlockState state : registered.states()) {
            NbtMap emission = registered.properties(state).component("minecraft:light_emission");
            assertNotNull(emission);
            boolean on = state.states().getByte("example:on") == 1;
            assertEquals(on, !emission.equals(registered.properties().component("minecraft:light_emission")), state.toString());
            assertFalse(on && registered.properties(state).equals(registered.properties()));
        }
    }
}
