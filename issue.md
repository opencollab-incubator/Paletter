<!-- report-hash: be89c8a8f61889f2 -->
Run: https://github.com/opencollab-incubator/Paletter/actions/runs/43877807113

# Paletter data verification: BDS 1.26.63.1

- **Date**: 2026-11-16
- **Mode**: verify only (nothing written)
- **BDS**: 1.26.63.1 linux (https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.26.63.1.zip)
- **Work directory**: `/home/runner/work/_temp/bds-cache/runs/linux-1.26.63.1` (logs, captures)
- **Server**: `/home/runner/work/_temp/bds-cache/servers/linux-1.26.63.1` (removed after the run)
- **Protocol**: 2225 (RakNet pong: 1.26.63)
- **Transport**: RakNet
- **Bundled version**: 26.60 (existing, same protocol)
- **Result**: FAILED

## WIRE FORMAT CHANGES

> **Component NBT that BDS sends changed shape.** Generated component types encode the old shape; review the component model (data/model) and the generated types before shipping this version.

- **spec capture vs 1.26.52.3: block:minecraft:collision_box: 18 leaves differ (18 with another value); its hand transform `Transforms.collisionBoxes` needs a protocol check**
- **spec capture vs 1.26.52.3: block:minecraft:embedded_visual: 126 leaves differ (0 with another value); its hand transform `Transforms.embeddedGeometry` needs a protocol check**
- **spec capture vs 1.26.52.3: block:minecraft:item_visual: 96 leaves differ (0 with another value); its hand transform `Transforms.materialInstancesPacked` needs a protocol check**
- **spec capture vs 1.26.52.3: item:minecraft:block_placer: 2 leaves differ (2 with another value); its hand transform `Transforms.blockName` needs a protocol check**

## Checks

