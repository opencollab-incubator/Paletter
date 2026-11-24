package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.block.BlockHashes;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.EmbeddedVisual;
import dev.opencollab.paletter.block.component.Geometry;
import dev.opencollab.paletter.block.component.ItemVisual;
import dev.opencollab.paletter.block.component.LightEmission;
import dev.opencollab.paletter.block.component.MaterialInstances;
import dev.opencollab.paletter.block.component.PlacementDirection;
import dev.opencollab.paletter.block.component.DestroyTime;
import dev.opencollab.paletter.block.component.UvLock;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.ItemDisplayName;
import dev.opencollab.paletter.item.component.Durability;
import dev.opencollab.paletter.item.component.Food;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.item.component.Tags;
import dev.opencollab.paletter.item.component.UseModifiers;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
            .component(ItemDisplayName.builder().value("item.ruby.name").build())
            .component(MaxStackSize.of(16))
            .component(Durability.builder().maxDurability(250).build())
            .component(Tags.builder().tags("minecraft:gems").build())
            .component(UseModifiers.builder().useDuration(1.6f).build())
            .menuCategory(CreativeCategory.ITEMS, "itemGroup.name.minerals")
            .build();
    }

    static CustomBlock lamp() {
        return CustomBlock.builder("example:lamp")
            .property(BlockProperty.bool("example:lit"))
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
            .component(Geometry.of("minecraft:geometry.full_block"))
            .component(MaterialInstances.builder().all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("lamp").build())).build())
            .permutation("q.block_state('example:lit')", LightEmission.of(15))
            .tags("minecraft:stone")
            .menuCategory(CreativeCategory.CONSTRUCTION)
            .build();
    }

    @Test
    void everyBundledVersionCompilesWithoutWarnings() {
        PaletteLayer layer = PaletteLayer.builder("content").item(ruby()).block(lamp()).build();
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = Palettes.palette(version.protocolVersion(), layer);
            assertTrue(palette.report().warnings().isEmpty(), version + ": " + palette.report().warnings());
        }
    }

    @Test
    void itemsGetTheCustomItemPropertiesAndTheirComponents() {
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").item(ruby()).build());
        RegisteredItem ruby = palette.items().getDefinition("example:ruby");
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
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("content").block(lamp).build());
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
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("fixes")
            .overrideItem("minecraft:apple", item -> item.modifyComponents(MaxStackSize.of(8))).build());
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
            Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
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

    /**
     * BDS 1.26.36.1 lists minecraft:sixteen_way_rotation with duplicate values (its Script API dump), so the states a 26.30
     * client adds for it are not well defined: the 26.30 palette leaves the value out of the trait and the states, with a
     * report warning naming the BDS bug, and sends the block's states without it. 26.40 and newer keep it, 0 to 15.
     */
    @Test
    void sixteenWayRotationIsLeftOutOf26_30WithAReport() {
        CustomBlock statue = CustomBlock.builder("example:statue")
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION,
                PlacementDirection.EnabledStates.SIXTEEN_WAY_ROTATION).build())
            .defaultState("minecraft:sixteen_way_rotation", 4)
            .menuCategory(CreativeCategory.CONSTRUCTION)
            .build();
        assertEquals(4 * 16, statue.states().size(), "the type has the newest states");
        var turned = statue.type().stateOf(java.util.Map.of("minecraft:cardinal_direction", "east", "minecraft:sixteen_way_rotation", 5));
        PaletteLayer layer = PaletteLayer.builder("content").block(statue).build();
        int checked26_30 = 0;
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = Palettes.palette(version.protocolVersion(), layer);
            NbtMap trait = palette.blocks().blockProperties().stream().filter(b -> b.getName().equals("example:statue")).findFirst()
                .orElseThrow().getProperties().getList("traits", NbtType.COMPOUND).getFirst();
            NbtMap sent = palette.blockDefinition(turned).getState().getCompound("states");
            List<String> warnings = palette.report().warnings().stream().map(PaletteReport.Entry::message).toList();
            if (version.protocolVersion() < dev.opencollab.paletter.internal.component.ComponentIndex.V26_40) {
                checked26_30++;
                assertEquals((byte) 0, trait.getCompound("enabled_states").getByte("sixteen_way_rotation"), version.toString());
                assertEquals((byte) 1, trait.getCompound("enabled_states").getByte("cardinal_direction"), version.toString());
                assertEquals(4, palette.blocks().states("example:statue").size(), version.toString());
                assertEquals(NbtMap.builder().putString("minecraft:cardinal_direction", "east").build(), sent, version.toString());
                assertEquals(1, warnings.size(), warnings.toString());
                assertTrue(warnings.getFirst().contains("enabled_states = minecraft:sixteen_way_rotation")
                    && warnings.getFirst().contains("BDS 1.26.36.1") && warnings.getFirst().contains("left out"), warnings.getFirst());
                assertFalse(palette.blocks().block("example:statue").defaultState().getState().getCompound("states")
                    .containsKey("minecraft:sixteen_way_rotation"));
            } else {
                assertEquals((byte) 1, trait.getCompound("enabled_states").getByte("sixteen_way_rotation"), version.toString());
                assertEquals(64, palette.blocks().states("example:statue").size(), version.toString());
                assertEquals(5, sent.getInt("minecraft:sixteen_way_rotation"), version.toString());
                assertTrue(warnings.isEmpty(), version + ": " + warnings);
                assertEquals(4, palette.blocks().block("example:statue").defaultState().getState().getCompound("states")
                    .getInt("minecraft:sixteen_way_rotation"));
            }
        }
        assertEquals(1, checked26_30, "26.30 is bundled");
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
    void moreThan64UvLockBonesAreLeftOutByEachPalette() {
        // BDS 1.26.63.1 accepts 64 bones and rejects the block above, counting duplicates: a palette's limit, not build()'s
        Geometry accepted = Geometry.builder().identifier("geometry.lamp").uvLock(UvLock.bones(Collections.nCopies(64, "lid"))).build();
        Geometry rejected = Geometry.builder().identifier("geometry.lamp").uvLock(UvLock.bones(Collections.nCopies(65, "lid"))).build();
        MaterialInstances materials = MaterialInstances.builder().build();
        ItemVisual.VisualGeometry itemGeometry = ItemVisual.VisualGeometry.builder().identifier("geometry.lamp").uvLock(Collections.nCopies(65, "lid")).build();
        CustomBlock fine = CustomBlock.builder("example:fine").component(accepted).build();
        CustomBlock lamp = CustomBlock.builder("example:lamp").component(rejected)
            .property(BlockProperty.bool("example:lit")).permutation("q.block_state('example:lit')", rejected)
            .component(ItemVisual.builder().geometry(itemGeometry).materialInstances(materials).build())
            .build();
        CustomBlock embedded = CustomBlock.builder("example:embedded").component(EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.VisualGeometry.builder().identifier("geometry.lamp").uvLock(Collections.nCopies(65, "lid")).build())
            .materialInstances(materials).build()).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(),
            PaletteLayer.builder("bones").block(fine).block(lamp).block(embedded).build());
        List<String> warnings = palette.report().warnings().stream().map(e -> e.message()).filter(m -> m.contains("uv_lock")).toList();
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().noneMatch(m -> m.contains("example:fine")), warnings.toString());
        NbtMap sent = palette.blocks().blockProperties().stream().filter(p -> p.getName().equals("example:lamp")).findFirst().orElseThrow()
            .getProperties().getCompound("components").getCompound("minecraft:geometry");
        assertEquals((byte) 0, sent.getByte("uv_lock"), "left out: sent as unset");
    }

    @Test
    void theItemOfABlockIsNumberedByItsBlock() {
        CustomBlock lamp = lamp();
        PaletteLayer layer = PaletteLayer.builder("ordered").item(ruby()).block(lamp).build();
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), layer);
        RegisteredItem ruby = palette.items().getDefinition("example:ruby");
        RegisteredItem item = palette.items().getDefinition(lamp.identifier());
        // The plain block item is numbered by its block, not after the custom items
        assertEquals(255 - palette.blocks().customBlockId(lamp.identifier()), item.getRuntimeId());
        assertTrue(item.getRuntimeId() < ruby.getRuntimeId());
        // The block's own placement applies
        assertTrue(palette.creativeLayout().stacks().anyMatch(s -> s.type().identifier().equals(lamp.identifier())));
    }

    @Test
    void laterLayersDoNotRepeatOrReattributeEarlierBlockWarnings() {
        // A component BDS rejects is not sent: compiling it warns
        @SuppressWarnings("deprecation")
        CustomBlock fence = CustomBlock.builder("example:fence")
            .component(DestroyTime.of(true))
            .build();
        Palette first = Paletter.builder().layer(PaletteLayer.builder("first").block(fence).build()).build()
            .palette(PALETTER.latest().protocolVersion());
        assertFalse(first.report().warnings().isEmpty());
        Palette second = first.extend(PaletteLayer.builder("second").block(lamp()).build());
        assertEquals(first.report().warnings(), second.report().warnings());
        assertTrue(second.report().warnings().stream().noneMatch(w -> w.source().equals("second")));
    }

    @Test
    void tagsAreTheTagsComponentAndCopiesKeepTheDefinition() {
        CustomBlock block = CustomBlock.builder("example:tagged").tag("example:a")
            .component(dev.opencollab.paletter.block.component.BlockTags.builder().value("example:b").build())
            .tags("example:c", "example:b").component(LightEmission.of(3)).build();
        assertEquals(List.of("example:b", "example:c"), block.tags(), "the component replaced the earlier tags; tag() merges");
        assertEquals(block, block.toBuilder().build());
        assertEquals(block, CustomBlock.builder("example:tagged").component(LightEmission.of(3))
            .tags(List.of("example:b", "example:c")).build(), "components in any order");
        assertFalse(block.toBuilder().tag("example:d").build().equals(block));

        CustomItem item = CustomItem.builder("example:tagged").tag("minecraft:is_food").component(Icon.of("x")).tag("example:x").build();
        assertEquals(List.of("minecraft:is_food", "example:x"), item.tags());
        assertEquals(item, item.toBuilder().build());
        assertEquals(item, CustomItem.builder("example:tagged").component(Icon.of("x")).tags("minecraft:is_food", "example:x").build());
    }

    @Test
    void flattenedFormsAndPermutationStatesAreChecked() {
        dev.opencollab.paletter.block.component.CollisionBox.Entry box = dev.opencollab.paletter.block.component.CollisionBox.Entry.builder().build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> dev.opencollab.paletter.block.component.CollisionBox.builder().enabled(true).boxes(box).build());
        assertTrue(e.getMessage().contains("CollisionBox.enabled"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.builder("example:p")
            .property(BlockProperty.bool("example:on")).permutation("q.block_state('example:off')", LightEmission.of(3)).build());
        CustomBlock.builder("example:p").property(BlockProperty.bool("example:on"))
            .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
            .permutation("q.block_state('example:on') && query.block_state('minecraft:cardinal_direction') == 'north'", LightEmission.of(3))
            .build();
    }

    @Test
    void thePropertyListFollowsTheBlockIds() {
        // Upper case compares '_' after letters: b_x has the lower block id, as BDS numbers the files
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("order")
            .block(CustomBlock.builder("example:block_x").build()).block(CustomBlock.builder("example:b_x").build()).build());
        List<String> names = palette.blocks().blockProperties().stream().map(BlockPropertyData::getName)
            .filter(name -> name.startsWith("example:")).toList();
        assertEquals(List.of("example:b_x", "example:block_x"), names);
        assertTrue(palette.blocks().customBlockId("example:b_x") < palette.blocks().customBlockId("example:block_x"));
    }

    @Test
    void aComponentWithOneValueHasAFactoryForIt() {
        assertEquals("Ruby", ItemDisplayName.of("Ruby").value());
        assertEquals(250, Durability.of(250).maxDurability());
        assertEquals(Durability.builder().maxDurability(250).build(), Durability.of(250));
    }
}
