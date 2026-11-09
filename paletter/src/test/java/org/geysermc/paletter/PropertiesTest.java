package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.paletter.block.BlockNetworkIds;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.RegisteredBlock;
import org.geysermc.paletter.block.RegisteredBlockState;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.property.PropertyKind;
import org.geysermc.paletter.vanilla.BedrockBlockType;
import org.geysermc.paletter.vanilla.BedrockItemType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesTest {
    static final Paletter PALETTER = Paletter.bundled();

    private static CustomItem ruby() {
        return CustomItem.builder("server:ruby").creative(CreativePlacement.loose(CreativeCategory.ITEMS))
            .components(MaxStackSize.of(16)).build();
    }

    @Test
    void aVanillaItemsPropertiesAreVersioned() {
        // red_shrub only exists from 26.50 on: its components are in the store for those versions only
        BedrockItemType shrub = BedrockItemType.vanilla("minecraft:red_shrub");
        assertNotNull(shrub);
        DataVersion older = PALETTER.versions().getFirst();
        DataVersion newer = PALETTER.latest();
        assertNotEquals(older, newer);
        assertTrue(PALETTER.properties(shrub, older).isEmpty());
        Properties current = PALETTER.properties(shrub, newer);
        assertFalse(current.isEmpty());
        RegisteredItem item = PALETTER.vanilla(newer).items().item("minecraft:red_shrub");
        assertNotNull(item);
        assertEquals(current, item.properties());
        assertNull(PALETTER.vanilla(older).items().item("minecraft:red_shrub"));
    }

    @Test
    void paletterAndPaletteAgree() {
        DataVersion version = PALETTER.latest();
        var palette = PALETTER.vanilla(version);
        int checked = 0;
        for (RegisteredItem item : palette.items().definitions()) {
            assertEquals(PALETTER.properties(item.key(), version), item.properties(), item.getIdentifier());
            checked += item.properties().isEmpty() ? 0 : 1;
        }
        assertTrue(checked > 50);
        for (RegisteredBlock block : palette.blocks().blocks()) {
            assertEquals(PALETTER.properties(block.key(), version), block.properties(), block.identifier());
            if (block.properties().get(PropertyKind.WIRE, "permutations") == null) {
                assertSame(block.properties(), block.properties(block.states().getFirst()));
            }
        }
    }

    @Test
    void customItemsCarryTheirComponents() {
        Palette palette = PALETTER.vanilla(PALETTER.latest()).extend(PaletteLayer.builder("server").item(ruby()).build());
        RegisteredItem item = palette.items().item("server:ruby");
        assertNotNull(item);
        assertTrue(item.properties().get(PropertyKind.COMPONENT, "minecraft:max_stack_size") != null);
        assertEquals(Properties.EMPTY, PALETTER.properties(BedrockItemType.of("server:ruby"), PALETTER.latest()));
        assertTrue(item.properties().of(PropertyKind.BEHAVIOUR).isEmpty());
    }

    @Test
    void overridesEditCustomItemsOfEarlierLayers() {
        Palette base = PALETTER.vanilla(PALETTER.latest()).extend(PaletteLayer.builder("server").item(ruby()).build());
        RegisteredItem before = base.items().item("server:ruby");
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("server:ruby", item -> item.components(MaxStackSize.of(1))).build());
        RegisteredItem after = session.items().item("server:ruby");
        assertNotNull(after);
        assertNotEquals(before.properties(), after.properties());
        assertEquals((byte) 1, after.properties().component("minecraft:max_stack_size").getByte("value"));
        assertEquals((byte) 16, before.properties().component("minecraft:max_stack_size").getByte("value"));
    }

    @Test
    void overridesEditVanillaItemsTheSame() {
        Palette base = PALETTER.vanilla(PALETTER.latest());
        Palette session = base.extend(PaletteLayer.builder("override")
            .overrideItem("minecraft:cake", item -> item.components(MaxStackSize.of(3))).build());
        assertEquals((byte) 3, session.items().item("minecraft:cake").properties().component("minecraft:max_stack_size").getByte("value"));
        assertNotEquals(base.items().item("minecraft:cake").properties(), session.items().item("minecraft:cake").properties());
    }

    @Test
    void blockStatesAddThePermutationComponentsThatApply() {
        CustomBlock block = CustomBlock.builder("example:glow")
            .property(BlockProperty.bool("example:on"))
            .component(LightEmission.of(3))
            .permutation("q.block_state('example:on')", LightEmission.of(15))
            .build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), BlockNetworkIds.HASHED, PaletteLayer.builder("x").block(block).build());
        RegisteredBlock registered = palette.blocks().block("example:glow");
        assertNotNull(registered);
        assertNotNull(registered.properties().component("minecraft:light_emission"));
        assertEquals(Properties.EMPTY, PALETTER.properties(BedrockBlockType.of(block), PALETTER.latest()));
        for (RegisteredBlockState state : registered.states()) {
            NbtMap emission = registered.properties(state).component("minecraft:light_emission");
            assertNotNull(emission);
            boolean on = state.states().getByte("example:on") == 1;
            assertEquals(on, !emission.equals(registered.properties().component("minecraft:light_emission")), state.toString());
            assertFalse(on && registered.properties(state).equals(registered.properties()));
        }
    }
}
