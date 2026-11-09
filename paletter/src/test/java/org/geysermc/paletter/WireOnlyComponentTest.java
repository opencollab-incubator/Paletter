package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.creative.CreativeCategory;
import org.geysermc.paletter.creative.CreativePlacement;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.OnPlayerPlacing;
import org.geysermc.paletter.item.CustomItem;
import org.geysermc.paletter.item.RegisteredItem;
import org.geysermc.paletter.item.component.FrameCount;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.MaxStackSize;
import org.geysermc.paletter.item.component.MiningSpeed;
import org.geysermc.paletter.item.component.RenderOffsets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written wire-only item components ({@code render_offsets}, {@code mining_speed}, {@code frame_count}): the exact
 * NBT they write, and how they combine with generated components.
 */
@SuppressWarnings("deprecation")
class WireOnlyComponentTest {
    private static final Paletter PALETTER = Paletter.bundled();

    private static NbtMap components(PaletteLayer layer, DataVersion version, String identifier) {
        Palette palette = PALETTER.palette(version.protocolVersion(), layer);
        assertTrue(palette.report().warnings().isEmpty(), version + ": " + palette.report().warnings());
        return palette.items().getDefinition(identifier).getComponentData().getCompound("components");
    }

    private static NbtMap components(CustomItem item, DataVersion version) {
        return components(PaletteLayer.builder("wire-only").item(item).build(), version, item.identifier());
    }

    private static CustomItem.Builder base() {
        return CustomItem.builder("example:wand").component(Icon.of("wand"))
            .creative(CreativePlacement.loose(CreativeCategory.ITEMS));
    }

    private static NbtMap scale(float x, float y, float z) {
        return NbtMap.builder().putList("scale", NbtType.FLOAT, List.of(x, y, z)).build();
    }

    @Test
    void miningSpeedAndFrameCountReplaceTheDefaults() {
        for (DataVersion version : PALETTER.versions()) {
            NbtMap defaults = components(base().build(), version).getCompound("item_properties");
            assertEquals(1f, defaults.getFloat("mining_speed"));
            assertEquals(1, defaults.getInt("frame_count"));

            NbtMap properties = components(base().component(MiningSpeed.of(2.5f)).component(FrameCount.of(7)).build(), version)
                .getCompound("item_properties");
            assertEquals(2.5f, properties.getFloat("mining_speed"));
            assertEquals(7, properties.getInt("frame_count"));
            assertEquals(defaults.size(), properties.size(), "no key added or lost");
        }
    }

    @Test
    void renderOffsetsWriteTheShapeGeyserWrites() {
        // Geyser's converted-item default: scale only, main hand first person and the other three views
        RenderOffsets.Offset hand = RenderOffsets.Offset.scale(0.075f, 0.125f, 0.075f);
        RenderOffsets offsets = new RenderOffsets(
            new RenderOffsets.Hand(RenderOffsets.Offset.scale(0.03f, 0.03f, 0.03f), hand),
            new RenderOffsets.Hand(hand, hand));
        NbtMap expected = NbtMap.builder()
            .putCompound("main_hand", NbtMap.builder()
                .putCompound("first_person", scale(0.03f, 0.03f, 0.03f))
                .putCompound("third_person", scale(0.075f, 0.125f, 0.075f)).build())
            .putCompound("off_hand", NbtMap.builder()
                .putCompound("first_person", scale(0.075f, 0.125f, 0.075f))
                .putCompound("third_person", scale(0.075f, 0.125f, 0.075f)).build())
            .build();
        for (DataVersion version : PALETTER.versions()) {
            assertEquals(expected, components(base().component(offsets).build(), version).getCompound("minecraft:render_offsets"));
        }
    }

    @Test
    void renderOffsetPresetsAreABareStringNotAComponent() {
        for (DataVersion version : PALETTER.versions()) {
            NbtMap only = components(base().component(RenderOffsets.preset(RenderOffsets.Preset.CHESTPLATES)).build(), version);
            assertEquals("chestplates", only.getString("render_offsets"));
            assertFalse(only.containsKey("minecraft:render_offsets"));

            NbtMap both = components(base().component(new RenderOffsets(
                new RenderOffsets.Hand(RenderOffsets.Offset.scale(1, 1, 1), null), null, RenderOffsets.Preset.BOOTS)).build(), version);
            assertEquals("boots", both.getString("render_offsets"));
            assertTrue(both.getCompound("minecraft:render_offsets").containsKey("main_hand"));
        }
        assertEquals("helmets", RenderOffsets.Preset.HELMETS.value());
        assertNull(new RenderOffsets(null, null).preset());
    }

    @Test
    void renderOffsetsWritePositionRotationScaleAndLeaveOutEmptyParts() {
        RenderOffsets offsets = new RenderOffsets(
            new RenderOffsets.Hand(new RenderOffsets.Offset(new RenderOffsets.Vec3(1, 2, 3), new RenderOffsets.Vec3(4, 5, 6),
                new RenderOffsets.Vec3(7, 8, 9)), new RenderOffsets.Offset(null, null, null)),
            new RenderOffsets.Hand(null, null));
        NbtMap node = components(base().component(offsets).build(), PALETTER.latest()).getCompound("minecraft:render_offsets");
        NbtMap expected = NbtMap.builder().putCompound("main_hand", NbtMap.builder().putCompound("first_person", NbtMap.builder()
            .putList("position", NbtType.FLOAT, List.of(1f, 2f, 3f))
            .putList("rotation", NbtType.FLOAT, List.of(4f, 5f, 6f))
            .putList("scale", NbtType.FLOAT, List.of(7f, 8f, 9f)).build()).build()).build();
        assertEquals(expected, node);
        assertFalse(node.containsKey("off_hand"));

        NbtMap empty = components(base().component(new RenderOffsets(null, null)).build(), PALETTER.latest());
        assertTrue(empty.getCompound("minecraft:render_offsets").isEmpty());
        assertTrue(empty.containsKey("minecraft:render_offsets"));
    }

