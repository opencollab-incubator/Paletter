# Paletter data update: BDS 1.26.52.3

- **Date**: 2026-11-09
- **Mode**: update
- **BDS**: 1.26.52.3 win (https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.52.3.zip)
- **Work directory**: `\c\Users\onebe\AppData\Local\Temp\claude\63432dece04b2fdf\ade55d3c-935a-44e6-a3c2-4cec7df9f030\scratchpad\bds\runs\win-1.26.52.3` (logs, captures)
- **Server**: `\c\Users\onebe\AppData\Local\Temp\claude\63432dece04b2fdf\ade55d3c-935a-44e6-a3c2-4cec7df9f030\scratchpad\bds\servers\win-1.26.52.3` (removed after the run)
- **Protocol**: 2193 (RakNet pong: 1.26.52)
- **Transport**: RakNet
- **Bundled version**: 26.50 (existing, same protocol)
- **Result**: FAILED

## WIRE FORMAT CHANGES

> **Component NBT that BDS sends changed shape.** Generated component types encode the old shape; review the component model (data/model) and the generated types before shipping this version.

- **add-on components vs 1.26.63.1: block:block_definition: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:collision_box: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:connection_rule: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:crafting_table: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:destructible_by_mining: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:destruction_particles: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:display_name: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:embedded_visual: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:flower_pottable: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:friction: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:geometry: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:item_visual: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:leashable: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:light_dampening: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:light_emission: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:liquid_detection: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:material_instances: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:placement_filter: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:precipitation_interactions: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:random_offset: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:redstone_conductivity: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:redstone_producer: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:replaceable: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:selection_box: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:sound: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:support: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:transformation: no longer sent**
- **add-on components vs 1.26.63.1: item:item_properties: no longer sent**
- **add-on components vs 1.26.63.1: item:item_tags: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:allow_off_hand: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:block_placer: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:bundle_interaction: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:can_destroy_in_creative: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:compostable: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:cooldown: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:damage: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:digger: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:display_name: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:durability: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:dyeable: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:enchantable: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:entity_placer: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:fire_resistant: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:food: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:fuel: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:glint: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:hand_equipped: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:hover_text_color: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:interact_button: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:kinetic_weapon: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:liquid_clipped: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:max_stack_size: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:piercing_weapon: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:projectile: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:publisher_on_use_on: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:rarity: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:record: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:repairable: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:shooter: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:should_despawn: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:stacked_by_data: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:storage_item: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:storage_weight_limit: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:storage_weight_modifier: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:swing_duration: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:swing_sounds: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:tags: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:throwable: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:use_animation: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:use_modifiers: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:wearable: no longer sent**

## Checks

