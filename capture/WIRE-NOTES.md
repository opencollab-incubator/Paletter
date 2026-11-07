# Wire notes: what BDS sends

Everything here was observed on the wire: a headless client (`capture` module) logged into a local
Bedrock Dedicated Server **1.26.63.1** (protocol **2225**, `serverEngine` 1.26.63) and recorded the start game
packet, the item registry packet (`ItemComponentPacket`) and the creative content packet. The custom content comes
from the add-on in `addon/` (87 items, 80 blocks), which uses JSON `format_version` 1.26.60 and a world with the
experiments `upcoming_creator_features`, `gametest`, `experimental_creator_cameras`, `y_2026_drop_4` (the ones BDS
reported back in start game; `experimental_molang_features`, `experimental_vanilla_shapes`, `data_driven_biomes`
were set in `level.dat` too but are not echoed).

Raw data: `captures/1.26.63.1/addon/custom_item_registry.{nbt,snbt}` and `custom_block_properties.{nbt,snbt}`
(gzipped big endian NBT, and typed SNBT in wire order: `1b` byte, `2s` short, `3` int, `4L` long, `5.0f` float).
The `.snbt` files are the reference for every shape below; the add-on JSON that produced each entry is
`addon/behavior_pack/items/<name>.json` / `blocks/<name>.json` (entry `paletter:<name>`).

## Rerunning

```
# 1. BDS: copy a server, set in server.properties
#    transport=raknet  server-port=19140  server-portv6=19141  online-mode=false  enable-lan-visibility=false
#    block-network-ids-are-hashes=true  content-log-level=verbose
#    (BDS 1.26.x logs "NetherNet is the only supported transport", but RakNet logins work)
# 2. Vanilla capture: start BDS once (creates the world), then
./gradlew :capture:run --args="capture --out <dir> --bds-version 1.26.63.1"
./gradlew :capture:run --args="compare --capture <dir> --reference 26.60"
# 3. Custom content: python capture/addon/generate.py capture/addon, copy addon/behavior_pack and
#    addon/resource_pack into the server's behavior_packs/ and resource_packs/, list their uuids in
#    worlds/<level>/world_behavior_packs.json / world_resource_packs.json, stop BDS, then
./gradlew :capture:run --args="experiments --level-dat <world>/level.dat --enable upcoming_creator_features,gametest,y_2026_drop_4,experimental_creator_cameras"
#    start BDS and check its content log for rejected definitions, then
./gradlew :capture:run --args="capture --out <dir> --custom-out capture/captures/<bds version>/addon --bds-version <bds version>"
# 4. A new vanilla version: generator import --source <dir> (the vanilla capture is in the importer's layout)
```

`capture` writes the importer layout (`block_palette.nbt`, `runtime_item_states.json`, `item_components.nbt`,
`creative_items.json`, `data_driven_blocks.nbt`) plus `raw_start_game_block_properties.nbt`, `raw_item_registry.nbt`,
`raw_entity_identifiers.nbt` (wire order, unmodified) and `capture.json` (start game flags, experiments, counts,
hash verification).

## What the wire gives, and what not

| Data | On the wire? |
|---|---|
| Item registry: identifier, runtime id, `componentBased`, version, component NBT | yes, complete (`ItemComponentPacket`) |
| Creative inventory: groups (name, category, icon), stacks (item, damage, count, block runtime id, NBT) | yes, complete |
| Block properties of data driven blocks (vanilla ones and custom ones) | yes (start game `blockProperties`) |
| **Vanilla block palette** (names and states of every block state) | **no**. The client has it built in. `block_palette.nbt` is the reference palette (bundled 26.60) plus the states the client derives for blocks with block properties. It is *verified*: with `block-network-ids-are-hashes=true` every block runtime id BDS sends is a hash of the state, and all 1065 (vanilla) / 1124 (with add-on) creative block ids matched a palette hash. |
| Item tags, block default states | no (item tags appear per item as `item_tags`; `generator --bds-dump` still needed for the tag index and default states) |
| Block runtime ids in sequential mode | not captured (BDS was in hashed mode) |
| Server-only block/item components | not sent at all, see below |

Block network hash, verified: FNV-1a 32 over the little endian NBT (root name empty) of
`{name: <id>, states: {<sorted keys>}}`, no `version` tag. Custom blocks hash the same way, and their states include
the **trait states**, which the wire does not list in `properties` (see Traits). Default state = first value of
every property, then every trait state at its first value.

## Vanilla 1.26.63 vs bundled 26.60

