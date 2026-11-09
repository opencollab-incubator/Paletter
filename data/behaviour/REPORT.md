# Behaviour data report

Written by `behaviour import` (tools); do not edit. Format and lookup rules: `BehaviourStore`.

## Captures

In application order (per version the weakest source first; the strongest decides what it covers).

| Version | Source | Reference | Date | Identified by |
|---|---|---|---|---|
| 26.30 | ALLAY_DATA | `AllayMC/Allay@cf68fe1ff0ab3a9c1d2df6b4af061be4653c9151` | 2026-07-17 | data/resources/unpacked/block_states_raw.json, same block states |
| 26.30 | CLOUDBURST_DATA | `CloudburstMC/Data@fb969c547236d87a17181941cd585a0eb18f7ceb` | 2026-07-15 | block_properties.json, same block states |
| 26.30 | SCRIPT_API | `Paletter data/bds/1.26.36.1/dump.json.gz` |  | BDS 1.26.36.1 |
| 26.40 | CLOUDBURST_DATA | `CloudburstMC/Data@7046791ae9fcf056a3b26cd6605ffc1db6d97357` | 2026-08-31 | blocks.json, same block states |
| 26.40 | SCRIPT_API | `Paletter data/bds/1.26.45.1/dump.json.gz` |  | BDS 1.26.45.1 |
| 26.50 | ALLAY_DATA | `AllayMC/Allay@f8c660ed3a7f773aec11536cb958c0433ecef9d6` | 2026-10-24 | data/resources/unpacked/block_states_raw.json, same block states |
| 26.50 | CLOUDBURST_DATA | `CloudburstMC/Data@88a2436a2d5c605da082a00fee5f103f888a9160` | 2026-10-20 | blocks.json, same block states |
| 26.50 | SCRIPT_API | `Paletter data/bds/1.26.52.3/dump.json.gz` |  | BDS 1.26.52.3 |
| 26.60 | CLOUDBURST_DATA | `CloudburstMC/Data@0021592b1a4253528bad1e1d8b537e306e097557` | 2026-10-28 | blocks.json, same block states |
| 26.60 | SCRIPT_API | `Paletter data/bds/1.26.63.1/dump.json.gz` |  | BDS 1.26.63.1 |

## Coverage

Per key and version: who decides the values of the version's block states / items. `inherited from V` means no capture of the version covers them and the value of version V applies.