| Status | Check | Detail |
|---|---|---|
| PASS | Script API dump | BDS reports 1.26.52.3; 1623 items, 1477 blocks, 77 item tags |
| PASS | vanilla capture | hashed block ids; protocol 2193 |
| PASS | Block hashes (vanilla) | all 1065 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | vanilla capture (sequential ids) | sequential block ids; protocol 2193 |
| PASS | Experiments: discovery | 48703 candidates (binary bedrock_server.exe, experimental packs, known ids); BDS kept 30 toggles in level.dat and logged as many active |
| PASS | experiments capture (all toggles) | hashed block ids; protocol 2193 |
| PASS | Experiments: current toggles | 7 streamed in start game [creator_world_clocks, experimental_creator_cameras, gametest, poi, upcoming_creator_features, villager_trades_rebalance, voxel_shapes], 23 retired (kept, not streamed); 7 `createWorldScreen.experimental*` UI keys in the binary |
| PASS | Experiments: resource pack stack | lists the same toggles as start game |
| PASS | experiments capture (creator_world_clocks) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (creator_world_clocks) | start game reports [creator_world_clocks] |
| PASS | experiments capture (experimental_creator_cameras) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (experimental_creator_cameras) | start game reports [experimental_creator_cameras] |
| PASS | experiments capture (gametest) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (gametest) | start game reports [gametest] |
| PASS | experiments capture (poi) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (poi) | start game reports [poi] |
| PASS | experiments capture (upcoming_creator_features) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (upcoming_creator_features) | start game reports [upcoming_creator_features] |
| PASS | experiments capture (villager_trades_rebalance) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (villager_trades_rebalance) | start game reports [villager_trades_rebalance] |
| PASS | experiments capture (voxel_shapes) | hashed block ids; protocol 2193 |
| PASS | Experiments applied (voxel_shapes) | start game reports [voxel_shapes] |
| PASS | add-on capture | hashed block ids; protocol 2193 |
| PASS | Block hashes (add-on) | all 2130 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | Experiments applied (add-on world) | start game reports [creator_world_clocks, experimental_creator_cameras, gametest, poi, upcoming_creator_features, villager_trades_rebalance, voxel_shapes] |
| PASS | add-on capture (sequential ids) | sequential block ids; protocol 2193 |
| PASS | creative order probe capture | hashed block ids; protocol 2193 |
| PASS | Block numbering | reference palette 26.50: 22091 states in FNV-1 64 name order |
| PASS | Block numbering | vanilla: 1019 sequential creative block ids match the FNV-1 64 name sort of 22091 states |
| PASS | Block numbering | add-on: 1019 sequential creative block ids match the FNV-1 64 name sort of 22091 states |
| FAIL | Block numbering | add-on: no custom blocks in the capture |
| DIFF | Script API vs baseline dump | 10 differences |
| PASS | Network data vs bundled 26.50 | identical (items, runtime ids, components, data driven blocks, creative layout) |
| PASS | Item tags and block defaults vs bundled 26.50 | identical |
| DIFF | Add-on content log | 4 changes in what BDS rejects or warns about in data/addons/capture |
| INFO | Experiments: creator docs | the docs name experiments this BDS does not have: [Holiday Creator Features] |
| PASS | Experiment marks vs bundled 26.50 | identical |
| PASS | Creative order (add-on) | CreativeOrdering.BDS reproduces the creative inventory BDS sent (0 custom stacks) |
| PASS | Creative order (creative order probe) | CreativeOrdering.BDS reproduces the creative inventory BDS sent (0 custom stacks) |
| DIFF | Component shapes | add-on components vs 1.26.63.1: 71 wire format changes, 0 new components |
| PASS | Component shapes | vanilla components vs bundled 26.50: identical |
| PASS | Spec coverage | every captured component shape is in data/model/mappings.json |
| DIFF | Add-on capture vs data\bds | `data\bds\1.26.52.3\addon` is missing or differs |
| INFO | Component model | new add-on capture staged; run the model checks (checkMappings, checkComponentApi) and review the diff |
| PASS | Vanilla constants | 0 added, 0 deprecated, 0 blocks with a lookup table |

## TODO (needs a decision)

- [ ] WIRE FORMAT CHANGE in add-on components vs 1.26.63.1: review the component model and the generated component types (see the top of this report)

## Repository files

- add `data/bds/1.26.52.3/dump.json.gz` (Script API baseline 1.26.52.3)
- update `data/addons/capture/content-log.txt` (add-on content log)
- add `data/bds/1.26.52.3/experiments.json` (experiment record)
- add `data/bds/1.26.52.3/addon/custom_block_properties.nbt` (component capture custom_block_properties.nbt)
- add `data/bds/1.26.52.3/addon/custom_item_registry.nbt` (component capture custom_item_registry.nbt)

## Script API: BDS 1.26.52.3 vs baseline dump

**removed blocks** (3)

- `minecraft:disc_press`
- `minecraft:ice_crystal`
- `minecraft:icicle`

**block state changes** (3)

- `minecraft:chorus_plant: -minecraft:connection_down; -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_up; -minecraft:connection_west`
- `minecraft:fire: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_up; -minecraft:connection_west`
- `minecraft:redstone_wire: -redstone_east; -redstone_north; -redstone_south; -redstone_west`

**block default changes** (3)

- `minecraft:chorus_plant: { "minecraft:connection_down": 0b, "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_up": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:fire: { "age": 0i, "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_up": 0b, "minecraft:connection_west": 0b } -> { "age": 0i }`
- `minecraft:redstone_wire: { "redstone_east": "none", "redstone_north": "none", "redstone_signal": 0i, "redstone_south": "none", "redstone_west": "none" } -> { "redstone_signal": 0i }`

