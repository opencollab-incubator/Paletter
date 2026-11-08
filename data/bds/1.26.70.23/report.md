# Paletter data verification: BDS 1.26.70.23 (preview)

- **Date**: 2026-11-07
- **Mode**: verify only (nothing written)
- **BDS**: 1.26.70.23 win-preview (https://www.minecraft.net/bedrockdedicatedserver/bin-win-preview/bedrock-server-1.26.70.23.zip)
- **Work directory**: `C:\Users\onebe\.gradle\caches\paletter\bds\runs\win-preview-1.26.70.23` (logs, captures)
- **Server**: `C:\Users\onebe\.gradle\caches\paletter\bds\servers\win-preview-1.26.70.23` (removed after the run)
- **Protocol**: 2250 (NetherNet LAN discovery: 1.26.70)
- **Transport**: NetherNet (HTTP signaling on server-port, WebRTC; self signed identity bound to the login chain)
- **Bundled version**: 26.70 (NEW protocol)
- **Result**: DIFFERENCES

## WIRE FORMAT CHANGES

> **Component NBT that BDS sends changed shape.** Generated component types encode the old shape; review `codegen/spec/inferred.json` and the generated types before shipping this version.

- **add-on components vs bds-1.26.63.1-addon: block:minecraft:collision_box shape: new field (string)**
- **add-on components vs bds-1.26.63.1-addon: block:minecraft:collision_box useVoxelShapes: new field (byte)**
- **vanilla components vs bundled 26.60: block:minecraft:collision_box shape: new field (string)**
- **vanilla components vs bundled 26.60: block:minecraft:collision_box useVoxelShapes: new field (byte)**
- **not covered by codegen/spec/inferred.json: block:minecraft:collision_box shape: new field (string)**
- **not covered by codegen/spec/inferred.json: block:minecraft:collision_box useVoxelShapes: new field (byte)**

## Checks

| Status | Check | Detail |
|---|---|---|
| PASS | Script API dump | BDS reports 1.26.70-beta23; 1650 items, 1507 blocks, 79 item tags |
| INFO | Reference palette | 26.60 adjusted with the Script API: generated minecraft:scaffolding (16 -> 32 states), minecraft:powered_repeater (16 -> 32 states), minecraft:unpowered_repeater (16 -> 32 states), minecraft:brittle_ice (new, 4 states), minecraft:calcite_double_slab (new, 2 states), minecraft:calcite_pillar (new, 3 states), minecraft:calcite_slab (new, 2 states), minecraft:calcite_stairs (new, 40 states), minecraft:calcite_wall (new, 162 states), minecraft:chiseled_packed_ice (new, 1 states), minecraft:chiseled_soulshale (new, 1 states), minecraft:packed_ice_brick_double_slab (new, 2 states), minecraft:packed_ice_brick_slab (new, 2 states), minecraft:packed_ice_brick_stairs (new, 40 states), minecraft:packed_ice_brick_wall (new, 162 states), minecraft:packed_ice_bricks (new, 1 states), minecraft:polished_calcite (new, 1 states), minecraft:polished_calcite_brick_double_slab (new, 2 states), minecraft:polished_calcite_brick_slab (new, 2 states), minecraft:polished_calcite_brick_stairs (new, 40 states), minecraft:polished_calcite_brick_wall (new, 162 states), minecraft:polished_calcite_bricks (new, 1 states), minecraft:polished_calcite_double_slab (new, 2 states), minecraft:polished_calcite_slab (new, 2 states), minecraft:polished_calcite_stairs (new, 40 states), minecraft:polished_calcite_wall (new, 162 states), minecraft:snow_brick_double_slab (new, 2 states), minecraft:snow_brick_slab (new, 2 states), minecraft:snow_brick_stairs (new, 40 states), minecraft:snow_brick_wall (new, 162 states), minecraft:snow_bricks (new, 1 states); removed [minecraft:disc_press] |
| DIFF | vanilla capture | hashed block ids; protocol 2250, decoded with the 2225 codec (no codec for 2250 in the protocol library: update it and rerun) |
| PASS | Block hashes (vanilla) | all 1087 creative block ids are FNV-1a 32 hashes of palette states |
| DIFF | vanilla capture (sequential ids) | sequential block ids; protocol 2250, decoded with the 2225 codec (no codec for 2250 in the protocol library: update it and rerun) |
| DIFF | add-on capture | hashed block ids; protocol 2250, decoded with the 2225 codec (no codec for 2250 in the protocol library: update it and rerun) |
| PASS | Block hashes (add-on) | all 2300 creative block ids are FNV-1a 32 hashes of palette states |
| INFO | Experiments echoed by BDS | upcoming_creator_features=true, gametest=true, experimental_creator_cameras=true, data_driven_vanilla_blocks_and_items=true |
| DIFF | add-on capture (sequential ids) | sequential block ids; protocol 2250, decoded with the 2225 codec (no codec for 2250 in the protocol library: update it and rerun) |
| PASS | Block numbering | reference palette 26.60+: 25047 states in FNV-1 64 name order |
| PASS | Block numbering | vanilla: 1041 sequential creative block ids match the FNV-1 64 name sort of 25047 states |
| PASS | Block numbering | add-on: 1104 sequential creative block ids match the FNV-1 64 name sort of 27433 states |
| PASS | Block numbering | add-on: 66 custom block ids are 10000+ in descending identifier order |
| PASS | Block numbering | add-on: 65 custom block items have runtime id 255 - block_id |
| DIFF | Script API vs baseline 1.26.63.1 | 102 differences |
| DIFF | New protocol 2250 | not bundled yet; 188 differences to 26.60 |
| INFO | Fallbacks | new content falls back to minecraft:unknown in older versions (network); curated fallbacks are optional, see the list |
| INFO | Import | preview not imported (pass --import-preview to bundle it as a preview version) |
| PASS | Add-on content log | 4 known rejections/warnings, unchanged |
| DIFF | Component shapes | add-on components vs bds-1.26.63.1-addon: 2 wire format changes, 0 new components, 0 notes |
| DIFF | Component shapes | vanilla components vs bundled 26.60: 2 wire format changes, 1 new components, 2 notes |
| DIFF | Spec coverage | 2 shapes not in the spec |
| DIFF | Add-on capture vs codegen/captures | `codegen\captures\bds-1.26.70.23-addon` is missing or differs |
| INFO | Add-on capture | preview capture not copied to codegen/captures (pass --import-preview) |

## TODO (needs a decision)

- [ ] Block palette: the states of 31 blocks were generated from the Script API. Their order within each block is a guess that only creative stacks verify (sequential ids of other blocks do not depend on it): minecraft:scaffolding (16 -> 32 states), minecraft:powered_repeater (16 -> 32 states), minecraft:unpowered_repeater (16 -> 32 states), minecraft:brittle_ice (new, 4 states), minecraft:calcite_double_slab (new, 2 states), minecraft:calcite_pillar (new, 3 states), minecraft:calcite_slab (new, 2 states), minecraft:calcite_stairs (new, 40 states), minecraft:calcite_wall (new, 162 states), minecraft:chiseled_packed_ice (new, 1 states), minecraft:chiseled_soulshale (new, 1 states), minecraft:packed_ice_brick_double_slab (new, 2 states), minecraft:packed_ice_brick_slab (new, 2 states), minecraft:packed_ice_brick_stairs (new, 40 states), minecraft:packed_ice_brick_wall (new, 162 states), minecraft:packed_ice_bricks (new, 1 states), minecraft:polished_calcite (new, 1 states), minecraft:polished_calcite_brick_double_slab (new, 2 states), minecraft:polished_calcite_brick_slab (new, 2 states), minecraft:polished_calcite_brick_stairs (new, 40 states), minecraft:polished_calcite_brick_wall (new, 162 states), minecraft:polished_calcite_bricks (new, 1 states), minecraft:polished_calcite_double_slab (new, 2 states), minecraft:polished_calcite_slab (new, 2 states), minecraft:polished_calcite_stairs (new, 40 states), minecraft:polished_calcite_wall (new, 162 states), minecraft:snow_brick_double_slab (new, 2 states), minecraft:snow_brick_slab (new, 2 states), minecraft:snow_brick_stairs (new, 40 states), minecraft:snow_brick_wall (new, 162 states), minecraft:snow_bricks (new, 1 states)
- [ ] WIRE FORMAT CHANGE in add-on components vs bds-1.26.63.1-addon: review the generated component types (see the top of this report)
- [ ] WIRE FORMAT CHANGE in vanilla components vs bundled 26.60: review the generated component types (see the top of this report)

## Script API: BDS 1.26.70.23 vs baseline 1.26.63.1

**new blocks** (28)

- `minecraft:brittle_ice {age=[0, 1, 2, 3]}`
- `minecraft:calcite_double_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:calcite_pillar {pillar_axis=[x, y, z]}`
- `minecraft:calcite_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:calcite_stairs {minecraft:corner=[inner_left, inner_right, none, outer_left, outer_right], upside_down_bit=[0, 1], weirdo_direction=[0, 1, 2, 3]}`
- `minecraft:calcite_wall {wall_connection_type_east=[none, short, tall], wall_connection_type_north=[none, short, tall], wall_connection_type_south=[none, short, tall], wall_connection_type_west=[none, short, tall], wall_post_bit=[0, 1]}`
- `minecraft:chiseled_packed_ice {}`
- `minecraft:chiseled_soulshale {}`
- `minecraft:packed_ice_brick_double_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:packed_ice_brick_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:packed_ice_brick_stairs {minecraft:corner=[inner_left, inner_right, none, outer_left, outer_right], upside_down_bit=[0, 1], weirdo_direction=[0, 1, 2, 3]}`
- `minecraft:packed_ice_brick_wall {wall_connection_type_east=[none, short, tall], wall_connection_type_north=[none, short, tall], wall_connection_type_south=[none, short, tall], wall_connection_type_west=[none, short, tall], wall_post_bit=[0, 1]}`
- `minecraft:packed_ice_bricks {}`
- `minecraft:polished_calcite {}`
- `minecraft:polished_calcite_brick_double_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:polished_calcite_brick_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:polished_calcite_brick_stairs {minecraft:corner=[inner_left, inner_right, none, outer_left, outer_right], upside_down_bit=[0, 1], weirdo_direction=[0, 1, 2, 3]}`
- `minecraft:polished_calcite_brick_wall {wall_connection_type_east=[none, short, tall], wall_connection_type_north=[none, short, tall], wall_connection_type_south=[none, short, tall], wall_connection_type_west=[none, short, tall], wall_post_bit=[0, 1]}`
- `minecraft:polished_calcite_bricks {}`
- `minecraft:polished_calcite_double_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:polished_calcite_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:polished_calcite_stairs {minecraft:corner=[inner_left, inner_right, none, outer_left, outer_right], upside_down_bit=[0, 1], weirdo_direction=[0, 1, 2, 3]}`
- `minecraft:polished_calcite_wall {wall_connection_type_east=[none, short, tall], wall_connection_type_north=[none, short, tall], wall_connection_type_south=[none, short, tall], wall_connection_type_west=[none, short, tall], wall_post_bit=[0, 1]}`
- `minecraft:snow_brick_double_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:snow_brick_slab {minecraft:vertical_half=[bottom, top]}`
- `minecraft:snow_brick_stairs {minecraft:corner=[inner_left, inner_right, none, outer_left, outer_right], upside_down_bit=[0, 1], weirdo_direction=[0, 1, 2, 3]}`
- `minecraft:snow_brick_wall {wall_connection_type_east=[none, short, tall], wall_connection_type_north=[none, short, tall], wall_connection_type_south=[none, short, tall], wall_connection_type_west=[none, short, tall], wall_post_bit=[0, 1]}`
- `minecraft:snow_bricks {}`

**removed blocks** (1)

- `minecraft:disc_press`

**block state changes** (3)

- `minecraft:powered_repeater: +locked=[0, 1]`
- `minecraft:scaffolding: +bottom=[0, 1]`
- `minecraft:unpowered_repeater: +locked=[0, 1]`

**block default changes** (3)

- `minecraft:powered_repeater: { "direction": 0i, "minecraft:cardinal_direction": "south", "repeater_delay": 0i } -> { "direction": 0i, "locked": 0b, "minecraft:cardinal_direction": "south", "repeater_delay": 0i }`
- `minecraft:scaffolding: { "stability": 0i, "stability_check": 0b } -> { "bottom": 0b, "stability": 0i, "stability_check": 0b }`
- `minecraft:unpowered_repeater: { "direction": 0i, "minecraft:cardinal_direction": "south", "repeater_delay": 0i } -> { "direction": 0i, "locked": 0b, "minecraft:cardinal_direction": "south", "repeater_delay": 0i }`

**new items** (27)

- `minecraft:brittle_ice (stack 64, key tile.brittle_ice.name, components [])`
- `minecraft:calcite_pillar (stack 64, key tile.calcite_pillar.name, components [])`
- `minecraft:calcite_slab (stack 64, key tile.calcite_slab.name, components [])`
- `minecraft:calcite_stairs (stack 64, key tile.calcite_stairs.name, components [])`
- `minecraft:calcite_wall (stack 64, key tile.calcite_wall.name, components [])`
- `minecraft:chiseled_packed_ice (stack 64, key tile.chiseled_packed_ice.name, components [])`
- `minecraft:frostbite_spawn_egg (stack 64, key item.spawn_egg.entity.frostbite.name, components [])`
- `minecraft:ice_ball (stack 16, key item.ice_ball.name, components [])`
- `minecraft:ice_crystal (stack 64, key tile.ice_crystal.name, components [])`
- `minecraft:icicle (stack 64, key tile.icicle.name, components [])`
- `minecraft:music_disc_flurry (stack 1, key item.record.name, components [])`
- `minecraft:packed_ice_brick_slab (stack 64, key tile.packed_ice_brick_slab.name, components [])`
- `minecraft:packed_ice_brick_stairs (stack 64, key tile.packed_ice_brick_stairs.name, components [])`
- `minecraft:packed_ice_brick_wall (stack 64, key tile.packed_ice_brick_wall.name, components [])`
- `minecraft:packed_ice_bricks (stack 64, key tile.packed_ice_bricks.name, components [])`
- `minecraft:polished_calcite (stack 64, key tile.polished_calcite.name, components [])`
- `minecraft:polished_calcite_brick_slab (stack 64, key tile.polished_calcite_brick_slab.name, components [])`
- `minecraft:polished_calcite_brick_stairs (stack 64, key tile.polished_calcite_brick_stairs.name, components [])`
- `minecraft:polished_calcite_brick_wall (stack 64, key tile.polished_calcite_brick_wall.name, components [])`
- `minecraft:polished_calcite_bricks (stack 64, key tile.polished_calcite_bricks.name, components [])`
- `minecraft:polished_calcite_slab (stack 64, key tile.polished_calcite_slab.name, components [])`
- `minecraft:polished_calcite_stairs (stack 64, key tile.polished_calcite_stairs.name, components [])`
- `minecraft:polished_calcite_wall (stack 64, key tile.polished_calcite_wall.name, components [])`
- `minecraft:snow_brick_slab (stack 64, key tile.snow_brick_slab.name, components [])`
- `minecraft:snow_brick_stairs (stack 64, key tile.snow_brick_stairs.name, components [])`
- `minecraft:snow_brick_wall (stack 64, key tile.snow_brick_wall.name, components [])`
- `minecraft:snow_bricks (stack 64, key tile.snow_bricks.name, components [])`

**Script API item components (+ added to, - removed from)** (1)

- `-minecraft:block_actor_dynamic_properties: 1623 items, e.g. [minecraft:acacia_boat, minecraft:acacia_button, minecraft:acacia_chest_boat, minecraft:acacia_door, minecraft:acacia_fence]`

**new item tags** (2)

- `minecraft:is_pickaxe_item_destructible: [minecraft:ice_crystal, minecraft:icicle]`
- `minecraft:sulfur_cube_archetype_cold: [minecraft:blue_ice]`

**item tags membership** (4)

- `minecraft:music_disc: +[minecraft:music_disc_flurry] -[]`
- `minecraft:spawn_egg: +[minecraft:frostbite_spawn_egg] -[]`
- `minecraft:sulfur_cube_archetype_fast_sliding: +[minecraft:chiseled_packed_ice, minecraft:packed_ice_bricks, minecraft:snow_bricks] -[minecraft:blue_ice]`
- `minecraft:sulfur_cube_archetype_slow_bouncy: +[minecraft:calcite_pillar, minecraft:polished_calcite, minecraft:polished_calcite_bricks] -[]`

**block tags membership** (3)

- `minecraft:cornerable_stairs: +[minecraft:calcite_stairs, minecraft:packed_ice_brick_stairs, minecraft:polished_calcite_brick_stairs, minecraft:polished_calcite_stairs, minecraft:snow_brick_stairs] -[]`
- `minecraft:is_pickaxe_item_destructible: +[minecraft:brittle_ice, minecraft:calcite_double_slab, minecraft:calcite_pillar, minecraft:calcite_slab, minecraft:calcite_stairs, minecraft:calcite_wall, minecraft:chiseled_packed_ice, minecraft:chiseled_soulshale, minecraft:packed_ice_brick_double_slab, minecraft:packed_ice_brick_slab, minecraft:packed_ice_brick_stairs, minecraft:packed_ice_brick_wall, minecraft:packed_ice_bricks, minecraft:polished_calcite, minecraft:polished_calcite_brick_double_slab, minecraft:polished_calcite_brick_slab, minecraft:polished_calcite_brick_stairs, minecraft:polished_calcite_brick_wall, minecraft:polished_calcite_bricks, minecraft:polished_calcite_double_slab, minecraft:polished_calcite_slab, minecraft:polished_calcite_stairs, minecraft:polished_calcite_wall] -[]`
- `minecraft:is_shovel_item_destructible: +[minecraft:snow_brick_double_slab, minecraft:snow_brick_slab, minecraft:snow_brick_stairs, minecraft:snow_brick_wall, minecraft:snow_bricks] -[]`

**block to item changes** (30)

- `minecraft:brittle_ice: null -> minecraft:brittle_ice`
- `minecraft:calcite_double_slab: null -> minecraft:calcite_slab`
- `minecraft:calcite_pillar: null -> minecraft:calcite_pillar`
- `minecraft:calcite_slab: null -> minecraft:calcite_slab`
- `minecraft:calcite_stairs: null -> minecraft:calcite_stairs`
- `minecraft:calcite_wall: null -> minecraft:calcite_wall`
- `minecraft:chiseled_packed_ice: null -> minecraft:chiseled_packed_ice`
- `minecraft:chiseled_soulshale: null -> minecraft:chiseled_soulshale`
- `minecraft:ice_crystal: null -> minecraft:ice_crystal`
- `minecraft:icicle: null -> minecraft:icicle`
- `minecraft:packed_ice_brick_double_slab: null -> minecraft:packed_ice_brick_slab`
- `minecraft:packed_ice_brick_slab: null -> minecraft:packed_ice_brick_slab`
- `minecraft:packed_ice_brick_stairs: null -> minecraft:packed_ice_brick_stairs`
- `minecraft:packed_ice_brick_wall: null -> minecraft:packed_ice_brick_wall`
- `minecraft:packed_ice_bricks: null -> minecraft:packed_ice_bricks`
- `minecraft:polished_calcite: null -> minecraft:polished_calcite`
- `minecraft:polished_calcite_brick_double_slab: null -> minecraft:polished_calcite_brick_slab`
- `minecraft:polished_calcite_brick_slab: null -> minecraft:polished_calcite_brick_slab`
- `minecraft:polished_calcite_brick_stairs: null -> minecraft:polished_calcite_brick_stairs`
- `minecraft:polished_calcite_brick_wall: null -> minecraft:polished_calcite_brick_wall`
- `minecraft:polished_calcite_bricks: null -> minecraft:polished_calcite_bricks`
- `minecraft:polished_calcite_double_slab: null -> minecraft:polished_calcite_slab`
- `minecraft:polished_calcite_slab: null -> minecraft:polished_calcite_slab`
- `minecraft:polished_calcite_stairs: null -> minecraft:polished_calcite_stairs`
- `minecraft:polished_calcite_wall: null -> minecraft:polished_calcite_wall`
- `minecraft:snow_brick_double_slab: null -> minecraft:snow_brick_slab`
- `minecraft:snow_brick_slab: null -> minecraft:snow_brick_slab`
- `minecraft:snow_brick_stairs: null -> minecraft:snow_brick_stairs`
- `minecraft:snow_brick_wall: null -> minecraft:snow_brick_wall`
- `minecraft:snow_bricks: null -> minecraft:snow_bricks`

## What changed: 26.70 (BDS 1.26.70.23) vs bundled 26.60

**new blocks** (28)

- `minecraft:brittle_ice (4 states)`
- `minecraft:calcite_double_slab (2 states)`
- `minecraft:calcite_pillar (3 states)`
- `minecraft:calcite_slab (2 states)`
- `minecraft:calcite_stairs (40 states)`
- `minecraft:calcite_wall (162 states)`
- `minecraft:chiseled_packed_ice (1 states)`
- `minecraft:chiseled_soulshale (1 states)`
- `minecraft:packed_ice_brick_double_slab (2 states)`
- `minecraft:packed_ice_brick_slab (2 states)`
- `minecraft:packed_ice_brick_stairs (40 states)`
- `minecraft:packed_ice_brick_wall (162 states)`
- `minecraft:packed_ice_bricks (1 states)`
- `minecraft:polished_calcite (1 states)`
- `minecraft:polished_calcite_brick_double_slab (2 states)`
- `minecraft:polished_calcite_brick_slab (2 states)`
- `minecraft:polished_calcite_brick_stairs (40 states)`
- `minecraft:polished_calcite_brick_wall (162 states)`
- `minecraft:polished_calcite_bricks (1 states)`
- `minecraft:polished_calcite_double_slab (2 states)`
- `minecraft:polished_calcite_slab (2 states)`
- `minecraft:polished_calcite_stairs (40 states)`
- `minecraft:polished_calcite_wall (162 states)`
- `minecraft:snow_brick_double_slab (2 states)`
- `minecraft:snow_brick_slab (2 states)`
- `minecraft:snow_brick_stairs (40 states)`
- `minecraft:snow_brick_wall (162 states)`
- `minecraft:snow_bricks (1 states)`

**removed blocks** (1)

- `minecraft:disc_press`

**blocks with changed states** (3)

- `minecraft:powered_repeater: 16 -> 32 states; state keys [minecraft:cardinal_direction, repeater_delay] -> [locked, minecraft:cardinal_direction, repeater_delay]; e.g. new {
  "minecraft:cardinal_direction": "south",
  "repeater_delay": 0i,
  "locked": 0b
}`
- `minecraft:scaffolding: 16 -> 32 states; state keys [stability, stability_check] -> [bottom, stability, stability_check]; e.g. new {
  "stability": 0i,
  "stability_check": 0b,
  "bottom": 0b
}`
- `minecraft:unpowered_repeater: 16 -> 32 states; state keys [minecraft:cardinal_direction, repeater_delay] -> [locked, minecraft:cardinal_direction, repeater_delay]; e.g. new {
  "minecraft:cardinal_direction": "south",
  "repeater_delay": 0i,
  "locked": 0b
}`

**new items** (30)

- `minecraft:brittle_ice (id -1249)`
- `minecraft:calcite_double_slab (id -1268)`
- `minecraft:calcite_pillar (id -1270)`
- `minecraft:calcite_slab (id -1267)`
- `minecraft:calcite_stairs (id -1266)`
- `minecraft:calcite_wall (id -1269)`
- `minecraft:chiseled_packed_ice (id -1258)`
- `minecraft:chiseled_soulshale (id -1281)`
- `minecraft:ice_ball (id 854, component based)`
- `minecraft:music_disc_flurry (id 839, component based)`
- `minecraft:packed_ice_brick_double_slab (id -1260)`
- `minecraft:packed_ice_brick_slab (id -1256)`
- `minecraft:packed_ice_brick_stairs (id -1257)`
- `minecraft:packed_ice_brick_wall (id -1259)`
- `minecraft:packed_ice_bricks (id -1250)`
- `minecraft:polished_calcite (id -1275)`
- `minecraft:polished_calcite_brick_double_slab (id -1278)`
- `minecraft:polished_calcite_brick_slab (id -1277)`
- `minecraft:polished_calcite_brick_stairs (id -1276)`
- `minecraft:polished_calcite_brick_wall (id -1279)`
- `minecraft:polished_calcite_bricks (id -1280)`
- `minecraft:polished_calcite_double_slab (id -1273)`
- `minecraft:polished_calcite_slab (id -1272)`
- `minecraft:polished_calcite_stairs (id -1271)`
- `minecraft:polished_calcite_wall (id -1274)`
- `minecraft:snow_brick_double_slab (id -1264)`
- `minecraft:snow_brick_slab (id -1263)`
- `minecraft:snow_brick_stairs (id -1262)`
- `minecraft:snow_brick_wall (id -1265)`
- `minecraft:snow_bricks (id -1261)`

**removed items** (1)

- `minecraft:disc_press`

**item runtime id changes** (45)

- `minecraft:apple: 883 -> 887`
- `minecraft:banner_pattern: 850 -> 851`
- `minecraft:black_bundle: 862 -> 866`
- `minecraft:blue_bundle: 863 -> 867`
- `minecraft:boat: 846 -> 847`
- `minecraft:breeze_rod: 879 -> 883`
- `minecraft:brown_bundle: 864 -> 868`
- `minecraft:bundle: 865 -> 869`
- `minecraft:chemistry_table: 839 -> 840`
- `minecraft:colored_torch_bp: 844 -> 845`
- `minecraft:colored_torch_rg: 843 -> 844`
- `minecraft:copper_spear: 855 -> 859`
- `minecraft:cyan_bundle: 866 -> 870`
- `minecraft:diamond_spear: 856 -> 860`
- `minecraft:dye: 849 -> 850`
- `minecraft:end_crystal: 852 -> 853`
- `minecraft:glow_berries: 884 -> 888`
- `minecraft:golden_spear: 857 -> 861`
- `minecraft:gray_bundle: 867 -> 871`
- `minecraft:green_bundle: 868 -> 872`
- `minecraft:hard_stained_glass: 841 -> 842`
- `minecraft:hard_stained_glass_pane: 842 -> 843`
- `minecraft:ice_crystal: -1248 -> 855`
- `minecraft:icicle: -1247 -> 856`
- `minecraft:iron_spear: 858 -> 862`
- `minecraft:light_block: 845 -> 846`
- `minecraft:light_blue_bundle: 869 -> 873`
- `minecraft:light_gray_bundle: 870 -> 874`
- `minecraft:lime_bundle: 871 -> 875`
- `minecraft:magenta_bundle: 872 -> 876`
- `minecraft:netherite_spear: 859 -> 863`
- `minecraft:ominous_trial_key: 880 -> 884`
- `minecraft:orange_bundle: 873 -> 877`
- `minecraft:pink_bundle: 874 -> 878`
- `minecraft:purple_bundle: 875 -> 879`
- `minecraft:red_bundle: 876 -> 880`
- `minecraft:red_shrub: 853 -> 857`
- `minecraft:shelf_mushroom: 854 -> 858`
- `minecraft:spawn_egg: 851 -> 852`
- `minecraft:stone_spear: 860 -> 864`
- `minecraft:trial_key: 881 -> 885`
- `minecraft:white_bundle: 877 -> 881`
- `minecraft:wind_charge: 882 -> 886`
- `minecraft:wooden_spear: 861 -> 865`
- `minecraft:yellow_bundle: 878 -> 882`

**item version/componentBased changes** (2)

- `minecraft:ice_crystal: version 2 -> 1, componentBased false -> true`
- `minecraft:icicle: version 2 -> 1, componentBased false -> true`

**item component data changes** (2)

- `minecraft:ice_crystal: +components={ "item_properties": { "allow_off_hand": 0b, "can_destroy_in_creative": 1b, "creative_category": 2i, "creative_group"...`
- `minecraft:icicle: +components={ "item_properties": { "allow_off_hand": 0b, "can_destroy_in_creative": 1b, "creative_category": 2i, "creative_group"...`

**new data driven blocks** (27)

- `minecraft:calcite_double_slab`
- `minecraft:calcite_pillar`
- `minecraft:calcite_slab`
- `minecraft:calcite_stairs`
- `minecraft:calcite_wall`
- `minecraft:chiseled_packed_ice`
- `minecraft:chiseled_soulshale`
- `minecraft:packed_ice_brick_double_slab`
- `minecraft:packed_ice_brick_slab`
- `minecraft:packed_ice_brick_stairs`
- `minecraft:packed_ice_brick_wall`
- `minecraft:packed_ice_bricks`
- `minecraft:polished_calcite`
- `minecraft:polished_calcite_brick_double_slab`
- `minecraft:polished_calcite_brick_slab`
- `minecraft:polished_calcite_brick_stairs`
- `minecraft:polished_calcite_brick_wall`
- `minecraft:polished_calcite_bricks`
- `minecraft:polished_calcite_double_slab`
- `minecraft:polished_calcite_slab`
- `minecraft:polished_calcite_stairs`
- `minecraft:polished_calcite_wall`
- `minecraft:snow_brick_double_slab`
- `minecraft:snow_brick_slab`
- `minecraft:snow_brick_stairs`
- `minecraft:snow_brick_wall`
- `minecraft:snow_bricks`

**data driven block property changes** (2)

- `minecraft:red_shrub: +components.minecraft:collision_box.shape=; +components.minecraft:collision_box.useVoxelShapes=0`
- `minecraft:shelf_mushroom: +components.minecraft:collision_box.shape=; +components.minecraft:collision_box.useVoxelShapes=0; +permutations[3].components.minecraft:collision_box.shape=; +permutations[3].components.minecraft:collision_box.useVoxelShapes=0; +permutations[4].components.minecraft:collision_box.shape=; +permutations[4].components.minecraft:collision_box.useVoxelShapes=0; +permutations[5].components.minecraft:collision_box.shape=; +permutations[5].components.minecraft:collision_box.useVoxelShapes=0; +permutations[6].components.minecraft:collision_box.shape=; +permutations[6].components.minecraft:collision_box.useVoxelShapes=0`

**creative layout** (41)

- `CONSTRUCTION: 587 -> 608 entries`
- `CONSTRUCTION + itemGroup.name.slab / minecraft:calcite_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION + itemGroup.name.slab / minecraft:packed_ice_brick_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION + itemGroup.name.slab / minecraft:polished_calcite_brick_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION + itemGroup.name.slab / minecraft:polished_calcite_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION + itemGroup.name.slab / minecraft:snow_brick_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:calcite_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:packed_ice_brick_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_calcite_brick_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_calcite_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:snow_brick_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:calcite_pillar { "pillar_axis": "y" }`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:chiseled_packed_ice {}`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:packed_ice_bricks {}`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:polished_calcite {}`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:polished_calcite_bricks {}`
- `CONSTRUCTION + itemGroup.name.stoneBrick / minecraft:snow_bricks {}`
- `CONSTRUCTION + itemGroup.name.walls / minecraft:calcite_wall { "wall_connection_type_east": "none", "wall_connection_type_north": "none", "wall_connection_type_south": "none", "wall_connection_type_west": "none", "wall_post_bit": 0b }`
- `CONSTRUCTION + itemGroup.name.walls / minecraft:packed_ice_brick_wall { "wall_connection_type_east": "none", "wall_connection_type_north": "none", "wall_connection_type_south": "none", "wall_connection_type_west": "none", "wall_post_bit": 0b }`
- `CONSTRUCTION + itemGroup.name.walls / minecraft:polished_calcite_brick_wall { "wall_connection_type_east": "none", "wall_connection_type_north": "none", "wall_connection_type_south": "none", "wall_connection_type_west": "none", "wall_post_bit": 0b }`
- `CONSTRUCTION + itemGroup.name.walls / minecraft:polished_calcite_wall { "wall_connection_type_east": "none", "wall_connection_type_north": "none", "wall_connection_type_south": "none", "wall_connection_type_west": "none", "wall_post_bit": 0b }`
- `CONSTRUCTION + itemGroup.name.walls / minecraft:snow_brick_wall { "wall_connection_type_east": "none", "wall_connection_type_north": "none", "wall_connection_type_south": "none", "wall_connection_type_west": "none", "wall_post_bit": 0b }`
- `CONSTRUCTION + minecraft:scaffolding { "stability": 0i, "stability_check": 0b, "bottom": 0b }`
- `CONSTRUCTION - minecraft:scaffolding { "stability": 0i, "stability_check": 0b }`
- `NATURE: 457 -> 461 entries`
- `NATURE + itemGroup.name.mobEgg / minecraft:frostbite_spawn_egg`
- `NATURE + minecraft:brittle_ice { "age": 0i }`
- `NATURE + minecraft:ice_crystal`
- `NATURE + minecraft:icicle`
- `EQUIPMENT: 389 -> 398 entries`
- `EQUIPMENT + itemGroup.name.arrow / minecraft:arrow@48`
- `EQUIPMENT + itemGroup.name.arrow / minecraft:arrow@49`
- `EQUIPMENT + itemGroup.name.lingeringPotion / minecraft:lingering_potion@47`
- `EQUIPMENT + itemGroup.name.lingeringPotion / minecraft:lingering_potion@48`
- `EQUIPMENT + itemGroup.name.potion / minecraft:potion@47`
- `EQUIPMENT + itemGroup.name.potion / minecraft:potion@48`
- `EQUIPMENT + itemGroup.name.splashPotion / minecraft:splash_potion@47`
- `EQUIPMENT + itemGroup.name.splashPotion / minecraft:splash_potion@48`
- `EQUIPMENT + minecraft:ice_ball`
- `ITEMS: 635 -> 636 entries`
- `ITEMS + itemGroup.name.record / minecraft:music_disc_flurry`

**new item tags** (2)

- `minecraft:is_pickaxe_item_destructible`
- `minecraft:sulfur_cube_archetype_cold`

**item tag membership** (4)

- `minecraft:music_disc: +[minecraft:music_disc_flurry] -[]`
- `minecraft:spawn_egg: +[minecraft:frostbite_spawn_egg] -[]`
- `minecraft:sulfur_cube_archetype_fast_sliding: +[minecraft:chiseled_packed_ice, minecraft:packed_ice_bricks, minecraft:snow_bricks] -[minecraft:blue_ice]`
- `minecraft:sulfur_cube_archetype_slow_bouncy: +[minecraft:calcite_pillar, minecraft:polished_calcite, minecraft:polished_calcite_bricks] -[]`

## Fallbacks (network): content that falls back to minecraft:unknown

Without a curated fallback these fall back to `minecraft:unknown` (block and item) in the older version. Add a target to the file for a better replacement; nothing is required.

- `generator/fallbacks/26.40.json`: 28 blocks `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`; 30 items `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:ice_ball`, `minecraft:music_disc_flurry`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`
- `generator/fallbacks/26.50.json`: 28 blocks `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`; 30 items `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:ice_ball`, `minecraft:music_disc_flurry`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`
- `generator/fallbacks/26.60.json`: 28 blocks `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`; 30 items `minecraft:brittle_ice`, `minecraft:calcite_double_slab`, `minecraft:calcite_pillar`, `minecraft:calcite_slab`, `minecraft:calcite_stairs`, `minecraft:calcite_wall`, `minecraft:chiseled_packed_ice`, `minecraft:chiseled_soulshale`, `minecraft:ice_ball`, `minecraft:music_disc_flurry`, `minecraft:packed_ice_brick_double_slab`, `minecraft:packed_ice_brick_slab`, `minecraft:packed_ice_brick_stairs`, `minecraft:packed_ice_brick_wall`, `minecraft:packed_ice_bricks`, `minecraft:polished_calcite`, `minecraft:polished_calcite_brick_double_slab`, `minecraft:polished_calcite_brick_slab`, `minecraft:polished_calcite_brick_stairs`, `minecraft:polished_calcite_brick_wall`, `minecraft:polished_calcite_bricks`, `minecraft:polished_calcite_double_slab`, `minecraft:polished_calcite_slab`, `minecraft:polished_calcite_stairs`, `minecraft:polished_calcite_wall`, `minecraft:snow_brick_double_slab`, `minecraft:snow_brick_slab`, `minecraft:snow_brick_stairs`, `minecraft:snow_brick_wall`, `minecraft:snow_bricks`

## Component shape notes: vanilla components vs bundled 26.60

- new component `block:minecraft:light_dampening`
- block:block_definition vanilla_block_data.block_archetype: now optional (absent in some observations)
- item:minecraft:max_stack_size value: byte no longer only 0/1