    @Test
    void notWrittenWithoutTheComponent() {
        NbtMap components = components(base().build(), PALETTER.latest());
        assertFalse(components.containsKey("minecraft:render_offsets"));
    }

    @Test
    void combineWithGeneratedComponentsWithoutDuplicatesOrConflicts() {
        CustomItem plain = base().component(MaxStackSize.of(16)).build();
        CustomItem both = base().component(MaxStackSize.of(16)).component(MiningSpeed.of(3f)).component(FrameCount.of(4))
            .component(new RenderOffsets(new RenderOffsets.Hand(RenderOffsets.Offset.scale(1, 1, 1), null), null)).build();
        NbtMap before = components(plain, PALETTER.latest());
        NbtMap after = components(both, PALETTER.latest());
        // only the three wire-only keys differ; the generated component's keys are untouched
        for (String key : before.keySet()) {
            if (!key.equals("item_properties")) {
                assertEquals(before.get(key), after.get(key), key);
            }
        }
        NbtMap b = before.getCompound("item_properties");
        NbtMap a = after.getCompound("item_properties");
        for (String key : b.keySet()) {
            if (!key.equals("mining_speed") && !key.equals("frame_count")) {
                assertEquals(b.get(key), a.get(key), key);
            }
        }
        assertEquals(1 + before.size(), after.size(), "render_offsets is the only added component key");
        assertEquals((byte) 16, after.getCompound("minecraft:max_stack_size").getByte("value"));

        // order does not matter
        CustomItem reversed = base().component(new RenderOffsets(new RenderOffsets.Hand(RenderOffsets.Offset.scale(1, 1, 1), null), null))
            .component(FrameCount.of(4)).component(MiningSpeed.of(3f)).component(MaxStackSize.of(16)).build();
        assertEquals(after, components(reversed, PALETTER.latest()));
    }

    @Test
    void theyAreOnePerNameLikeGeneratedComponents() {
        assertThrows(IllegalArgumentException.class, () -> new CustomItem("example:x", List.of(MiningSpeed.of(1), MiningSpeed.of(2)), null, null));
        CustomItem replaced = CustomItem.builder("example:x").component(FrameCount.of(2)).component(FrameCount.of(5)).build();
        assertEquals(5, replaced.component(FrameCount.class).value());
        assertEquals(1, replaced.components().size());
        assertNull(replaced.component(MiningSpeed.class));
    }

    @Test
    void overridesOfVanillaItemsWriteThem() {
        Palette palette = PALETTER.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("fixes")
            .overrideItem("minecraft:bow", item -> item.components(FrameCount.of(10), MiningSpeed.of(0.5f))).build());
        NbtMap properties = palette.items().getDefinition("minecraft:bow").getComponentData().getCompound("components")
            .getCompound("item_properties");
        assertEquals(10, properties.getInt("frame_count"));
        assertEquals(0.5f, properties.getFloat("mining_speed"));
    }

    @Test
    void valuesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> FrameCount.of(0));
        assertThrows(IllegalArgumentException.class, () -> MiningSpeed.of(Float.NaN));
        assertEquals("item_properties.frame_count", FrameCount.of(1).name());
        assertEquals("minecraft:render_offsets", new RenderOffsets(null, null).name());
    }

    @Test
    void onPlayerPlacingIsSentAsTheTriggerType() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion(), PaletteLayer.builder("placing")
                .block(CustomBlock.builder("example:slab").component(OnPlayerPlacing.of("geyser:place_event")).build()).build());
            assertTrue(palette.report().warnings().isEmpty(), version + ": " + palette.report().warnings());
            NbtMap components = palette.blocks().blockProperties().stream().filter(b -> b.getName().equals("example:slab")).findFirst()
                .orElseThrow().getProperties().getCompound("components");
            assertEquals(NbtMap.builder().putString("triggerType", "geyser:place_event").build(),
                components.getCompound("minecraft:on_player_placing"), version.toString());
        }
        CustomBlock replaced = CustomBlock.builder("example:slab").component(OnPlayerPlacing.of("a:b")).component(OnPlayerPlacing.of("c:d")).build();
        assertEquals("c:d", replaced.component(OnPlayerPlacing.class).triggerType());
        assertEquals("minecraft:on_player_placing", OnPlayerPlacing.of("a:b").name());
    }

    @Test
    void overridesOfLegacyItemsKeepNameAndId() {
        // Legacy items (cake) have no component data of their own: the override starts from {name, id, components}
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = PALETTER.palette(version.protocolVersion(), PaletteLayer.builder("fixes")
                .overrideItem("minecraft:cake", item -> item.components(MiningSpeed.of(2f))).build());
            RegisteredItem cake = palette.items().getDefinition("minecraft:cake");
            NbtMap data = cake.getComponentData();
            assertEquals("minecraft:cake", data.getString("name"), version.toString());
            assertEquals(cake.getRuntimeId(), data.getInt("id"), version.toString());
            assertEquals(2f, data.getCompound("components").getCompound("item_properties").getFloat("mining_speed"));
            assertTrue(cake.isComponentBased());
        }
    }
}