The vanilla capture (no experiments, no add-on) is **identical** to the bundled 26.60 data: same 2080 items with the
same runtime ids, versions, `componentBased` flags and component NBT, the same 98 block properties, the same
creative layout (124 groups, 1980 stacks). No new bundled version was generated; `generator import` of the capture
would reproduce `26.60.nbt`. `captures/1.26.63.1/vanilla/compare-26.60.txt`.

With experiments and the add-on (`addon/compare-26.60.txt`), apart from the 152 custom items:
- **Custom items shift vanilla runtime ids.** BDS numbers data driven items after the last legacy item
  (`minecraft:end_crystal` 852): the add-on's items took 853..939 (alphabetical), the vanilla data driven items
  (`red_shrub`, spears, bundles, `breeze_rod`, trial keys, `wind_charge`, `apple`, `glow_berries`) moved up by the
  same amount. Item runtime ids are therefore per server content, not per version.
- Custom **block items** get `id = 255 - block_id` (block ids 10000+ in load order, so -9745..-9810),
  `version` NONE, `componentBased` false and empty data.
- `y_2026_drop_4` adds items (`minecraft:ice_ball`, ...) and turns `minecraft:ice_crystal` / `icicle` into
  component based items (version 2 -> 1) with new runtime ids.

## Common item shape

Every component based item: `{components: {item_properties: {...}, item_tags: [...], <component>: {...}}}`.
`item_properties` is always complete (all 18 keys) for custom items; the JSON components that feed it:

| item_properties key | type | fed by | notes |
|---|---|---|---|
| `allow_off_hand` | byte | `minecraft:allow_off_hand` | |
| `can_destroy_in_creative` | byte (default 1) | `minecraft:can_destroy_in_creative` | |
| `creative_category` | int | `description.menu_category.category` | 1 construction, 2 nature, 3 equipment, 4 items, 5 none |
| `creative_group` | string | `menu_category.group` | kept as written (`minecraft:itemGroup.name.sword`); the **creative packet** group name has the `minecraft:` prefix stripped (`itemGroup.name.sword`) |
| `hidden_in_commands` | byte | `menu_category.is_hidden_in_commands` | **2 = not set (default), 1 = true**, not a boolean |
| `damage` | int | `minecraft:damage` | |
| `enchantable_slot`, `enchantable_value` | string, int | `minecraft:enchantable` | default `"none"`, 0 |
| `foil` | byte | `minecraft:glint` | renamed |
| `hand_equipped`, `liquid_clipped`, `should_despawn`, `stacked_by_data` | byte | same name | `should_despawn` default 1 |
| `hover_text_color` | string | `minecraft:hover_text_color` | **converted to a formatting code**: `"red"` -> `"§c"`, `"dark_purple"` -> `"§5"`; only present when set |
| `max_stack_size` | int | `minecraft:max_stack_size`; forced to 1 by `minecraft:wearable` | |
| `minecraft:icon` | compound `{textures: {default: ...}}` | `minecraft:icon` | lives **only** in item_properties; string shorthand becomes `textures.default`; no top level icon component |
| `use_animation` | int | `minecraft:use_animation` | enum: eat 1, spear 6 (vanilla: drink 2, bow 4, ...) |
| `use_duration` | int (ticks) | `minecraft:use_modifiers.use_duration` (seconds) | `1.6` -> 32, `3600` -> 72000 |
| `mining_speed` | float 1.0 | — | always 1.0 for custom items |
| `frame_count` | int 1 | — | |

`item_tags` (list of string, top level sibling of the components) is filled from `minecraft:tags`.
Custom items without `minecraft:display_name` send no display name component (vanilla items always send one).

## Item components: wire shape vs JSON

`value` wrapping: every boolean/number/string shorthand component is sent as `{value: X}`, whether the JSON used
the shorthand or the full form.