| Status | Check | Detail |
|---|---|---|
| PASS | Script API dump | BDS reports 1.26.63.1; 1623 items, 1480 blocks, 77 item tags |
| PASS | vanilla capture | hashed block ids; protocol 2225 |
| PASS | Block hashes (vanilla) | all 1065 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | vanilla capture (sequential ids) | sequential block ids; protocol 2225 |
| PASS | Experiments: discovery | 85653 candidates (binary bedrock_server, experimental packs, known ids); BDS kept 32 toggles in level.dat and logged as many active |
| PASS | experiments capture (all toggles) | hashed block ids; protocol 2225 |
| PASS | Experiments: current toggles | 7 streamed in start game [experimental_creator_cameras, gametest, poi, upcoming_creator_features, villager_trades_rebalance, voxel_shapes, y_2026_drop_4], 25 retired (kept, not streamed); 7 `createWorldScreen.experimental*` UI keys in the binary |
| PASS | Experiments: resource pack stack | lists the same toggles as start game |
| PASS | experiments capture (experimental_creator_cameras) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (experimental_creator_cameras) | start game reports [experimental_creator_cameras] |
| PASS | experiments capture (gametest) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (gametest) | start game reports [gametest] |
| PASS | experiments capture (poi) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (poi) | start game reports [poi] |
| PASS | experiments capture (upcoming_creator_features) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (upcoming_creator_features) | start game reports [upcoming_creator_features] |
| PASS | experiments capture (villager_trades_rebalance) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (villager_trades_rebalance) | start game reports [villager_trades_rebalance] |
| PASS | experiments capture (voxel_shapes) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (voxel_shapes) | start game reports [voxel_shapes] |
| PASS | experiments capture (y_2026_drop_4) | hashed block ids; protocol 2225 |
| PASS | Experiments applied (y_2026_drop_4) | start game reports [y_2026_drop_4] |
| PASS | add-on capture | hashed block ids; protocol 2225 |
| PASS | Block hashes (add-on) | all 2256 creative block ids are FNV-1a 32 hashes of palette states |
| PASS | Experiments applied (add-on world) | start game reports [experimental_creator_cameras, gametest, poi, upcoming_creator_features, villager_trades_rebalance, voxel_shapes, y_2026_drop_4] |
| PASS | add-on capture (sequential ids) | sequential block ids; protocol 2225 |
| PASS | creative order probe capture | hashed block ids; protocol 2225 |
| PASS | Block numbering | reference palette 26.60: 23960 states in FNV-1 64 name order |
| PASS | Block numbering | vanilla: 1019 sequential creative block ids match the FNV-1 64 name sort of 23960 states |
| PASS | Block numbering | add-on: 1082 sequential creative block ids match the FNV-1 64 name sort of 26346 states |
| PASS | Block numbering | add-on: 65 custom block items have runtime id 255 - block_id |
| FAIL | Block numbering | add-on: custom block ids are not 10000+ in descending identifier order; first difference at #0: paletter:connection_rule has the id of paletter:vibration_properties |
| FAIL | Voxel shapes | java.io.IOException: BDS answers no RakNet ping with a protocol; the voxel shape capture needs a RakNet server |
| PASS | Script API vs baseline dump | identical |
| DIFF | BDS inventory vs 1.26.52.3 | 97 new or removed properties |
| PASS | Network data vs bundled 26.60 | identical (items, runtime ids, components, data driven blocks, creative layout) |
| PASS | Item tags and block defaults vs bundled 26.60 | identical |
| PASS | Add-on content log | 4 known rejections/warnings, unchanged |
| INFO | Experiments: creator docs | the docs name experiments this BDS does not have: [Holiday Creator Features] |
| PASS | Experiment marks vs bundled 26.60 | identical |
| DIFF | Creative order (add-on) | BDS places custom content differently from CreativeOrdering.BDS: 1+ differences |
| DIFF | Creative order (creative order probe) | BDS places custom content differently from CreativeOrdering.BDS: 2+ differences |
| PASS | Component shapes | add-on components vs 1.26.63.1: identical |
| PASS | Component values | add-on components vs 1.26.63.1: identical |
| PASS | Component shapes | vanilla components vs bundled 26.60: identical |
| PASS | Component values | vanilla components vs bundled 26.60: identical |
| PASS | Spec coverage | every captured wire path is in data/model/mappings.json or has a known reason (listed below) |
| DIFF | Add-on capture vs data/bds | `data/bds/1.26.63.1/addon` is missing or differs |
| INFO | Component model | new add-on capture staged; run the model checks (checkMappings, checkComponentApi) and review the diff |
| INFO | Spec capture | `/home/runner/work/_temp/bds-cache/runs/linux-1.26.63.1/spec` |
| DIFF | Spec capture values | 242 leaves in 4 components differ from 1.26.52.3, variant by variant |
| INFO | Component model | mappings not inferred (verify only or preview not imported) |
| PASS | Vanilla constants | 0 added, 0 deprecated, 0 blocks with a lookup table |

## TODO (needs a decision)

- [ ] Creative order: BDS changed where it puts custom items/blocks (add-on); see data/bds/CREATIVE-ORDER.md, update CreativeOrdering.BDS and the committed probe captures
- [ ] Creative order: BDS changed where it puts custom items/blocks (creative order probe); see data/bds/CREATIVE-ORDER.md, update CreativeOrdering.BDS and the committed probe captures
- [ ] VALUE CHANGE in the spec capture vs 1.26.52.3: review the protocol windows of the components listed in the report

## Repository files

- would update `data/bds/1.26.63.1/experiments.json` (experiment record)
- would update `data/bds/1.26.63.1/addon/custom_block_properties.nbt` (component capture custom_block_properties.nbt)
- would update `data/bds/1.26.63.1/addon/custom_item_registry.nbt` (component capture custom_item_registry.nbt)

## Failure: Voxel shapes

