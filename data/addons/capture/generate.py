"""Generates the Paletter capture add-on (behavior + resource pack) into the given directory: python generate.py capture/addon"""
import json, os, struct, sys, zlib, shutil

OUT = sys.argv[1]
FV = "1.26.60"
NS = "paletter"
BP = os.path.join(OUT, "behavior_pack")
RP = os.path.join(OUT, "resource_pack")
BP_UUID, BP_MOD = "a1e5c0de-0000-4000-8000-00000000b001", "a1e5c0de-0000-4000-8000-00000000b002"
RP_UUID, RP_MOD = "a1e5c0de-0000-4000-8000-00000000c001", "a1e5c0de-0000-4000-8000-00000000c002"

for d in (BP, RP):
    if os.path.isdir(d):
        shutil.rmtree(d)


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        if isinstance(data, str):
            f.write(data)
        else:
            json.dump(data, f, indent=2)
            f.write("\n")


def png(path, rgba):
    raw = b"".join(b"\x00" + bytes(rgba) * 16 for _ in range(16))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)


# Items: (name, components, description extras). One component under test per item where the game allows it;
# companions that a component requires are listed explicitly.
ITEMS = [
    ("plain", {"minecraft:max_stack_size": 64}, {}),
    ("menu_hidden", {"minecraft:max_stack_size": 64}, {"menu_category": {"category": "equipment", "group": "minecraft:itemGroup.name.sword", "is_hidden_in_commands": True}}),
    ("menu_none", {"minecraft:max_stack_size": 64}, {"menu_category": {"category": "none"}}),
    ("allow_off_hand_short", {"minecraft:allow_off_hand": True}, {}),
    ("allow_off_hand_full", {"minecraft:allow_off_hand": {"value": False}}, {}),
    ("block_placer", {"minecraft:block_placer": {"block": f"{NS}:plain_block"}}, {}),
    ("block_placer_use_on", {"minecraft:block_placer": {"block": "minecraft:dirt", "use_on": ["minecraft:grass_block", {"name": "minecraft:stone"}]}}, {}),
    ("plain_block", {"minecraft:block_placer": {"block": f"{NS}:plain_block", "replace_block_item": True}}, {}),
    ("bundle_interaction", {"minecraft:bundle_interaction": {"num_viewable_slots": 4}, "minecraft:storage_item": {}}, {}),
    ("bundle_interaction_default", {"minecraft:bundle_interaction": {}, "minecraft:storage_item": {"max_slots": 12}}, {}),
    ("can_destroy_in_creative_short", {"minecraft:can_destroy_in_creative": False}, {}),
    ("can_destroy_in_creative_full", {"minecraft:can_destroy_in_creative": {"value": True}}, {}),
    ("compostable", {"minecraft:compostable": {"composting_chance": 50}}, {}),
    ("compostable_max", {"minecraft:compostable": {"composting_chance": 100}}, {}),
    ("cooldown", {"minecraft:cooldown": {"category": "pal", "duration": 1.5}}, {}),
    ("cooldown_attack", {"minecraft:cooldown": {"category": "pal2", "duration": 2, "type": "attack"}}, {}),
    ("damage_short", {"minecraft:damage": 5}, {}),
    ("damage_full", {"minecraft:damage": {"value": 300}}, {}),
    ("damage_absorption", {"minecraft:damage_absorption": {"absorbable_causes": ["all"]},
                            "minecraft:wearable": {"slot": "slot.armor.body"}, "minecraft:durability": {"max_durability": 64}}, {}),
    ("digger", {"minecraft:digger": {"use_efficiency": True, "destroy_speeds": [
        {"block": "minecraft:dirt", "speed": 5},
        {"block": {"tags": "query.any_tag('stone', 'metal')"}, "speed": 8},
        {"block": {"name": "minecraft:oak_log", "states": {"pillar_axis": "y"}}, "speed": 3}]}}, {}),
    ("digger_empty", {"minecraft:digger": {}}, {}),
    ("display_name", {"minecraft:display_name": {"value": "Paletter Display"}}, {}),
    ("display_name_key", {"minecraft:display_name": {"value": "item.paletter.key.name"}}, {}),
    ("durability", {"minecraft:durability": {"max_durability": 100}}, {}),
    ("durability_chance", {"minecraft:durability": {"max_durability": 50, "damage_chance": {"min": 10, "max": 50}}}, {}),
    ("durability_sensor", {"minecraft:durability": {"max_durability": 30}, "minecraft:durability_sensor": {"durability_thresholds": [
        {"durability": 10, "sound_event": "break"}, {"durability": 5}]}}, {}),
    ("dyeable", {"minecraft:dyeable": {}}, {}),
    ("dyeable_hex", {"minecraft:dyeable": {"default_color": "#ff0080"}}, {}),
    ("dyeable_rgb", {"minecraft:dyeable": {"default_color": [10, 20, 30]}}, {}),
    ("enchantable", {"minecraft:enchantable": {"slot": "sword", "value": 10}}, {}),
    ("enchantable_all", {"minecraft:enchantable": {"slot": "all", "value": 1}}, {}),
    ("entity_placer", {"minecraft:entity_placer": {"entity": "minecraft:pig"}}, {}),
    ("entity_placer_full", {"minecraft:entity_placer": {"entity": "minecraft:cow", "use_on": ["minecraft:grass_block"], "dispense_on": [{"name": "minecraft:stone"}]}}, {}),
    ("fire_resistant_true", {"minecraft:fire_resistant": {"value": True}}, {}),
    ("fire_resistant_full", {"minecraft:fire_resistant": {"value": False}}, {}),
    ("food", {"minecraft:food": {"nutrition": 4, "saturation_modifier": 0.5}, "minecraft:use_modifiers": {"use_duration": 1.6}}, {}),
    ("food_full", {"minecraft:food": {"can_always_eat": True, "nutrition": 2, "using_converts_to": "minecraft:bowl"}, "minecraft:use_modifiers": {"use_duration": 1}}, {}),
    ("fuel", {"minecraft:fuel": {"duration": 3}}, {}),
    ("fuel_fraction", {"minecraft:fuel": {"duration": 10.5}}, {}),
    ("glint_short", {"minecraft:glint": True}, {}),
    ("glint_full", {"minecraft:glint": {"value": False}}, {}),
    ("hand_equipped_short", {"minecraft:hand_equipped": True}, {}),
    ("hand_equipped_full", {"minecraft:hand_equipped": {"value": False}}, {}),
    ("hover_text_color", {"minecraft:hover_text_color": "red"}, {}),
    ("hover_text_color_full", {"minecraft:hover_text_color": {"value": "dark_purple"}}, {}),
    ("icon_short", {"minecraft:icon": "paletter_icon"}, {}),
    ("icon_textures", {"minecraft:icon": {"textures": {"default": "paletter_icon", "dyed": "paletter_icon_alt"}}}, {}),
    ("interact_button_true", {"minecraft:interact_button": True}, {}),
    ("interact_button_text", {"minecraft:interact_button": "Use Paletter"}, {}),
    ("kinetic_weapon", {"minecraft:kinetic_weapon": {
        "delay": 5, "reach": {"min": 0.5, "max": 4}, "creative_reach": {"min": 0, "max": 6}, "hitbox_margin": 0.125,
        "damage_multiplier": 1.5, "damage_modifier": 2,
        "damage_conditions": {"max_duration": 20, "min_speed": 1.5, "min_relative_speed": 0.5},
        "knockback_conditions": {"max_duration": 40},
        "dismount_conditions": {"min_speed": 3}}}, {}),
    ("kinetic_weapon_empty", {"minecraft:kinetic_weapon": {}}, {}),
    ("liquid_clipped_short", {"minecraft:liquid_clipped": True}, {}),
    ("liquid_clipped_full", {"minecraft:liquid_clipped": {"value": False}}, {}),
    ("max_stack_size_short", {"minecraft:max_stack_size": 16}, {}),
    ("max_stack_size_full", {"minecraft:max_stack_size": {"value": 1}}, {}),
    ("piercing_weapon", {"minecraft:piercing_weapon": {"reach": {"min": 0.25, "max": 4.5}, "creative_reach": {"min": 0, "max": 7}, "hitbox_margin": 0.2}}, {}),
    ("piercing_weapon_empty", {"minecraft:piercing_weapon": {}}, {}),
    ("projectile", {"minecraft:projectile": {"projectile_entity": "minecraft:arrow", "minimum_critical_power": 1.25}}, {}),
    ("projectile_min", {"minecraft:projectile": {"projectile_entity": "snowball"}}, {}),
    ("rarity_short", {"minecraft:rarity": "rare"}, {}),
    ("rarity_full", {"minecraft:rarity": {"value": "epic"}}, {}),
    ("record", {"minecraft:record": {"sound_event": "record.cat", "duration": 185, "comparator_signal": 2}}, {}),
    ("record_min", {"minecraft:record": {"sound_event": "record.13"}}, {}),
    ("repairable", {"minecraft:durability": {"max_durability": 80}, "minecraft:repairable": {"repair_items": [
        {"items": ["minecraft:stick"], "repair_amount": 10},
        {"items": ["minecraft:diamond", {"name": "minecraft:iron_ingot"}], "repair_amount": "query.max_durability * 0.25"}]}}, {}),
    ("shooter", {"minecraft:use_modifiers": {"use_duration": 3600}, "minecraft:shooter": {
        "ammunition": [{"item": "minecraft:arrow", "use_offhand": True, "search_inventory": True, "use_in_creative": True}],
        "max_draw_duration": 1, "scale_power_by_draw_duration": True, "charge_on_draw": False}}, {}),
    ("shooter_min", {"minecraft:use_modifiers": {"use_duration": 10}, "minecraft:shooter": {"ammunition": [{"item": "minecraft:snowball"}]}}, {}),
    ("should_despawn_short", {"minecraft:should_despawn": False}, {}),
    ("should_despawn_full", {"minecraft:should_despawn": {"value": True}}, {}),
    ("stacked_by_data_short", {"minecraft:stacked_by_data": True}, {}),
    ("stacked_by_data_full", {"minecraft:stacked_by_data": {"value": False}}, {}),
    ("storage_item", {"minecraft:storage_item": {"max_slots": 8, "allow_nested_storage_items": False,
                                                 "banned_items": ["minecraft:shulker_box"], "allowed_items": ["minecraft:dirt", "minecraft:stone"]},
                      "minecraft:storage_weight_limit": {"max_weight_limit": 32}}, {}),
    ("storage_item_default", {"minecraft:storage_item": {}}, {}),
    ("storage_weight_modifier", {"minecraft:storage_weight_modifier": {"weight_in_storage_item": 4}}, {}),
    ("storage_weight_modifier_zero", {"minecraft:storage_weight_modifier": {"weight_in_storage_item": 0}}, {}),
    ("swing_duration", {"minecraft:swing_duration": {"value": 0.5}}, {}),
    ("swing_sounds", {"minecraft:swing_sounds": {"attack_hit": "attack.strong", "attack_miss": "attack.nodamage", "attack_critical_hit": "attack.strong"}}, {}),
    ("swing_sounds_min", {"minecraft:swing_sounds": {"attack_miss": "attack.nodamage"}}, {}),
    ("tags", {"minecraft:tags": {"tags": ["paletter:tag_a", "minecraft:is_sword"]}}, {}),
    ("throwable", {"minecraft:throwable": {"do_swing_animation": True, "launch_power_scale": 1.5, "max_draw_duration": 1,
                                           "max_launch_power": 2, "min_draw_duration": 0.25, "scale_power_by_draw_duration": True},
                   "minecraft:projectile": {"projectile_entity": "minecraft:snowball"}}, {}),
    ("throwable_empty", {"minecraft:throwable": {}, "minecraft:projectile": {"projectile_entity": "minecraft:snowball"}}, {}),
    ("use_animation_eat", {"minecraft:use_animation": "eat", "minecraft:use_modifiers": {"use_duration": 1}}, {}),
    ("use_animation_spear", {"minecraft:use_animation": {"value": "spear"}, "minecraft:use_modifiers": {"use_duration": 1}}, {}),
    ("use_modifiers", {"minecraft:use_modifiers": {"use_duration": 1.6, "movement_modifier": 0.35}}, {}),
    ("use_modifiers_full", {"minecraft:use_modifiers": {"use_duration": 2, "movement_modifier": 0.5, "emit_vibrations": False, "start_sound": "bow"}}, {}),
    ("wearable_chest", {"minecraft:wearable": {"slot": "slot.armor.chest", "protection": 5}}, {}),
    ("wearable_head", {"minecraft:wearable": {"slot": "slot.armor.head"}}, {}),
    ("wearable_offhand", {"minecraft:wearable": {"slot": "slot.weapon.offhand"}, "minecraft:allow_off_hand": True}, {}),
]