| Component | JSON (add-on) | Wire | Differences |
|---|---|---|---|
| `minecraft:allow_off_hand` | `true` / `{value}` | `{value: byte}` | also `item_properties.allow_off_hand` |
| `minecraft:block_placer` | `{block, use_on, replace_block_item}` | `{alignedPlacement: byte, block: string, canUseBlockAsIcon: byte(1), replaceBlockItem: byte, use_on: [{name, states: {}, tags: ""}]}` | camelCase renames; `use_on` entries always expanded to `{name, states, tags}`; adds sibling `minecraft:publisher_on_use_on: {autoSucceedOnClient: 0b}` |
| `minecraft:bundle_interaction` | `{num_viewable_slots}` | `{num_viewable_slots: int}` | default 12; requires `storage_item` |
| `minecraft:can_destroy_in_creative` | bool | `{value: byte}` | |
| `minecraft:compostable` | `{composting_chance: 50}` | `{composting_chance: byte}` | byte (Geyser sends int) |
| `minecraft:cooldown` | `{category, duration, type?}` | `{category: string, duration: float, type: string}` | `type` always sent, default `"use"` |
| `minecraft:damage` | `5` / `{value: 300}` | `{value: short}` | short (Geyser sends byte); also `item_properties.damage` |
| `minecraft:damage_absorption` | `{absorbable_causes}` | **not sent** | server only |
| `minecraft:digger` | `{use_efficiency, destroy_speeds: [{block, speed}]}` | `{use_efficiency: byte, destroy_speeds: [{block: {name, states, tags}, speed: int}]}` | block descriptor always the full triple (`name` "" for tag filters, `tags` molang string); **state values become ints** (`pillar_axis: "y"` -> `0`); `speed` int |
| `minecraft:display_name` | `{value}` | `{value: string}` | localisation keys unchanged |
| `minecraft:durability` | `{max_durability, damage_chance?}` | `{max_durability: int, damage_chance: {min: int, max: int}}` | `damage_chance` always sent, default **100/100** for custom items (vanilla tools send 0..100) |
| `minecraft:durability_sensor` | `{durability_thresholds}` | **not sent** | server only |
| `minecraft:dyeable` | `{}` / `{default_color: "#ff0080"}` / `[10,20,30]` | `{default_color: [int, int, int]}` | hex and RGB both become an int list; default `[255,255,255]` |
| `minecraft:enchantable` | `{slot, value}` | `{slot: string, value: byte}` | value byte; mirrored into item_properties (int) |
| `minecraft:entity_placer` | `{entity, use_on?, dispense_on?}` | `{entity: "minecraft:pig<>", use_on: [...], dispense_on: [...]}` | **entity gets a `<>` (spawn event) suffix**; descriptors as in block_placer; adds `minecraft:publisher_on_use_on` |
| `minecraft:fire_resistant` | `{value}` only (bare `true` is rejected) | `{value: byte}` | |
| `minecraft:food` | `{nutrition, saturation_modifier, can_always_eat, using_converts_to}` | `{can_always_eat: byte, nutrition: int, saturation_modifier: float, using_converts_to: {} \| {name}}` | `using_converts_to` string -> `{name}`; default saturation 0.6 |
| `minecraft:fuel` | `{duration}` | `{duration: float}` | |
| `minecraft:glint` | bool | `{value: byte}` | also `item_properties.foil` |
| `minecraft:hand_equipped` | bool | `{value: byte}` | |
| `minecraft:hover_text_color` | `"red"` / `{value}` | `{value: "red"}` | component keeps the name; item_properties gets `§c` |
| `minecraft:icon` | string / `{textures}` | (item_properties only) | see above |
| `minecraft:interact_button` | `true` / `"text"` | `{interact_text: string, requires_interact: 1b}` | `true` -> `"action.interact.use"` |
| `minecraft:kinetic_weapon` | `{delay, reach, creative_reach, hitbox_margin, damage_multiplier, damage_modifier, *_conditions}` | `{minecraft:kinetic_weapon: {...}}` | **double nested**; `delay` short (sent, also by vanilla spears: 12s), conditions `{max_duration: short (-1 default), min_speed: float, min_relative_speed: float}`; `creative_reach` and each `*_conditions` omitted when absent; `reach` default {0, 3} |
| `minecraft:liquid_clipped` | bool | `{value: byte}` | |
| `minecraft:max_stack_size` | `16` / `{value}` | `{value: byte}` | byte |
| `minecraft:piercing_weapon` | `{reach, creative_reach?, hitbox_margin}` | `{reach: {min, max: float}, creative_reach?, hitbox_margin: float}` | `creative_reach` omitted when absent |
| `minecraft:projectile` | `{projectile_entity, minimum_critical_power?}` | `{projectile_entity: "minecraft:snowball<>", minimum_critical_power: float}` | namespace added, `<>` suffix |
| `minecraft:rarity` | `"rare"` / `{value}` | `{value: string}` | |
| `minecraft:record` | `{sound_event, duration?, comparator_signal?}` | `{sound_event: string, duration: float, comparator_signal: int}` | defaults 0.0 / 1 |
| `minecraft:repairable` | `{repair_items: [{items, repair_amount}]}` | `{repair_items: [{items: [{name}], repair_amount: float \| string}]}` | **`repair_amount` is a float for numbers and a string for molang** (both observed); items always `{name}` |
| `minecraft:shooter` | `{ammunition: [{item, ...}], max_draw_duration, scale_power_by_draw_duration, charge_on_draw}` | same keys; `ammunition[].item` -> `{name}`; all flags as bytes, always present | |
| `minecraft:should_despawn`, `minecraft:stacked_by_data` | bool | `{value: byte}` | |
| `minecraft:storage_item` | `{max_slots, allow_nested_storage_items, allowed_items, banned_items}` | same, items as `{name}`, empty lists typed END | defaults 64 / 1b |
| `minecraft:storage_weight_limit` | `{max_weight_limit}` | `{max_weight_limit: int}` | |
| `minecraft:storage_weight_modifier` | `{weight_in_storage_item}` | `{weight_in_storage_item: int}` | |
| `minecraft:swing_duration` | `{value}` | `{value: float}` | |
| `minecraft:swing_sounds` | `{attack_hit?, attack_miss?, attack_critical_hit?}` | same keys, strings, absent keys omitted | |
| `minecraft:tags` | `{tags}` | `{tags: [string]}` | also top level `item_tags` |
| `minecraft:throwable` | all optional | all six keys always sent (`do_swing_animation` byte, floats, `scale_power_by_draw_duration` byte) | defaults launch_power_scale 1, max_launch_power 1 |
| `minecraft:use_animation` | `"eat"` / `{value}` | `{value: string}` | also `item_properties.use_animation` (int enum) |
| `minecraft:use_modifiers` | `{use_duration, movement_modifier?, emit_vibrations?, start_sound?}` | `{use_duration: float, movement_modifier?: float, emit_vibrations: byte, start_sound?: string, start_using: "if_first"}` | **`start_using` added** (custom default `"if_first"`, vanilla food `"always"`); `movement_modifier` omitted when absent |
| `minecraft:wearable` | `{slot, protection?}` | `{slot: string, protection: int, hides_player_location: 0b}` | `hides_player_location` added; forces max_stack_size 1 |
| `minecraft:render_offsets` | — | — | no longer an item component in 1.26 JSON; nothing to capture |

