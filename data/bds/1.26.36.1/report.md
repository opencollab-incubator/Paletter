# Paletter data update: BDS 1.26.36.1

- **Date**: 2026-11-09
- **Mode**: update
- **BDS**: 1.26.36.1 win (https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.26.36.1.zip)
- **Work directory**: `C:\Users\onebe\AppData\Local\Temp\claude\63432dece04b2fdf\ade55d3c-935a-44e6-a3c2-4cec7df9f030\scratchpad\bds\runs\win-1.26.36.1` (logs, captures)
- **Server**: `C:\Users\onebe\AppData\Local\Temp\claude\63432dece04b2fdf\ade55d3c-935a-44e6-a3c2-4cec7df9f030\scratchpad\bds\servers\win-1.26.36.1` (removed after the run)
- **Protocol**: 1001 (RakNet pong: 1.26.36)
- **Transport**: RakNet
- **Bundled version**: 26.30 (NEW protocol)
- **Result**: DIFFERENCES

## WIRE FORMAT CHANGES

> **Component NBT that BDS sends changed shape.** Generated component types encode the old shape; review the component model (data/model) and the generated types before shipping this version.

- **add-on components vs 1.26.63.1: block:block_definition traits[].place_behavior: field removed (was byte)**
- **add-on components vs 1.26.63.1: block:minecraft:geometry bone_visibility.bone_a: field removed (was string)**
- **add-on components vs 1.26.63.1: block:minecraft:geometry bone_visibility.bone_b: field removed (was string)**
- **add-on components vs 1.26.63.1: block:minecraft:replaceable: no longer sent**
- **add-on components vs 1.26.63.1: block:minecraft:sound: no longer sent**
- **add-on components vs 1.26.63.1: item:minecraft:repairable repair_items[].repair_amount.expression: new field (string)**
- **add-on components vs 1.26.63.1: item:minecraft:repairable repair_items[].repair_amount.version: new field (short)**
- **vanilla components vs bundled 26.60: block:block_definition: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:collision_box: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:connection_rule: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:destructible_by_mining: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:destruction_particles: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:geometry: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:liquid_detection: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:material_instances: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:precipitation_interactions: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:redstone_conductivity: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:replaceable: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:selection_box: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:support: no longer sent**
- **vanilla components vs bundled 26.60: block:minecraft:transformation: no longer sent**
- **vanilla components vs bundled 26.60: item:minecraft:block_placer: no longer sent**
- **vanilla components vs bundled 26.60: item:minecraft:compostable: no longer sent**
- **vanilla components vs bundled 26.60: item:minecraft:publisher_on_use_on: no longer sent**
- **vanilla components vs bundled 26.60: item:minecraft:repairable repair_items[].repair_amount: type string -> compound**
- **vanilla components vs bundled 26.60: item:minecraft:repairable repair_items[].repair_amount.expression: new field (string)**
- **vanilla components vs bundled 26.60: item:minecraft:repairable repair_items[].repair_amount.version: new field (short)**

## Checks

| Status | Check | Detail |
|---|---|---|
| PASS | Script API dump | BDS reports 1.26.36.1; 1518 items, 1356 blocks, 76 item tags |
| INFO | Reference palette | 26.60 adjusted with the Script API: generated minecraft:blue_stained_glass_pane (16 -> 1 states), minecraft:nether_brick_stairs (40 -> 8 states), minecraft:spruce_stairs (40 -> 8 states), minecraft:hard_green_stained_glass_pane (16 -> 1 states), minecraft:yellow_stained_glass_pane (16 -> 1 states), minecraft:cobbled_deepslate_stairs (40 -> 8 states), minecraft:prismarine_bricks_stairs (40 -> 8 states), minecraft:hard_orange_stained_glass_pane (16 -> 1 states), minecraft:orange_stained_glass_pane (16 -> 1 states), minecraft:hard_black_stained_glass_pane (16 -> 1 states), minecraft:oak_stairs (40 -> 8 states), minecraft:brown_stained_glass_pane (16 -> 1 states), minecraft:polished_deepslate_stairs (40 -> 8 states), minecraft:oxidized_cut_copper_stairs (40 -> 8 states), minecraft:pale_oak_fence (16 -> 1 states), minecraft:jungle_fence (16 -> 1 states), minecraft:spruce_fence (16 -> 1 states), minecraft:waxed_cut_copper_stairs (40 -> 8 states), minecraft:light_gray_stained_glass_pane (16 -> 1 states), minecraft:waxed_copper_bars (16 -> 1 states), minecraft:bamboo_fence (16 -> 1 states), minecraft:normal_stone_stairs (40 -> 8 states), minecraft:hard_glass_pane (16 -> 1 states), minecraft:bamboo_stairs (40 -> 8 states), minecraft:stone_brick_stairs (40 -> 8 states), minecraft:cherry_fence (16 -> 1 states), minecraft:oxidized_copper_bars (16 -> 1 states), minecraft:hard_brown_stained_glass_pane (16 -> 1 states), minecraft:black_stained_glass_pane (16 -> 1 states), minecraft:granite_stairs (40 -> 8 states), minecraft:sandstone_stairs (40 -> 8 states), minecraft:smooth_sandstone_stairs (40 -> 8 states), minecraft:stone_stairs (40 -> 8 states), minecraft:warped_stairs (40 -> 8 states), minecraft:redstone_wire (1296 -> 16 states), minecraft:light_blue_stained_glass_pane (16 -> 1 states), minecraft:waxed_exposed_cut_copper_stairs (40 -> 8 states), minecraft:mossy_cobblestone_stairs (40 -> 8 states), minecraft:green_stained_glass_pane (16 -> 1 states), minecraft:polished_granite_stairs (40 -> 8 states), minecraft:waxed_exposed_copper_bars (16 -> 1 states), minecraft:sulfur_stairs (40 -> 8 states), minecraft:hard_yellow_stained_glass_pane (16 -> 1 states), minecraft:nether_brick_fence (16 -> 1 states), minecraft:polished_blackstone_stairs (40 -> 8 states), minecraft:weathered_cut_copper_stairs (40 -> 8 states), minecraft:polished_sulfur_stairs (40 -> 8 states), minecraft:copper_bars (16 -> 1 states), minecraft:diorite_stairs (40 -> 8 states), minecraft:polished_blackstone_brick_stairs (40 -> 8 states), minecraft:white_stained_glass_pane (16 -> 1 states), minecraft:sulfur_brick_stairs (40 -> 8 states), minecraft:hard_lime_stained_glass_pane (16 -> 1 states), minecraft:hard_light_gray_stained_glass_pane (16 -> 1 states), minecraft:exposed_cut_copper_stairs (40 -> 8 states), minecraft:mangrove_stairs (40 -> 8 states), minecraft:cut_copper_stairs (40 -> 8 states), minecraft:deepslate_tile_stairs (40 -> 8 states), minecraft:quartz_stairs (40 -> 8 states), minecraft:magenta_stained_glass_pane (16 -> 1 states), minecraft:iron_bars (16 -> 1 states), minecraft:purple_stained_glass_pane (16 -> 1 states), minecraft:dark_oak_stairs (40 -> 8 states), minecraft:glass_pane (16 -> 1 states), minecraft:andesite_stairs (40 -> 8 states), minecraft:red_sandstone_stairs (40 -> 8 states), minecraft:hard_light_blue_stained_glass_pane (16 -> 1 states), minecraft:chorus_plant (64 -> 1 states), minecraft:mud_brick_stairs (40 -> 8 states), minecraft:smooth_red_sandstone_stairs (40 -> 8 states), minecraft:oak_fence (16 -> 1 states), minecraft:waxed_oxidized_cut_copper_stairs (40 -> 8 states), minecraft:waxed_weathered_copper_bars (16 -> 1 states), minecraft:warped_fence (16 -> 1 states), minecraft:mossy_stone_brick_stairs (40 -> 8 states), minecraft:tuff_stairs (40 -> 8 states), minecraft:fire (512 -> 16 states), minecraft:resin_brick_stairs (40 -> 8 states), minecraft:cinnabar_brick_stairs (40 -> 8 states), minecraft:waxed_weathered_cut_copper_stairs (40 -> 8 states), minecraft:acacia_stairs (40 -> 8 states), minecraft:bamboo_mosaic_stairs (40 -> 8 states), minecraft:tuff_brick_stairs (40 -> 8 states), minecraft:exposed_copper_bars (16 -> 1 states), minecraft:crimson_stairs (40 -> 8 states), minecraft:waxed_oxidized_copper_bars (16 -> 1 states), minecraft:hard_magenta_stained_glass_pane (16 -> 1 states), minecraft:end_brick_stairs (40 -> 8 states), minecraft:cyan_stained_glass_pane (16 -> 1 states), minecraft:hard_gray_stained_glass_pane (16 -> 1 states), minecraft:cinnabar_stairs (40 -> 8 states), minecraft:brick_stairs (40 -> 8 states), minecraft:red_nether_brick_stairs (40 -> 8 states), minecraft:mangrove_fence (16 -> 1 states), minecraft:polished_diorite_stairs (40 -> 8 states), minecraft:gray_stained_glass_pane (16 -> 1 states), minecraft:jungle_stairs (40 -> 8 states), minecraft:birch_stairs (40 -> 8 states), minecraft:blackstone_stairs (40 -> 8 states), minecraft:hard_red_stained_glass_pane (16 -> 1 states), minecraft:polished_andesite_stairs (40 -> 8 states), minecraft:hard_purple_stained_glass_pane (16 -> 1 states), minecraft:red_stained_glass_pane (16 -> 1 states), minecraft:pink_stained_glass_pane (16 -> 1 states), minecraft:dark_oak_fence (16 -> 1 states), minecraft:cherry_stairs (40 -> 8 states), minecraft:hard_white_stained_glass_pane (16 -> 1 states), minecraft:hard_cyan_stained_glass_pane (16 -> 1 states), minecraft:prismarine_stairs (40 -> 8 states), minecraft:polished_tuff_stairs (40 -> 8 states), minecraft:pale_oak_stairs (40 -> 8 states), minecraft:deepslate_brick_stairs (40 -> 8 states), minecraft:dark_prismarine_stairs (40 -> 8 states), minecraft:trip_wire (256 -> 16 states), minecraft:hard_pink_stained_glass_pane (16 -> 1 states), minecraft:weathered_copper_bars (16 -> 1 states), minecraft:lime_stained_glass_pane (16 -> 1 states), minecraft:polished_cinnabar_stairs (40 -> 8 states), minecraft:smooth_quartz_stairs (40 -> 8 states), minecraft:purpur_stairs (40 -> 8 states), minecraft:crimson_fence (16 -> 1 states), minecraft:birch_fence (16 -> 1 states), minecraft:acacia_fence (16 -> 1 states), minecraft:hard_blue_stained_glass_pane (16 -> 1 states); removed [minecraft:cyan_wool_double_slab, minecraft:light_blue_wool_double_slab, minecraft:poplar_button, minecraft:white_wool_double_slab, minecraft:black_concrete_slab, minecraft:gray_concrete_slab, minecraft:lime_wool_stairs, minecraft:purple_concrete_stairs, minecraft:blue_concrete_double_slab, minecraft:poplar_trapdoor, minecraft:magenta_concrete_slab, minecraft:purple_concrete_double_slab, minecraft:green_wool_double_slab, minecraft:poplar_slab, minecraft:black_wool_double_slab, minecraft:purple_wool_slab, minecraft:gray_concrete_stairs, minecraft:yellow_concrete_slab, minecraft:white_wool_slab, minecraft:white_concrete_double_slab, minecraft:poplar_standing_sign, minecraft:pink_wool_slab, minecraft:lime_concrete_stairs, minecraft:light_blue_concrete_double_slab, minecraft:orange_wool_double_slab, minecraft:black_wool_stairs, minecraft:poplar_hanging_sign, minecraft:green_wool_stairs, minecraft:magenta_concrete_double_slab, minecraft:pink_concrete_stairs, minecraft:icicle, minecraft:brown_wool_stairs, minecraft:light_gray_wool_stairs, minecraft:light_blue_concrete_stairs, minecraft:magenta_wool_stairs, minecraft:yellow_wool_stairs, minecraft:gray_wool_stairs, minecraft:magenta_concrete_stairs, minecraft:red_wool_stairs, minecraft:brown_concrete_stairs, minecraft:yellow_wool_slab, minecraft:brown_wool_slab, minecraft:pink_wool_double_slab, minecraft:magenta_wool_slab, minecraft:purple_concrete_slab, minecraft:lime_wool_slab, minecraft:blue_wool_stairs, minecraft:red_wool_slab, minecraft:ice_crystal, minecraft:light_gray_concrete_stairs, minecraft:light_gray_wool_slab, minecraft:green_concrete_stairs, minecraft:lime_concrete_slab, minecraft:blue_concrete_slab, minecraft:white_concrete_slab, minecraft:green_concrete_double_slab, minecraft:light_blue_wool_slab, minecraft:purple_wool_double_slab, minecraft:red_wool_double_slab, minecraft:orange_wool_slab, minecraft:black_concrete_stairs, minecraft:red_concrete_slab, minecraft:black_concrete_double_slab, minecraft:gray_wool_double_slab, minecraft:red_shrub, minecraft:white_concrete_stairs, minecraft:poplar_shelf, minecraft:red_concrete_stairs, minecraft:shelf_mushroom, minecraft:brown_concrete_double_slab, minecraft:blue_concrete_stairs, minecraft:cyan_wool_stairs, minecraft:poplar_pressure_plate, minecraft:purple_wool_stairs, minecraft:light_blue_wool_stairs, minecraft:poplar_door, minecraft:yellow_wool_double_slab, minecraft:gray_concrete_double_slab, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood, minecraft:cyan_concrete_double_slab, minecraft:light_blue_concrete_slab, minecraft:poplar_planks, minecraft:light_gray_concrete_double_slab, minecraft:cyan_concrete_stairs, minecraft:black_wool_slab, minecraft:orange_concrete_slab, minecraft:lime_wool_double_slab, minecraft:disc_press, minecraft:straw_bed, minecraft:poplar_double_slab, minecraft:orange_concrete_double_slab, minecraft:light_gray_wool_double_slab, minecraft:poplar_stairs, minecraft:gray_wool_slab, minecraft:green_concrete_slab, minecraft:brown_wool_double_slab, minecraft:cyan_wool_slab, minecraft:magenta_wool_double_slab, minecraft:yellow_concrete_stairs, minecraft:pink_concrete_double_slab, minecraft:green_wool_slab, minecraft:cyan_concrete_slab, minecraft:light_gray_concrete_slab, minecraft:pink_wool_stairs, minecraft:blue_wool_slab, minecraft:orange_wool_stairs, minecraft:lime_concrete_double_slab, minecraft:poplar_fence, minecraft:brown_concrete_slab, minecraft:orange_poplar_leaves, minecraft:pink_concrete_slab, minecraft:poplar_sapling, minecraft:white_wool_stairs, minecraft:poplar_wall_sign, minecraft:yellow_concrete_double_slab, minecraft:blue_wool_double_slab, minecraft:yellow_poplar_leaves, minecraft:red_poplar_leaves, minecraft:poplar_log, minecraft:poplar_fence_gate, minecraft:orange_concrete_stairs, minecraft:poplar_wood, minecraft:red_concrete_double_slab] |
| PASS | vanilla capture | hashed block ids; protocol 1001 |
| PASS | Block hashes (vanilla) | all 984 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | vanilla capture (sequential ids) | sequential block ids; protocol 1001 |
| PASS | Experiments: discovery | 47714 candidates (binary bedrock_server.exe, experimental packs, known ids); BDS kept 27 toggles in level.dat and logged as many active |
| PASS | experiments capture (all toggles) | hashed block ids; protocol 1001 |
| PASS | Experiments: current toggles | 5 streamed in start game [experimental_creator_cameras, gametest, upcoming_creator_features, villager_trades_rebalance, voxel_shapes], 22 retired (kept, not streamed); 5 `createWorldScreen.experimental*` UI keys in the binary |
| PASS | Experiments: resource pack stack | lists the same toggles as start game |
| PASS | experiments capture (experimental_creator_cameras) | hashed block ids; protocol 1001 |
| PASS | Experiments applied (experimental_creator_cameras) | start game reports [experimental_creator_cameras] |
| PASS | experiments capture (gametest) | hashed block ids; protocol 1001 |
| PASS | Experiments applied (gametest) | start game reports [gametest] |
| PASS | experiments capture (upcoming_creator_features) | hashed block ids; protocol 1001 |
| PASS | Experiments applied (upcoming_creator_features) | start game reports [upcoming_creator_features] |
| PASS | experiments capture (villager_trades_rebalance) | hashed block ids; protocol 1001 |
| PASS | Experiments applied (villager_trades_rebalance) | start game reports [villager_trades_rebalance] |
| PASS | experiments capture (voxel_shapes) | hashed block ids; protocol 1001 |
| PASS | Experiments applied (voxel_shapes) | start game reports [voxel_shapes] |
| PASS | add-on capture | hashed block ids; protocol 1001 |
| PASS | Block hashes (add-on) | all 2084 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | Experiments applied (add-on world) | start game reports [experimental_creator_cameras, gametest, upcoming_creator_features, villager_trades_rebalance, voxel_shapes] |
| PASS | add-on capture (sequential ids) | sequential block ids; protocol 1001 |
| PASS | creative order probe capture | hashed block ids; protocol 1001 |
| PASS | Block numbering | reference palette 26.60+: 16913 states in FNV-1 64 name order |
| PASS | Block numbering | vanilla: 938 sequential creative block ids match the FNV-1 64 name sort of 16913 states |
| PASS | Block numbering | add-on: 996 sequential creative block ids match the FNV-1 64 name sort of 19293 states |
| PASS | Block numbering | add-on: 61 custom block ids are 10000+ in descending identifier order |
| PASS | Block numbering | add-on: 60 custom block items have runtime id 255 - block_id |
| DIFF | Script API vs baseline dump | 623 differences |
| DIFF | New protocol 1001 | not bundled yet; 1547 differences to 26.60 |
| INFO | Import | imported 26.30 (1001) |
| DIFF | Add-on content log | 11 changes in what BDS rejects or warns about in data/addons/capture |
| INFO | Experiments: creator docs | the docs name experiments this BDS does not have: [Holiday Creator Features] |
| PASS | Experiment marks vs bundled 26.30 | identical |
| INFO | Creative order | skipped: protocol 1001 is not bundled, so Paletter has no vanilla layout of this version to place custom content in |
| DIFF | Component shapes | add-on components vs 1.26.63.1: 7 wire format changes, 0 new components |
| DIFF | Component shapes | vanilla components vs bundled 26.60: 20 wire format changes, 0 new components |
| PASS | Spec coverage | every captured component shape is in data/model/mappings.json |
| DIFF | Add-on capture vs data\bds | `data\bds\1.26.36.1\addon` is missing or differs |
| INFO | Component model | new add-on capture staged; run the model checks (checkMappings, checkComponentApi) and review the diff |
| INFO | Spec capture | `data\bds\1.26.36.1\spec` |
| INFO | Component model | mappings inferred; run :tools:generateComponentApi and the conformance suite (:tools:test) for the new protocol |
| PASS | Vanilla constants | 0 added, 0 deprecated, 0 blocks with a lookup table |