GEO = "geometry.paletter_cube"
TEX = {"texture": "paletter_tex"}

BLOCKS = [
    ("plain_block", {}, {}),
    ("menu_hidden_block", {}, {"menu_category": {"category": "nature", "group": "minecraft:itemGroup.name.dirt", "is_hidden_in_commands": True}}),
    ("block_entity", {"minecraft:block_entity": {"dynamic_properties": True}}, {}),
    ("breathability_air", {"minecraft:breathability": "air"}, {}),
    ("breathability_solid", {"minecraft:breathability": "solid"}, {}),
    ("chest_obstruction", {"minecraft:chest_obstruction": {"obstruction_rule": "never"}}, {}),
    ("collision_box_false", {"minecraft:collision_box": False}, {}),
    ("collision_box_true", {"minecraft:collision_box": True}, {}),
    ("collision_box_box", {"minecraft:collision_box": {"origin": [-8.0, 0.0, -8.0], "size": [16.0, 8.0, 16.0]}}, {}),
    ("collision_box_single_list", {"minecraft:collision_box": [{"origin": [-6, 0, -6], "size": [12, 10, 12]}]}, {}),
    ("collision_box_list", {"minecraft:collision_box": [{"origin": [-8, 0, -8], "size": [16, 4, 16]}, {"origin": [-2, 4, -2], "size": [4, 12, 4]}]}, {}),
    
    ("connection_rule", {"minecraft:connection_rule": {"accepts_connections_from": "only_fences", "enabled_directions": ["north", "south"]}}, {}),
    ("connection_rule_default", {"minecraft:connection_rule": {}}, {}),
    ("crafting_table", {"minecraft:crafting_table": {"crafting_tags": ["crafting_table", "paletter"], "table_name": "Paletter Table"}}, {}),
    ("destructible_by_explosion_false", {"minecraft:destructible_by_explosion": False}, {}),
    ("destructible_by_explosion", {"minecraft:destructible_by_explosion": {"explosion_resistance": 20}}, {}),
    ("destructible_by_mining_false", {"minecraft:destructible_by_mining": False}, {}),
    ("destructible_by_mining", {"minecraft:destructible_by_mining": {"seconds_to_destroy": 2.5, "item_specific_speeds": [
        {"item": "minecraft:iron_pickaxe", "destroy_speed": 1}, {"item": {"tags": "query.any_tag('minecraft:is_pickaxe')"}, "destroy_speed": 0.5}]}}, {}),
    ("destruction_particles", {"minecraft:destruction_particles": {"texture": "paletter_tex", "tint_method": "grass", "particle_count": 50}}, {}),
    ("destruction_particles_min", {"minecraft:destruction_particles": {"texture": "paletter_tex"}}, {}),
    ("display_name", {"minecraft:display_name": "Paletter Block Name"}, {}),
    ("embedded_visual", {"minecraft:embedded_visual": {"geometry": "minecraft:geometry.full_block", "material_instances": {"*": TEX}}}, {}),
    ("enchantment_power_transmitter", {"minecraft:enchantment_power_transmitter": True}, {}),
    ("entity_fall_on", {"minecraft:entity_fall_on": {"min_fall_distance": 2}}, {}),
    ("flammable_true", {"minecraft:flammable": True}, {}),
    ("flammable", {"minecraft:flammable": {"catch_chance_modifier": 10, "destroy_chance_modifier": 30}}, {}),
    ("flower_pottable", {"minecraft:flower_pottable": {}, "minecraft:geometry": "minecraft:geometry.full_block", "minecraft:material_instances": {"*": TEX}}, {}),
    ("friction", {"minecraft:friction": 0.2}, {}),
    ("geometry_short", {"minecraft:geometry": GEO, "minecraft:material_instances": {"*": TEX}}, {}),
    ("geometry_full", {"minecraft:geometry": {"identifier": GEO, "bone_visibility": {"bone_a": True, "bone_b": "query.block_state('paletter:on')"},
                                              "culling": "paletter:culling", "culling_layer": "minecraft:culling_layer.leaves", "uv_lock": True},
                       "minecraft:material_instances": {"*": TEX}}, {"states": {"paletter:on": [False, True]}}),
    ("geometry_uv_lock_list", {"minecraft:geometry": {"identifier": GEO, "uv_lock": ["bone_a"]}, "minecraft:material_instances": {"*": TEX}}, {}),
    ("geometry_full_block", {"minecraft:geometry": "minecraft:geometry.full_block", "minecraft:material_instances": {"*": TEX}}, {}),
    ("instrument_sound", {"minecraft:instrument_sound": {"up": "note.bell", "down": "note.harp"}}, {}),
    ("item_visual", {"minecraft:item_visual": {"geometry": "minecraft:geometry.full_block", "material_instances": {"*": TEX}}, "minecraft:geometry": GEO, "minecraft:material_instances": {"*": {"texture": "paletter_tex_alt"}}}, {}),
    ("leashable", {"minecraft:leashable": {"offset": [0, 0.5, 0]}}, {}),
    ("leashable_default", {"minecraft:leashable": {}}, {}),
    ("light_dampening", {"minecraft:light_dampening": 7}, {}),
    ("light_emission", {"minecraft:light_emission": 10}, {}),
    ("liquid_detection", {"minecraft:liquid_detection": {"detection_rules": [{"liquid_type": "water", "can_contain_liquid": True,
        "on_liquid_touches": "no_reaction", "stops_liquid_flowing_from_direction": ["up", "north"]}]}}, {}),
    ("loot", {"minecraft:loot": "loot_tables/paletter_empty.json"}, {}),
    ("map_color_hex", {"minecraft:map_color": "#ff0000"}, {}),
    ("map_color_rgb", {"minecraft:map_color": [0, 255, 0]}, {}),
    ("map_color_full", {"minecraft:map_color": {"color": "#0000ff", "tint_method": "grass"}}, {}),
    ("material_instances", {"minecraft:material_instances": {"*": {"texture": "paletter_tex", "render_method": "alpha_test", "ambient_occlusion": 0.5,
        "face_dimming": False, "isotropic": True, "tint_method": "grass"}, "up": "north", "north": {"texture": "paletter_tex_alt", "render_method": "blend"}}, "minecraft:geometry": "minecraft:geometry.full_block"}, {}),
    ("material_instances_min", {"minecraft:material_instances": {"*": TEX}, "minecraft:geometry": "minecraft:geometry.full_block"}, {}),
    ("movable_immovable", {"minecraft:movable": {"movement_type": "immovable"}}, {}),
    ("movable_sticky", {"minecraft:movable": {"movement_type": "push_pull", "sticky": "same"}}, {}),
    ("neighbor_change", {"minecraft:neighbor_change": {"neighbor_directions": ["north", "up"]}}, {}),
    ("neighbor_change_default", {"minecraft:neighbor_change": {}}, {}),
    ("on_fall_on", {"minecraft:on_fall_on": {}}, {}),
    ("on_interact", {"minecraft:on_interact": {}}, {}),
    ("on_placed", {"minecraft:on_placed": {}}, {}),
    ("on_player_destroyed", {"minecraft:on_player_destroyed": {}}, {}),
    ("on_player_placing", {"minecraft:on_player_placing": {}}, {}),
    ("on_step_off", {"minecraft:on_step_off": {}}, {}),
    ("on_step_on", {"minecraft:on_step_on": {}}, {}),
    ("placement_filter", {"minecraft:placement_filter": {"conditions": [{"allowed_faces": ["up", "north"], "block_filter": [
        "minecraft:dirt", {"name": "minecraft:stone"}, {"tags": "query.any_tag('stone')"}]}]}}, {}),
    ("precipitation_interactions", {"minecraft:precipitation_interactions": {"precipitation_behavior": "none"}}, {}),
    ("queued_ticking", {"minecraft:queued_ticking": {}}, {}),
    ("random_offset", {"minecraft:random_offset": {"x": {"range": {"min": -0.25, "max": 0.25}, "steps": 4}, "z": {"range": {"min": -0.125, "max": 0.125}}}}, {}),
    ("random_ticking", {"minecraft:random_ticking": {}}, {}),
    ("redstone_conductivity", {"minecraft:redstone_conductivity": {"redstone_conductor": False, "allows_wire_to_step_down": False}}, {}),
    ("redstone_consumer", {"minecraft:redstone_consumer": {"min_power": 3, "propagates_power": True}}, {}),
    ("redstone_producer", {"minecraft:redstone_producer": {"power": 15, "strongly_powered_face": "north", "connected_faces": ["north", "south"], "transform_relative": True}}, {}),
    ("redstone_producer_min", {"minecraft:redstone_producer": {"power": 4}}, {}),
    ("replaceable", {"minecraft:replaceable": {}}, {}),
    ("selection_box_false", {"minecraft:selection_box": False}, {}),
    ("selection_box_box", {"minecraft:selection_box": {"origin": [-4, 0, -4], "size": [8, 8, 8]}}, {}),
    ("sound", {"minecraft:sound": {"sound": "metal"}}, {}),
    ("support", {"minecraft:support": {"shape": "stair"}}, {}),
    ("tags", {"minecraft:tags": ["paletter:block_tag", "stone"]}, {}),
    ("tag_legacy", {"tag:paletter:legacy_tag": {}}, {}),
    ("tick", {"minecraft:tick": {"interval_range": [10, 20], "looping": True}}, {}),
    ("transformation", {"minecraft:transformation": {"rotation": [0, 90, 0], "scale": [0.5, 1, 0.5], "translation": [0, 0.25, 0],
                                                     "rotation_pivot": [0.5, 0, 0], "scale_pivot": [0, 0.5, 0]}}, {}),
    ("transformation_rotation", {"minecraft:transformation": {"rotation": [0, 180, 0]}}, {}),
    ("unit_cube", {"minecraft:unit_cube": {}}, {}),
    ("vibration_properties", {"minecraft:vibration_properties": {"dampen_vibrations": True, "occlude_vibrations": True}}, {}),
    # States, traits and permutations (the property and permutation encoding)
    ("states_block", {"minecraft:material_instances": {"*": TEX}, "minecraft:geometry": "minecraft:geometry.full_block"}, {
        "states": {"paletter:count": {"values": {"min": 0, "max": 3}}, "paletter:color": ["red", "green"], "paletter:flag": [False, True], "paletter:list": [1, 5, 9]},
        "traits": {"minecraft:placement_direction": {"enabled_states": ["minecraft:cardinal_direction"], "y_rotation_offset": 180},
                   "minecraft:placement_position": {"enabled_states": ["minecraft:block_face", "minecraft:vertical_half"]}},
        "permutations": [
            {"condition": "query.block_state('paletter:flag') == true", "components": {"minecraft:light_emission": 15}},
            {"condition": "query.block_state('minecraft:cardinal_direction') == 'north'", "components": {"minecraft:transformation": {"rotation": [0, 0, 0]}}}]}),
    ("connection_trait_block", {}, {"traits": {"minecraft:connection": {"enabled_states": ["minecraft:cardinal_connections"]}}}),
    ("multi_block", {"minecraft:movable": {"movement_type": "immovable"}}, {"traits": {"minecraft:multi_block": {"enabled_states": ["minecraft:multi_block_part"], "parts": 2, "direction": "up"}}}),
]