```
java.io.IOException: BDS answers no RakNet ping with a protocol; the voxel shape capture needs a RakNet server
	at org.geysermc.paletter.tools.shape.ShapeProbe.capture(ShapeProbe.java:127)
	at org.geysermc.paletter.tools.update.Update.voxelShapes(Update.java:228)
	at org.geysermc.paletter.tools.update.Update.pipeline(Update.java:180)
	at org.geysermc.paletter.tools.update.Update.run(Update.java:139)
	at org.geysermc.paletter.tools.Cli$UpdateCommand.call(Cli.java:274)
	at org.geysermc.paletter.tools.Cli$UpdateCommand.call(Cli.java:242)
	at picocli.CommandLine.executeUserObject(CommandLine.java:2031)
	at picocli.CommandLine.access$1500(CommandLine.java:148)
	at picocli.CommandLine$RunLast.executeUserObjectOfLastSubcommandWithSameParent(CommandLine.java:2469)
	at picocli.CommandLine$RunLast.handle(CommandLine.java:2461)
	at picocli.CommandLine$RunLast.handle(CommandLine.java:2423)
	at picocli.CommandLine$AbstractParseResultHandler.execute(CommandLine.java:2277)
	at picocli.CommandLine$RunLast.execute(CommandLine.java:2425)
	at picocli.CommandLine.execute(CommandLine.java:2174)
	at org.geysermc.paletter.tools.Cli.main(Cli.java:67)
```

## BDS inventory: BDS 1.26.63.1 vs 1.26.52.3