**block tags membership** (1)

- `minecraft:is_pickaxe_item_destructible: +[] -[minecraft:ice_crystal, minecraft:icicle]`

## Add-on content log changes

- gone: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/collision_box_box.json |  -> components -> minecraft:collision_box: expected an array`
- gone: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/collision_box_box.json |  -> components -> minecraft:collision_box: invalid numeric value`
- gone: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/tag_legacy.json |  -> components -> tag:paletter:legacy_tag: this component was found in the input, but is not present in the Schema`
- gone: `WARN [Components] All MaterialInstances must use the same render_method for a given block. This may create rendering issues otherwise.`

## Experiments

Source: **discovered** (48703 candidates), attribution: each, 120 s for this step.

| Toggle | Status | Log code | UI key | Name | Items | Blocks | Creative stacks |
|---|---|---|---|---|---|---|---|
| `creator_world_clocks` | current | cwcl | `createWorldScreen.experimentalCreatorWorldClocksFeatures` | Creator World Clocks Features | 0 | 0 | 0 |
| `experimental_creator_cameras` | current | exca | `createWorldScreen.experimentalCreatorCameraFeatures` | Experimental Creator Camera Features | 0 | 0 | 0 |
| `gametest` | current | gtst | `createWorldScreen.experimentalGameTest` | Beta APIs | 0 | 0 | 0 |
| `poi` | current | epoi | `createWorldScreen.experimentalPoi` | Points of Interest (POI) | 0 | 0 | 0 |
| `upcoming_creator_features` | current | ucft | `createWorldScreen.experimentalCreatorFeatures` | Upcoming Creator Features | 0 | 0 | 0 |
| `villager_trades_rebalance` | current | vtra | `createWorldScreen.experimentalVillagerTradesRebalance` | Villager Trade Rebalancing | 0 | 0 | 0 |
| `voxel_shapes` | current | vavs | `createWorldScreen.experimentalVoxelShapeFeatures` | Experimental Voxel Shape Features | 0 | 0 | 0 |
| `armadillo` | retired | | | | | | |
| `camera_aim_assist` | retired | | | | | | |
| `caves_and_cliffs` | retired | | | | | | |
| `custom_projectiles` | retired | | | | | | |
| `data_driven_biomes` | retired | | | | | | |
| `data_driven_items` | retired | | | | | | |
| `experimental_molang_features` | retired | | | | | | |
| `furnace_recipe_book` | retired | | | | | | |
| `jigsaw_structures` | retired | | | | | | |
| `locator_bar` | retired | | | | | | |
| `next_major_update` | retired | | | | | | |
| `scripting` | retired | | | | | | |
| `sniffer` | retired | | | | | | |
| `spectator_mode` | retired | | | | | | |
| `updateAnnouncedLive2023` | retired | | | | | | |
| `vanilla_experiments` | retired | | | | | | |
| `wild_update` | retired | | | | | | |
| `y_2025_drop_1` | retired | | | | | | |
| `y_2025_drop_2` | retired | | | | | | |
| `y_2025_drop_3` | retired | | | | | | |
| `y_2026_drop_1` | retired | | | | | | |
| `y_2026_drop_2` | retired | | | | | | |
| `y_2026_drop_3` | retired | | | | | | |

**creator_world_clocks**: no item, block or creative change

**experimental_creator_cameras**: no item, block or creative change

**gametest**: no item, block or creative change

**poi**: no item, block or creative change

**upcoming_creator_features**: no item, block or creative change

**villager_trades_rebalance**: no item, block or creative change

**voxel_shapes**: no item, block or creative change

**All toggles together**: no change

Experiments the creator docs name (`data/model/components.json`):

- Holiday Creator Features: **no toggle of this BDS**
- Upcoming Creator Features: `upcoming_creator_features`
- experimental (toggle not named): **no toggle of this BDS**

## Vanilla constants

From 26.60: {BedrockStates=124, state enums=24, BedrockBlocks=1480, BedrockItems=2080}