## Common block shape

Block property data: `{components: {...}, menu_category: {category, group, is_hidden_in_commands: byte},
molangVersion: 13, vanilla_block_data: {block_id: 10000+ (load order), material: "dirt"}, properties?, traits?,
permutations?, blockTags?}`. `menu_category.group` keeps the `minecraft:` prefix here.

- `properties: [{name, enum: [...]}]`: int states -> int list, bool states -> **byte** list, strings -> string list,
  `{values: {min, max}}` ranges are expanded to the full int list. Wire order is not the JSON order.
- `permutations: [{condition: molang, components: {...}}]`, components in the same shapes as below.
- `minecraft:tags` (`["a", "b"]`) is **not** a component on the wire: it becomes top level `blockTags: [string]`.
  The legacy `tag:<name>` components are rejected by 1.26.60 JSON.

### Traits

Trait states are added by the client, not listed in `properties`; verified by hash (`BlockHashes`):

| JSON trait | Wire | States the client adds (first = default) |
|---|---|---|
| `minecraft:placement_direction {enabled_states, y_rotation_offset}` | `{name, enabled_states: {cardinal_direction, corner_and_cardinal_direction, facing_direction, sixteen_way_rotation: byte}, y_rotation_offset: float, blocks_to_corner_with: []}` | `minecraft:cardinal_direction` string, default **`south`** (verified), then west, north, east |
| `minecraft:placement_position {enabled_states}` | `{name, enabled_states: {block_face, vertical_half: byte}}` | `minecraft:block_face` (default `down`), `minecraft:vertical_half` (default `bottom`), both verified |
| `minecraft:multi_block {enabled_states, parts, direction}` | `{name, enabled_states: {multi_block: byte}, part_count: byte, direction: byte (up = 1), place_behavior: byte}` | `minecraft:multi_block_part` int 0..parts-1 (default 0, verified); requires `minecraft:movable` |
| `minecraft:connection {enabled_states: ["minecraft:cardinal_connections"]}` | `{name, enabled_states: int bit set (1), type: int, update_flags: int (3)}` | `minecraft:connection_north/east/south/west` **byte** (bool), default 0 (verified) |