+ Script API ScriptModuleMinecraft ScriptBlock hasScheduledNamedTick()
+ Script API ScriptModuleMinecraft ScriptBlock removeScheduledNamedTick()
+ Script API ScriptModuleMinecraft ScriptBlock scheduleNamedTick()
+ Script API ScriptModuleMinecraft ScriptBlockCustomComponentNamedTickAfterEvent isName()
+ Script API ScriptModuleMinecraft ScriptBlockCustomComponentNeighborChangedAfterEvent getChanges()
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent get mDefinitionId
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent get mUniqueId
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent getEntities()
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent release()
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent size()
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent store()
+ Script API ScriptModuleMinecraft ScriptBlockEntityStorageComponent transfer()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeCraftingComponent get getPlayers
+ Script API ScriptModuleMinecraft ScriptBlockRecipeCraftingComponent getCraftingContext()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent get getInputSlotCount
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent getInputItem()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent getInputSlotEnabled()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent getOutputItem()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent setInputItem()
+ Script API ScriptModuleMinecraft ScriptBlockRecipeProcessingComponent setInputSlotEnabled()
+ Script API ScriptModuleMinecraft ScriptBlockVibrationPropertiesComponent getCanDampenVibrations()
+ Script API ScriptModuleMinecraft ScriptBlockVibrationPropertiesComponent getCanOccludeVibrations()
+ Script API ScriptModuleMinecraft ScriptInvalidRecipeError get mRecipeId
+ Script API ScriptModuleMinecraft ScriptNeighborChange get mBlockPermutation
+ Script API ScriptModuleMinecraft ScriptNeighborChange get mDirection
+ Script API ScriptModuleMinecraft ScriptNeighborChange get mPreviousPermutation
+ Script API ScriptModuleMinecraft ScriptNeighborChange set mBlockPermutation
+ Script API ScriptModuleMinecraft ScriptNeighborChange set mDirection
+ Script API ScriptModuleMinecraft ScriptNeighborChange set mPreviousPermutation
+ Script API ScriptModuleMinecraft ScriptPlayerCraftRecipeAfterEvent get mBlock
+ Script API ScriptModuleMinecraft ScriptPlayerCraftRecipeAfterEvent get mItemStack
+ Script API ScriptModuleMinecraft ScriptPlayerCraftRecipeAfterEvent get mPlayer
+ Script API ScriptModuleMinecraft ScriptPlayerCursorItemGrabAfterEvent get mItem
+ Script API ScriptModuleMinecraft ScriptPlayerCursorItemGrabAfterEvent get mPlayerHandle
+ Script API ScriptModuleMinecraft ScriptPlayerCursorItemReleaseAfterEvent get mItem
+ Script API ScriptModuleMinecraft ScriptPlayerCursorItemReleaseAfterEvent get mPlayerHandle
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent get mAimDirection
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent get mCancel
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent get mItemStack
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent get mPlayer
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent get mTarget
+ Script API ScriptModuleMinecraft ScriptPlayerItemAttackEntityBeforeEvent set mCancel
+ Script API ScriptModuleMinecraft ScriptPrimitiveShape get getDefaultVisibleToAll
+ Script API ScriptModuleMinecraft ScriptPrimitiveShape get getHiddenFrom
+ Script API ScriptModuleMinecraft ScriptPrimitiveShape set setDefaultVisibleToAll
+ Script API ScriptModuleMinecraft ScriptPrimitiveShape set setHiddenFrom
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext get getInputSlotCount
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext get getValidRecipes
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext get isValid
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext getInputItem()
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext getOutputItem()
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext setInputItem()
+ Script API ScriptModuleMinecraft ScriptRecipeCraftingContext setSelectedRecipe()
+ Script API ScriptModuleMinecraft ScriptTickingAreaManager get _getMaxNumberOfChunks
+ Script API ScriptModuleMinecraft ScriptWorldBeforeEvents get mBeforePlayerItemAttackEntityEventSignal
+ Script API ScriptModuleMinecraftServerUI ScriptButtonOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptButtonOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptCustomForm multiButtonRow()
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData get mLabel
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData get mOnclick
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData get mOptions
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData set mLabel
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData set mOnclick
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormButtonData set mOptions
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptCustomFormOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptDividerOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptDividerOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptDropdownOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptDropdownOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBox bodyWithOptions()
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBox button3WithOptions()
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxBodyOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxBodyOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxButtonOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxButtonOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxOptions get mShowHorizontalButtons
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxOptions set mShowHorizontalButtons
+ Script API ScriptModuleMinecraftServerUI ScriptMessageBoxOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMultiButtonRowOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMultiButtonRowOptions get mVisible
+ Script API ScriptModuleMinecraftServerUI ScriptMultiButtonRowOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptMultiButtonRowOptions set mVisible
+ Script API ScriptModuleMinecraftServerUI ScriptSliderOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptSliderOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptTextFieldOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptTextFieldOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptTextOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptTextOptions set mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptToggleOptions get mStyleId
+ Script API ScriptModuleMinecraftServerUI ScriptToggleOptions set mStyleId
+ block component BlockNeighborChangeConfigurationComponentDescription (BlockNeighborChangeConfigurationComponentDescription)
+ block component minecraft:block_entity key entity_storage
+ block component minecraft:enchantment_power_transmitter (BlockEnchantmentPowerTransmitterDescription)
+ block component minecraft:vibration_properties (BlockVibrationPropertiesDescription)
- Script API ScriptModuleMinecraft ScriptTickingAreaManager get mMaxNumberOfChunks

## Experiments

Source: **discovered** (85653 candidates), attribution: each, 55 s for this step.

| Toggle | Status | Log code | UI key | Name | Items | Blocks | Creative stacks |
|---|---|---|---|---|---|---|---|
| `experimental_creator_cameras` | current | exca | `createWorldScreen.experimentalCreatorCameraFeatures` | Experimental Creator Camera Features | 0 | 0 | 0 |
| `gametest` | current | gtst | `createWorldScreen.experimentalGameTest` | Beta APIs | 0 | 0 | 0 |
| `poi` | current | epoi | `createWorldScreen.experimentalPoi` | Points of Interest (POI) | 0 | 0 | 0 |
| `upcoming_creator_features` | current | ucft | `createWorldScreen.experimentalCreatorFeatures` | Upcoming Creator Features | 0 | 0 | 0 |
| `villager_trades_rebalance` | current | vtra | `createWorldScreen.experimentalVillagerTradesRebalance` | Villager Trade Rebalancing | 0 | 0 | 0 |
| `voxel_shapes` | current | vavs | `createWorldScreen.experimentalVoxelShapeFeatures` | Experimental Voxel Shape Features | 0 | 0 | 0 |
| `y_2026_drop_4` | current | 26d4 | `createWorldScreen.experimentalY2026Drop4` | Drop 4 of 2026 | 4 | 0 | 12 |
| `armadillo` | retired | | | | | | |
| `bundles` | retired | | | | | | |
| `camera_aim_assist` | retired | | | | | | |
| `caves_and_cliffs` | retired | | | | | | |
| `creator_world_clocks` | retired | | | | | | |
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

