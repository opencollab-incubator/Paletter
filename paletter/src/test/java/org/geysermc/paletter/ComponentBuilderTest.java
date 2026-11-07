package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockComponents;
import org.geysermc.paletter.block.BlockProperty;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.block.component.Box;
import org.geysermc.paletter.block.component.Face;
import org.geysermc.paletter.block.component.Geometry;
import org.geysermc.paletter.block.component.Material;
import org.geysermc.paletter.block.component.PlacementFilter;
import org.geysermc.paletter.block.component.RenderMethod;
import org.geysermc.paletter.block.component.TintMethod;
import org.geysermc.paletter.block.component.Transformation;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.internal.encode.BlockEncoder;
import org.geysermc.paletter.internal.encode.EncodeContext;
import org.geysermc.paletter.internal.encode.ItemEncoder;
import org.geysermc.paletter.internal.wire.block.BlockDefinition;
import org.geysermc.paletter.internal.wire.item.Damage;
import org.geysermc.paletter.item.ItemComponents;
import org.geysermc.paletter.item.component.Color;
import org.geysermc.paletter.item.component.Cooldown;
import org.geysermc.paletter.item.component.CooldownType;
import org.geysermc.paletter.item.component.Durability;
import org.geysermc.paletter.item.component.EquipmentSlot;
import org.geysermc.paletter.item.component.Food;
import org.geysermc.paletter.item.component.KineticWeapon;
import org.geysermc.paletter.item.component.Reach;
import org.geysermc.paletter.item.component.Repairable;
import org.geysermc.paletter.item.component.Throwing;
import org.geysermc.paletter.item.component.UseAnimation;
import org.geysermc.paletter.item.component.UseModifiers;
import org.geysermc.paletter.item.component.Wearable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semantic API encodes to what vanilla and BDS send. Covers every "Geyser should change" point of
 * {@code codegen/DEVIATIONS.md}, which Paletter's encoders now own.
 */
class ComponentBuilderTest {
    static final DataVersion LATEST = new DataVersion("26.60", 2225);

    private final PaletteReport report = new PaletteReport();
    private final EncodeContext context = EncodeContext.of(LATEST, report, "test");

    private NbtMap components(ItemComponents components) {
        NbtMap nbt = ItemEncoder.componentData(components, "test:item", 1000, null, context).getCompound("components");
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
        return nbt;
    }

    private NbtMap blockComponents(BlockComponents components) {
        NbtMap nbt = BlockEncoder.components(components, "test:block", context);
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
        return nbt;
    }

    @Test
    void attackDamageIsAShortAndMirrorsTheItemProperty() {
        NbtMap nbt = components(ItemComponents.builder().attackDamage(300).build());
        // A short, as vanilla sends it; Geyser's byte overflowed above 127
        assertEquals((short) 300, nbt.getCompound("minecraft:damage").get("value"));
        assertEquals(300, nbt.getCompound("item_properties").get("damage"));
    }

    @Test
    void compostingChanceIsAByte() {
        NbtMap nbt = components(ItemComponents.builder().compostable(65).build());
        assertEquals((byte) 65, nbt.getCompound("minecraft:compostable").get("composting_chance"));
    }

    @Test
    void tagsAreSentInBothForms() {
        ItemComponents components = ItemComponents.builder().tag("geyser:is_custom").tags("minecraft:is_tool").build();
        NbtMap nbt = components(components);
        assertEquals(List.of("geyser:is_custom", "minecraft:is_tool"), nbt.getList("item_tags", NbtType.STRING));
        assertEquals(List.of("geyser:is_custom", "minecraft:is_tool"), nbt.getCompound("minecraft:tags").getList("tags", NbtType.STRING));
        // Decoding merges both forms back into one set
        assertEquals(components.tags(), ItemEncoder.decode(nbt).tags());
    }

    @Test
    void durabilityDamageChanceIsInPercent() {
        NbtMap always = components(ItemComponents.builder().durability(Durability.of(250)).build());
        // BDS default for custom items: always damaged. Geyser's 1/1 meant a 1 % chance
        assertEquals(NbtMap.builder()
            .putCompound("damage_chance", NbtMap.builder().putInt("max", 100).putInt("min", 100).build())
            .putInt("max_durability", 250)
            .build(), always.getCompound("minecraft:durability"));
        NbtMap ranged = components(ItemComponents.builder().durability(Durability.of(10).damageChance(20, 60)).build());
        assertEquals(NbtMap.builder().putInt("max", 60).putInt("min", 20).build(),
            ranged.getCompound("minecraft:durability").getCompound("damage_chance"));
        assertThrows(IllegalArgumentException.class, () -> Durability.of(10).damageChance(60, 20));
    }

