package dev.opencollab.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import dev.opencollab.paletter.creative.CreativeCategory;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.block.CustomBlock;
import dev.opencollab.paletter.block.component.OnPlayerPlacing;
import dev.opencollab.paletter.block.component.ScriptHooks;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.component.FrameCount;
import dev.opencollab.paletter.item.component.Icon;
import dev.opencollab.paletter.item.component.MaxStackSize;
import dev.opencollab.paletter.item.component.MiningSpeed;
import dev.opencollab.paletter.item.component.RenderOffsets;
import dev.opencollab.paletter.item.component.UseOnHook;
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
        Palette palette = Palettes.palette(version.protocolVersion(), layer);
        assertTrue(palette.report().warnings().isEmpty(), version + ": " + palette.report().warnings());
        return palette.items().getDefinition(identifier).getComponentData().getCompound("components");
    }

    private static NbtMap components(CustomItem item, DataVersion version) {
        return components(PaletteLayer.builder("wire-only").item(item).build(), version, item.identifier());
    }

    private static CustomItem.Builder base() {
        return CustomItem.builder("example:wand").component(Icon.of("wand"))
            .menuCategory(CreativeCategory.ITEMS);
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
        RenderOffsets offsets = RenderOffsets.builder()
            .mainHand(RenderOffsets.Hand.builder().firstPerson(RenderOffsets.Offset.scale(0.03f, 0.03f, 0.03f)).thirdPerson(hand).build())
            .offHand(RenderOffsets.Hand.builder().firstPerson(hand).thirdPerson(hand).build()).build();
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

            NbtMap both = components(base().component(RenderOffsets.builder().mainHand(scaledFirstPerson())
                .preset(RenderOffsets.Preset.BOOTS).build()).build(), version);
            assertEquals("boots", both.getString("render_offsets"));
            assertTrue(both.getCompound("minecraft:render_offsets").containsKey("main_hand"));
        }
        assertEquals("helmets", RenderOffsets.Preset.HELMETS.value());
        assertNull(RenderOffsets.builder().build().preset());
    }

    /** A hand with a first person view that scales by 1. */
    private static RenderOffsets.Hand scaledFirstPerson() {
        return RenderOffsets.Hand.builder().firstPerson(RenderOffsets.Offset.scale(1, 1, 1)).build();
    }

    @Test
    void renderOffsetsWritePositionRotationScaleAndLeaveOutEmptyParts() {
        RenderOffsets offsets = RenderOffsets.builder()
            .mainHand(RenderOffsets.Hand.builder()
                .firstPerson(RenderOffsets.Offset.builder().position(RenderOffsets.Vec3.of(1, 2, 3)).rotation(RenderOffsets.Vec3.of(4, 5, 6))
                    .scale(RenderOffsets.Vec3.of(7, 8, 9)).build())
                .thirdPerson(RenderOffsets.Offset.builder().build()).build())
            .offHand(RenderOffsets.Hand.builder().build()).build();
        NbtMap node = components(base().component(offsets).build(), PALETTER.latest()).getCompound("minecraft:render_offsets");
        NbtMap expected = NbtMap.builder().putCompound("main_hand", NbtMap.builder().putCompound("first_person", NbtMap.builder()
            .putList("position", NbtType.FLOAT, List.of(1f, 2f, 3f))
            .putList("rotation", NbtType.FLOAT, List.of(4f, 5f, 6f))
            .putList("scale", NbtType.FLOAT, List.of(7f, 8f, 9f)).build()).build()).build();
        assertEquals(expected, node);
        assertFalse(node.containsKey("off_hand"));

        NbtMap empty = components(base().component(RenderOffsets.builder().build()).build(), PALETTER.latest());
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
            .component(RenderOffsets.builder().mainHand(scaledFirstPerson()).build()).build();
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
        CustomItem reversed = base().component(RenderOffsets.builder().mainHand(scaledFirstPerson()).build())
            .component(FrameCount.of(4)).component(MiningSpeed.of(3f)).component(MaxStackSize.of(16)).build();
        assertEquals(after, components(reversed, PALETTER.latest()));
    }

    @Test
    void theyAreOnePerNameLikeGeneratedComponents() {
        CustomItem replaced = CustomItem.builder("example:x").component(FrameCount.of(2)).component(FrameCount.of(5)).build();
        assertEquals(5, replaced.component(FrameCount.class).value());
        assertEquals(1, replaced.components().size());
        assertNull(replaced.component(MiningSpeed.class));
    }

    @Test
    void overridesOfVanillaItemsWriteThem() {
        Palette palette = Palettes.palette(PALETTER.latest().protocolVersion(), PaletteLayer.builder("fixes")
            .overrideItem("minecraft:bow", item -> item.modifyComponents(FrameCount.of(10), MiningSpeed.of(0.5f))).build());
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
        assertEquals("minecraft:render_offsets", RenderOffsets.builder().build().name());
    }

    @Test
    void onPlayerPlacingIsSentAsTheTriggerType() {
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = Palettes.palette(version.protocolVersion(), PaletteLayer.builder("placing")
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
    void scriptHooksAreThreeBytesInTheNewForm() {
        for (DataVersion version : PALETTER.versions()) {
            for (boolean interact : new boolean[] {false, true}) {
                for (boolean placing : new boolean[] {false, true}) {
                    Palette palette = Palettes.palette(version.protocolVersion(), PaletteLayer.builder("hooks")
                        .block(CustomBlock.builder("example:door").component(ScriptHooks.of(interact, placing)).build()).build());
                    NbtMap components = palette.blocks().blockProperties().stream().filter(b -> b.getName().equals("example:door")).findFirst()
                        .orElseThrow().getProperties().getCompound("components");
                    assertEquals(NbtMap.builder().putByte("hasPlayerInteract", (byte) (interact ? 1 : 0))
                        .putByte("hasPlayerPlacing", (byte) (placing ? 1 : 0)).putByte("isV1Component", (byte) 0).build(),
                        components.getCompound("minecraft:custom_components"), version.toString());
                }
            }
        }
        assertEquals("minecraft:custom_components", ScriptHooks.of(true, false).name());
    }

    @Test
    void useOnHookIsThePublisherByte() {
        for (DataVersion version : PALETTER.versions()) {
            NbtMap hook = components(base().component(UseOnHook.of(true)).build(), version).getCompound("minecraft:publisher_on_use_on");
            assertEquals(NbtMap.builder().putByte("autoSucceedOnClient", (byte) 1).build(), hook, version.toString());
            assertFalse(components(base().build(), version).containsKey("minecraft:publisher_on_use_on"));
        }
        assertEquals("minecraft:publisher_on_use_on", UseOnHook.of(false).name());
    }

    @Test
    void overridesOfLegacyItemsKeepNameAndId() {
        // Legacy items (cake) have no component data of their own: the override starts from {name, id, components}
        for (DataVersion version : PALETTER.versions()) {
            Palette palette = Palettes.palette(version.protocolVersion(), PaletteLayer.builder("fixes")
                .overrideItem("minecraft:cake", item -> item.modifyComponents(MiningSpeed.of(2f))).build());
            RegisteredItem cake = palette.items().getDefinition("minecraft:cake");
            NbtMap data = cake.getComponentData();
            assertEquals("minecraft:cake", data.getString("name"), version.toString());
            assertEquals(cake.getRuntimeId(), data.getInt("id"), version.toString());
            assertEquals(2f, data.getCompound("components").getCompound("item_properties").getFloat("mining_speed"));
            assertTrue(cake.isComponentBased());
        }
    }
}