| Key | 26.30 | 26.40 | 26.50 | 26.60 |
|---|---|---|---|---|
| `block:destroy_time` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:explosion_resistance` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:friction` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:solid` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:collision_shape` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:outline_shape` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:light_emission` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:light_dampening` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:can_contain_liquid` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:liquid_reaction` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:requires_correct_tool` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:map_color` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:tint_method` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:flame_odds` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:burn_odds` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:translucency` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:thickness` | CLOUDBURST_DATA 16913<br> | CLOUDBURST_DATA 17499<br> | CLOUDBURST_DATA 22091<br> | CLOUDBURST_DATA 23960<br> |
| `block:climbable` | none 16913<br> | none 17499<br> | none 22091<br> | none 23960<br> |
| `item:max_stack_size` | ALLAY_DATA 415<br>SCRIPT_API 1518<br> | SCRIPT_API 1518<br>inherited from 26.30 ALLAY_DATA 415<br>none 43<br> | ALLAY_DATA 453<br>SCRIPT_API 1623<br> | SCRIPT_API 1623<br>inherited from 26.50 ALLAY_DATA 453<br>none 4<br> |
| `item:max_damage` | ALLAY_DATA 415<br>SCRIPT_API 1518<br> | SCRIPT_API 1518<br>inherited from 26.30 ALLAY_DATA 415<br>none 43<br> | ALLAY_DATA 453<br>SCRIPT_API 1623<br> | SCRIPT_API 1623<br>inherited from 26.50 ALLAY_DATA 453<br>none 4<br> |
| `item:attack_damage` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:armor_value` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:toughness` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:enchant_value` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:furnace_burn_duration` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:furnace_xp_multiplier` | ALLAY_DATA 1933<br> | inherited from 26.30 ALLAY_DATA 1933<br>none 43<br> | ALLAY_DATA 2076<br> | inherited from 26.50 ALLAY_DATA 2076<br>none 4<br> |
| `item:use_duration` | none 1933<br> | none 1976<br> | none 2076<br> | none 2080<br> |
| `item:use_animation` | none 1933<br> | none 1976<br> | none 2076<br> | none 2080<br> |

Keys no dump has:

- `block:climbable`: no source: needs the native capture (BEHAVIOUR-DATA-PLAN section 6)
- `item:use_animation`: read at runtime from the component data (WIRE) of component based items; native items need the native capture
- `item:use_duration`: read at runtime from the component data (WIRE) of component based items; native items (bow, trident, potion) need the native capture

## Disagreements

Every pair of captures of the same version, on the ids both cover. The stronger source is stored; check the other.

### 26.30: ALLAY_DATA vs CLOUDBURST_DATA

| Key | Compared | Different | Examples (ALLAY_DATA / CLOUDBURST_DATA) |
|---|---|---|---|
| `block:collision_shape` | 16913 | 672 | warped_door[door_hinge_bit=false,cardinal_direction=south,open_bit=false,upper_block_bit=false] (3763596343): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 0.1875, 1.0, 1.0]<br>warped_door[door_hinge_bit=false,cardinal_direction=west,open_bit=false,upper_block_bit=false] (3673010788): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 1.0, 1.0, 0.1875]<br>warped_door[door_hinge_bit=false,cardinal_direction=north,open_bit=false,upper_block_bit=false] (382742353): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.8125, 0.0, 0.0, 1.0, 1.0, 1.0]<br>in acacia_door 32, bamboo_door 32, birch_door 32, cherry_door 32, copper_door 32, crimson_door 32, dark_oak_door 32, exposed_copper_door 32, iron_door 32, jungle_door 32, mangrove_door 32, oxidized_copper_door 32, pale_oak_door 32, spruce_door 32, warped_door 32, waxed_copper_door 32, waxed_exposed_copper_door 32, waxed_oxidized_copper_door 32, waxed_weathered_copper_door 32, weathered_copper_door 32, wooden_door 32 |
| `block:outline_shape` | 16913 | 672 | warped_door[door_hinge_bit=false,cardinal_direction=south,open_bit=false,upper_block_bit=false] (3763596343): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 0.1875, 1.0, 1.0]<br>warped_door[door_hinge_bit=false,cardinal_direction=west,open_bit=false,upper_block_bit=false] (3673010788): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 1.0, 1.0, 0.1875]<br>warped_door[door_hinge_bit=false,cardinal_direction=north,open_bit=false,upper_block_bit=false] (382742353): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.8125, 0.0, 0.0, 1.0, 1.0, 1.0]<br>in acacia_door 32, bamboo_door 32, birch_door 32, cherry_door 32, copper_door 32, crimson_door 32, dark_oak_door 32, exposed_copper_door 32, iron_door 32, jungle_door 32, mangrove_door 32, oxidized_copper_door 32, pale_oak_door 32, spruce_door 32, warped_door 32, waxed_copper_door 32, waxed_exposed_copper_door 32, waxed_oxidized_copper_door 32, waxed_weathered_copper_door 32, weathered_copper_door 32, wooden_door 32 |

### 26.50: ALLAY_DATA vs CLOUDBURST_DATA

| Key | Compared | Different | Examples (ALLAY_DATA / CLOUDBURST_DATA) |
|---|---|---|---|
| `block:collision_shape` | 22091 | 736 | warped_door[door_hinge_bit=false,cardinal_direction=south,open_bit=false,upper_block_bit=false] (3763596343): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 0.1875, 1.0, 1.0]<br>warped_door[door_hinge_bit=false,cardinal_direction=west,open_bit=false,upper_block_bit=false] (3673010788): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 1.0, 1.0, 0.1875]<br>warped_door[door_hinge_bit=false,cardinal_direction=north,open_bit=false,upper_block_bit=false] (382742353): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.8125, 0.0, 0.0, 1.0, 1.0, 1.0]<br>in acacia_door 32, bamboo 12, bamboo_door 32, birch_door 32, cherry_door 32, copper_door 32, crimson_door 32, dark_oak_door 32, exposed_copper_door 32, iron_door 32, jungle_door 32, mangrove_door 32, oxidized_copper_door 32, pale_oak_door 32, pointed_dripstone 10, poplar_door 32, spruce_door 32, sulfur_spike 10, warped_door 32, waxed_copper_door 32, waxed_exposed_copper_door 32, waxed_oxidized_copper_door 32, waxed_weathered_copper_door 32, weathered_copper_door 32, wooden_door 32 |
| `block:outline_shape` | 22091 | 817 | warped_door[door_hinge_bit=false,cardinal_direction=south,open_bit=false,upper_block_bit=false] (3763596343): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 0.1875, 1.0, 1.0]<br>warped_door[door_hinge_bit=false,cardinal_direction=west,open_bit=false,upper_block_bit=false] (3673010788): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.0, 0.0, 0.0, 1.0, 1.0, 0.1875]<br>warped_door[door_hinge_bit=false,cardinal_direction=north,open_bit=false,upper_block_bit=false] (382742353): [0.0, 0.0, 0.0, 0.1825, 1.0, 1.0] / [0.8125, 0.0, 0.0, 1.0, 1.0, 1.0]<br>in acacia_door 32, allium 1, azure_bluet 1, bamboo 12, bamboo_door 32, bamboo_sapling 2, birch_door 32, blue_orchid 1, brain_coral 1, brain_coral_fan 2, bubble_coral 1, bubble_coral_fan 2, cherry_door 32, closed_eyeblossom 1, copper_door 32, cornflower 1, crimson_door 32, crimson_roots 1, dandelion 1, dark_oak_door 32, dead_brain_coral 1, dead_brain_coral_fan 2, dead_bubble_coral 1, dead_bubble_coral_fan 2, dead_fire_coral 1, dead_fire_coral_fan 2, dead_horn_coral 1, dead_horn_coral_fan 2, dead_tube_coral 1, dead_tube_coral_fan 2, exposed_copper_door 32, fern 1, fire_coral 1, fire_coral_fan 2, golden_dandelion 1, hanging_roots 1, horn_coral 1, horn_coral_fan 2, iron_door 32, jungle_door 32, ... (79 in all) |

## Unmapped source fields

Fields a source has that no key reads (add a key and a line in `EndstoneDump`, or ignore them there).

- CLOUDBURST_DATA block geometry.shapeSource (1408)

## Log

- CLOUDBURST_DATA 0021592b1 2026-10-28 (More 26.60 data): 26.60, blocks.json, same block states
- CLOUDBURST_DATA 88a2436a2 2026-10-20 (Restructure block geometry data and include native position samples): 26.50, blocks.json, same block states
- CLOUDBURST_DATA 659ce1e2e 2026-09-28 (Fix bad data dump): 26.50, older than the commit used
- CLOUDBURST_DATA e5c84599b 2026-09-16 (Update data-driven block definitions): 26.50, older than the commit used
- CLOUDBURST_DATA f5d93ee2d 2026-09-16 (More 26.50 data): 26.50, older than the commit used
- CLOUDBURST_DATA 7046791ae 2026-08-31 (More 26.50 data): 26.40, blocks.json, same block states
- CLOUDBURST_DATA 247e816d1 2026-08-12 (Add missing data from 1.26.40 (2168)): 26.40, older than the commit used
- CLOUDBURST_DATA fb969c547 2026-07-15 (Update generated block properties): 26.30, block_properties.json, same block states
- CLOUDBURST_DATA 6ce0e699c 2026-06-29 (Update missing data for 1.26.30 (1001)): 26.30, older than the commit used
- CLOUDBURST_DATA ca99088d0 2026-05-19 (Update missing data for 1.26.20 (975)): 26.30, older than the commit used
- CLOUDBURST_DATA 96e312da2 2026-03-31 (Update block properties for 1.26.10 (944)): matches no bundled version
- ALLAY_DATA f8c660ed3 2026-10-24 (chore: checkpoint MCBE 1.26.50 feature upgrade): 26.50, data/resources/unpacked/block_states_raw.json, same block states
- ALLAY_DATA cf68fe1ff 2026-07-17 (feat: 1.26.30  (#1813)): 26.30, data/resources/unpacked/block_states_raw.json, same block states
- ALLAY_DATA 987669b14 2026-04-07 (feat: update feature version to 1.26.10): matches no bundled version