    @Test
    void kineticWeaponIsNestedLikeVanilla() {
        KineticWeapon.Conditions conditions = KineticWeapon.Conditions.of(100, 10f, 0f);
        NbtMap nbt = components(ItemComponents.builder().kineticWeapon(KineticWeapon.builder()
            .delayTicks(10).reach(Reach.of(0, 3)).creativeReach(Reach.of(0, 5)).hitboxMargin(0.3f)
            .dismountConditions(conditions)
            .build()).build()).getCompound("minecraft:kinetic_weapon");
        assertEquals(Set.of("minecraft:kinetic_weapon"), nbt.keySet());
        NbtMap weapon = nbt.getCompound("minecraft:kinetic_weapon");
        assertEquals((short) 10, weapon.get("delay"));
        assertEquals(NbtMap.builder().putFloat("max", 3f).putFloat("min", 0f).build(), weapon.getCompound("reach"));
        assertEquals(NbtMap.builder().putShort("max_duration", (short) 100).putFloat("min_relative_speed", 0f).putFloat("min_speed", 10f).build(),
            weapon.getCompound("dismount_conditions"));
        // Absent conditions are omitted
        assertFalse(weapon.containsKey("damage_conditions"));
        assertFalse(weapon.containsKey("knockback_conditions"));
    }

    @Test
    void dyeableColourIsAListOfInts() {
        NbtMap nbt = components(ItemComponents.builder().dyeable(Color.of(0xA06540)).build());
        Object color = nbt.getCompound("minecraft:dyeable").get("default_color");
        assertEquals(List.of(160, 101, 64), color);
        assertFalse(color instanceof int[]);
    }

    @Test
    void conveniencesMatchTheWire() {
        NbtMap nbt = components(ItemComponents.builder()
            .maxStackSize(16)
            .throwable(Throwing.of(true))
            .wearable(Wearable.of(EquipmentSlot.CHEST, 5))
            .blockPlacer("geyser:block", true)
            .cooldown(Cooldown.of("geyser:cooldown", 1.5f))
            .glint(true)
            .icon("custom_icon")
            .build());
        assertEquals(NbtMap.builder().putByte("value", (byte) 16).build(), nbt.getCompound("minecraft:max_stack_size"));
        assertEquals(NbtMap.builder().putBoolean("do_swing_animation", true).build(), nbt.getCompound("minecraft:throwable"));
        // A throwable needs a projectile for the swing animation
        assertEquals(NbtMap.builder().putString("projectile_entity", "minecraft:snowball").build(), nbt.getCompound("minecraft:projectile"));
        assertEquals(NbtMap.builder().putString("slot", "slot.armor.chest").putInt("protection", 5).build(), nbt.getCompound("minecraft:wearable"));
        assertEquals(NbtMap.builder().putString("block", "geyser:block").putBoolean("canUseBlockAsIcon", true)
            .putList("use_on", NbtType.COMPOUND).build(), nbt.getCompound("minecraft:block_placer"));
        assertEquals(NbtMap.builder().putString("category", "geyser:cooldown").putFloat("duration", 1.5f).build(), nbt.getCompound("minecraft:cooldown"));
        assertEquals(NbtMap.builder().putBoolean("value", true).build(), nbt.getCompound("minecraft:glint"));
        NbtMap properties = nbt.getCompound("item_properties");
        assertEquals(16, properties.get("max_stack_size"));
        assertEquals((byte) 1, properties.get("foil"));
        assertEquals(NbtMap.builder().putCompound("textures", NbtMap.builder().putString("default", "custom_icon").build()).build(),
            properties.get("minecraft:icon"));
        assertFalse(properties.containsKey("allow_off_hand"));

        NbtMap attack = components(ItemComponents.builder().cooldown(Cooldown.of("x", 1).type(CooldownType.ATTACK)).build());
        assertEquals("attack", attack.getCompound("minecraft:cooldown").getString("type"));
    }

    @Test
    void consumablesWriteComponentAndItemProperty() {
        NbtMap nbt = components(ItemComponents.builder()
            .food(Food.of(4, 0.3f))
            .useAnimation(UseAnimation.EAT)
            .useModifiers(UseModifiers.of(1.6f).movementModifier(0.2f))
            .build());
        assertEquals(NbtMap.builder().putBoolean("can_always_eat", false).putInt("nutrition", 4).putFloat("saturation_modifier", 0.3f)
            .putCompound("using_converts_to", NbtMap.EMPTY).build(), nbt.getCompound("minecraft:food"));
        assertEquals("eat", nbt.getCompound("minecraft:use_animation").getString("value"));
        assertEquals(1, nbt.getCompound("item_properties").get("use_animation"));
        assertEquals(32, nbt.getCompound("item_properties").get("use_duration"));
        assertEquals(1.6f, nbt.getCompound("minecraft:use_modifiers").get("use_duration"));
    }