**experimental_creator_cameras**: no item, block or creative change

**gametest**: no item, block or creative change

**poi**: no item, block or creative change

**upcoming_creator_features**: no item, block or creative change

**villager_trades_rebalance**: no item, block or creative change

**voxel_shapes**: no item, block or creative change

**y_2026_drop_4**

- items added (1): `minecraft:ice_ball`
- items changed (2): `minecraft:ice_crystal`, `minecraft:icicle`
- Script API items added (4): `minecraft:frostbite_spawn_egg`, `minecraft:ice_ball`, `minecraft:ice_crystal`, `minecraft:icicle`
- creative stacks added (12): `minecraft:arrow:48`, `minecraft:arrow:49`, `minecraft:frostbite_spawn_egg`, `minecraft:ice_ball`, `minecraft:ice_crystal`, `minecraft:icicle`, `minecraft:lingering_potion:47`, `minecraft:lingering_potion:48`, `minecraft:potion:47`, `minecraft:potion:48`, `minecraft:splash_potion:47`, `minecraft:splash_potion:48`

**All toggles together**

- items added (1): `minecraft:ice_ball`
- items changed (2): `minecraft:ice_crystal`, `minecraft:icicle`
- Script API items added (4): `minecraft:frostbite_spawn_egg`, `minecraft:ice_ball`, `minecraft:ice_crystal`, `minecraft:icicle`
- creative stacks added (12): `minecraft:arrow:48`, `minecraft:arrow:49`, `minecraft:frostbite_spawn_egg`, `minecraft:ice_ball`, `minecraft:ice_crystal`, `minecraft:icicle`, `minecraft:lingering_potion:47`, `minecraft:lingering_potion:48`, `minecraft:potion:47`, `minecraft:potion:48`, `minecraft:splash_potion:47`, `minecraft:splash_potion:48`

Experiments the creator docs name (`data/model/components.json`):

- Holiday Creator Features: **no toggle of this BDS**
- Upcoming Creator Features: `upcoming_creator_features`
- experimental (toggle not named): **no toggle of this BDS**

## Creative order: add-on

