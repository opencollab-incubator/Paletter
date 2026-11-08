package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Connection;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.block.component.PlacementDirection;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.PaletteItem;
import org.geysermc.paletter.item.component.DisplayName;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.item.component.Tags;
import org.geysermc.paletter.item.component.UseModifiers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom items and blocks defined with the generated components, compiled into palettes: what the definition core adds
 * (item properties, menu category, block envelope, trait states, permutations, overrides) and that every bundled version
 * compiles them without report warnings (the BDS 1.26.63.1 mapping is the only window, so it holds for all of them).
 */
class CustomContentTest {
    private static final Paletter PALETTER = Paletter.bundled();

    static CustomItem ruby() {
        return CustomItem.builder("example:ruby")
            .component(Icon.of("ruby"))
            .component(DisplayName.builder().value("item.ruby.name").build())
            .component(MaxStackSize.of(16))
            .component(Durability.builder().maxDurability(250).build())
            .component(Tags.builder().tags("minecraft:gems").build())
            .component(UseModifiers.builder().useDuration(1.6f).build())
            .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minerals"))
            .build();
    }

    static CustomBlock lamp() {
        return CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
            .component(Geometry.of("minecraft:geometry.full_block"))
            .component(MaterialInstances.builder().all(MaterialInstances.Instance.builder().texture("lamp").build()).build())
            .permutation("q.block_state('example:lit')", LightEmission.of(15))
            .tags("minecraft:stone")
            .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
            .build();
    }