def item(name, components, extra):
    desc = {"identifier": f"{NS}:{name}"}
    desc.update(extra)
    desc.setdefault("menu_category", {"category": "items"})
    return {"format_version": FV, "minecraft:item": {"description": desc, "components": components}}


LEGACY = {"unit_cube", "queued_ticking", "random_ticking", "on_fall_on", "on_interact", "on_placed", "on_player_destroyed",
          "on_player_placing", "on_step_off", "on_step_on", "breathability_air", "breathability_solid"}
LEGACY_FV = "1.19.40"


def block(name, components, extra):
    desc = {"identifier": f"{NS}:{name}", "menu_category": extra.get("menu_category", {"category": "construction"})}
    if "states" in extra:
        desc["states"] = extra["states"]
    if "traits" in extra:
        desc["traits"] = extra["traits"]
    body = {"description": desc, "components": components}
    if "permutations" in extra:
        body["permutations"] = extra["permutations"]
    return {"format_version": LEGACY_FV if name in LEGACY else FV, "minecraft:block": body}


for name, components, extra in ITEMS:
    write(os.path.join(BP, "items", name + ".json"), item(name, components, extra))
for name, components, extra in BLOCKS:
    if name in LEGACY:
        continue  # rejected by BDS 1.26.63 in every format version (see WIRE-NOTES.md)
    write(os.path.join(BP, "blocks", name + ".json"), block(name, components, extra))