    @Test
    void repairAmountIsAFloatOrAMolangString() {
        NbtMap nbt = components(ItemComponents.builder().repairable(Repairable.of(
            Repairable.Entry.items("minecraft:diamond").amount("query.max_durability * 0.25"),
            Repairable.Entry.tags("1").amount(1f))).build());
        List<NbtMap> items = nbt.getCompound("minecraft:repairable").getList("repair_items", NbtType.COMPOUND);
        assertEquals("query.max_durability * 0.25", items.get(0).getString("repair_amount"));
        assertEquals(NbtMap.builder().putString("name", "minecraft:diamond").build(), items.get(0).getList("items", NbtType.COMPOUND).get(0));
        assertEquals(1f, items.get(1).get("repair_amount"));
        assertEquals(NbtMap.builder().putString("tags", "1").build(), items.get(1).getList("items", NbtType.COMPOUND).get(0));
    }

    @Test
    void rawComponentsAreWrittenAsGivenAndSemanticValuesWin() {
        NbtMap rarity = NbtMap.builder().putString("value", "rare").build();
        NbtMap nbt = components(ItemComponents.builder()
            .raw("minecraft:rarity", rarity)
            .raw("minecraft:max_stack_size", NbtMap.builder().putByte("value", (byte) 2).build())
            .rawProperty("max_stack_size", 2)
            .maxStackSize(8)
            .build());
        assertEquals(rarity, nbt.getCompound("minecraft:rarity"));
        assertEquals((byte) 8, nbt.getCompound("minecraft:max_stack_size").get("value"));
        assertEquals(8, nbt.getCompound("item_properties").get("max_stack_size"));
    }

    @Test
    void materialsPackTheirFlagsLikeVanilla() {
        NbtMap material = blockComponents(BlockComponents.builder()
            .materials(Map.of(Material.ALL_FACES, Material.of("custom").renderMethod(RenderMethod.ALPHA_TEST)))
            .build()).getCompound("minecraft:material_instances").getCompound("materials").getCompound("*");
        assertFalse(material.containsKey("isotropic"));
        // Bit 0 face dimming, bit 2 always set: the JSON defaults, as BDS sends them
        assertEquals((byte) 5, material.get("packed_bools"));
        assertEquals(1f, material.get("ambient_occlusion"));
        assertEquals("alpha_test", material.getString("render_method"));
        assertFalse(material.containsKey("tint_method"));
    }

    @Test
    void isotropicWithoutDimmingPacksLikeTheCapture() {
        // The capture's isotropic material without face dimming sends packed_bools 6
        NbtMap material = blockComponents(BlockComponents.builder()
            .materials(Map.of("*", Material.of("custom").renderMethod(RenderMethod.OPAQUE).tintMethod(TintMethod.NONE)
                .faceDimming(false).isotropic(true)))
            .build()).getCompound("minecraft:material_instances").getCompound("materials").getCompound("*");
        assertEquals((byte) 6, material.get("packed_bools"));
        assertEquals("none", material.getString("tint_method"));
    }

    @Test
    void faceAliasesGoToMappings() {
        NbtMap nbt = blockComponents(BlockComponents.builder()
            .materials(Map.of("*", Material.of("custom")), Map.of("up", "north"))
            .build()).getCompound("minecraft:material_instances");
        assertEquals(NbtMap.builder().putCompound("up", NbtMap.builder().putString("value", "north").build()).build(),
            nbt.getCompound("mappings"));
    }

    @Test
    void placementFiltersUseTheWireKeys() {
        NbtMap condition = blockComponents(BlockComponents.builder()
            .placementFilter(PlacementFilter.allowOn(Face.UP).blocks("minecraft:stone").tags("q.any_tag('stone')"))
            .build()).getCompound("minecraft:placement_filter").getList("conditions", NbtType.COMPOUND).get(0);
        assertEquals((byte) 2, condition.get("allowed_faces"));
        // block_filter (singular) with tags_version 13; Geyser's block_filters / 6 is not on the wire
        assertFalse(condition.containsKey("block_filters"));
        List<NbtMap> filters = condition.getList("block_filter", NbtType.COMPOUND);
        assertEquals(NbtMap.builder().putString("name", "minecraft:stone").build(), filters.get(0));
        assertEquals(NbtMap.builder().putString("tags", "q.any_tag('stone')").putInt("tags_version", 13).build(), filters.get(1));
    }

