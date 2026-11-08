package org.geysermc.paletter.internal.component;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.PaletteReport;
import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.CollisionBox;
import org.geysermc.paletter.block.component.ConnectionRule;
import org.geysermc.paletter.block.component.DestructibleByMining;
import org.geysermc.paletter.block.component.EmbeddedVisual;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.ItemVisual;
import org.geysermc.paletter.block.component.LightEmission;
import org.geysermc.paletter.block.component.LiquidDetection;
import org.geysermc.paletter.block.component.MaterialInstances;
import org.geysermc.paletter.block.component.PlacementDirection;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.RedstoneProducer;
import org.geysermc.paletter.block.component.SelectionBox;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.data.VanillaData;
import org.geysermc.paletter.item.component.BlockPlacer;
import org.geysermc.paletter.item.component.Digger;
import org.geysermc.paletter.item.component.Dyeable;
import org.geysermc.paletter.item.component.EntityPlacer;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.HoverTextColor;
import org.geysermc.paletter.item.component.Icon;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.UseAnimation;
import org.geysermc.paletter.vanilla.BedrockBlocks;
import org.geysermc.paletter.vanilla.BedrockItems;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every named transform of {@code codegen/model/transforms.json}, on its own. Expected values are the wire forms BDS
 * 1.26.63.1 sends (see each entry's {@code why}); the conformance suite in codegen checks them against the capture too.
 */
class TransformsTest {
    private static final Paletter PALETTER = Paletter.bundled();
    private static final VanillaData DATA = PALETTER.vanillaData(PALETTER.latest());

    private final PaletteReport report = new PaletteReport();
    private final WireContext ctx = WireContext.of(DATA, report, "test").subject("test:thing");

    private static NbtMap compound(WireNode node, String path) {
        return node.build().getCompound(path);
    }

    @Test
    void collisionBoxes() {
        WireNode out = new WireNode();
        CollisionBox box = CollisionBox.builder().boxes(CollisionBox.Entry.builder().origin(1, 2, 3).size(1, 2, 3).build()).build();
        Transforms.collisionBoxes(ctx, out, box);
        NbtMap wire = compound(out, "minecraft:collision_box");
        assertEquals((byte) 1, wire.getByte("enabled"));
        NbtMap b = wire.getList("boxes", NbtType.COMPOUND).getFirst();
        // x mirrored: 8 - origin - size .. 8 - origin; y as is; z shifted by 8
        assertEquals(6f, b.getFloat("minX"));
        assertEquals(7f, b.getFloat("maxX"));
        assertEquals(2f, b.getFloat("minY"));
        assertEquals(4f, b.getFloat("maxY"));
        assertEquals(11f, b.getFloat("minZ"));
        assertEquals(14f, b.getFloat("maxZ"));
        // The boolean form is the generated rules' business
        WireNode untouched = new WireNode();
        Transforms.collisionBoxes(ctx, untouched, CollisionBox.builder().enabled(false).build());
        assertTrue(untouched.isEmpty());
    }

    @Test
    void geometry() {
        WireNode out = new WireNode();
        Transforms.geometry(ctx, out, Geometry.builder().identifier("geometry.lamp").boneVisibility(Map.of("lid", false)).uvLock(true).build());
        NbtMap g = compound(out, "minecraft:geometry");
        assertEquals("geometry.lamp", g.getString("identifier"));
        assertEquals("0.000000", g.getCompound("bone_visibility").getString("lid"));
        assertEquals("minecraft:culling_layer.undefined", g.getString("culling_layer"));
        assertEquals("", g.getString("culling_shape"), "the documented default minecraft:empty is sent as an empty string");
        assertEquals((byte) 1, g.getByte("uv_lock"));
        assertEquals((byte) 0, g.getByte("isV1Fullblock"));

        WireNode embedded = new WireNode();
        Transforms.geometry(ctx, embedded, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.Geometry.builder().identifier("minecraft:geometry.cross").build())
            .materialInstances(MaterialInstances.builder().build()).build());
        NbtMap e = compound(embedded, "minecraft:embedded_visual").getCompound("geometry");
        assertEquals("minecraft:geometry.cross", e.getString("identifier"));
        assertFalse(e.containsKey("isV1Fullblock"));
    }

    @Test
    void materialInstancesPackedInlineAndAliases() {
        MaterialInstances materials = MaterialInstances.builder()
            .all(MaterialInstances.Instance.builder().texture("stone").faceDimming(false).isotropic(true).build())
            .upAlias("*")
            .build();
        WireNode out = new WireNode();
        Transforms.materialInstances(ctx, out, materials);
        NbtMap m = compound(out, "minecraft:material_instances");
        NbtMap all = m.getCompound("materials").getCompound("*");
        assertEquals((byte) 6, all.getByte("packed_bools"), "bit 2 always, bit 1 isotropic, no face dimming");
        assertEquals(1f, all.getFloat("ambient_occlusion"));
        assertEquals("opaque", all.getString("render_method"));
        assertEquals("none", all.getString("tint_method"));
        assertEquals("*", m.getCompound("mappings").getCompound("up").getString("value"));

        WireNode visual = new WireNode();
        Transforms.materialInstancesPacked(ctx, visual, ItemVisual.builder().geometry(ItemVisual.Geometry.of("minecraft:geometry.full_block"))
            .materialInstances(materials).build());
        assertEquals(m, compound(visual, "minecraft:item_visual").getCompound("materialInstancesDescription"));

        WireNode inline = new WireNode();
        Transforms.materialInstancesInline(ctx, inline, EmbeddedVisual.builder()
            .geometry(EmbeddedVisual.Geometry.builder().identifier("minecraft:geometry.full_block").build()).materialInstances(materials).build());
        NbtMap face = compound(inline, "minecraft:embedded_visual").getCompound("material_instances").getCompound("*");
        assertEquals((byte) 0, face.getByte("face_dimming"));
        assertEquals((byte) 1, face.getByte("isotropic"));
    }

    @Test
    void blockDescriptorStatesAreTypedByTheRegistry() {
        WireNode placer = new WireNode();
        Transforms.blockDescriptorStates(ctx, placer, BlockPlacer.UseOn.of(BedrockBlocks.OAK_LOG.defaultState()));
        NbtMap states = placer.build().getCompound("states");
        assertEquals("minecraft:oak_log", placer.build().getString("name"));
        assertEquals("", placer.build().getString("tags"), "item descriptors are always the full triple");
        assertEquals(BedrockBlocks.OAK_LOG.defaultState().toNbt().getCompound("states"), states);

        WireNode entity = new WireNode();
        Transforms.blockDescriptorStates(ctx, entity, EntityPlacer.UseOn.builder().tags("q.any_tag('stone')").build());
        assertEquals("", entity.build().getString("name"));
        assertEquals("q.any_tag('stone')", entity.build().getString("tags"));

        WireNode digger = new WireNode();
        Transforms.blockDescriptorStates(ctx, digger, Digger.Block.builder().name("minecraft:stone").build());
        assertEquals(NbtMap.EMPTY, digger.build().getCompound("block").getCompound("states"));

        WireNode filter = new WireNode();
        Transforms.blockDescriptorStates(ctx, filter, PlacementFilter.BlockFilter.builder().name("minecraft:dirt").states(Map.of("x", 1)).build());
        NbtMap entry = filter.build().getList("states", NbtType.COMPOUND).getFirst();
        assertEquals("x", entry.getString("state"));
        assertEquals(2, entry.getInt("type"));
        assertEquals(1, entry.getInt("value"));
        WireNode empty = new WireNode();
        Transforms.blockDescriptorStates(ctx, empty, PlacementFilter.BlockFilter.builder().name("minecraft:dirt").build());
        assertTrue(empty.isEmpty(), "placement filters send no states when there are none");
    }

    @Test
    void itemDescriptors() {
        WireNode food = new WireNode();
        Transforms.itemDescriptorName(ctx, food, Food.builder().usingConvertsTo(BedrockItems.BOWL).build());
        assertEquals("minecraft:bowl", compound(food, "minecraft:food").getCompound("using_converts_to").getString("name"));
        WireNode unknown = new WireNode();
        Transforms.itemDescriptorName(ctx, unknown, Food.builder().usingConvertsTo("test:nothing").build());
        assertEquals(NbtMap.EMPTY, compound(unknown, "minecraft:food").getCompound("using_converts_to"));
        assertEquals(1, report.warnings().size());

        WireNode repair = new WireNode();
        Transforms.itemDescriptorList(ctx, repair, Repairable.builder().repairItems(
            Repairable.RepairItems.builder().items("minecraft:stick").repairAmount(1.5f).build(),
            Repairable.RepairItems.builder().items(List.of()).repairAmountExpression("q.max_durability * 0.25").build()).build());
        NbtList<NbtMap> entries = (NbtList<NbtMap>) compound(repair, "minecraft:repairable").getList("repair_items", NbtType.COMPOUND);
        assertEquals("minecraft:stick", entries.get(0).getList("items", NbtType.COMPOUND).getFirst().getString("name"));
        assertEquals(1.5f, entries.get(0).getFloat("repair_amount"));
        assertEquals("q.max_durability * 0.25", entries.get(1).getString("repair_amount"));
        assertTrue(entries.get(1).getList("items", NbtType.COMPOUND).isEmpty());
    }

    @Test
    void formattingCode() {
        WireNode out = new WireNode();
        Transforms.formattingCode(ctx, out, HoverTextColor.builder().value("dark_purple").build());
        assertEquals("dark_purple", compound(out, "minecraft:hover_text_color").getString("value"));
        assertEquals("§5", compound(out, "item_properties").getString("hover_text_color"));
        WireNode unknown = new WireNode();
        Transforms.formattingCode(ctx, unknown, HoverTextColor.builder().value("ultraviolet").build());
        assertFalse(unknown.build().containsKey("item_properties"));
    }

    @Test
    void permutationComponents() {
        WireNode block = new WireNode();
        Transforms.permutationComponents(ctx, block, new CustomBlock.Permutation("q.block_state('a')", List.of(LightEmission.of(15))));
        Transforms.permutationComponents(ctx, block, new CustomBlock.Permutation("q.block_state('b')", List.of()));
        NbtList<NbtMap> permutations = (NbtList<NbtMap>) block.build().getList("permutations", NbtType.COMPOUND);
        assertEquals(1, permutations.size(), "a permutation without components is not sent");
        assertEquals((byte) 15, permutations.getFirst().getCompound("components").getCompound("minecraft:light_emission").getByte("emission"));
        assertEquals("q.block_state('a')", permutations.getFirst().getString("condition"));
    }

    @Test
    void quarterTurnsFacesAndFlags() {
        WireNode t = new WireNode();
        Transforms.quarterTurns(ctx, t, Transformation.builder().rotation(90, 180, -90).build());
        NbtMap r = compound(t, "minecraft:transformation");
        assertEquals(1, r.getInt("RX"));
        assertEquals(2, r.getInt("RY"));
        assertEquals(3, r.getInt("RZ"));

        WireNode filter = new WireNode();
        Transforms.faceBits(ctx, filter, PlacementFilter.Conditions.builder().allowedFaces("up", "east").build());
        assertEquals((byte) 34, filter.build().getByte("allowed_faces"));
        WireNode liquid = new WireNode();
        Transforms.faceBits(ctx, liquid, LiquidDetection.DetectionRules.builder().stopsLiquidFlowingFromDirection("up", "north").build());
        assertEquals((byte) 6, liquid.build().getByte("stopsLiquidFromDirection"));

        WireNode producer = new WireNode();
        Transforms.faceList(ctx, producer, RedstoneProducer.builder().power(15).build());
        assertEquals(List.of("down", "up", "north", "south", "west", "east"),
            compound(producer, "minecraft:redstone_producer").getList("connected_faces", NbtType.STRING));
        WireNode rule = new WireNode();
        Transforms.faceList(ctx, rule, ConnectionRule.builder().build());
        assertEquals(4, compound(rule, "minecraft:connection_rule").getList("enabled_directions", NbtType.STRING).size());

        WireNode trait = new WireNode();
        Transforms.enabledStateFlags(ctx, trait, PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.FACING_DIRECTION).build());
        NbtMap flags = trait.build().getCompound("enabled_states");
        assertEquals((byte) 1, flags.getByte("facing_direction"));
        assertEquals((byte) 0, flags.getByte("cardinal_direction"));
        assertEquals((byte) 0, flags.getByte("sixteen_way_rotation"));
    }

    @Test
    void iconColorDestructionSelectionAndAnimation() {
        WireNode icon = new WireNode();
        Transforms.iconTextures(ctx, icon, Icon.of("ruby"));
        assertEquals("ruby", compound(icon, "item_properties").getCompound("minecraft:icon").getCompound("textures").getString("default"));

        WireNode color = new WireNode();
        Transforms.rgbColor(ctx, color, Dyeable.builder().defaultColorHex("#ff0080").build());
        assertEquals(List.of(255, 0, 128), compound(color, "minecraft:dyeable").getList("default_color", NbtType.INT));
        WireNode clamped = new WireNode();
        Transforms.rgbColor(ctx, clamped, Dyeable.builder().defaultColor(10, 20, 270).build());
        assertEquals(List.of(10, 20, 255), compound(clamped, "minecraft:dyeable").getList("default_color", NbtType.INT));
        WireNode white = new WireNode();
        Transforms.rgbColor(ctx, white, Dyeable.builder().build());
        assertEquals(List.of(255, 255, 255), compound(white, "minecraft:dyeable").getList("default_color", NbtType.INT));

        WireNode mining = new WireNode();
        Transforms.destructibleByMining(ctx, mining, DestructibleByMining.builder().secondsToDestroy(2.5f).build());
        assertEquals(2.5f, compound(mining, "minecraft:destructible_by_mining").getFloat("value"));
        assertFalse(compound(mining, "minecraft:destructible_by_mining").containsKey("item_specific_speeds"));
        WireNode indestructible = new WireNode();
        Transforms.destructibleByMining(ctx, indestructible, DestructibleByMining.of(false));
        assertEquals(-1f, compound(indestructible, "minecraft:destructible_by_mining").getFloat("value"));

        WireNode selection = new WireNode();
        Transforms.selectionBox(ctx, selection, SelectionBox.of(false));
        NbtMap s = compound(selection, "minecraft:selection_box");
        assertEquals((byte) 0, s.getByte("enabled"));
        assertEquals(List.of(-8f, 0f, -8f), s.getList("origin", NbtType.FLOAT));
        assertEquals(List.of(16f, 16f, 16f), s.getList("size", NbtType.FLOAT));

        WireNode animation = new WireNode();
        Transforms.useAnimationId(ctx, animation, UseAnimation.of("spear"));
        assertEquals(6, compound(animation, "item_properties").getInt("use_animation"));
        assertEquals("spear", compound(animation, "minecraft:use_animation").getString("value"));
        WireNode none = new WireNode();
        Transforms.useAnimationId(ctx, none, UseAnimation.builder().build());
        assertEquals(0, compound(none, "item_properties").getInt("use_animation"));
    }
}