write(os.path.join(BP, "loot_tables", "paletter_empty.json"), {"pools": []})
write(os.path.join(BP, "manifest.json"), {
    "format_version": 2,
    "header": {"name": "Paletter capture", "description": "Custom items and blocks with every component, for wire captures",
               "uuid": BP_UUID, "version": [1, 0, 0], "min_engine_version": [1, 26, 60]},
    "modules": [{"type": "data", "uuid": BP_MOD, "version": [1, 0, 0]}],
    "dependencies": [{"uuid": RP_UUID, "version": [1, 0, 0]}]})

write(os.path.join(RP, "manifest.json"), {
    "format_version": 2,
    "header": {"name": "Paletter capture resources", "description": "Textures and geometry for the capture pack",
               "uuid": RP_UUID, "version": [1, 0, 0], "min_engine_version": [1, 26, 60]},
    "modules": [{"type": "resources", "uuid": RP_MOD, "version": [1, 0, 0]}]})
write(os.path.join(RP, "textures", "terrain_texture.json"), {
    "resource_pack_name": "paletter", "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
    "texture_data": {"paletter_tex": {"textures": "textures/blocks/paletter_tex"},
                     "paletter_tex_alt": {"textures": "textures/blocks/paletter_tex_alt"}}})
write(os.path.join(RP, "textures", "item_texture.json"), {
    "resource_pack_name": "paletter", "texture_name": "atlas.items",
    "texture_data": {"paletter_icon": {"textures": "textures/items/paletter_icon"},
                     "paletter_icon_alt": {"textures": "textures/items/paletter_icon_alt"}}})