    @Test
    void everyBundledVersionCompilesWithoutWarnings() {
        PaletteLayer layer = PaletteLayer.builder("content").item(ruby()).block(lamp()).build();
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion(), layer);
            assertTrue(palette.report().warnings().isEmpty(), version + ": " + palette.report().warnings());
        }
    }

    @Test
    void itemsGetTheCustomItemPropertiesAndTheirComponents() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").item(ruby()).build());
        PaletteItem ruby = palette.items().getDefinition("example:ruby");
        NbtMap components = ruby.getComponentData().getCompound("components");
        NbtMap properties = components.getCompound("item_properties");
        assertEquals(16, properties.getInt("max_stack_size"));
        assertEquals(4, properties.getInt("creative_category"));
        assertEquals("minecraft:itemGroup.name.minerals", properties.getString("creative_group"));
        assertEquals((byte) 2, properties.getByte("hidden_in_commands"));
        assertEquals(1f, properties.getFloat("mining_speed"));
        assertEquals(32, properties.getInt("use_duration"), "1.6 s in ticks");
        assertEquals("ruby", properties.getCompound("minecraft:icon").getCompound("textures").getString("default"));
        assertEquals(List.of("minecraft:gems"), components.getList("item_tags", NbtType.STRING));
        assertEquals(100, components.getCompound("minecraft:durability").getCompound("damage_chance").getInt("min"));
        assertEquals("if_first", components.getCompound("minecraft:use_modifiers").getString("start_using"),
            "unset: what BDS sends, not the docs default");
        assertTrue(palette.items().tags().get("minecraft:gems").contains("example:ruby"));
    }

    @Test
    void blocksGetTheEnvelopeTraitStatesAndPermutations() {
        CustomBlock lamp = lamp();
        assertEquals(8, lamp.states().size(), "2 lit values x 4 cardinal directions of the trait");
        assertEquals("south", lamp.defaultState().getCompound("states").getString("minecraft:cardinal_direction"));
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp).build());
        assertEquals(8, palette.blocks().states("example:lamp").size());
        BlockPropertyData data = palette.blocks().blockProperties().stream().filter(b -> b.getName().equals("example:lamp")).findFirst().orElseThrow();
        NbtMap p = data.getProperties();
        assertEquals("construction", p.getCompound("menu_category").getString("category"));
        assertEquals(13, p.getInt("molangVersion"));
        assertEquals("dirt", p.getCompound("vanilla_block_data").getString("material"));
        assertEquals(List.of("minecraft:stone"), p.getList("blockTags", NbtType.STRING));
        NbtMap trait = p.getList("traits", NbtType.COMPOUND).getFirst();
        assertEquals("minecraft:placement_direction", trait.getString("name"));
        assertEquals((byte) 1, trait.getCompound("enabled_states").getByte("cardinal_direction"));
        assertEquals(1, p.getList("permutations", NbtType.COMPOUND).size());
        assertEquals(List.of("example:lit"), p.getList("properties", NbtType.COMPOUND).stream().map(e -> e.getString("name")).toList(),
            "trait states are not listed in properties");
    }

    @Test
    void overridesWriteOverTheOriginalComponentData() {
        Palette vanilla = PALETTER.vanilla(PALETTER.latest());
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("fixes")
            .overrideItem("minecraft:apple", item -> item.components(MaxStackSize.of(8))).build());
        NbtMap before = vanilla.items().getDefinition("minecraft:apple").getComponentData().getCompound("components");
        NbtMap after = palette.items().getDefinition("minecraft:apple").getComponentData().getCompound("components");
        assertEquals(8, after.getCompound("item_properties").getInt("max_stack_size"));
        assertEquals((byte) 8, after.getCompound("minecraft:max_stack_size").getByte("value"));
        for (String key : before.keySet()) {
            assertTrue(after.containsKey(key), key);
        }
        assertEquals(before.getCompound("minecraft:food"), after.getCompound("minecraft:food"));
    }

    @Test
    void componentsAreOnePerName() {
        assertThrows(IllegalArgumentException.class, () -> new CustomItem("example:x", List.of(MaxStackSize.of(1), MaxStackSize.of(2)), null, null));
        CustomItem replaced = CustomItem.builder("example:x").component(MaxStackSize.of(1)).component(MaxStackSize.of(2)).build();
        assertEquals(2, replaced.component(MaxStackSize.class).value());
        assertFalse(replaced.components().size() > 1);
    }

    @Test
    void componentsMayReferToItemsTheLayerRegistersLater() {
        CustomItem stew = CustomItem.builder("example:stew")
            .component(Icon.of("stew"))
            .component(Food.builder().usingConvertsTo("example:bowl").build())
            .build();
        CustomItem bowl = CustomItem.builder("example:bowl").component(Icon.of("bowl")).build();
        for (PaletteLayer layer : List.of(PaletteLayer.builder("stew-first").item(stew).item(bowl).build(),
            PaletteLayer.builder("bowl-first").item(bowl).item(stew).build())) {
            Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), layer);
            NbtMap food = palette.items().getDefinition("example:stew").getComponentData().getCompound("components")
                .getCompound("minecraft:food");
            assertEquals("example:bowl", food.getCompound("using_converts_to").getString("name"), layer.id());
        }
    }

    @Test
    void defaultStatesMayNameTraitStates() {
        CustomBlock block = CustomBlock.builder("example:facing")
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
            .defaultState("minecraft:cardinal_direction", "east")
            .build();
        assertEquals("east", block.defaultState().getCompound("states").getString("minecraft:cardinal_direction"));
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("example:facing")
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
            .defaultState("minecraft:cardinal_direction", "up").build());
    }

    @Test
    void blocksWithTooManyStateCombinationsAreRejected() {
        assertEquals(1 << 24, CustomBlock.builder("example:big")
            .property(BlockProperty.range("example:a", 0, 4095)).property(BlockProperty.range("example:b", 0, 4095))
            .build().stateCount());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("example:huge")
            .property(BlockProperty.range("example:a", 0, 4095)).property(BlockProperty.range("example:b", 0, 4096))
            .build());
        // The product would overflow an int
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("example:overflow")
            .property(BlockProperty.range("example:a", 0, 4095)).property(BlockProperty.range("example:b", 0, 4095))
            .property(BlockProperty.range("example:c", 0, 4095)).build());
    }

    @Test
    void blockItemTakesOverTheItemOfABlockOfTheSameLayer() {
        CustomBlock lamp = lamp();
        PaletteLayer layer = PaletteLayer.builder("ordered").item(ruby())
            .blockItem(lamp.identifier(), lamp.item(), null).block(lamp).build();
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), layer);
        PaletteItem ruby = palette.items().getDefinition("example:ruby");
        PaletteItem item = palette.items().getDefinition(lamp.identifier());
        assertTrue(item.getRuntimeId() > ruby.getRuntimeId());
        // The block's own placement still applies
        assertTrue(palette.creativeLayout().stacks().anyMatch(s -> s.identifier().equals(lamp.identifier())));
    }

    @Test
    void laterLayersDoNotRepeatOrReattributeEarlierBlockWarnings() {
        // A connection trait has no wire mapping: compiling it warns
        CustomBlock fence = CustomBlock.builder("example:fence")
            .trait(Connection.builder().enabledStates(Connection.EnabledStates.CARDINAL_CONNECTIONS).build())
            .build();
        Palette first = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("first").block(fence).build());
        assertFalse(first.report().warnings().isEmpty());
        Palette second = first.extend(PaletteLayer.builder("second").block(lamp()).build());
        assertEquals(first.report().warnings(), second.report().warnings());
        assertTrue(second.report().warnings().stream().noneMatch(w -> w.source().equals("second")));
    }
}