Non default trait values are taken from the vanilla states of the same name (not verifiable from creative data).

## Block components: wire shape vs JSON

| Component | JSON (add-on) | Wire | Differences |
|---|---|---|---|
| `minecraft:collision_box` | `true` / `false` / `[{origin, size}, ...]` | `{enabled: byte, boxes: [{minX, minY, minZ, maxX, maxY, maxZ: float}]}` | **origin/size become min/max in 0..16 block space** (origin + 8); `true`/`false` send `boxes: []`. The single object form `{origin, size}` is **rejected** by 1.26.60 JSON (array required) |
| `minecraft:connection_rule` | `{accepts_connections_from?, enabled_directions?}` | same keys | defaults `"all"`, `["south","north","east","west"]` |
| `minecraft:crafting_table` | `{crafting_tags, table_name}` | `{crafting_tags: [string], table_name: string, grid_size: 3}` | `grid_size` added |
| `minecraft:destructible_by_mining` | `false` / `{seconds_to_destroy, item_specific_speeds}` | `{value: float, item_specific_speeds?: [{destroy_speed: float, item: {Name, Aux: 32767s} \| {Tags, MolangVersion: 13s}}]}` | `seconds_to_destroy` -> `value`; `false` -> `value: -1.0`; item descriptors in the legacy PascalCase shape |
| `minecraft:destruction_particles` | `{texture, tint_method?, particle_count?}` | `{texture, tint_method: string, particle_count: int}` | defaults `"none"`, 100 |
| `minecraft:display_name` | `"..."` | `{value: string}` | |
| `minecraft:embedded_visual` | `{geometry, material_instances}` | `{geometry: {bone_visibility, culling, culling_layer, culling_shape, identifier, uv_lock}, material_instances: {<face>: {texture, render_method, tint_method, ambient_occlusion: float, face_dimming, isotropic, alpha_masked_tint: byte}}}` | material instances **not** in the `mappings`/`materials`/`packed_bools` form used by `minecraft:material_instances` |
| `minecraft:flower_pottable` | `{}` | `{usePreR26U2FlowerPotOffset: 0b}` | needs geometry + material_instances |
| `minecraft:friction` | `0.2` | `{value: float}` | |
| `minecraft:geometry` | `"id"` / `{identifier, bone_visibility, culling, culling_layer, uv_lock}` | `{identifier, bone_visibility: {bone: string}, culling: string, culling_layer: string, culling_shape: "", uv_lock: byte \| [string], ignoreGeometryForIsSolid, isV1Fullblock, needsLegacyTopRotation, useBlockTypeLightAbsorption: byte}` | **`bone_visibility: true` -> `"1.000000"`** (molang strings kept); `culling_layer` default `"minecraft:culling_layer.undefined"`; `uv_lock` list stays a list; needs material_instances |
| `minecraft:item_visual` | `{geometry, material_instances}` | `{geometryDescription: <geometry shape>, materialInstancesDescription: <material_instances shape>}` | **renamed** keys, full `minecraft:geometry` / `minecraft:material_instances` shapes inside |
| `minecraft:leashable` | `{offset?}` | `{offset: [float x3]}` | default `[0, 0.25, 0]` |
| `minecraft:light_dampening` | `7` | `{lightLevel: byte}` | renamed |
| `minecraft:light_emission` | `10` | `{emission: byte}` | renamed |
| `minecraft:liquid_detection` | `{detection_rules: [{liquid_type, can_contain_liquid, on_liquid_touches, stops_liquid_flowing_from_direction}]}` | `{detectionRules: [{liquidType, canContainLiquid: byte, onLiquidTouches, stopsLiquidFromDirection: byte bit set, use_liquid_clipping: byte}]}` | camelCase, **direction list -> bit set** (down 1, up 2, north 4, ...: `["up","north"]` = 6) |
| `minecraft:material_instances` | `{"*": {texture, render_method, ambient_occlusion, face_dimming, isotropic, tint_method}, "up": "north"}` | `{mappings: {up: {value: "north"}}, materials: {"*": {texture, render_method, tint_method, ambient_occlusion: float, packed_bools: byte}}}` | face aliases go to `mappings`; **`face_dimming`/`isotropic` packed into `packed_bools`**: bit 0 face_dimming, bit 1 isotropic, bit 2 always set in every capture (5 = default, 6 = no dimming + isotropic); `ambient_occlusion` float (bool true -> 1.0); needs geometry |
| `minecraft:placement_filter` | `{conditions: [{allowed_faces, block_filter}]}` | `{conditions: [{allowed_faces: byte bit set, block_filter: [{name} \| {tags, tags_version: 13}]}]}` | **faces list -> bit set** (same encoding as liquid detection); string filters -> `{name}` |
| `minecraft:precipitation_interactions` | `{precipitation_behavior}` | same | |
| `minecraft:random_offset` | `{x?, y?, z?: {range: {min, max}, steps?}}` | all three axes always, `{range: {min, max: float}, steps: int}` | |
| `minecraft:redstone_conductivity` | `{redstone_conductor, allows_wire_to_step_down}` | `{redstoneConductor, allowsWireToStepDown: byte}` | camelCase |
| `minecraft:redstone_producer` | `{power, strongly_powered_face?, connected_faces?, transform_relative?}` | same + `allowPowerUp: 0b, allowPowerDown: 1b`; `power` byte | defaults: all six faces, transform_relative 1; `strongly_powered_face` omitted when absent |
| `minecraft:replaceable` | `{}` | `{}` | |
| `minecraft:selection_box` | `false` / `{origin, size}` | `{enabled: byte, origin: [float x3], size: [float x3]}` | `false` still sends the full cube origin/size |
| `minecraft:sound` | `{sound}` (a bare string is rejected) | `{sound: string}` | |
| `minecraft:support` | `{shape}` | `{shape: string}` | |
| `minecraft:transformation` | `{rotation, scale, translation, rotation_pivot, scale_pivot}` | `{RX, RY, RZ: int (rotation / 90), RXP.. : float (rotation pivot), SX.., SXP.. (scale, scale pivot), TX.. (translation), hasJsonVersionBeforeValidation: 0b}` | per axis keys; rotation in quarter turns |