- `node 24: BDS construction (loose) [minecraft:purpur_block, minecraft:purpur_pillar, minecraft:packed_mud, minecraft:mud_bricks, paletter:tick, paletter:transformation, paletter:liquid_detection, paletter:random_offset, paletter:destruction_particles, paletter:support, paletter:destruction_particles_min, paletter:collision_box_list, paletter:flammable_true, paletter:light_emission, paletter:leashable, paletter:placement_filter, paletter:chest_obstruction, paletter:embedded_visual, paletter:map_color_rgb, paletter:map_color_full, paletter:block_entity, paletter:movable_immovable, paletter:loot, paletter:precipitation_interactions, paletter:light_dampening, paletter:destructible_by_mining_false, paletter:redstone_producer_min, paletter:movable_sticky, paletter:flower_pottable, paletter:item_visual, paletter:destructible_by_explosion_false, paletter:material_instances_min, paletter:redstone_conductivity, paletter:collision_box_true, paletter:connection_trait_block, paletter:multi_block, paletter:enchantment_power_transmitter, paletter:redstone_producer, paletter:material_instances, paletter:neighbor_change, paletter:states_block, paletter:transformation_rotation, paletter:neighbor_change_default, paletter:geometry_uv_lock_list, paletter:redstone_consumer, paletter:leashable_default, paletter:destructible_by_explosion, paletter:collision_box_false, paletter:map_color_hex, paletter:connection_rule_default, paletter:flammable, paletter:collision_box_single_list, paletter:geometry_short, paletter:entity_fall_on, paletter:selection_box_box, paletter:instrument_sound, paletter:replaceable, paletter:destructible_by_mining, paletter:sound, paletter:geometry_full, paletter:selection_box_false, paletter:connection_rule, paletter:friction, paletter:vibration_properties, paletter:geometry_full_block, paletter:crafting_table] / Paletter construction (loose) [minecraft:purpur_block, minecraft:purpur_pillar, minecraft:packed_mud, minecraft:mud_bricks, paletter:vibration_properties, paletter:states_block, paletter:transformation_rotation, paletter:transformation, paletter:crafting_table, paletter:tick, paletter:entity_fall_on, paletter:sound, paletter:support, paletter:enchantment_power_transmitter, paletter:redstone_conductivity, paletter:selection_box_false, paletter:collision_box_false, paletter:selection_box_box, paletter:replaceable, paletter:redstone_producer_min, paletter:redstone_producer, paletter:leashable, paletter:redstone_consumer, paletter:destructible_by_mining, paletter:embedded_visual, paletter:leashable_default, paletter:random_offset, paletter:geometry_short, paletter:precipitation_interactions, paletter:movable_immovable, paletter:placement_filter, paletter:instrument_sound, paletter:block_entity, paletter:neighbor_change_default, paletter:neighbor_change, paletter:multi_block, paletter:movable_sticky, paletter:destructible_by_explosion_false, paletter:material_instances_min, paletter:destructible_by_explosion, paletter:material_instances, paletter:map_color_rgb, paletter:destruction_particles, paletter:map_color_hex, paletter:map_color_full, paletter:loot, paletter:liquid_detection, paletter:light_emission, paletter:light_dampening, paletter:item_visual, paletter:geometry_uv_lock_list, paletter:geometry_full_block, paletter:geometry_full, paletter:friction, paletter:flower_pottable, paletter:flammable_true, paletter:destruction_particles_min, paletter:flammable, paletter:destructible_by_mining_false, paletter:connection_trait_block, paletter:connection_rule_default, paletter:connection_rule, paletter:collision_box_true, paletter:collision_box_single_list, paletter:collision_box_list, paletter:chest_obstruction]`

## Creative order: creative order probe

- `node 24: BDS construction (loose) [minecraft:purpur_block, minecraft:purpur_pillar, minecraft:packed_mud, minecraft:mud_bricks, zzz:block_c, exp:block_b, exp:block_hidden, exp:block_placed, exp:block_a, aaa:block_d, exp:construction_loose] / Paletter construction (loose) [minecraft:purpur_block, minecraft:purpur_pillar, minecraft:packed_mud, minecraft:mud_bricks, zzz:block_c, exp:block_b, exp:block_placed, exp:block_hidden, exp:block_a, aaa:block_d, exp:construction_loose]`
- `node 130: BDS items (loose) [minecraft:target, minecraft:decorated_pot, minecraft:trial_key, minecraft:ominous_trial_key, exp:block_items, exp:block_with_item, aaa:loose_z] / Paletter items (loose) [minecraft:target, minecraft:decorated_pot, minecraft:trial_key, minecraft:ominous_trial_key, exp:block_with_item, exp:block_items, aaa:loose_z]`

## Wire paths the model does not cover

- block definition data around the components: written by BlockCompiler, checked by the conformance suite:
  - `block:block_definition blockTags`
  - `block:block_definition blockTags[]`
  - `block:block_definition vanilla_block_data.block_id`
- the creator docs do not describe the component, so the model has no fields for it:
  - `block:minecraft:leashable`
  - `block:minecraft:leashable offset`
  - `block:minecraft:leashable offset[]`
  - `block:minecraft:sound`
  - `block:minecraft:sound sound`