png(os.path.join(RP, "textures", "blocks", "paletter_tex.png"), (200, 60, 60, 255))
png(os.path.join(RP, "textures", "blocks", "paletter_tex_alt.png"), (60, 200, 60, 255))
png(os.path.join(RP, "textures", "items", "paletter_icon.png"), (60, 60, 200, 255))
png(os.path.join(RP, "textures", "items", "paletter_icon_alt.png"), (200, 200, 60, 255))
write(os.path.join(RP, "models", "blocks", "paletter_cube.geo.json"), {
    "format_version": "1.21.0",
    "minecraft:geometry": [{"description": {"identifier": GEO, "texture_width": 16, "texture_height": 16},
                            "bones": [{"name": "bone_a", "pivot": [0, 0, 0], "cubes": [{"origin": [-8, 0, -8], "size": [16, 8, 16], "uv": [0, 0]}]},
                                      {"name": "bone_b", "pivot": [0, 8, 0], "cubes": [{"origin": [-4, 8, -4], "size": [8, 8, 8], "uv": [0, 0]}]}]}]})
write(os.path.join(RP, "block_culling", "paletter_culling.json"), {
    "format_version": "1.21.80",
    "minecraft:block_culling_rules": {"description": {"identifier": "paletter:culling"},
                                      "rules": [{"geometry_part": {"bone": "bone_a", "cube": 0, "face": "down"}, "direction": "down"}]}})
print(f"{len(ITEMS)} items, {len(BLOCKS)} blocks -> {OUT}")