**Not sent** (server only, accepted by BDS, no NBT on the wire): `block_entity`, `chest_obstruction`,
`destructible_by_explosion`, `enchantment_power_transmitter`, `entity_fall_on`, `flammable`, `instrument_sound`,
`loot`, `map_color` (hex, RGB and full form), `movable`, `neighbor_change`, `redstone_consumer`, `tick`,
`vibration_properties`.

**Rejected by BDS 1.26.63** (JSON, at format 1.26.60 and at 1.19.40): `unit_cube`, `breathability`,
`queued_ticking`, `random_ticking` (deprecated), `on_fall_on`, `on_interact`, `on_placed`,
`on_player_destroyed`, `on_player_placing`, `on_step_off`, `on_step_on` ("not valid here"; replaced by script custom
components), `tag:<name>`. No wire shape exists for them in 1.26.

Also found in the content log: blocks need **both** `geometry` and `material_instances` (or neither);
`flower_pottable` and `item_visual` need both; `sticky: "same"` only works with `push_pull`; an item needs at least
one component; a block and an item with the same identifier make BDS rename the block item to
`<namespace>:item.<name>` (`paletter:item.display_name`, `paletter:item.tags`), unless the item uses
`block_placer.replace_block_item`.

## needs-capture.txt coverage

`codegen/needs-capture.txt` (branch `codegen`), every line: **W** = wire shape captured above, **N** = accepted but
not sent, **R** = rejected by 1.26.63, **-** = nothing to capture.

- block: block_entity N, breathability R, chest_obstruction N, crafting_table W, destructible_by_explosion N,
  display_name W, embedded_visual W, enchantment_power_transmitter N, entity_fall_on N, flammable N,
  flower_pottable W, friction W, instrument_sound N, item_visual W, leashable W, light_dampening W, light_emission W,
  loot N, map_color N, material_instances W (`isotropic` is sent, inside `packed_bools`), movable N,
  neighbor_change N, on_fall_on/on_interact/on_placed/on_player_destroyed/on_player_placing/on_step_off/on_step_on R,
  placement_filter W, queued_ticking R, random_offset W, random_ticking R, redstone_consumer N, redstone_producer W,
  sound W, tags W (as `blockTags`), tick N, unit_cube R, vibration_properties N.
- item: allow_off_hand W, can_destroy_in_creative W, compostable W (byte), damage W (short), damage_absorption N,
  digger W, durability_sensor N, dyeable W, entity_placer W, glint W, hover_text_color W, icon W (item_properties),
  interact_button W, kinetic_weapon W (`delay` *is* sent, short; vanilla spears send it too), liquid_clipped W,
  rarity W, record W, render_offsets - , repairable W (float or string), shooter W, should_despawn W,
  stacked_by_data W, wearable W.
