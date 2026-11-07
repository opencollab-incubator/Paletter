package org.geysermc.paletter;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.paletter.block.BlockComponents;
import org.geysermc.paletter.block.CustomBlock;
import org.geysermc.paletter.component.block.BlockDefinition;
import org.geysermc.paletter.component.block.MaterialInstances;
import org.geysermc.paletter.component.block.Transformation;
import org.geysermc.paletter.component.item.Damage;
import org.geysermc.paletter.component.item.ItemProperties;
import org.geysermc.paletter.component.item.KineticWeapon;
import org.geysermc.paletter.component.item.Repairable;
import org.geysermc.paletter.component.item.Tags;
import org.geysermc.paletter.item.ItemComponents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentBuilderTest {
    private static NbtMap components(ItemComponents components) {
        return components.toComponentData("test:item", 1000, null).getCompound("components");
    }

    @Test
    void typedComponentsAreEncodedAsOnTheWire() {
        NbtMap nbt = components(ItemComponents.builder().with(Damage.of((short) 300)).build());
        // A short, as vanilla sends it; the old byte encoding overflowed above 127
        assertEquals((short) 300, nbt.getCompound("minecraft:damage").get("value"));
    }

    @Test
    void attackDamageMirrorsTheItemProperty() {
        ItemComponents components = ItemComponents.builder().attackDamage(7).build();
        NbtMap nbt = components(components);
        assertEquals((short) 7, nbt.getCompound("minecraft:damage").get("value"));
        assertEquals(7, nbt.getCompound("item_properties").get("damage"));
        assertEquals(Damage.of((short) 7), components.get(Damage.TYPE));
        assertEquals(7, components.itemProperties().damage());
    }

    @Test
    void compostingChanceIsAByte() {
        NbtMap nbt = components(ItemComponents.builder().compostable(65).build());
        assertEquals((byte) 65, nbt.getCompound("minecraft:compostable").get("composting_chance"));
    }

    @Test
    void tagsAreSentInBothForms() {
        ItemComponents components = ItemComponents.builder().tag("geyser:is_custom").with(Tags.of(List.of("minecraft:is_tool"))).build();
        NbtMap nbt = components(components);
        assertEquals(List.of("geyser:is_custom", "minecraft:is_tool"), nbt.getList("item_tags", NbtType.STRING));
        assertEquals(List.of("geyser:is_custom", "minecraft:is_tool"), nbt.getCompound("minecraft:tags").getList("tags", NbtType.STRING));
        // Reading merges both forms back into one set
        assertEquals(components.tags(), ItemComponents.fromComponentData(nbt).tags());
    }

    @Test
    void legacyEncodingsAreReadLeniently() {
        // Geyser and old Paletter versions wrote damage as a byte; reading converts, writing uses the wire type
        Damage damage = Damage.TYPE.read(NbtMap.builder().putByte("value", (byte) 5).build());
        assertEquals((short) 5, damage.toNbt().get("value"));
    }

    @Test
    void conveniencesMatchTheWire() {
        NbtMap nbt = components(ItemComponents.builder()
            .maxStackSize(16)
            .durability(250)
            .throwable(true)
            .wearable("armor.chest", 5)
            .blockPlacer("geyser:block", true)
            .cooldown("geyser:cooldown", 1.5f)
            .glint(true)
            .build());
        assertEquals(NbtMap.builder().putByte("value", (byte) 16).build(), nbt.getCompound("minecraft:max_stack_size"));
        assertEquals(NbtMap.builder()
            .putCompound("damage_chance", NbtMap.builder().putInt("max", 100).putInt("min", 100).build())
            .putInt("max_durability", 250)
            .build(), nbt.getCompound("minecraft:durability"));
        assertEquals(NbtMap.builder().putBoolean("do_swing_animation", true).build(), nbt.getCompound("minecraft:throwable"));
        assertEquals(NbtMap.builder().putString("projectile_entity", "minecraft:snowball").build(), nbt.getCompound("minecraft:projectile"));
        assertEquals(NbtMap.builder().putString("slot", "slot.armor.chest").putInt("protection", 5).build(), nbt.getCompound("minecraft:wearable"));
        assertEquals(NbtMap.builder().putString("block", "geyser:block").putBoolean("canUseBlockAsIcon", true)
            .putList("use_on", NbtType.STRING).build(), nbt.getCompound("minecraft:block_placer"));
        assertEquals(NbtMap.builder().putString("category", "geyser:cooldown").putFloat("duration", 1.5f).build(), nbt.getCompound("minecraft:cooldown"));
        assertEquals(NbtMap.builder().putBoolean("value", true).build(), nbt.getCompound("minecraft:glint"));
        NbtMap properties = nbt.getCompound("item_properties");
        assertEquals(16, properties.get("max_stack_size"));
        assertEquals((byte) 1, properties.get("foil"));
    }

    @Test
    void itemPropertiesMergeOnlySetValues() {
        ItemComponents components = ItemComponents.builder()
            .icon("custom_icon")
            .properties(ItemProperties.builder().handEquipped(true).miningSpeed(1f).build())
            .build();
        NbtMap properties = components(components).getCompound("item_properties");
        assertEquals((byte) 1, properties.get("hand_equipped"));
        assertEquals(1f, properties.get("mining_speed"));
        assertEquals(Map.of("default", "custom_icon"), components.itemProperties().icon().textures());
        assertFalse(properties.containsKey("allow_off_hand"));
    }

    @Test
    void rawComponentsStayAvailable() {
        NbtMap raw = NbtMap.builder().putString("value", "rare").build();
        ItemComponents components = ItemComponents.builder().component("minecraft:rarity", raw).build();
        assertEquals(raw, components(components).getCompound("minecraft:rarity"));
    }

    @Test
    void kineticWeaponIsNestedLikeVanilla() {
        KineticWeapon.Range reach = new KineticWeapon.Range(3f, 0f);
        KineticWeapon.Conditions conditions = new KineticWeapon.Conditions((short) 100, 0f, 10f);
        KineticWeapon weapon = new KineticWeapon(KineticWeapon.Weapon.builder()
            .reach(reach).creativeReach(reach).hitboxMargin(0.25f).delay((short) 10).damageMultiplier(1f)
            .damageConditions(conditions).dismountConditions(conditions).knockbackConditions(conditions)
            .build());
        NbtMap nbt = weapon.toNbt();
        assertEquals(Set.of("minecraft:kinetic_weapon"), nbt.keySet());
        assertEquals((short) 10, nbt.getCompound("minecraft:kinetic_weapon").get("delay"));
    }

    @Test
    void repairAmountIsAMolangString() {
        Repairable repairable = Repairable.of(List.of(new Repairable.RepairItem(
            List.of(new Repairable.ItemDescriptor("minecraft:diamond", null)), "query.max_durability * 0.25")));
        NbtMap item = repairable.toNbt().getList("repair_items", NbtType.COMPOUND).get(0);
        assertEquals("query.max_durability * 0.25", item.getString("repair_amount"));
        assertEquals(NbtMap.builder().putString("name", "minecraft:diamond").build(), item.getList("items", NbtType.COMPOUND).get(0));
    }

    @Test
    void materialsPackTheirFlagsLikeVanilla() {
        BlockComponents components = BlockComponents.builder()
            .materials(Map.of("*", BlockComponents.Material.of("custom").renderMethod("alpha_test")))
            .build();
        NbtMap material = components.toNbt().getCompound("minecraft:material_instances").getCompound("materials").getCompound("*");
        assertFalse(material.containsKey("isotropic"));
        // Bit 0 face dimming, bit 2 always set: the JSON defaults, as BDS sends them
        assertEquals((byte) 5, material.get("packed_bools"));
        assertEquals(1f, material.get("ambient_occlusion"));
        assertEquals("alpha_test", material.getString("render_method"));
        assertFalse(material.containsKey("tint_method"));
        MaterialInstances instances = components.get(MaterialInstances.TYPE);
        assertEquals("custom", instances.materials().get("*").texture());
    }

    @Test
    void isotropicWithoutDimmingPacksLikeTheCapture() {
        // The capture's isotropic material without face dimming sends packed_bools 6
        BlockComponents.Material material = new BlockComponents.Material("custom", "opaque", "none", 1f, false, true);
        assertEquals((byte) 6, material.toComponent().packedBools());
    }

    @Test
    void faceAliasesGoToMappings() {
        NbtMap nbt = BlockComponents.builder()
            .materials(Map.of("*", BlockComponents.Material.of("custom")), Map.of("up", "north"))
            .build().toNbt().getCompound("minecraft:material_instances");
        assertEquals(NbtMap.builder().putCompound("up", NbtMap.builder().putString("value", "north").build()).build(),
            nbt.getCompound("mappings"));
    }

    @Test
    void placementFiltersUseTheWireKeys() {
        NbtMap condition = BlockComponents.builder()
            .placementFilter(new BlockComponents.PlacementCondition(Set.of(BlockComponents.Face.UP),
                List.of("minecraft:stone"), List.of("q.any_tag('stone')")))
            .build().toNbt().getCompound("minecraft:placement_filter").getList("conditions", NbtType.COMPOUND).get(0);
        assertEquals((byte) 2, condition.get("allowed_faces"));
        List<NbtMap> filters = condition.getList("block_filter", NbtType.COMPOUND);
        assertEquals(NbtMap.builder().putString("name", "minecraft:stone").build(), filters.get(0));
        assertEquals(NbtMap.builder().putString("tags", "q.any_tag('stone')").putInt("tags_version", 13).build(), filters.get(1));
    }

    @Test
    void customBlockPropertyDataHasTheTopLevelShapeOfBds() {
        NbtMap data = CustomBlock.builder("test:block")
            .components(BlockComponents.builder().lightEmission(3).build())
            .tag("test:tag")
            .material("dirt")
            .build()
            .propertyData(10000);
        assertEquals(List.of("test:tag"), data.getList("blockTags", NbtType.STRING));
        assertEquals(13, data.getInt("molangVersion"));
        assertEquals(NbtMap.builder().putInt("block_id", 10000).putString("material", "dirt").build(),
            data.getCompound("vanilla_block_data"));
        assertEquals(NbtMap.builder().putString("category", "none").putString("group", "")
            .putBoolean("is_hidden_in_commands", false).build(), data.getCompound("menu_category"));
        assertTrue(data.containsKey("components") && data.containsKey("permutations") && data.containsKey("properties"));
        BlockDefinition definition = BlockDefinition.fromNbt(data);
        assertEquals(10000, definition.vanillaBlockData().blockId());
        // Without tags or material, nothing is sent for them, as Geyser does today
        NbtMap plain = CustomBlock.builder("test:plain").build().propertyData(10001);
        assertFalse(plain.containsKey("blockTags"));
        assertFalse(plain.getCompound("vanilla_block_data").containsKey("material"));
    }

    @Test
    void blockConveniencesKeepGeyserParity() {
        NbtMap nbt = BlockComponents.builder()
            .lightEmission(15)
            .selectionBox(BlockComponents.Box.FULL)
            .collisionBoxes(BlockComponents.Box.EMPTY)
            .rotation(0, 90, 0)
            .tag("geyser:custom")
            .build()
            .toNbt();
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
        assertTrue(nbt.containsKey("tag:geyser:custom"));
        assertNull(BlockComponents.EMPTY.get(Transformation.TYPE));
    }
}