## TODO (needs a decision)

- [ ] Block palette: the states of 124 blocks were generated from the Script API. Their order within each block is inferred from the reference palette (Update#keyOrder) and only creative stacks verify it (sequential ids of other blocks do not depend on it): minecraft:blue_stained_glass_pane (16 -> 1 states), minecraft:nether_brick_stairs (40 -> 8 states), minecraft:spruce_stairs (40 -> 8 states), minecraft:hard_green_stained_glass_pane (16 -> 1 states), minecraft:yellow_stained_glass_pane (16 -> 1 states), minecraft:cobbled_deepslate_stairs (40 -> 8 states), minecraft:prismarine_bricks_stairs (40 -> 8 states), minecraft:hard_orange_stained_glass_pane (16 -> 1 states), minecraft:orange_stained_glass_pane (16 -> 1 states), minecraft:hard_black_stained_glass_pane (16 -> 1 states), minecraft:oak_stairs (40 -> 8 states), minecraft:brown_stained_glass_pane (16 -> 1 states), minecraft:polished_deepslate_stairs (40 -> 8 states), minecraft:oxidized_cut_copper_stairs (40 -> 8 states), minecraft:pale_oak_fence (16 -> 1 states), minecraft:jungle_fence (16 -> 1 states), minecraft:spruce_fence (16 -> 1 states), minecraft:waxed_cut_copper_stairs (40 -> 8 states), minecraft:light_gray_stained_glass_pane (16 -> 1 states), minecraft:waxed_copper_bars (16 -> 1 states), minecraft:bamboo_fence (16 -> 1 states), minecraft:normal_stone_stairs (40 -> 8 states), minecraft:hard_glass_pane (16 -> 1 states), minecraft:bamboo_stairs (40 -> 8 states), minecraft:stone_brick_stairs (40 -> 8 states), minecraft:cherry_fence (16 -> 1 states), minecraft:oxidized_copper_bars (16 -> 1 states), minecraft:hard_brown_stained_glass_pane (16 -> 1 states), minecraft:black_stained_glass_pane (16 -> 1 states), minecraft:granite_stairs (40 -> 8 states), minecraft:sandstone_stairs (40 -> 8 states), minecraft:smooth_sandstone_stairs (40 -> 8 states), minecraft:stone_stairs (40 -> 8 states), minecraft:warped_stairs (40 -> 8 states), minecraft:redstone_wire (1296 -> 16 states), minecraft:light_blue_stained_glass_pane (16 -> 1 states), minecraft:waxed_exposed_cut_copper_stairs (40 -> 8 states), minecraft:mossy_cobblestone_stairs (40 -> 8 states), minecraft:green_stained_glass_pane (16 -> 1 states), minecraft:polished_granite_stairs (40 -> 8 states), minecraft:waxed_exposed_copper_bars (16 -> 1 states), minecraft:sulfur_stairs (40 -> 8 states), minecraft:hard_yellow_stained_glass_pane (16 -> 1 states), minecraft:nether_brick_fence (16 -> 1 states), minecraft:polished_blackstone_stairs (40 -> 8 states), minecraft:weathered_cut_copper_stairs (40 -> 8 states), minecraft:polished_sulfur_stairs (40 -> 8 states), minecraft:copper_bars (16 -> 1 states), minecraft:diorite_stairs (40 -> 8 states), minecraft:polished_blackstone_brick_stairs (40 -> 8 states), minecraft:white_stained_glass_pane (16 -> 1 states), minecraft:sulfur_brick_stairs (40 -> 8 states), minecraft:hard_lime_stained_glass_pane (16 -> 1 states), minecraft:hard_light_gray_stained_glass_pane (16 -> 1 states), minecraft:exposed_cut_copper_stairs (40 -> 8 states), minecraft:mangrove_stairs (40 -> 8 states), minecraft:cut_copper_stairs (40 -> 8 states), minecraft:deepslate_tile_stairs (40 -> 8 states), minecraft:quartz_stairs (40 -> 8 states), minecraft:magenta_stained_glass_pane (16 -> 1 states), minecraft:iron_bars (16 -> 1 states), minecraft:purple_stained_glass_pane (16 -> 1 states), minecraft:dark_oak_stairs (40 -> 8 states), minecraft:glass_pane (16 -> 1 states), minecraft:andesite_stairs (40 -> 8 states), minecraft:red_sandstone_stairs (40 -> 8 states), minecraft:hard_light_blue_stained_glass_pane (16 -> 1 states), minecraft:chorus_plant (64 -> 1 states), minecraft:mud_brick_stairs (40 -> 8 states), minecraft:smooth_red_sandstone_stairs (40 -> 8 states), minecraft:oak_fence (16 -> 1 states), minecraft:waxed_oxidized_cut_copper_stairs (40 -> 8 states), minecraft:waxed_weathered_copper_bars (16 -> 1 states), minecraft:warped_fence (16 -> 1 states), minecraft:mossy_stone_brick_stairs (40 -> 8 states), minecraft:tuff_stairs (40 -> 8 states), minecraft:fire (512 -> 16 states), minecraft:resin_brick_stairs (40 -> 8 states), minecraft:cinnabar_brick_stairs (40 -> 8 states), minecraft:waxed_weathered_cut_copper_stairs (40 -> 8 states), minecraft:acacia_stairs (40 -> 8 states), minecraft:bamboo_mosaic_stairs (40 -> 8 states), minecraft:tuff_brick_stairs (40 -> 8 states), minecraft:exposed_copper_bars (16 -> 1 states), minecraft:crimson_stairs (40 -> 8 states), minecraft:waxed_oxidized_copper_bars (16 -> 1 states), minecraft:hard_magenta_stained_glass_pane (16 -> 1 states), minecraft:end_brick_stairs (40 -> 8 states), minecraft:cyan_stained_glass_pane (16 -> 1 states), minecraft:hard_gray_stained_glass_pane (16 -> 1 states), minecraft:cinnabar_stairs (40 -> 8 states), minecraft:brick_stairs (40 -> 8 states), minecraft:red_nether_brick_stairs (40 -> 8 states), minecraft:mangrove_fence (16 -> 1 states), minecraft:polished_diorite_stairs (40 -> 8 states), minecraft:gray_stained_glass_pane (16 -> 1 states), minecraft:jungle_stairs (40 -> 8 states), minecraft:birch_stairs (40 -> 8 states), minecraft:blackstone_stairs (40 -> 8 states), minecraft:hard_red_stained_glass_pane (16 -> 1 states), minecraft:polished_andesite_stairs (40 -> 8 states), minecraft:hard_purple_stained_glass_pane (16 -> 1 states), minecraft:red_stained_glass_pane (16 -> 1 states), minecraft:pink_stained_glass_pane (16 -> 1 states), minecraft:dark_oak_fence (16 -> 1 states), minecraft:cherry_stairs (40 -> 8 states), minecraft:hard_white_stained_glass_pane (16 -> 1 states), minecraft:hard_cyan_stained_glass_pane (16 -> 1 states), minecraft:prismarine_stairs (40 -> 8 states), minecraft:polished_tuff_stairs (40 -> 8 states), minecraft:pale_oak_stairs (40 -> 8 states), minecraft:deepslate_brick_stairs (40 -> 8 states), minecraft:dark_prismarine_stairs (40 -> 8 states), minecraft:trip_wire (256 -> 16 states), minecraft:hard_pink_stained_glass_pane (16 -> 1 states), minecraft:weathered_copper_bars (16 -> 1 states), minecraft:lime_stained_glass_pane (16 -> 1 states), minecraft:polished_cinnabar_stairs (40 -> 8 states), minecraft:smooth_quartz_stairs (40 -> 8 states), minecraft:purpur_stairs (40 -> 8 states), minecraft:crimson_fence (16 -> 1 states), minecraft:birch_fence (16 -> 1 states), minecraft:acacia_fence (16 -> 1 states), minecraft:hard_blue_stained_glass_pane (16 -> 1 states)
- [ ] WIRE FORMAT CHANGE in add-on components vs 1.26.63.1: review the component model and the generated component types (see the top of this report)
- [ ] WIRE FORMAT CHANGE in vanilla components vs bundled 26.60: review the component model and the generated component types (see the top of this report)

## Repository files

- add `data/bds/1.26.36.1/dump.json.gz` (Script API baseline 1.26.36.1)
- add `data/bds/1.26.36.1/experiments.json` (experiment record)
- add `data/bds/1.26.36.1/addon/custom_block_properties.nbt` (component capture custom_block_properties.nbt)
- add `data/bds/1.26.36.1/addon/custom_item_registry.nbt` (component capture custom_item_registry.nbt)
- add or update `data/bds/1.26.36.1/spec` (spec capture)
- update `data/model/mappings.json`, `coverage.md`, `bds-feedback.json` (inferred from the spec captures)
- add `paletter/src/main/resources/paletter/26.30.nbt` (bundled data)
- update `paletter/src/main/resources/paletter/properties.nbt` (bundled data)
- update `paletter/src/main/resources/paletter/versions.txt` (bundled data)

## Script API: BDS 1.26.36.1 vs baseline dump

**removed blocks** (124)

- `minecraft:black_concrete_double_slab`
- `minecraft:black_concrete_slab`
- `minecraft:black_concrete_stairs`
- `minecraft:black_wool_double_slab`
- `minecraft:black_wool_slab`
- `minecraft:black_wool_stairs`
- `minecraft:blue_concrete_double_slab`
- `minecraft:blue_concrete_slab`
- `minecraft:blue_concrete_stairs`
- `minecraft:blue_wool_double_slab`
- `minecraft:blue_wool_slab`
- `minecraft:blue_wool_stairs`
- `minecraft:brown_concrete_double_slab`
- `minecraft:brown_concrete_slab`
- `minecraft:brown_concrete_stairs`
- `minecraft:brown_wool_double_slab`
- `minecraft:brown_wool_slab`
- `minecraft:brown_wool_stairs`
- `minecraft:cyan_concrete_double_slab`
- `minecraft:cyan_concrete_slab`
- `minecraft:cyan_concrete_stairs`
- `minecraft:cyan_wool_double_slab`
- `minecraft:cyan_wool_slab`
- `minecraft:cyan_wool_stairs`
- `minecraft:disc_press`
- `minecraft:gray_concrete_double_slab`
- `minecraft:gray_concrete_slab`
- `minecraft:gray_concrete_stairs`
- `minecraft:gray_wool_double_slab`
- `minecraft:gray_wool_slab`
- `minecraft:gray_wool_stairs`
- `minecraft:green_concrete_double_slab`
- `minecraft:green_concrete_slab`
- `minecraft:green_concrete_stairs`
- `minecraft:green_wool_double_slab`
- `minecraft:green_wool_slab`
- `minecraft:green_wool_stairs`
- `minecraft:ice_crystal`
- `minecraft:icicle`
- `minecraft:light_blue_concrete_double_slab`
- `minecraft:light_blue_concrete_slab`
- `minecraft:light_blue_concrete_stairs`
- `minecraft:light_blue_wool_double_slab`
- `minecraft:light_blue_wool_slab`
- `minecraft:light_blue_wool_stairs`
- `minecraft:light_gray_concrete_double_slab`
- `minecraft:light_gray_concrete_slab`
- `minecraft:light_gray_concrete_stairs`
- `minecraft:light_gray_wool_double_slab`
- `minecraft:light_gray_wool_slab`
- `minecraft:light_gray_wool_stairs`
- `minecraft:lime_concrete_double_slab`
- `minecraft:lime_concrete_slab`
- `minecraft:lime_concrete_stairs`
- `minecraft:lime_wool_double_slab`
- `minecraft:lime_wool_slab`
- `minecraft:lime_wool_stairs`
- `minecraft:magenta_concrete_double_slab`
- `minecraft:magenta_concrete_slab`
- `minecraft:magenta_concrete_stairs`
- `minecraft:magenta_wool_double_slab`
- `minecraft:magenta_wool_slab`
- `minecraft:magenta_wool_stairs`
- `minecraft:orange_concrete_double_slab`
- `minecraft:orange_concrete_slab`
- `minecraft:orange_concrete_stairs`
- `minecraft:orange_poplar_leaves`
- `minecraft:orange_wool_double_slab`
- `minecraft:orange_wool_slab`
- `minecraft:orange_wool_stairs`
- `minecraft:pink_concrete_double_slab`
- `minecraft:pink_concrete_slab`
- `minecraft:pink_concrete_stairs`
- `minecraft:pink_wool_double_slab`
- `minecraft:pink_wool_slab`
- `minecraft:pink_wool_stairs`
- `minecraft:poplar_button`
- `minecraft:poplar_door`
- `minecraft:poplar_double_slab`
- `minecraft:poplar_fence`
- `minecraft:poplar_fence_gate`
- `minecraft:poplar_hanging_sign`
- `minecraft:poplar_log`
- `minecraft:poplar_planks`
- `minecraft:poplar_pressure_plate`
- `minecraft:poplar_sapling`
- `minecraft:poplar_shelf`
- `minecraft:poplar_slab`
- `minecraft:poplar_stairs`
- `minecraft:poplar_standing_sign`
- `minecraft:poplar_trapdoor`
- `minecraft:poplar_wall_sign`
- `minecraft:poplar_wood`
- `minecraft:purple_concrete_double_slab`
- `minecraft:purple_concrete_slab`
- `minecraft:purple_concrete_stairs`
- `minecraft:purple_wool_double_slab`
- `minecraft:purple_wool_slab`
- `minecraft:purple_wool_stairs`
- `minecraft:red_concrete_double_slab`
- `minecraft:red_concrete_slab`
- `minecraft:red_concrete_stairs`
- `minecraft:red_poplar_leaves`
- `minecraft:red_shrub`
- `minecraft:red_wool_double_slab`
- `minecraft:red_wool_slab`
- `minecraft:red_wool_stairs`
- `minecraft:shelf_mushroom`
- `minecraft:straw_bed`
- `minecraft:stripped_poplar_log`
- `minecraft:stripped_poplar_wood`
- `minecraft:white_concrete_double_slab`
- `minecraft:white_concrete_slab`
- `minecraft:white_concrete_stairs`
- `minecraft:white_wool_double_slab`
- `minecraft:white_wool_slab`
- `minecraft:white_wool_stairs`
- `minecraft:yellow_concrete_double_slab`
- `minecraft:yellow_concrete_slab`
- `minecraft:yellow_concrete_stairs`
- `minecraft:yellow_poplar_leaves`
- `minecraft:yellow_wool_double_slab`
- `minecraft:yellow_wool_slab`
- `minecraft:yellow_wool_stairs`

**block state changes** (124)

- `minecraft:acacia_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:acacia_stairs: -minecraft:corner`
- `minecraft:andesite_stairs: -minecraft:corner`
- `minecraft:bamboo_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:bamboo_mosaic_stairs: -minecraft:corner`
- `minecraft:bamboo_stairs: -minecraft:corner`
- `minecraft:birch_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:birch_stairs: -minecraft:corner`
- `minecraft:black_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:blackstone_stairs: -minecraft:corner`
- `minecraft:blue_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:brick_stairs: -minecraft:corner`
- `minecraft:brown_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:cherry_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:cherry_stairs: -minecraft:corner`
- `minecraft:chorus_plant: -minecraft:connection_down; -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_up; -minecraft:connection_west`
- `minecraft:cinnabar_brick_stairs: -minecraft:corner`
- `minecraft:cinnabar_stairs: -minecraft:corner`
- `minecraft:cobbled_deepslate_stairs: -minecraft:corner`
- `minecraft:copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:crimson_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:crimson_stairs: -minecraft:corner`
- `minecraft:cut_copper_stairs: -minecraft:corner`
- `minecraft:cyan_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:dark_oak_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:dark_oak_stairs: -minecraft:corner`
- `minecraft:dark_prismarine_stairs: -minecraft:corner`
- `minecraft:deepslate_brick_stairs: -minecraft:corner`
- `minecraft:deepslate_tile_stairs: -minecraft:corner`
- `minecraft:diorite_stairs: -minecraft:corner`
- `minecraft:end_brick_stairs: -minecraft:corner`
- `minecraft:exposed_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:exposed_cut_copper_stairs: -minecraft:corner`
- `minecraft:fire: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_up; -minecraft:connection_west`
- `minecraft:glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:granite_stairs: -minecraft:corner`
- `minecraft:gray_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:green_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_black_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_blue_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_brown_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_cyan_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_gray_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_green_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_light_blue_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_light_gray_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_lime_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_magenta_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_orange_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_pink_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_purple_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_red_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_white_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:hard_yellow_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:iron_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:jungle_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:jungle_stairs: -minecraft:corner`
- `minecraft:light_blue_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:light_gray_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:lime_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:magenta_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:mangrove_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:mangrove_stairs: -minecraft:corner`
- `minecraft:mossy_cobblestone_stairs: -minecraft:corner`
- `minecraft:mossy_stone_brick_stairs: -minecraft:corner`
- `minecraft:mud_brick_stairs: -minecraft:corner`
- `minecraft:nether_brick_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:nether_brick_stairs: -minecraft:corner`
- `minecraft:normal_stone_stairs: -minecraft:corner`
- `minecraft:oak_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:oak_stairs: -minecraft:corner`
- `minecraft:orange_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:oxidized_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:oxidized_cut_copper_stairs: -minecraft:corner`
- `minecraft:pale_oak_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:pale_oak_stairs: -minecraft:corner`
- `minecraft:pink_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:polished_andesite_stairs: -minecraft:corner`
- `minecraft:polished_blackstone_brick_stairs: -minecraft:corner`
- `minecraft:polished_blackstone_stairs: -minecraft:corner`
- `minecraft:polished_cinnabar_stairs: -minecraft:corner`
- `minecraft:polished_deepslate_stairs: -minecraft:corner`
- `minecraft:polished_diorite_stairs: -minecraft:corner`
- `minecraft:polished_granite_stairs: -minecraft:corner`
- `minecraft:polished_sulfur_stairs: -minecraft:corner`
- `minecraft:polished_tuff_stairs: -minecraft:corner`
- `minecraft:prismarine_bricks_stairs: -minecraft:corner`
- `minecraft:prismarine_stairs: -minecraft:corner`
- `minecraft:purple_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:purpur_stairs: -minecraft:corner`
- `minecraft:quartz_stairs: -minecraft:corner`
- `minecraft:red_nether_brick_stairs: -minecraft:corner`
- `minecraft:red_sandstone_stairs: -minecraft:corner`
- `minecraft:red_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:redstone_wire: -redstone_east; -redstone_north; -redstone_south; -redstone_west`
- `minecraft:resin_brick_stairs: -minecraft:corner`
- `minecraft:sandstone_stairs: -minecraft:corner`
- `minecraft:smooth_quartz_stairs: -minecraft:corner`
- `minecraft:smooth_red_sandstone_stairs: -minecraft:corner`
- `minecraft:smooth_sandstone_stairs: -minecraft:corner`
- `minecraft:spruce_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:spruce_stairs: -minecraft:corner`
- `minecraft:stone_brick_stairs: -minecraft:corner`
- `minecraft:stone_stairs: -minecraft:corner`
- `minecraft:sulfur_brick_stairs: -minecraft:corner`
- `minecraft:sulfur_stairs: -minecraft:corner`
- `minecraft:trip_wire: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:tuff_brick_stairs: -minecraft:corner`
- `minecraft:tuff_stairs: -minecraft:corner`
- `minecraft:warped_fence: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:warped_stairs: -minecraft:corner`
- `minecraft:waxed_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:waxed_cut_copper_stairs: -minecraft:corner`
- `minecraft:waxed_exposed_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:waxed_exposed_cut_copper_stairs: -minecraft:corner`
- `minecraft:waxed_oxidized_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:waxed_oxidized_cut_copper_stairs: -minecraft:corner`
- `minecraft:waxed_weathered_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:waxed_weathered_cut_copper_stairs: -minecraft:corner`
- `minecraft:weathered_copper_bars: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:weathered_cut_copper_stairs: -minecraft:corner`
- `minecraft:white_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`
- `minecraft:yellow_stained_glass_pane: -minecraft:connection_east; -minecraft:connection_north; -minecraft:connection_south; -minecraft:connection_west`

**block default changes** (124)

- `minecraft:acacia_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "acacia" } -> { "wood_type": "acacia" }`
- `minecraft:acacia_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:andesite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:bamboo_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:bamboo_mosaic_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:bamboo_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:birch_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "birch" } -> { "wood_type": "birch" }`
- `minecraft:birch_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:black_stained_glass_pane: { "color": "black", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "black" }`
- `minecraft:blackstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:blue_stained_glass_pane: { "color": "blue", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "blue" }`
- `minecraft:brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:brown_stained_glass_pane: { "color": "brown", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "brown" }`
- `minecraft:cherry_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:cherry_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:chorus_plant: { "minecraft:connection_down": 0b, "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_up": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:cinnabar_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:cinnabar_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:cobbled_deepslate_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:crimson_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:crimson_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:cyan_stained_glass_pane: { "color": "cyan", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "cyan" }`
- `minecraft:dark_oak_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "dark_oak" } -> { "wood_type": "dark_oak" }`
- `minecraft:dark_oak_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:dark_prismarine_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:deepslate_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:deepslate_tile_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:diorite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:end_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:exposed_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:exposed_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:fire: { "age": 0i, "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_up": 0b, "minecraft:connection_west": 0b } -> { "age": 0i }`
- `minecraft:glass_pane: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:granite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:gray_stained_glass_pane: { "color": "gray", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "gray" }`
- `minecraft:green_stained_glass_pane: { "color": "green", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "green" }`
- `minecraft:hard_black_stained_glass_pane: { "color": "black", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "black" }`
- `minecraft:hard_blue_stained_glass_pane: { "color": "blue", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "blue" }`
- `minecraft:hard_brown_stained_glass_pane: { "color": "brown", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "brown" }`
- `minecraft:hard_cyan_stained_glass_pane: { "color": "cyan", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "cyan" }`
- `minecraft:hard_glass_pane: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:hard_gray_stained_glass_pane: { "color": "gray", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "gray" }`
- `minecraft:hard_green_stained_glass_pane: { "color": "green", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "green" }`
- `minecraft:hard_light_blue_stained_glass_pane: { "color": "light_blue", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "light_blue" }`
- `minecraft:hard_light_gray_stained_glass_pane: { "color": "silver", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "silver" }`
- `minecraft:hard_lime_stained_glass_pane: { "color": "lime", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "lime" }`
- `minecraft:hard_magenta_stained_glass_pane: { "color": "magenta", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "magenta" }`
- `minecraft:hard_orange_stained_glass_pane: { "color": "orange", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "orange" }`
- `minecraft:hard_pink_stained_glass_pane: { "color": "pink", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "pink" }`
- `minecraft:hard_purple_stained_glass_pane: { "color": "purple", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "purple" }`
- `minecraft:hard_red_stained_glass_pane: { "color": "red", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "red" }`
- `minecraft:hard_white_stained_glass_pane: { "color": "white", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "white" }`
- `minecraft:hard_yellow_stained_glass_pane: { "color": "yellow", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "yellow" }`
- `minecraft:iron_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:jungle_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "jungle" } -> { "wood_type": "jungle" }`
- `minecraft:jungle_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:light_blue_stained_glass_pane: { "color": "light_blue", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "light_blue" }`
- `minecraft:light_gray_stained_glass_pane: { "color": "silver", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "silver" }`
- `minecraft:lime_stained_glass_pane: { "color": "lime", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "lime" }`
- `minecraft:magenta_stained_glass_pane: { "color": "magenta", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "magenta" }`
- `minecraft:mangrove_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:mangrove_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:mossy_cobblestone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:mossy_stone_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:mud_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:nether_brick_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:nether_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:normal_stone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:oak_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "oak" } -> { "wood_type": "oak" }`
- `minecraft:oak_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:orange_stained_glass_pane: { "color": "orange", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "orange" }`
- `minecraft:oxidized_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:oxidized_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:pale_oak_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:pale_oak_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:pink_stained_glass_pane: { "color": "pink", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "pink" }`
- `minecraft:polished_andesite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_blackstone_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_blackstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_cinnabar_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_deepslate_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_diorite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_granite_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_sulfur_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:polished_tuff_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:prismarine_bricks_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:prismarine_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:purple_stained_glass_pane: { "color": "purple", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "purple" }`
- `minecraft:purpur_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:quartz_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:red_nether_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:red_sandstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:red_stained_glass_pane: { "color": "red", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "red" }`
- `minecraft:redstone_wire: { "redstone_east": "none", "redstone_north": "none", "redstone_signal": 0i, "redstone_south": "none", "redstone_west": "none" } -> { "redstone_signal": 0i }`
- `minecraft:resin_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:sandstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:smooth_quartz_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:smooth_red_sandstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:smooth_sandstone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:spruce_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "wood_type": "spruce" } -> { "wood_type": "spruce" }`
- `minecraft:spruce_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:stone_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:stone_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:sulfur_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:sulfur_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:trip_wire: { "attached_bit": 0b, "disarmed_bit": 0b, "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b, "powered_bit": 0b, "suspended_bit": 0b } -> { "attached_bit": 0b, "disarmed_bit": 0b, "powered_bit": 0b, "suspended_bit": 0b }`
- `minecraft:tuff_brick_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:tuff_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:warped_fence: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:warped_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:waxed_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:waxed_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:waxed_exposed_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:waxed_exposed_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:waxed_oxidized_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:waxed_oxidized_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:waxed_weathered_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:waxed_weathered_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:weathered_copper_bars: { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> {}`
- `minecraft:weathered_cut_copper_stairs: { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i } -> { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `minecraft:white_stained_glass_pane: { "color": "white", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "white" }`
- `minecraft:yellow_stained_glass_pane: { "color": "yellow", "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b } -> { "color": "yellow" }`

**removed items** (105)

- `minecraft:black_concrete_slab`
- `minecraft:black_concrete_stairs`
- `minecraft:black_cushion`
- `minecraft:black_wool_slab`
- `minecraft:black_wool_stairs`
- `minecraft:blue_concrete_slab`
- `minecraft:blue_concrete_stairs`
- `minecraft:blue_cushion`
- `minecraft:blue_wool_slab`
- `minecraft:blue_wool_stairs`
- `minecraft:brown_concrete_slab`
- `minecraft:brown_concrete_stairs`
- `minecraft:brown_cushion`
- `minecraft:brown_wool_slab`
- `minecraft:brown_wool_stairs`
- `minecraft:cyan_concrete_slab`
- `minecraft:cyan_concrete_stairs`
- `minecraft:cyan_cushion`
- `minecraft:cyan_wool_slab`
- `minecraft:cyan_wool_stairs`
- `minecraft:gray_concrete_slab`
- `minecraft:gray_concrete_stairs`
- `minecraft:gray_cushion`
- `minecraft:gray_wool_slab`
- `minecraft:gray_wool_stairs`
- `minecraft:green_concrete_slab`
- `minecraft:green_concrete_stairs`
- `minecraft:green_cushion`
- `minecraft:green_wool_slab`
- `minecraft:green_wool_stairs`
- `minecraft:light_blue_concrete_slab`
- `minecraft:light_blue_concrete_stairs`
- `minecraft:light_blue_cushion`
- `minecraft:light_blue_wool_slab`
- `minecraft:light_blue_wool_stairs`
- `minecraft:light_gray_concrete_slab`
- `minecraft:light_gray_concrete_stairs`
- `minecraft:light_gray_cushion`
- `minecraft:light_gray_wool_slab`
- `minecraft:light_gray_wool_stairs`
- `minecraft:lime_concrete_slab`
- `minecraft:lime_concrete_stairs`
- `minecraft:lime_cushion`
- `minecraft:lime_wool_slab`
- `minecraft:lime_wool_stairs`
- `minecraft:magenta_concrete_slab`
- `minecraft:magenta_concrete_stairs`
- `minecraft:magenta_cushion`
- `minecraft:magenta_wool_slab`
- `minecraft:magenta_wool_stairs`
- `minecraft:orange_concrete_slab`
- `minecraft:orange_concrete_stairs`
- `minecraft:orange_cushion`
- `minecraft:orange_poplar_leaves`
- `minecraft:orange_wool_slab`
- `minecraft:orange_wool_stairs`
- `minecraft:pink_concrete_slab`
- `minecraft:pink_concrete_stairs`
- `minecraft:pink_cushion`
- `minecraft:pink_wool_slab`
- `minecraft:pink_wool_stairs`
- `minecraft:poplar_boat`
- `minecraft:poplar_button`
- `minecraft:poplar_chest_boat`
- `minecraft:poplar_door`
- `minecraft:poplar_fence`
- `minecraft:poplar_fence_gate`
- `minecraft:poplar_hanging_sign`
- `minecraft:poplar_log`
- `minecraft:poplar_planks`
- `minecraft:poplar_pressure_plate`
- `minecraft:poplar_sapling`
- `minecraft:poplar_shelf`
- `minecraft:poplar_sign`
- `minecraft:poplar_slab`
- `minecraft:poplar_stairs`
- `minecraft:poplar_trapdoor`
- `minecraft:poplar_wood`
- `minecraft:purple_concrete_slab`
- `minecraft:purple_concrete_stairs`
- `minecraft:purple_cushion`
- `minecraft:purple_wool_slab`
- `minecraft:purple_wool_stairs`
- `minecraft:red_concrete_slab`
- `minecraft:red_concrete_stairs`
- `minecraft:red_cushion`
- `minecraft:red_poplar_leaves`
- `minecraft:red_shrub`
- `minecraft:red_wool_slab`
- `minecraft:red_wool_stairs`
- `minecraft:shelf_mushroom`
- `minecraft:straw_bed`
- `minecraft:stripped_poplar_log`
- `minecraft:stripped_poplar_wood`
- `minecraft:white_concrete_slab`
- `minecraft:white_concrete_stairs`
- `minecraft:white_cushion`
- `minecraft:white_wool_slab`
- `minecraft:white_wool_stairs`
- `minecraft:yellow_concrete_slab`
- `minecraft:yellow_concrete_stairs`
- `minecraft:yellow_cushion`
- `minecraft:yellow_poplar_leaves`
- `minecraft:yellow_wool_slab`
- `minecraft:yellow_wool_stairs`

**Script API item components (+ added to, - removed from)** (1)

- `-minecraft:block_actor_dynamic_properties: 1518 items, e.g. [minecraft:acacia_boat, minecraft:acacia_button, minecraft:acacia_chest_boat, minecraft:acacia_door, minecraft:acacia_fence]`

**removed item tags** (1)

- `minecraft:mushrooms_for_stew`

**item tags membership** (12)

- `minecraft:boat: +[] -[minecraft:poplar_boat, minecraft:poplar_chest_boat]`
- `minecraft:boats: +[] -[minecraft:poplar_boat, minecraft:poplar_chest_boat]`
- `minecraft:door: +[] -[minecraft:poplar_door]`
- `minecraft:hanging_sign: +[] -[minecraft:poplar_hanging_sign]`
- `minecraft:logs: +[] -[minecraft:poplar_log, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:logs_that_burn: +[] -[minecraft:poplar_log, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:planks: +[] -[minecraft:poplar_planks]`
- `minecraft:sign: +[] -[minecraft:poplar_hanging_sign, minecraft:poplar_sign]`
- `minecraft:sulfur_cube_archetype_bouncy: +[] -[minecraft:poplar_log, minecraft:poplar_planks, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:sulfur_cube_archetype_slow_bouncy: +[minecraft:observer, minecraft:redstone_lamp] -[]`
- `minecraft:vibration_damper: +[] -[minecraft:black_wool_slab, minecraft:black_wool_stairs, minecraft:blue_wool_slab, minecraft:blue_wool_stairs, minecraft:brown_wool_slab, minecraft:brown_wool_stairs, minecraft:cyan_wool_slab, minecraft:cyan_wool_stairs, minecraft:gray_wool_slab, minecraft:gray_wool_stairs, minecraft:green_wool_slab, minecraft:green_wool_stairs, minecraft:light_blue_wool_slab, minecraft:light_blue_wool_stairs, minecraft:light_gray_wool_slab, minecraft:light_gray_wool_stairs, minecraft:lime_wool_slab, minecraft:lime_wool_stairs, minecraft:magenta_wool_slab, minecraft:magenta_wool_stairs, minecraft:orange_wool_slab, minecraft:orange_wool_stairs, minecraft:pink_wool_slab, minecraft:pink_wool_stairs, minecraft:purple_wool_slab, minecraft:purple_wool_stairs, minecraft:red_wool_slab, minecraft:red_wool_stairs, minecraft:white_wool_slab, minecraft:white_wool_stairs, minecraft:yellow_wool_slab, minecraft:yellow_wool_stairs]`
- `minecraft:wooden_slabs: +[] -[minecraft:poplar_slab]`

**removed block tags** (2)

- `minecraft:cornerable_stairs`
- `minecraft:leaves`

**block tags membership** (9)

- `minecraft:is_axe_item_destructible: +[] -[minecraft:poplar_button, minecraft:poplar_door, minecraft:poplar_double_slab, minecraft:poplar_fence, minecraft:poplar_fence_gate, minecraft:poplar_hanging_sign, minecraft:poplar_log, minecraft:poplar_planks, minecraft:poplar_pressure_plate, minecraft:poplar_shelf, minecraft:poplar_slab, minecraft:poplar_stairs, minecraft:poplar_standing_sign, minecraft:poplar_trapdoor, minecraft:poplar_wall_sign, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:is_hoe_item_destructible: +[] -[minecraft:orange_poplar_leaves, minecraft:red_poplar_leaves, minecraft:straw_bed, minecraft:yellow_poplar_leaves]`
- `minecraft:is_pickaxe_item_destructible: +[] -[minecraft:black_concrete_double_slab, minecraft:black_concrete_slab, minecraft:black_concrete_stairs, minecraft:blue_concrete_double_slab, minecraft:blue_concrete_slab, minecraft:blue_concrete_stairs, minecraft:brown_concrete_double_slab, minecraft:brown_concrete_slab, minecraft:brown_concrete_stairs, minecraft:cyan_concrete_double_slab, minecraft:cyan_concrete_slab, minecraft:cyan_concrete_stairs, minecraft:gray_concrete_double_slab, minecraft:gray_concrete_slab, minecraft:gray_concrete_stairs, minecraft:green_concrete_double_slab, minecraft:green_concrete_slab, minecraft:green_concrete_stairs, minecraft:ice_crystal, minecraft:icicle, minecraft:light_blue_concrete_double_slab, minecraft:light_blue_concrete_slab, minecraft:light_blue_concrete_stairs, minecraft:light_gray_concrete_double_slab, minecraft:light_gray_concrete_slab, minecraft:light_gray_concrete_stairs, minecraft:lime_concrete_double_slab, minecraft:lime_concrete_slab, minecraft:lime_concrete_stairs, minecraft:magenta_concrete_double_slab, minecraft:magenta_concrete_slab, minecraft:magenta_concrete_stairs, minecraft:orange_concrete_double_slab, minecraft:orange_concrete_slab, minecraft:orange_concrete_stairs, minecraft:pink_concrete_double_slab, minecraft:pink_concrete_slab, minecraft:pink_concrete_stairs, minecraft:purple_concrete_double_slab, minecraft:purple_concrete_slab, minecraft:purple_concrete_stairs, minecraft:red_concrete_double_slab, minecraft:red_concrete_slab, minecraft:red_concrete_stairs, minecraft:white_concrete_double_slab, minecraft:white_concrete_slab, minecraft:white_concrete_stairs, minecraft:yellow_concrete_double_slab, minecraft:yellow_concrete_slab, minecraft:yellow_concrete_stairs]`
- `minecraft:is_shears_item_destructible: +[] -[minecraft:black_wool_double_slab, minecraft:black_wool_slab, minecraft:black_wool_stairs, minecraft:blue_wool_double_slab, minecraft:blue_wool_slab, minecraft:blue_wool_stairs, minecraft:brown_wool_double_slab, minecraft:brown_wool_slab, minecraft:brown_wool_stairs, minecraft:cyan_wool_double_slab, minecraft:cyan_wool_slab, minecraft:cyan_wool_stairs, minecraft:gray_wool_double_slab, minecraft:gray_wool_slab, minecraft:gray_wool_stairs, minecraft:green_wool_double_slab, minecraft:green_wool_slab, minecraft:green_wool_stairs, minecraft:light_blue_wool_double_slab, minecraft:light_blue_wool_slab, minecraft:light_blue_wool_stairs, minecraft:light_gray_wool_double_slab, minecraft:light_gray_wool_slab, minecraft:light_gray_wool_stairs, minecraft:lime_wool_double_slab, minecraft:lime_wool_slab, minecraft:lime_wool_stairs, minecraft:magenta_wool_double_slab, minecraft:magenta_wool_slab, minecraft:magenta_wool_stairs, minecraft:orange_wool_double_slab, minecraft:orange_wool_slab, minecraft:orange_wool_stairs, minecraft:pink_wool_double_slab, minecraft:pink_wool_slab, minecraft:pink_wool_stairs, minecraft:purple_wool_double_slab, minecraft:purple_wool_slab, minecraft:purple_wool_stairs, minecraft:red_wool_double_slab, minecraft:red_wool_slab, minecraft:red_wool_stairs, minecraft:white_wool_double_slab, minecraft:white_wool_slab, minecraft:white_wool_stairs, minecraft:yellow_wool_double_slab, minecraft:yellow_wool_slab, minecraft:yellow_wool_stairs]`
- `not_feature_replaceable: +[] -[minecraft:poplar_shelf]`
- `one_way_collidable: +[] -[minecraft:poplar_door, minecraft:poplar_fence_gate, minecraft:poplar_trapdoor]`
- `text_sign: +[] -[minecraft:poplar_hanging_sign, minecraft:poplar_standing_sign, minecraft:poplar_wall_sign]`
- `trapdoors: +[] -[minecraft:poplar_trapdoor]`
- `wood: +[] -[minecraft:poplar_button, minecraft:poplar_fence_gate, minecraft:poplar_hanging_sign, minecraft:poplar_log, minecraft:poplar_pressure_plate, minecraft:poplar_standing_sign, minecraft:poplar_trapdoor, minecraft:poplar_wall_sign, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`

**block to item changes** (121)

- `minecraft:black_concrete_double_slab: minecraft:black_concrete_slab -> null`
- `minecraft:black_concrete_slab: minecraft:black_concrete_slab -> null`
- `minecraft:black_concrete_stairs: minecraft:black_concrete_stairs -> null`
- `minecraft:black_wool_double_slab: minecraft:black_wool_slab -> null`
- `minecraft:black_wool_slab: minecraft:black_wool_slab -> null`
- `minecraft:black_wool_stairs: minecraft:black_wool_stairs -> null`
- `minecraft:blue_concrete_double_slab: minecraft:blue_concrete_slab -> null`
- `minecraft:blue_concrete_slab: minecraft:blue_concrete_slab -> null`
- `minecraft:blue_concrete_stairs: minecraft:blue_concrete_stairs -> null`
- `minecraft:blue_wool_double_slab: minecraft:blue_wool_slab -> null`
- `minecraft:blue_wool_slab: minecraft:blue_wool_slab -> null`
- `minecraft:blue_wool_stairs: minecraft:blue_wool_stairs -> null`
- `minecraft:brown_concrete_double_slab: minecraft:brown_concrete_slab -> null`
- `minecraft:brown_concrete_slab: minecraft:brown_concrete_slab -> null`
- `minecraft:brown_concrete_stairs: minecraft:brown_concrete_stairs -> null`
- `minecraft:brown_wool_double_slab: minecraft:brown_wool_slab -> null`
- `minecraft:brown_wool_slab: minecraft:brown_wool_slab -> null`
- `minecraft:brown_wool_stairs: minecraft:brown_wool_stairs -> null`
- `minecraft:cyan_concrete_double_slab: minecraft:cyan_concrete_slab -> null`
- `minecraft:cyan_concrete_slab: minecraft:cyan_concrete_slab -> null`
- `minecraft:cyan_concrete_stairs: minecraft:cyan_concrete_stairs -> null`
- `minecraft:cyan_wool_double_slab: minecraft:cyan_wool_slab -> null`
- `minecraft:cyan_wool_slab: minecraft:cyan_wool_slab -> null`
- `minecraft:cyan_wool_stairs: minecraft:cyan_wool_stairs -> null`
- `minecraft:gray_concrete_double_slab: minecraft:gray_concrete_slab -> null`
- `minecraft:gray_concrete_slab: minecraft:gray_concrete_slab -> null`
- `minecraft:gray_concrete_stairs: minecraft:gray_concrete_stairs -> null`
- `minecraft:gray_wool_double_slab: minecraft:gray_wool_slab -> null`
- `minecraft:gray_wool_slab: minecraft:gray_wool_slab -> null`
- `minecraft:gray_wool_stairs: minecraft:gray_wool_stairs -> null`
- `minecraft:green_concrete_double_slab: minecraft:green_concrete_slab -> null`
- `minecraft:green_concrete_slab: minecraft:green_concrete_slab -> null`
- `minecraft:green_concrete_stairs: minecraft:green_concrete_stairs -> null`
- `minecraft:green_wool_double_slab: minecraft:green_wool_slab -> null`
- `minecraft:green_wool_slab: minecraft:green_wool_slab -> null`
- `minecraft:green_wool_stairs: minecraft:green_wool_stairs -> null`
- `minecraft:light_blue_concrete_double_slab: minecraft:light_blue_concrete_slab -> null`
- `minecraft:light_blue_concrete_slab: minecraft:light_blue_concrete_slab -> null`
- `minecraft:light_blue_concrete_stairs: minecraft:light_blue_concrete_stairs -> null`
- `minecraft:light_blue_wool_double_slab: minecraft:light_blue_wool_slab -> null`
- `minecraft:light_blue_wool_slab: minecraft:light_blue_wool_slab -> null`
- `minecraft:light_blue_wool_stairs: minecraft:light_blue_wool_stairs -> null`
- `minecraft:light_gray_concrete_double_slab: minecraft:light_gray_concrete_slab -> null`
- `minecraft:light_gray_concrete_slab: minecraft:light_gray_concrete_slab -> null`
- `minecraft:light_gray_concrete_stairs: minecraft:light_gray_concrete_stairs -> null`
- `minecraft:light_gray_wool_double_slab: minecraft:light_gray_wool_slab -> null`
- `minecraft:light_gray_wool_slab: minecraft:light_gray_wool_slab -> null`
- `minecraft:light_gray_wool_stairs: minecraft:light_gray_wool_stairs -> null`
- `minecraft:lime_concrete_double_slab: minecraft:lime_concrete_slab -> null`
- `minecraft:lime_concrete_slab: minecraft:lime_concrete_slab -> null`
- `minecraft:lime_concrete_stairs: minecraft:lime_concrete_stairs -> null`
- `minecraft:lime_wool_double_slab: minecraft:lime_wool_slab -> null`
- `minecraft:lime_wool_slab: minecraft:lime_wool_slab -> null`
- `minecraft:lime_wool_stairs: minecraft:lime_wool_stairs -> null`
- `minecraft:magenta_concrete_double_slab: minecraft:magenta_concrete_slab -> null`
- `minecraft:magenta_concrete_slab: minecraft:magenta_concrete_slab -> null`
- `minecraft:magenta_concrete_stairs: minecraft:magenta_concrete_stairs -> null`
- `minecraft:magenta_wool_double_slab: minecraft:magenta_wool_slab -> null`
- `minecraft:magenta_wool_slab: minecraft:magenta_wool_slab -> null`
- `minecraft:magenta_wool_stairs: minecraft:magenta_wool_stairs -> null`
- `minecraft:orange_concrete_double_slab: minecraft:orange_concrete_slab -> null`
- `minecraft:orange_concrete_slab: minecraft:orange_concrete_slab -> null`
- `minecraft:orange_concrete_stairs: minecraft:orange_concrete_stairs -> null`
- `minecraft:orange_poplar_leaves: minecraft:orange_poplar_leaves -> null`
- `minecraft:orange_wool_double_slab: minecraft:orange_wool_slab -> null`
- `minecraft:orange_wool_slab: minecraft:orange_wool_slab -> null`
- `minecraft:orange_wool_stairs: minecraft:orange_wool_stairs -> null`
- `minecraft:pink_concrete_double_slab: minecraft:pink_concrete_slab -> null`
- `minecraft:pink_concrete_slab: minecraft:pink_concrete_slab -> null`
- `minecraft:pink_concrete_stairs: minecraft:pink_concrete_stairs -> null`
- `minecraft:pink_wool_double_slab: minecraft:pink_wool_slab -> null`
- `minecraft:pink_wool_slab: minecraft:pink_wool_slab -> null`
- `minecraft:pink_wool_stairs: minecraft:pink_wool_stairs -> null`
- `minecraft:poplar_button: minecraft:poplar_button -> null`
- `minecraft:poplar_door: minecraft:poplar_door -> null`
- `minecraft:poplar_double_slab: minecraft:poplar_slab -> null`
- `minecraft:poplar_fence: minecraft:poplar_fence -> null`
- `minecraft:poplar_fence_gate: minecraft:poplar_fence_gate -> null`
- `minecraft:poplar_hanging_sign: minecraft:poplar_hanging_sign -> null`
- `minecraft:poplar_log: minecraft:poplar_log -> null`
- `minecraft:poplar_planks: minecraft:poplar_planks -> null`
- `minecraft:poplar_pressure_plate: minecraft:poplar_pressure_plate -> null`
- `minecraft:poplar_sapling: minecraft:poplar_sapling -> null`
- `minecraft:poplar_shelf: minecraft:poplar_shelf -> null`
- `minecraft:poplar_slab: minecraft:poplar_slab -> null`
- `minecraft:poplar_stairs: minecraft:poplar_stairs -> null`
- `minecraft:poplar_standing_sign: minecraft:poplar_sign -> null`
- `minecraft:poplar_trapdoor: minecraft:poplar_trapdoor -> null`
- `minecraft:poplar_wall_sign: minecraft:poplar_sign -> null`
- `minecraft:poplar_wood: minecraft:poplar_wood -> null`
- `minecraft:purple_concrete_double_slab: minecraft:purple_concrete_slab -> null`
- `minecraft:purple_concrete_slab: minecraft:purple_concrete_slab -> null`
- `minecraft:purple_concrete_stairs: minecraft:purple_concrete_stairs -> null`
- `minecraft:purple_wool_double_slab: minecraft:purple_wool_slab -> null`
- `minecraft:purple_wool_slab: minecraft:purple_wool_slab -> null`
- `minecraft:purple_wool_stairs: minecraft:purple_wool_stairs -> null`
- `minecraft:red_concrete_double_slab: minecraft:red_concrete_slab -> null`
- `minecraft:red_concrete_slab: minecraft:red_concrete_slab -> null`
- `minecraft:red_concrete_stairs: minecraft:red_concrete_stairs -> null`
- `minecraft:red_poplar_leaves: minecraft:red_poplar_leaves -> null`
- `minecraft:red_shrub: minecraft:red_shrub -> null`
- `minecraft:red_wool_double_slab: minecraft:red_wool_slab -> null`
- `minecraft:red_wool_slab: minecraft:red_wool_slab -> null`
- `minecraft:red_wool_stairs: minecraft:red_wool_stairs -> null`
- `minecraft:shelf_mushroom: minecraft:shelf_mushroom -> null`
- `minecraft:straw_bed: minecraft:straw_bed -> null`
- `minecraft:stripped_poplar_log: minecraft:stripped_poplar_log -> null`
- `minecraft:stripped_poplar_wood: minecraft:stripped_poplar_wood -> null`
- `minecraft:white_concrete_double_slab: minecraft:white_concrete_slab -> null`
- `minecraft:white_concrete_slab: minecraft:white_concrete_slab -> null`
- `minecraft:white_concrete_stairs: minecraft:white_concrete_stairs -> null`
- `minecraft:white_wool_double_slab: minecraft:white_wool_slab -> null`
- `minecraft:white_wool_slab: minecraft:white_wool_slab -> null`
- `minecraft:white_wool_stairs: minecraft:white_wool_stairs -> null`
- `minecraft:yellow_concrete_double_slab: minecraft:yellow_concrete_slab -> null`
- `minecraft:yellow_concrete_slab: minecraft:yellow_concrete_slab -> null`
- `minecraft:yellow_concrete_stairs: minecraft:yellow_concrete_stairs -> null`
- `minecraft:yellow_poplar_leaves: minecraft:yellow_poplar_leaves -> null`
- `minecraft:yellow_wool_double_slab: minecraft:yellow_wool_slab -> null`
- `minecraft:yellow_wool_slab: minecraft:yellow_wool_slab -> null`
- `minecraft:yellow_wool_stairs: minecraft:yellow_wool_stairs -> null`

## What changed: 26.30 (BDS 1.26.36.1) vs bundled 26.60

**removed blocks** (124)

- `minecraft:black_concrete_double_slab`
- `minecraft:black_concrete_slab`
- `minecraft:black_concrete_stairs`
- `minecraft:black_wool_double_slab`
- `minecraft:black_wool_slab`
- `minecraft:black_wool_stairs`
- `minecraft:blue_concrete_double_slab`
- `minecraft:blue_concrete_slab`
- `minecraft:blue_concrete_stairs`
- `minecraft:blue_wool_double_slab`
- `minecraft:blue_wool_slab`
- `minecraft:blue_wool_stairs`
- `minecraft:brown_concrete_double_slab`
- `minecraft:brown_concrete_slab`
- `minecraft:brown_concrete_stairs`
- `minecraft:brown_wool_double_slab`
- `minecraft:brown_wool_slab`
- `minecraft:brown_wool_stairs`
- `minecraft:cyan_concrete_double_slab`
- `minecraft:cyan_concrete_slab`
- `minecraft:cyan_concrete_stairs`
- `minecraft:cyan_wool_double_slab`
- `minecraft:cyan_wool_slab`
- `minecraft:cyan_wool_stairs`
- `minecraft:disc_press`
- `minecraft:gray_concrete_double_slab`
- `minecraft:gray_concrete_slab`
- `minecraft:gray_concrete_stairs`
- `minecraft:gray_wool_double_slab`
- `minecraft:gray_wool_slab`
- `minecraft:gray_wool_stairs`
- `minecraft:green_concrete_double_slab`
- `minecraft:green_concrete_slab`
- `minecraft:green_concrete_stairs`
- `minecraft:green_wool_double_slab`
- `minecraft:green_wool_slab`
- `minecraft:green_wool_stairs`
- `minecraft:ice_crystal`
- `minecraft:icicle`
- `minecraft:light_blue_concrete_double_slab`
- `minecraft:light_blue_concrete_slab`
- `minecraft:light_blue_concrete_stairs`
- `minecraft:light_blue_wool_double_slab`
- `minecraft:light_blue_wool_slab`
- `minecraft:light_blue_wool_stairs`
- `minecraft:light_gray_concrete_double_slab`
- `minecraft:light_gray_concrete_slab`
- `minecraft:light_gray_concrete_stairs`
- `minecraft:light_gray_wool_double_slab`
- `minecraft:light_gray_wool_slab`
- `minecraft:light_gray_wool_stairs`
- `minecraft:lime_concrete_double_slab`
- `minecraft:lime_concrete_slab`
- `minecraft:lime_concrete_stairs`
- `minecraft:lime_wool_double_slab`
- `minecraft:lime_wool_slab`
- `minecraft:lime_wool_stairs`
- `minecraft:magenta_concrete_double_slab`
- `minecraft:magenta_concrete_slab`
- `minecraft:magenta_concrete_stairs`
- `minecraft:magenta_wool_double_slab`
- `minecraft:magenta_wool_slab`
- `minecraft:magenta_wool_stairs`
- `minecraft:orange_concrete_double_slab`
- `minecraft:orange_concrete_slab`
- `minecraft:orange_concrete_stairs`
- `minecraft:orange_poplar_leaves`
- `minecraft:orange_wool_double_slab`
- `minecraft:orange_wool_slab`
- `minecraft:orange_wool_stairs`
- `minecraft:pink_concrete_double_slab`
- `minecraft:pink_concrete_slab`
- `minecraft:pink_concrete_stairs`
- `minecraft:pink_wool_double_slab`
- `minecraft:pink_wool_slab`
- `minecraft:pink_wool_stairs`
- `minecraft:poplar_button`
- `minecraft:poplar_door`
- `minecraft:poplar_double_slab`
- `minecraft:poplar_fence`
- `minecraft:poplar_fence_gate`
- `minecraft:poplar_hanging_sign`
- `minecraft:poplar_log`
- `minecraft:poplar_planks`
- `minecraft:poplar_pressure_plate`
- `minecraft:poplar_sapling`
- `minecraft:poplar_shelf`
- `minecraft:poplar_slab`
- `minecraft:poplar_stairs`
- `minecraft:poplar_standing_sign`
- `minecraft:poplar_trapdoor`
- `minecraft:poplar_wall_sign`
- `minecraft:poplar_wood`
- `minecraft:purple_concrete_double_slab`
- `minecraft:purple_concrete_slab`
- `minecraft:purple_concrete_stairs`
- `minecraft:purple_wool_double_slab`
- `minecraft:purple_wool_slab`
- `minecraft:purple_wool_stairs`
- `minecraft:red_concrete_double_slab`
- `minecraft:red_concrete_slab`
- `minecraft:red_concrete_stairs`
- `minecraft:red_poplar_leaves`
- `minecraft:red_shrub`
- `minecraft:red_wool_double_slab`
- `minecraft:red_wool_slab`
- `minecraft:red_wool_stairs`
- `minecraft:shelf_mushroom`
- `minecraft:straw_bed`
- `minecraft:stripped_poplar_log`
- `minecraft:stripped_poplar_wood`
- `minecraft:white_concrete_double_slab`
- `minecraft:white_concrete_slab`
- `minecraft:white_concrete_stairs`
- `minecraft:white_wool_double_slab`
- `minecraft:white_wool_slab`
- `minecraft:white_wool_stairs`
- `minecraft:yellow_concrete_double_slab`
- `minecraft:yellow_concrete_slab`
- `minecraft:yellow_concrete_stairs`
- `minecraft:yellow_poplar_leaves`
- `minecraft:yellow_wool_double_slab`
- `minecraft:yellow_wool_slab`
- `minecraft:yellow_wool_stairs`

**blocks with changed states** (124)

- `minecraft:acacia_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:acacia_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:andesite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:bamboo_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:bamboo_mosaic_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:bamboo_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:birch_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:birch_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:black_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:blackstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:blue_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:brown_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:cherry_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:cherry_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:chorus_plant: 64 -> 1 states; state keys [minecraft:connection_down, minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_up, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:cinnabar_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:cinnabar_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:cobbled_deepslate_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:crimson_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:crimson_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:cyan_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:dark_oak_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:dark_oak_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:dark_prismarine_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:deepslate_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:deepslate_tile_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:diorite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:end_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:exposed_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:exposed_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:fire: 512 -> 16 states; state keys [age, minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_up, minecraft:connection_west] -> [age]; e.g. new {
  "age": 0i
}`
- `minecraft:glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:granite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:gray_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:green_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_black_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_blue_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_brown_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_cyan_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_gray_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_green_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_light_blue_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_light_gray_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_lime_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_magenta_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_orange_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_pink_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_purple_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_red_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_white_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:hard_yellow_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:iron_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:jungle_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:jungle_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:light_blue_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:light_gray_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:lime_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:magenta_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:mangrove_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:mangrove_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:mossy_cobblestone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:mossy_stone_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:mud_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:nether_brick_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:nether_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:normal_stone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:oak_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:oak_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:orange_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:oxidized_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:oxidized_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:pale_oak_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:pale_oak_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:pink_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:polished_andesite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_blackstone_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_blackstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_cinnabar_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_deepslate_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_diorite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_granite_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_sulfur_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:polished_tuff_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:prismarine_bricks_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:prismarine_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:purple_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:purpur_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:quartz_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:red_nether_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:red_sandstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:red_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:redstone_wire: 1296 -> 16 states; state keys [redstone_east, redstone_north, redstone_signal, redstone_south, redstone_west] -> [redstone_signal]; e.g. new {
  "redstone_signal": 0i
}`
- `minecraft:resin_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:sandstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:smooth_quartz_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:smooth_red_sandstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:smooth_sandstone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:spruce_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:spruce_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:stone_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:stone_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:sulfur_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:sulfur_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:trip_wire: 256 -> 16 states; state keys [attached_bit, disarmed_bit, minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west, powered_bit, suspended_bit] -> [attached_bit, disarmed_bit, powered_bit, suspended_bit]; e.g. new {
  "attached_bit": 0b,
  "disarmed_bit": 0b,
  "powered_bit": 0b,
  "suspended_bit": 0b
}`
- `minecraft:tuff_brick_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:tuff_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:warped_fence: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:warped_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:waxed_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:waxed_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:waxed_exposed_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:waxed_exposed_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:waxed_oxidized_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:waxed_oxidized_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:waxed_weathered_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:waxed_weathered_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:weathered_copper_bars: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:weathered_cut_copper_stairs: 40 -> 8 states; state keys [minecraft:corner, upside_down_bit, weirdo_direction] -> [upside_down_bit, weirdo_direction]; e.g. new {
  "upside_down_bit": 0b,
  "weirdo_direction": 0i
}`
- `minecraft:white_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`
- `minecraft:yellow_stained_glass_pane: 16 -> 1 states; state keys [minecraft:connection_east, minecraft:connection_north, minecraft:connection_south, minecraft:connection_west] -> []; e.g. new {}`

**removed items** (147)

- `minecraft:black_concrete_double_slab`
- `minecraft:black_concrete_slab`
- `minecraft:black_concrete_stairs`
- `minecraft:black_cushion`
- `minecraft:black_wool_double_slab`
- `minecraft:black_wool_slab`
- `minecraft:black_wool_stairs`
- `minecraft:blue_concrete_double_slab`
- `minecraft:blue_concrete_slab`
- `minecraft:blue_concrete_stairs`
- `minecraft:blue_cushion`
- `minecraft:blue_wool_double_slab`
- `minecraft:blue_wool_slab`
- `minecraft:blue_wool_stairs`
- `minecraft:brown_concrete_double_slab`
- `minecraft:brown_concrete_slab`
- `minecraft:brown_concrete_stairs`
- `minecraft:brown_cushion`
- `minecraft:brown_wool_double_slab`
- `minecraft:brown_wool_slab`
- `minecraft:brown_wool_stairs`
- `minecraft:cyan_concrete_double_slab`
- `minecraft:cyan_concrete_slab`
- `minecraft:cyan_concrete_stairs`
- `minecraft:cyan_cushion`
- `minecraft:cyan_wool_double_slab`
- `minecraft:cyan_wool_slab`
- `minecraft:cyan_wool_stairs`
- `minecraft:disc_press`
- `minecraft:frostbite_spawn_egg`
- `minecraft:gray_concrete_double_slab`
- `minecraft:gray_concrete_slab`
- `minecraft:gray_concrete_stairs`
- `minecraft:gray_cushion`
- `minecraft:gray_wool_double_slab`
- `minecraft:gray_wool_slab`
- `minecraft:gray_wool_stairs`
- `minecraft:green_concrete_double_slab`
- `minecraft:green_concrete_slab`
- `minecraft:green_concrete_stairs`
- `minecraft:green_cushion`
- `minecraft:green_wool_double_slab`
- `minecraft:green_wool_slab`
- `minecraft:green_wool_stairs`
- `minecraft:ice_crystal`
- `minecraft:icicle`
- `minecraft:item.straw_bed`
- `minecraft:light_blue_concrete_double_slab`
- `minecraft:light_blue_concrete_slab`
- `minecraft:light_blue_concrete_stairs`
- `minecraft:light_blue_cushion`
- `minecraft:light_blue_wool_double_slab`
- `minecraft:light_blue_wool_slab`
- `minecraft:light_blue_wool_stairs`
- `minecraft:light_gray_concrete_double_slab`
- `minecraft:light_gray_concrete_slab`
- `minecraft:light_gray_concrete_stairs`
- `minecraft:light_gray_cushion`
- `minecraft:light_gray_wool_double_slab`
- `minecraft:light_gray_wool_slab`
- `minecraft:light_gray_wool_stairs`
- `minecraft:lime_concrete_double_slab`
- `minecraft:lime_concrete_slab`
- `minecraft:lime_concrete_stairs`
- `minecraft:lime_cushion`
- `minecraft:lime_wool_double_slab`
- `minecraft:lime_wool_slab`
- `minecraft:lime_wool_stairs`
- `minecraft:magenta_concrete_double_slab`
- `minecraft:magenta_concrete_slab`
- `minecraft:magenta_concrete_stairs`
- `minecraft:magenta_cushion`
- `minecraft:magenta_wool_double_slab`
- `minecraft:magenta_wool_slab`
- `minecraft:magenta_wool_stairs`
- `minecraft:orange_concrete_double_slab`
- `minecraft:orange_concrete_slab`
- `minecraft:orange_concrete_stairs`
- `minecraft:orange_cushion`
- `minecraft:orange_poplar_leaves`
- `minecraft:orange_wool_double_slab`
- `minecraft:orange_wool_slab`
- `minecraft:orange_wool_stairs`
- `minecraft:photo_item`
- `minecraft:pink_concrete_double_slab`
- `minecraft:pink_concrete_slab`
- `minecraft:pink_concrete_stairs`
- `minecraft:pink_cushion`
- `minecraft:pink_wool_double_slab`
- `minecraft:pink_wool_slab`
- `minecraft:pink_wool_stairs`
- `minecraft:poplar_boat`
- `minecraft:poplar_button`
- `minecraft:poplar_chest_boat`
- `minecraft:poplar_door`
- `minecraft:poplar_double_slab`
- `minecraft:poplar_fence`
- `minecraft:poplar_fence_gate`
- `minecraft:poplar_hanging_sign`
- `minecraft:poplar_log`
- `minecraft:poplar_planks`
- `minecraft:poplar_pressure_plate`
- `minecraft:poplar_sapling`
- `minecraft:poplar_shelf`
- `minecraft:poplar_sign`
- `minecraft:poplar_slab`
- `minecraft:poplar_stairs`
- `minecraft:poplar_standing_sign`
- `minecraft:poplar_trapdoor`
- `minecraft:poplar_wall_sign`
- `minecraft:poplar_wood`
- `minecraft:portfolio`
- `minecraft:purple_concrete_double_slab`
- `minecraft:purple_concrete_slab`
- `minecraft:purple_concrete_stairs`
- `minecraft:purple_cushion`
- `minecraft:purple_wool_double_slab`
- `minecraft:purple_wool_slab`
- `minecraft:purple_wool_stairs`
- `minecraft:red_concrete_double_slab`
- `minecraft:red_concrete_slab`
- `minecraft:red_concrete_stairs`
- `minecraft:red_cushion`
- `minecraft:red_poplar_leaves`
- `minecraft:red_shrub`
- `minecraft:red_wool_double_slab`
- `minecraft:red_wool_slab`
- `minecraft:red_wool_stairs`
- `minecraft:shelf_mushroom`
- `minecraft:straw_bed`
- `minecraft:stripped_poplar_log`
- `minecraft:stripped_poplar_wood`
- `minecraft:white_concrete_double_slab`
- `minecraft:white_concrete_slab`
- `minecraft:white_concrete_stairs`
- `minecraft:white_cushion`
- `minecraft:white_wool_double_slab`
- `minecraft:white_wool_slab`
- `minecraft:white_wool_stairs`
- `minecraft:yellow_concrete_double_slab`
- `minecraft:yellow_concrete_slab`
- `minecraft:yellow_concrete_stairs`
- `minecraft:yellow_cushion`
- `minecraft:yellow_poplar_leaves`
- `minecraft:yellow_wool_double_slab`
- `minecraft:yellow_wool_slab`
- `minecraft:yellow_wool_stairs`

**item runtime id changes** (586)

- `minecraft:acacia_boat: 382 -> 411`
- `minecraft:acacia_chest_boat: 657 -> 684`
- `minecraft:acacia_door: 566 -> 595`
- `minecraft:acacia_sign: 589 -> 618`
- `minecraft:agent_spawn_egg: 492 -> 521`
- `minecraft:allay_spawn_egg: 646 -> 673`
- `minecraft:amethyst_shard: 639 -> 666`
- `minecraft:angler_pottery_sherd: 672 -> 699`
- `minecraft:apple: 883 -> 285`
- `minecraft:archer_pottery_sherd: 673 -> 700`
- `minecraft:armadillo_scute: 722 -> 746`
- `minecraft:armadillo_spawn_egg: 721 -> 745`
- `minecraft:armor_stand: 562 -> 591`
- `minecraft:arms_up_pottery_sherd: 674 -> 701`
- `minecraft:arrow: 303 -> 332`
- `minecraft:axolotl_bucket: 372 -> 401`
- `minecraft:axolotl_spawn_egg: 507 -> 536`
- `minecraft:baked_potato: 281 -> 310`
- `minecraft:balloon: 614 -> 641`
- `minecraft:bamboo_chest_raft: 669 -> 696`
- `minecraft:bamboo_raft: 668 -> 695`
- `minecraft:bamboo_sign: 667 -> 694`
- `minecraft:banner: 577 -> 606`
- `minecraft:banner_pattern: 850 -> 842`
- `minecraft:bat_spawn_egg: 457 -> 486`
- `minecraft:bed: 421 -> 450`
- `minecraft:bee_spawn_egg: 499 -> 528`
- `minecraft:beef: 273 -> 302`
- `minecraft:beetroot: 285 -> 314`
- `minecraft:beetroot_seeds: 295 -> 324`
- `minecraft:beetroot_soup: 286 -> 315`
- `minecraft:birch_boat: 379 -> 408`
- `minecraft:birch_chest_boat: 654 -> 681`
- `minecraft:birch_door: 564 -> 593`
- `minecraft:birch_sign: 587 -> 616`
- `minecraft:black_bundle: 862 -> 264`
- `minecraft:black_dye: 398 -> 427`
- `minecraft:black_harness: 732 -> 756`
- `minecraft:blade_pottery_sherd: 675 -> 702`
- `minecraft:blaze_powder: 433 -> 462`
- `minecraft:blaze_rod: 426 -> 455`
- `minecraft:blaze_spawn_egg: 460 -> 489`
- `minecraft:bleach: 612 -> 639`
- `minecraft:blue_bundle: 863 -> 265`
- `minecraft:blue_dye: 402 -> 431`
- `minecraft:blue_egg: 729 -> 753`
- `minecraft:blue_harness: 733 -> 757`
- `minecraft:board: 606 -> 635`
- `minecraft:boat: 846 -> 840`
- `minecraft:bogged_spawn_egg: 467 -> 496`
- `minecraft:bolt_armor_trim_smithing_template: 714 -> 741`
- `minecraft:bone: 418 -> 447`
- `minecraft:bone_meal: 414 -> 443`
- `minecraft:book: 390 -> 419`
- `minecraft:bordure_indented_banner_pattern: 596 -> 625`
- `minecraft:bow: 302 -> 331`
- `minecraft:bowl: 324 -> 353`
- `minecraft:bread: 261 -> 290`
- `minecraft:breeze_rod: 879 -> 281`
- `minecraft:breeze_spawn_egg: 506 -> 535`
- `minecraft:brewer_pottery_sherd: 676 -> 703`
- `minecraft:brewing_stand: 435 -> 464`
- `minecraft:brick: 386 -> 415`
- `minecraft:brown_bundle: 864 -> 266`
- `minecraft:brown_dye: 401 -> 430`
- `minecraft:brown_egg: 730 -> 754`
- `minecraft:brown_harness: 734 -> 758`
- `minecraft:brush: 695 -> 722`
- `minecraft:bucket: 363 -> 392`
- `minecraft:bundle: 865 -> 267`
- `minecraft:burn_pottery_sherd: 677 -> 704`
- `minecraft:cake: 420 -> 449`
- `minecraft:camel_husk_spawn_egg: 671 -> 698`
- `minecraft:camel_spawn_egg: 670 -> 697`
- `minecraft:camera: 609 -> 636`
- `minecraft:campfire: 601 -> 630`
- `minecraft:carpet: 793 -> 796`
- `minecraft:carrot: 279 -> 308`
- `minecraft:carrot_on_a_stick: 527 -> 556`
- `minecraft:cat_spawn_egg: 493 -> 522`
- `minecraft:cauldron: 436 -> 465`
- `minecraft:cave_spider_spawn_egg: 461 -> 490`
- `minecraft:chainmail_boots: 345 -> 374`
- `minecraft:chainmail_chestplate: 343 -> 372`
- `minecraft:chainmail_helmet: 342 -> 371`
- `minecraft:chainmail_leggings: 344 -> 373`
- `minecraft:charcoal: 305 -> 334`
- `minecraft:chemistry_table: 839 -> 834`
- `minecraft:cherry_boat: 664 -> 691`
- `minecraft:cherry_chest_boat: 665 -> 692`
- `minecraft:cherry_sign: 666 -> 693`
- `minecraft:chest_boat: 660 -> 687`
- `minecraft:chest_minecart: 392 -> 421`
- `minecraft:chicken: 275 -> 304`
- `minecraft:chicken_spawn_egg: 439 -> 468`
- `minecraft:chorus_fruit: 568 -> 597`
- `minecraft:clay_ball: 387 -> 416`
- `minecraft:clock: 396 -> 425`
- `minecraft:coal: 304 -> 333`
- `minecraft:coast_armor_trim_smithing_template: 699 -> 726`
- `minecraft:cocoa_beans: 415 -> 444`
- `minecraft:cod: 264 -> 293`
- `minecraft:cod_bucket: 367 -> 396`
- `minecraft:cod_spawn_egg: 485 -> 514`
- `minecraft:colored_torch_bp: 844 -> 838`
- `minecraft:colored_torch_rg: 843 -> 837`
- `minecraft:command_block_minecart: 573 -> 602`
- `minecraft:comparator: 532 -> 561`
- `minecraft:compass: 394 -> 423`
- `minecraft:compound: 610 -> 637`
- `minecraft:concrete: 827 -> 822`
- `minecraft:concrete_powder: 828 -> 823`
- `minecraft:cooked_beef: 274 -> 303`
- `minecraft:cooked_chicken: 276 -> 305`
- `minecraft:cooked_cod: 268 -> 297`
- `minecraft:cooked_mutton: 561 -> 590`
- `minecraft:cooked_porkchop: 263 -> 292`
- `minecraft:cooked_rabbit: 289 -> 318`
- `minecraft:cooked_salmon: 269 -> 298`
- `minecraft:cookie: 271 -> 300`
- `minecraft:copper_axe: 752 -> 776`
- `minecraft:copper_boots: 757 -> 781`
- `minecraft:copper_chestplate: 755 -> 779`
- `minecraft:copper_golem_spawn_egg: 748 -> 772`
- `minecraft:copper_helmet: 754 -> 778`
- `minecraft:copper_hoe: 753 -> 777`
- `minecraft:copper_horse_armor: 759 -> 783`
- `minecraft:copper_ingot: 515 -> 544`
- `minecraft:copper_leggings: 756 -> 780`
- `minecraft:copper_nautilus_armor: 763 -> 787`
- `minecraft:copper_nugget: 758 -> 782`
- `minecraft:copper_pickaxe: 751 -> 775`
- `minecraft:copper_shovel: 750 -> 774`
- `minecraft:copper_spear: 855 -> 257`
- `minecraft:copper_sword: 749 -> 773`
- `minecraft:coral: 821 -> 818`
- `minecraft:coral_block: 797 -> 800`
- `minecraft:coral_fan: 806 -> 809`
- `minecraft:coral_fan_dead: 807 -> 810`
- `minecraft:cow_spawn_egg: 440 -> 469`
- `minecraft:creaking_spawn_egg: 727 -> 751`
- `minecraft:creeper_banner_pattern: 592 -> 621`
- `minecraft:creeper_spawn_egg: 445 -> 474`
- `minecraft:crimson_door: 632 -> 659`
- `minecraft:crimson_sign: 630 -> 657`
- `minecraft:crossbow: 585 -> 614`
- `minecraft:cyan_bundle: 866 -> 268`
- `minecraft:cyan_dye: 404 -> 433`
- `minecraft:cyan_harness: 735 -> 759`
- `minecraft:danger_pottery_sherd: 678 -> 705`
- `minecraft:dark_oak_boat: 383 -> 412`
- `minecraft:dark_oak_chest_boat: 658 -> 685`
- `minecraft:dark_oak_door: 567 -> 596`
- `minecraft:dark_oak_sign: 590 -> 619`
- `minecraft:diamond: 306 -> 335`
- `minecraft:diamond_axe: 321 -> 350`
- `minecraft:diamond_boots: 353 -> 382`
- `minecraft:diamond_chestplate: 351 -> 380`
- `minecraft:diamond_helmet: 350 -> 379`
- `minecraft:diamond_hoe: 335 -> 364`
- `minecraft:diamond_horse_armor: 543 -> 572`
- `minecraft:diamond_leggings: 352 -> 381`
- `minecraft:diamond_nautilus_armor: 766 -> 790`
- `minecraft:diamond_pickaxe: 320 -> 349`
- `minecraft:diamond_shovel: 319 -> 348`
- `minecraft:diamond_spear: 856 -> 258`
- `minecraft:diamond_sword: 318 -> 347`
- `minecraft:disc_fragment_5: 652 -> 679`
- `minecraft:dolphin_spawn_egg: 489 -> 518`
- `minecraft:donkey_spawn_egg: 470 -> 499`
- `minecraft:double_plant: 815 -> 816`
- `minecraft:double_stone_block_slab: 802 -> 805`
- `minecraft:double_stone_block_slab2: 803 -> 806`
- `minecraft:double_stone_block_slab3: 804 -> 807`
- `minecraft:double_stone_block_slab4: 805 -> 808`
- `minecraft:dragon_breath: 570 -> 599`
- `minecraft:dried_kelp: 270 -> 299`
- `minecraft:drowned_spawn_egg: 488 -> 517`
- `minecraft:dune_armor_trim_smithing_template: 698 -> 725`
- `minecraft:dye: 849 -> 841`
- `minecraft:echo_shard: 662 -> 689`
- `minecraft:egg: 393 -> 422`
- `minecraft:elder_guardian_spawn_egg: 476 -> 505`
- `minecraft:elytra: 574 -> 603`
- `minecraft:emerald: 523 -> 552`
- `minecraft:empty_map: 526 -> 555`
- `minecraft:enchanted_book: 531 -> 560`
- `minecraft:enchanted_golden_apple: 259 -> 288`
- `minecraft:end_crystal: 852 -> 844`
- `minecraft:ender_dragon_spawn_egg: 512 -> 541`
- `minecraft:ender_eye: 437 -> 466`
- `minecraft:ender_pearl: 425 -> 454`
- `minecraft:enderman_spawn_egg: 446 -> 475`
- `minecraft:endermite_spawn_egg: 464 -> 493`
- `minecraft:evoker_spawn_egg: 480 -> 509`
- `minecraft:experience_bottle: 519 -> 548`
- `minecraft:explorer_pottery_sherd: 679 -> 706`
- `minecraft:eye_armor_trim_smithing_template: 702 -> 729`
- `minecraft:feather: 330 -> 359`
- `minecraft:fence: 795 -> 798`
- ... 386 more

**item component data changes** (7)

- `minecraft:copper_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:diamond_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:golden_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:iron_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:netherite_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:stone_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`
- `minecraft:wooden_spear: components.minecraft:repairable.repair_items[0].repair_amount: context.other->query.remaining_durability -> { "expression": "context.other->query.remaining_durability", "version": 13s }; components.minecraft:repairable.repair_items[1].repair_amount: query.max_durability * 0.25 -> { "expression": "query.max_durability * 0.25", "version": 13s }`

**removed data driven blocks** (98)

- `minecraft:black_concrete_double_slab`
- `minecraft:black_concrete_slab`
- `minecraft:black_concrete_stairs`
- `minecraft:black_wool_double_slab`
- `minecraft:black_wool_slab`
- `minecraft:black_wool_stairs`
- `minecraft:blue_concrete_double_slab`
- `minecraft:blue_concrete_slab`
- `minecraft:blue_concrete_stairs`
- `minecraft:blue_wool_double_slab`
- `minecraft:blue_wool_slab`
- `minecraft:blue_wool_stairs`
- `minecraft:brown_concrete_double_slab`
- `minecraft:brown_concrete_slab`
- `minecraft:brown_concrete_stairs`
- `minecraft:brown_wool_double_slab`
- `minecraft:brown_wool_slab`
- `minecraft:brown_wool_stairs`
- `minecraft:cyan_concrete_double_slab`
- `minecraft:cyan_concrete_slab`
- `minecraft:cyan_concrete_stairs`
- `minecraft:cyan_wool_double_slab`
- `minecraft:cyan_wool_slab`
- `minecraft:cyan_wool_stairs`
- `minecraft:gray_concrete_double_slab`
- `minecraft:gray_concrete_slab`
- `minecraft:gray_concrete_stairs`
- `minecraft:gray_wool_double_slab`
- `minecraft:gray_wool_slab`
- `minecraft:gray_wool_stairs`
- `minecraft:green_concrete_double_slab`
- `minecraft:green_concrete_slab`
- `minecraft:green_concrete_stairs`
- `minecraft:green_wool_double_slab`
- `minecraft:green_wool_slab`
- `minecraft:green_wool_stairs`
- `minecraft:light_blue_concrete_double_slab`
- `minecraft:light_blue_concrete_slab`
- `minecraft:light_blue_concrete_stairs`
- `minecraft:light_blue_wool_double_slab`
- `minecraft:light_blue_wool_slab`
- `minecraft:light_blue_wool_stairs`
- `minecraft:light_gray_concrete_double_slab`
- `minecraft:light_gray_concrete_slab`
- `minecraft:light_gray_concrete_stairs`
- `minecraft:light_gray_wool_double_slab`
- `minecraft:light_gray_wool_slab`
- `minecraft:light_gray_wool_stairs`
- `minecraft:lime_concrete_double_slab`
- `minecraft:lime_concrete_slab`
- `minecraft:lime_concrete_stairs`
- `minecraft:lime_wool_double_slab`
- `minecraft:lime_wool_slab`
- `minecraft:lime_wool_stairs`
- `minecraft:magenta_concrete_double_slab`
- `minecraft:magenta_concrete_slab`
- `minecraft:magenta_concrete_stairs`
- `minecraft:magenta_wool_double_slab`
- `minecraft:magenta_wool_slab`
- `minecraft:magenta_wool_stairs`
- `minecraft:orange_concrete_double_slab`
- `minecraft:orange_concrete_slab`
- `minecraft:orange_concrete_stairs`
- `minecraft:orange_wool_double_slab`
- `minecraft:orange_wool_slab`
- `minecraft:orange_wool_stairs`
- `minecraft:pink_concrete_double_slab`
- `minecraft:pink_concrete_slab`
- `minecraft:pink_concrete_stairs`
- `minecraft:pink_wool_double_slab`
- `minecraft:pink_wool_slab`
- `minecraft:pink_wool_stairs`
- `minecraft:purple_concrete_double_slab`
- `minecraft:purple_concrete_slab`
- `minecraft:purple_concrete_stairs`
- `minecraft:purple_wool_double_slab`
- `minecraft:purple_wool_slab`
- `minecraft:purple_wool_stairs`
- `minecraft:red_concrete_double_slab`
- `minecraft:red_concrete_slab`
- `minecraft:red_concrete_stairs`
- `minecraft:red_shrub`
- `minecraft:red_wool_double_slab`
- `minecraft:red_wool_slab`
- `minecraft:red_wool_stairs`
- `minecraft:shelf_mushroom`
- `minecraft:white_concrete_double_slab`
- `minecraft:white_concrete_slab`
- `minecraft:white_concrete_stairs`
- `minecraft:white_wool_double_slab`
- `minecraft:white_wool_slab`
- `minecraft:white_wool_stairs`
- `minecraft:yellow_concrete_double_slab`
- `minecraft:yellow_concrete_slab`
- `minecraft:yellow_concrete_stairs`
- `minecraft:yellow_wool_double_slab`
- `minecraft:yellow_wool_slab`
- `minecraft:yellow_wool_stairs`

**creative layout** (325)

- `CONSTRUCTION: 587 -> 516 entries`
- `CONSTRUCTION + [group itemGroup.name.bars icon minecraft:iron_bars {}]`
- `CONSTRUCTION + [group itemGroup.name.fence icon minecraft:oak_fence {}]`
- `CONSTRUCTION + [group itemGroup.name.glassPane icon minecraft:glass_pane {}]`
- `CONSTRUCTION + [group itemGroup.name.stairs icon minecraft:stone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }]`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:exposed_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:iron_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:oxidized_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:waxed_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:waxed_exposed_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:waxed_oxidized_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:waxed_weathered_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.bars / minecraft:weathered_copper_bars {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:acacia_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:bamboo_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:birch_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:cherry_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:crimson_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:dark_oak_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:jungle_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:mangrove_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:nether_brick_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:oak_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:pale_oak_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:spruce_fence {}`
- `CONSTRUCTION + itemGroup.name.fence / minecraft:warped_fence {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:black_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:blue_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:brown_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:cyan_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:gray_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:green_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:light_blue_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:light_gray_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:lime_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:magenta_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:orange_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:pink_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:purple_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:red_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:white_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.glassPane / minecraft:yellow_stained_glass_pane {}`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:acacia_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:andesite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:bamboo_mosaic_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:bamboo_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:birch_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:blackstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:cherry_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:cinnabar_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:cinnabar_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:cobbled_deepslate_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:crimson_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:dark_oak_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:dark_prismarine_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:deepslate_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:deepslate_tile_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:diorite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:end_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:exposed_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:granite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:jungle_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:mangrove_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:mossy_cobblestone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:mossy_stone_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:mud_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:nether_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:normal_stone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:oak_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:oxidized_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:pale_oak_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_andesite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_blackstone_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_blackstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_cinnabar_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_deepslate_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_diorite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_granite_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_sulfur_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:polished_tuff_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:prismarine_bricks_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:prismarine_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:purpur_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:quartz_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:red_nether_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:red_sandstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:resin_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:sandstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:smooth_quartz_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:smooth_red_sandstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:smooth_sandstone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:spruce_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:stone_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:stone_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:sulfur_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:sulfur_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:tuff_brick_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:tuff_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:warped_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:waxed_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:waxed_exposed_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:waxed_oxidized_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:waxed_weathered_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION + itemGroup.name.stairs / minecraft:weathered_cut_copper_stairs { "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - [group itemGroup.name.bars icon minecraft:iron_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }]`
- `CONSTRUCTION - [group itemGroup.name.fence icon minecraft:oak_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }]`
- `CONSTRUCTION - [group itemGroup.name.glassPane icon minecraft:glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }]`
- `CONSTRUCTION - [group itemGroup.name.stairs icon minecraft:stone_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }]`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:exposed_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:iron_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:oxidized_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:waxed_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:waxed_exposed_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:waxed_oxidized_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:waxed_weathered_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.bars / minecraft:weathered_copper_bars { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.door / minecraft:poplar_door`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:acacia_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:bamboo_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:birch_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:cherry_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:crimson_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:dark_oak_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:jungle_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:mangrove_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:nether_brick_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:oak_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:pale_oak_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:poplar_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:spruce_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fence / minecraft:warped_fence { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.fenceGate / minecraft:poplar_fence_gate { "in_wall_bit": 0b, "minecraft:cardinal_direction": "south", "open_bit": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:black_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:blue_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:brown_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:cyan_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:gray_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:green_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:light_blue_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:light_gray_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:lime_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:magenta_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:orange_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:pink_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:purple_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:red_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:white_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.glassPane / minecraft:yellow_stained_glass_pane { "minecraft:connection_east": 0b, "minecraft:connection_north": 0b, "minecraft:connection_south": 0b, "minecraft:connection_west": 0b }`
- `CONSTRUCTION - itemGroup.name.planks / minecraft:poplar_planks {}`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:black_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:black_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:blue_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:blue_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:brown_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:brown_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:cyan_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:cyan_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:gray_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:gray_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:green_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:green_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:light_blue_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:light_blue_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:light_gray_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:light_gray_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:lime_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:lime_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:magenta_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:magenta_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:orange_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:orange_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:pink_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:pink_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:poplar_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:purple_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:purple_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:red_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:red_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:white_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:white_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:yellow_concrete_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.slab / minecraft:yellow_wool_slab { "minecraft:vertical_half": "bottom" }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:acacia_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:andesite_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:bamboo_mosaic_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:bamboo_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:birch_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:black_concrete_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:black_wool_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:blackstone_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:blue_concrete_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:blue_wool_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:brick_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- `CONSTRUCTION - itemGroup.name.stairs / minecraft:brown_concrete_stairs { "minecraft:corner": "none", "upside_down_bit": 0b, "weirdo_direction": 0i }`
- ... 125 more

**removed item tags** (1)

- `minecraft:mushrooms_for_stew`

**item tag membership** (12)

- `minecraft:boat: +[] -[minecraft:poplar_boat, minecraft:poplar_chest_boat]`
- `minecraft:boats: +[] -[minecraft:poplar_boat, minecraft:poplar_chest_boat]`
- `minecraft:door: +[] -[minecraft:poplar_door]`
- `minecraft:hanging_sign: +[] -[minecraft:poplar_hanging_sign]`
- `minecraft:logs: +[] -[minecraft:poplar_log, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:logs_that_burn: +[] -[minecraft:poplar_log, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:planks: +[] -[minecraft:poplar_planks]`
- `minecraft:sign: +[] -[minecraft:poplar_hanging_sign, minecraft:poplar_sign]`
- `minecraft:sulfur_cube_archetype_bouncy: +[] -[minecraft:poplar_log, minecraft:poplar_planks, minecraft:poplar_wood, minecraft:stripped_poplar_log, minecraft:stripped_poplar_wood]`
- `minecraft:sulfur_cube_archetype_slow_bouncy: +[minecraft:observer, minecraft:redstone_lamp] -[]`
- `minecraft:vibration_damper: +[] -[minecraft:black_wool_slab, minecraft:black_wool_stairs, minecraft:blue_wool_slab, minecraft:blue_wool_stairs, minecraft:brown_wool_slab, minecraft:brown_wool_stairs, minecraft:cyan_wool_slab, minecraft:cyan_wool_stairs, minecraft:gray_wool_slab, minecraft:gray_wool_stairs, minecraft:green_wool_slab, minecraft:green_wool_stairs, minecraft:light_blue_wool_slab, minecraft:light_blue_wool_stairs, minecraft:light_gray_wool_slab, minecraft:light_gray_wool_stairs, minecraft:lime_wool_slab, minecraft:lime_wool_stairs, minecraft:magenta_wool_slab, minecraft:magenta_wool_stairs, minecraft:orange_wool_slab, minecraft:orange_wool_stairs, minecraft:pink_wool_slab, minecraft:pink_wool_stairs, minecraft:purple_wool_slab, minecraft:purple_wool_stairs, minecraft:red_wool_slab, minecraft:red_wool_stairs, minecraft:white_wool_slab, minecraft:white_wool_stairs, minecraft:yellow_wool_slab, minecraft:yellow_wool_stairs]`
- `minecraft:wooden_slabs: +[] -[minecraft:poplar_slab]`

**block defaults (non first state)** (2)

- `minecraft:ice_crystal: { "minecraft:block_face": "up" } -> first state`
- `minecraft:icicle: { "attached_bit": 0b, "dripstone_thickness": "tip", "hanging": 1b } -> first state`

**block to item relation** (121)

- `minecraft:black_concrete_double_slab: minecraft:black_concrete_slab -> no item`
- `minecraft:black_concrete_slab: minecraft:black_concrete_slab -> no item`
- `minecraft:black_concrete_stairs: minecraft:black_concrete_stairs -> no item`
- `minecraft:black_wool_double_slab: minecraft:black_wool_slab -> no item`
- `minecraft:black_wool_slab: minecraft:black_wool_slab -> no item`
- `minecraft:black_wool_stairs: minecraft:black_wool_stairs -> no item`
- `minecraft:blue_concrete_double_slab: minecraft:blue_concrete_slab -> no item`
- `minecraft:blue_concrete_slab: minecraft:blue_concrete_slab -> no item`
- `minecraft:blue_concrete_stairs: minecraft:blue_concrete_stairs -> no item`
- `minecraft:blue_wool_double_slab: minecraft:blue_wool_slab -> no item`
- `minecraft:blue_wool_slab: minecraft:blue_wool_slab -> no item`
- `minecraft:blue_wool_stairs: minecraft:blue_wool_stairs -> no item`
- `minecraft:brown_concrete_double_slab: minecraft:brown_concrete_slab -> no item`
- `minecraft:brown_concrete_slab: minecraft:brown_concrete_slab -> no item`
- `minecraft:brown_concrete_stairs: minecraft:brown_concrete_stairs -> no item`
- `minecraft:brown_wool_double_slab: minecraft:brown_wool_slab -> no item`
- `minecraft:brown_wool_slab: minecraft:brown_wool_slab -> no item`
- `minecraft:brown_wool_stairs: minecraft:brown_wool_stairs -> no item`
- `minecraft:cyan_concrete_double_slab: minecraft:cyan_concrete_slab -> no item`
- `minecraft:cyan_concrete_slab: minecraft:cyan_concrete_slab -> no item`
- `minecraft:cyan_concrete_stairs: minecraft:cyan_concrete_stairs -> no item`
- `minecraft:cyan_wool_double_slab: minecraft:cyan_wool_slab -> no item`
- `minecraft:cyan_wool_slab: minecraft:cyan_wool_slab -> no item`
- `minecraft:cyan_wool_stairs: minecraft:cyan_wool_stairs -> no item`
- `minecraft:gray_concrete_double_slab: minecraft:gray_concrete_slab -> no item`
- `minecraft:gray_concrete_slab: minecraft:gray_concrete_slab -> no item`
- `minecraft:gray_concrete_stairs: minecraft:gray_concrete_stairs -> no item`
- `minecraft:gray_wool_double_slab: minecraft:gray_wool_slab -> no item`
- `minecraft:gray_wool_slab: minecraft:gray_wool_slab -> no item`
- `minecraft:gray_wool_stairs: minecraft:gray_wool_stairs -> no item`
- `minecraft:green_concrete_double_slab: minecraft:green_concrete_slab -> no item`
- `minecraft:green_concrete_slab: minecraft:green_concrete_slab -> no item`
- `minecraft:green_concrete_stairs: minecraft:green_concrete_stairs -> no item`
- `minecraft:green_wool_double_slab: minecraft:green_wool_slab -> no item`
- `minecraft:green_wool_slab: minecraft:green_wool_slab -> no item`
- `minecraft:green_wool_stairs: minecraft:green_wool_stairs -> no item`
- `minecraft:light_blue_concrete_double_slab: minecraft:light_blue_concrete_slab -> no item`
- `minecraft:light_blue_concrete_slab: minecraft:light_blue_concrete_slab -> no item`
- `minecraft:light_blue_concrete_stairs: minecraft:light_blue_concrete_stairs -> no item`
- `minecraft:light_blue_wool_double_slab: minecraft:light_blue_wool_slab -> no item`
- `minecraft:light_blue_wool_slab: minecraft:light_blue_wool_slab -> no item`
- `minecraft:light_blue_wool_stairs: minecraft:light_blue_wool_stairs -> no item`
- `minecraft:light_gray_concrete_double_slab: minecraft:light_gray_concrete_slab -> no item`
- `minecraft:light_gray_concrete_slab: minecraft:light_gray_concrete_slab -> no item`
- `minecraft:light_gray_concrete_stairs: minecraft:light_gray_concrete_stairs -> no item`
- `minecraft:light_gray_wool_double_slab: minecraft:light_gray_wool_slab -> no item`
- `minecraft:light_gray_wool_slab: minecraft:light_gray_wool_slab -> no item`
- `minecraft:light_gray_wool_stairs: minecraft:light_gray_wool_stairs -> no item`
- `minecraft:lime_concrete_double_slab: minecraft:lime_concrete_slab -> no item`
- `minecraft:lime_concrete_slab: minecraft:lime_concrete_slab -> no item`
- `minecraft:lime_concrete_stairs: minecraft:lime_concrete_stairs -> no item`
- `minecraft:lime_wool_double_slab: minecraft:lime_wool_slab -> no item`
- `minecraft:lime_wool_slab: minecraft:lime_wool_slab -> no item`
- `minecraft:lime_wool_stairs: minecraft:lime_wool_stairs -> no item`
- `minecraft:magenta_concrete_double_slab: minecraft:magenta_concrete_slab -> no item`
- `minecraft:magenta_concrete_slab: minecraft:magenta_concrete_slab -> no item`
- `minecraft:magenta_concrete_stairs: minecraft:magenta_concrete_stairs -> no item`
- `minecraft:magenta_wool_double_slab: minecraft:magenta_wool_slab -> no item`
- `minecraft:magenta_wool_slab: minecraft:magenta_wool_slab -> no item`
- `minecraft:magenta_wool_stairs: minecraft:magenta_wool_stairs -> no item`
- `minecraft:orange_concrete_double_slab: minecraft:orange_concrete_slab -> no item`
- `minecraft:orange_concrete_slab: minecraft:orange_concrete_slab -> no item`
- `minecraft:orange_concrete_stairs: minecraft:orange_concrete_stairs -> no item`
- `minecraft:orange_poplar_leaves: minecraft:orange_poplar_leaves -> no item`
- `minecraft:orange_wool_double_slab: minecraft:orange_wool_slab -> no item`
- `minecraft:orange_wool_slab: minecraft:orange_wool_slab -> no item`
- `minecraft:orange_wool_stairs: minecraft:orange_wool_stairs -> no item`
- `minecraft:pink_concrete_double_slab: minecraft:pink_concrete_slab -> no item`
- `minecraft:pink_concrete_slab: minecraft:pink_concrete_slab -> no item`
- `minecraft:pink_concrete_stairs: minecraft:pink_concrete_stairs -> no item`
- `minecraft:pink_wool_double_slab: minecraft:pink_wool_slab -> no item`
- `minecraft:pink_wool_slab: minecraft:pink_wool_slab -> no item`
- `minecraft:pink_wool_stairs: minecraft:pink_wool_stairs -> no item`
- `minecraft:poplar_button: minecraft:poplar_button -> no item`
- `minecraft:poplar_door: minecraft:poplar_door -> no item`
- `minecraft:poplar_double_slab: minecraft:poplar_slab -> no item`
- `minecraft:poplar_fence: minecraft:poplar_fence -> no item`
- `minecraft:poplar_fence_gate: minecraft:poplar_fence_gate -> no item`
- `minecraft:poplar_hanging_sign: minecraft:poplar_hanging_sign -> no item`
- `minecraft:poplar_log: minecraft:poplar_log -> no item`
- `minecraft:poplar_planks: minecraft:poplar_planks -> no item`
- `minecraft:poplar_pressure_plate: minecraft:poplar_pressure_plate -> no item`
- `minecraft:poplar_sapling: minecraft:poplar_sapling -> no item`
- `minecraft:poplar_shelf: minecraft:poplar_shelf -> no item`
- `minecraft:poplar_slab: minecraft:poplar_slab -> no item`
- `minecraft:poplar_stairs: minecraft:poplar_stairs -> no item`
- `minecraft:poplar_standing_sign: minecraft:poplar_sign -> no item`
- `minecraft:poplar_trapdoor: minecraft:poplar_trapdoor -> no item`
- `minecraft:poplar_wall_sign: minecraft:poplar_sign -> no item`
- `minecraft:poplar_wood: minecraft:poplar_wood -> no item`
- `minecraft:purple_concrete_double_slab: minecraft:purple_concrete_slab -> no item`
- `minecraft:purple_concrete_slab: minecraft:purple_concrete_slab -> no item`
- `minecraft:purple_concrete_stairs: minecraft:purple_concrete_stairs -> no item`
- `minecraft:purple_wool_double_slab: minecraft:purple_wool_slab -> no item`
- `minecraft:purple_wool_slab: minecraft:purple_wool_slab -> no item`
- `minecraft:purple_wool_stairs: minecraft:purple_wool_stairs -> no item`
- `minecraft:red_concrete_double_slab: minecraft:red_concrete_slab -> no item`
- `minecraft:red_concrete_slab: minecraft:red_concrete_slab -> no item`
- `minecraft:red_concrete_stairs: minecraft:red_concrete_stairs -> no item`
- `minecraft:red_poplar_leaves: minecraft:red_poplar_leaves -> no item`
- `minecraft:red_shrub: minecraft:red_shrub -> no item`
- `minecraft:red_wool_double_slab: minecraft:red_wool_slab -> no item`
- `minecraft:red_wool_slab: minecraft:red_wool_slab -> no item`
- `minecraft:red_wool_stairs: minecraft:red_wool_stairs -> no item`
- `minecraft:shelf_mushroom: minecraft:shelf_mushroom -> no item`
- `minecraft:straw_bed: minecraft:straw_bed -> no item`
- `minecraft:stripped_poplar_log: minecraft:stripped_poplar_log -> no item`
- `minecraft:stripped_poplar_wood: minecraft:stripped_poplar_wood -> no item`
- `minecraft:white_concrete_double_slab: minecraft:white_concrete_slab -> no item`
- `minecraft:white_concrete_slab: minecraft:white_concrete_slab -> no item`
- `minecraft:white_concrete_stairs: minecraft:white_concrete_stairs -> no item`
- `minecraft:white_wool_double_slab: minecraft:white_wool_slab -> no item`
- `minecraft:white_wool_slab: minecraft:white_wool_slab -> no item`
- `minecraft:white_wool_stairs: minecraft:white_wool_stairs -> no item`
- `minecraft:yellow_concrete_double_slab: minecraft:yellow_concrete_slab -> no item`
- `minecraft:yellow_concrete_slab: minecraft:yellow_concrete_slab -> no item`
- `minecraft:yellow_concrete_stairs: minecraft:yellow_concrete_stairs -> no item`
- `minecraft:yellow_poplar_leaves: minecraft:yellow_poplar_leaves -> no item`
- `minecraft:yellow_wool_double_slab: minecraft:yellow_wool_slab -> no item`
- `minecraft:yellow_wool_slab: minecraft:yellow_wool_slab -> no item`
- `minecraft:yellow_wool_stairs: minecraft:yellow_wool_stairs -> no item`

## Add-on content log changes

- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/enchantment_power_transmitter.json |  -> components -> minecraft:enchantment_power_transmitter: this component was found in the input, but is not present in the Schema`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/geometry_full.json |  -> components -> minecraft:geometry -> bone_visibility -> bone_a: expected an object`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/geometry_full.json |  -> components -> minecraft:geometry -> bone_visibility -> bone_a: invalid numeric value`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/geometry_full.json |  -> components -> minecraft:geometry -> bone_visibility -> bone_a: invalid string`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/geometry_full.json |  -> components -> minecraft:geometry: invalid string`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/neighbor_change.json |  -> components -> minecraft:neighbor_change: this component was found in the input, but is not present in the Schema`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/neighbor_change_default.json |  -> components -> minecraft:neighbor_change: this component was found in the input, but is not present in the Schema`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/sound.json |  -> components -> minecraft:sound: this component was found in the input, but is not present in the Schema`
- new: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/vibration_properties.json |  -> components -> minecraft:vibration_properties: this component was found in the input, but is not present in the Schema`
- gone: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/collision_box_box.json |  -> components -> minecraft:collision_box: expected an array`
- gone: `ERROR [Blocks] block_definitions | behavior_packs/paletter_capture | blocks/collision_box_box.json |  -> components -> minecraft:collision_box: invalid numeric value`

## Experiments

Source: **discovered** (47714 candidates), attribution: each, 81 s for this step.

| Toggle | Status | Log code | UI key | Name | Items | Blocks | Creative stacks |
|---|---|---|---|---|---|---|---|
| `experimental_creator_cameras` | current | exca | `createWorldScreen.experimentalCreatorCameraFeatures` | Experimental Creator Camera Features | 0 | 0 | 0 |
| `gametest` | current | gtst | `createWorldScreen.experimentalGameTest` | Beta APIs | 0 | 0 | 0 |
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

**experimental_creator_cameras**: no item, block or creative change

**gametest**: no item, block or creative change

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