    @Test
    void customBlockPropertyDataHasTheTopLevelShapeOfBds() {
        NbtMap data = BlockEncoder.propertyData(CustomBlock.builder("test:block")
            .property(BlockProperty.bool("test:on"))
            .components(BlockComponents.builder().lightEmission(3).tags("test:from_components").build())
            .tag("test:tag")
            .build(), 10000, context);
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
        // Tags as top level blockTags, not tag:<name> components
        assertEquals(List.of("test:tag", "test:from_components"), data.getList("blockTags", NbtType.STRING));
        assertFalse(data.getCompound("components").keySet().stream().anyMatch(key -> key.startsWith("tag:")));
        assertEquals(13, data.getInt("molangVersion"));
        assertEquals(NbtMap.builder().putInt("block_id", 10000).putString("material", "dirt").build(),
            data.getCompound("vanilla_block_data"));
        assertEquals(NbtMap.builder().putString("category", "none").putString("group", "")
            .putBoolean("is_hidden_in_commands", false).build(), data.getCompound("menu_category"));
        assertEquals(List.of(NbtMap.builder().putString("name", "test:on").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build()),
            data.getList("properties", NbtType.COMPOUND));
        assertEquals(10000, BlockDefinition.fromNbt(data).vanillaBlockData().blockId());

        NbtMap plain = BlockEncoder.propertyData(CustomBlock.builder("test:plain").build(), 10001, context);
        assertFalse(plain.containsKey("blockTags"));
    }

    @Test
    void permutationTagsAreReportedAndLeftOut() {
        NbtMap data = BlockEncoder.propertyData(CustomBlock.builder("test:block")
            .property(BlockProperty.bool("test:on"))
            .permutation("q.block_state('test:on') == 1", BlockComponents.builder().tags("test:lit").lightEmission(15).build())
            .build(), 10000, context);
        assertEquals(1, report.warnings().size(), report.entries().toString());
        assertTrue(report.warnings().getFirst().message().contains("test:lit"));
        assertFalse(data.containsKey("blockTags"));
        assertEquals(Set.of("minecraft:light_emission"),
            data.getList("permutations", NbtType.COMPOUND).getFirst().getCompound("components").keySet());
    }

    @Test
    void blockConveniencesKeepGeyserParity() {
        NbtMap nbt = blockComponents(BlockComponents.builder()
            .lightEmission(15)
            .selectionBox(Box.FULL)
            .collisionBoxes(Box.EMPTY)
            .rotation(0, 90, 0)
            .geometry(Geometry.of("geometry.custom").boneVisibility("lid", "q.block_state('x') == 1"))
            .build());
        assertEquals(NbtMap.builder().putByte("emission", (byte) 15).build(), nbt.getCompound("minecraft:light_emission"));
        assertEquals(NbtMap.builder().putBoolean("enabled", true)
            .putList("origin", NbtType.FLOAT, -8f, 0f, -8f).putList("size", NbtType.FLOAT, 16f, 16f, 16f).build(),
            nbt.getCompound("minecraft:selection_box"));
        assertEquals(NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build(),
            nbt.getCompound("minecraft:collision_box"));
        NbtMap transformation = nbt.getCompound("minecraft:transformation");
        assertEquals(1, transformation.get("RY"));
        assertFalse(transformation.containsKey("RXP"));
        assertEquals(9, transformation.size());
        assertEquals(NbtMap.builder().putString("identifier", "geometry.custom")
            .putCompound("bone_visibility", NbtMap.builder().putString("lid", "q.block_state('x') == 1").build()).build(),
            nbt.getCompound("minecraft:geometry"));

        // Like BDS for JSON false: disabled, but the full cube is still sent
        assertEquals(NbtMap.builder().putBoolean("enabled", false)
            .putList("origin", NbtType.FLOAT, -8f, 0f, -8f).putList("size", NbtType.FLOAT, 16f, 16f, 16f).build(),
            blockComponents(BlockComponents.builder().selectionBox(Box.EMPTY).build()).getCompound("minecraft:selection_box"));

        NbtMap pivoted = blockComponents(BlockComponents.builder()
            .transformation(Transformation.builder().rotation(0, 180, 0).rotationPivot(0, 0.5f, 0).build())
            .build()).getCompound("minecraft:transformation");
        assertEquals(2, pivoted.get("RY"));
        assertEquals(0.5f, pivoted.get("RYP"));
    }

    @Test
    void legacyEncodingsAreReadLeniently() {
        // Geyser and old Paletter versions wrote damage as a byte; reading converts, writing uses the wire type
        Damage damage = Damage.TYPE.read(NbtMap.builder().putByte("value", (byte) 5).build());
        assertEquals((short) 5, damage.toNbt().get("value"));
    }

    @Test
    void semanticValuesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> ItemComponents.builder().maxStackSize(99));
        assertThrows(IllegalArgumentException.class, () -> ItemComponents.builder().compostable(101));
        assertThrows(IllegalArgumentException.class, () -> BlockComponents.builder().lightEmission(16));
        assertThrows(IllegalArgumentException.class, () -> Color.rgb(256, 0, 0));
    }
}