## Spec capture value changes vs 1.26.52.3

- `block:minecraft:collision_box`: its hand transform `Transforms.collisionBoxes` needs a protocol check
  - paletter:b_collision_box_11 (array<object>:[1]) minecraft:collision_box.boxes[0].maxX: 10.0 (float) -> 7.0 (float)
  - paletter:b_collision_box_11 (array<object>:[1]) minecraft:collision_box.boxes[0].minX: 9.0 (float) -> 6.0 (float)
  - paletter:b_collision_box_12 (array<object>:[2]) minecraft:collision_box.boxes[0].maxX: 10.0 (float) -> 7.0 (float)
  - paletter:b_collision_box_12 (array<object>:[2]) minecraft:collision_box.boxes[0].minX: 9.0 (float) -> 6.0 (float)
  - paletter:b_collision_box_12 (array<object>:[2]) minecraft:collision_box.boxes[1].maxX: 10.0 (float) -> 7.0 (float)
  - paletter:b_collision_box_12 (array<object>:[2]) minecraft:collision_box.boxes[1].minX: 9.0 (float) -> 6.0 (float)
  - ... 12 more
- `block:minecraft:embedded_visual`: its hand transform `Transforms.embeddedGeometry` needs a protocol check
  - paletter:b_embedded_visual_2 (base) minecraft:embedded_visual.geometry.n_way_visual_rotation.x: not sent -> paletter_a (string)
  - paletter:b_embedded_visual_2 (base) minecraft:embedded_visual.geometry.n_way_visual_rotation.y: not sent -> paletter_a (string)
  - paletter:b_embedded_visual_2 (base) minecraft:embedded_visual.geometry.n_way_visual_rotation.z: not sent -> paletter_a (string)
  - paletter:b_embedded_visual_5 (object:minimal) minecraft:embedded_visual.geometry.n_way_visual_rotation.x: not sent -> paletter_a (string)
  - paletter:b_embedded_visual_5 (object:minimal) minecraft:embedded_visual.geometry.n_way_visual_rotation.y: not sent -> paletter_a (string)
  - paletter:b_embedded_visual_5 (object:minimal) minecraft:embedded_visual.geometry.n_way_visual_rotation.z: not sent -> paletter_a (string)
  - ... 120 more
- `block:minecraft:item_visual`: its hand transform `Transforms.materialInstancesPacked` needs a protocol check
  - paletter:b_item_visual_10 (object:minimal) minecraft:item_visual.geometryDescription.n_way_visual_rotation.x: not sent -> paletter_a (string)
  - paletter:b_item_visual_10 (object:minimal) minecraft:item_visual.geometryDescription.n_way_visual_rotation.y: not sent -> paletter_a (string)
  - paletter:b_item_visual_10 (object:minimal) minecraft:item_visual.geometryDescription.n_way_visual_rotation.z: not sent -> paletter_a (string)
  - paletter:b_item_visual_11 (object:{}) minecraft:item_visual.geometryDescription.n_way_visual_rotation.x: not sent -> paletter_a (string)
  - paletter:b_item_visual_11 (object:{}) minecraft:item_visual.geometryDescription.n_way_visual_rotation.y: not sent -> paletter_a (string)
  - paletter:b_item_visual_11 (object:{}) minecraft:item_visual.geometryDescription.n_way_visual_rotation.z: not sent -> paletter_a (string)
  - ... 90 more
- `item:minecraft:block_placer`: its hand transform `Transforms.blockName` needs a protocol check
  - paletter:block_placer_6 (string:minecraft:ice_crystal) minecraft:block_placer.block:  (string) -> minecraft:ice_crystal (string)
  - paletter:block_placer_7 (string:minecraft:icicle) minecraft:block_placer.block:  (string) -> minecraft:icicle (string)

## Vanilla constants

From 26.60: {BedrockStates=124, state enums=24, BedrockBlocks=1480, BedrockItems=2080}

