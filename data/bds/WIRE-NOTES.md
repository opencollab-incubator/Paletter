# Wire notes: what BDS sends

Observed with the headless client (`capture`) against Bedrock Dedicated Server 1.26.63.1 (protocol 2225): the start game
packet, the item registry packet and the creative content packet, with the capture add-on's custom content. The
per component wire shapes are not repeated here: `data/model/mappings.json` is their record, and the conformance suite
holds the encoders to it. This file keeps what the model does not say.

Raw data: `data/bds/<bds version>/addon/custom_item_registry.{nbt,snbt}` and `custom_block_properties.{nbt,snbt}`
(typed SNBT in wire order: `1b` byte, `2s` short, `3` int, `4L` long, `5.0f` float).

## Rerunning

`./gradlew :tools:run --args="update --channel release"` does everything (README, "Updating data"). By hand, set in
`server.properties` `transport=raknet`, `online-mode=false`, `block-network-ids-are-hashes=true`,
`content-log-level=verbose` (1.26.70 only speaks NetherNet: `capture --transport nethernet`, with
`enable-lan-visibility=true`), then:

```
./gradlew :tools:run --args="capture --out <dir> --bds-version <v>"          # vanilla
./gradlew :tools:run --args="compare --capture <dir> --reference <bundled>"
./gradlew :tools:run --args="addon --kind capture --out data/addons/capture"  # install in BDS, enable experiments
./gradlew :tools:run --args="experiments --level-dat <world>/level.dat --enable <toggles>"
./gradlew :tools:run --args="capture --out <dir> --custom-out data/bds/<v>/addon --bds-version <v>"
```

## What the wire gives, and what not

| Data | On the wire? |
|---|---|
| Item registry: identifier, runtime id, `componentBased`, version, component NBT | yes |
| Creative inventory: groups, stacks | yes |
| Block properties of data driven blocks (vanilla and custom) | yes (start game `blockProperties`) |
| Vanilla block palette (names and states) | no, the client has it built in; verified by hash (below) |
| Item tags, block default states | no (tags appear per item as `item_tags`; the Script API dump provides the index) |
| Server-only components | no |

Block network hash (verified against every creative block id): FNV-1a 32 over the little endian NBT (root name empty)
of `{name, states: {<sorted keys>}}`, no `version` tag. Custom blocks hash the same way, with their **trait states**
(which `properties` does not list). Default state = first value of every property, then every trait state at its first
value.

Item runtime ids are per server content: BDS numbers data driven items after the last legacy item, so custom items shift
the vanilla data driven items up. Custom block items get `id = 255 - block_id` with `version` NONE.

## Common item shape

`{components: {item_properties: {...}, item_tags: [...], <component>: {...}}}`. `item_properties` has all 18 keys for
custom items. Shorthand components are sent as `{value: X}`. Notable keys:

| item_properties key | notes |
|---|---|
| `creative_category` | 1 construction, 2 nature, 3 equipment, 4 items, 5 none |
| `creative_group` | kept as written (`minecraft:itemGroup.name.sword`); the creative packet strips `minecraft:` |
| `hidden_in_commands` | 2 = not set (default), 1 = true; not a boolean |
| `hover_text_color` | converted to a formatting code (`"red"` -> `"§c"`), only when set |
| `max_stack_size` | forced to 1 by `minecraft:wearable` |
| `minecraft:icon` | lives only in item_properties; the string shorthand becomes `textures.default` |
| `use_animation`, `use_duration` | int enum; seconds -> ticks |
| `mining_speed`, `frame_count` | always 1.0 / 1 for custom items |

`item_tags` is filled from `minecraft:tags`. Items without `minecraft:display_name` send no display name.

## Common block shape

`{components, menu_category: {category, group, is_hidden_in_commands: byte}, molangVersion: 13,
vanilla_block_data: {block_id: 10000+, material: "dirt"}, properties?, traits?, permutations?, blockTags?}`.

- `properties: [{name, enum}]`: int states -> int list, bool states -> byte list, strings -> string list, ranges are
  expanded; wire order is not the JSON order.
- `minecraft:tags` is not a component on the wire: it becomes top level `blockTags`. The legacy `tag:<name>` components
  are rejected by 1.26 JSON.

### Traits

The client adds trait states; they are verified by hash and taken from vanilla states of the same name otherwise.

| Trait | States added (first = default) |
|---|---|
| `minecraft:placement_direction` | `minecraft:cardinal_direction`: south, west, north, east |
| `minecraft:placement_position` | `minecraft:block_face` (default `down`), `minecraft:vertical_half` (default `bottom`) |
| `minecraft:multi_block` | `minecraft:multi_block_part` int 0..parts-1; requires `minecraft:movable` |
| `minecraft:connection` | `minecraft:connection_north/east/south/west` byte, default 0 |

## Rejected by BDS 1.26.63

Block components `unit_cube`, `breathability`, `queued_ticking`, `random_ticking`, `on_fall_on`, `on_interact`,
`on_placed`, `on_player_destroyed`, `on_player_placing`, `on_step_off`, `on_step_on` (replaced by script custom
components) and `tag:<name>`, at format 1.26.60 and 1.19.40; no wire shape exists for them.

## Voxel shapes

`VoxelShapesPacket` (id 337, protocol 924 and later, sent before start game): every voxel shape the server knows, a
name to id map and, since 944, the number of shapes beyond vanilla. Captured per build by `voxel-shapes capture`
(`data/bds/<build>/voxel-shapes`), reproduced by `Palette#voxelShapesPacket` and checked by `VoxelShapeConformanceTest`
against every capture.

- **What they are.** Culling shapes: the faces a block hides of its neighbours. Blocks name them in the start game block
  properties, `minecraft:geometry.culling_shape` (per permutation, so per state). Vanilla blocks name none on the wire;
  their shapes are picked in BDS's code. Nothing ties them to collision, selection or movement (the string form of
  `collision_box`, "using a voxel shape" in the docs, is refused by every captured build). Collision stays
  `minecraft:collision_box`.
- **Shape format.** A grid: per axis the sorted coordinates in blocks, the cells between them filled or not, cell
  `(x * ySize + y) * zSize + z` is bit `i % 8` of byte `i / 8`. The grid is minimal (merged boxes, no plane that separates
  nothing). The empty shape has the coordinate 0 on every axis and no cells. The 218 shape files of 1.26.63.1 re-encode
  to the bytes BDS sends.
- **Registry order.** `minecraft:empty` (0), `minecraft:unit_cube` (1); the vanilla shape files in name order; custom
  shape files in the order the file system lists them (NTFS: by upper case name); then BDS walks all blocks in name hash
  order (`BlockRegistry#nameHash`), each state in order, and registers without a name the state's culling shape with its
  `minecraft:transformation` applied, then the shapes of its six faces (down, up, north, south, west, east: the cells that
  reach the face from inside, drawn through the block). Equal shapes share one id. A block that names no culling shape, or
  uses `minecraft:geometry.full_block` ("cannot have a custom culling shape"), adds nothing. Which vanilla block makes which
  unnamed shape is measured with anchors: a custom block whose name hashes just before each vanilla block.
- **Name order.** The iteration order of MSVC's `std::unordered_map<HashedString, ...>` (FNV-1 64, 8 buckets growing x8
  below 512 then x2, a new key in front of its bucket): `VoxelShapeCompiler#hashMapOrder`.
- **Transformation** (in floats, in this order): scale `(v - c) * s + c` with `c = 0.5 + pivot` on every axis if any
  factor is not 1; the quarter turns x, then y, then z as one change of axes (`1 - v` for a mirrored axis, around a pivot
  `±(v - c) + c'`); translation `v + t`.
- **Limits** (content log): at least one box, `min < max`, boxes within (-14, -14, -14) to (30, 30, 30) pixels, at least one
  box overlapping the block, no `minecraft` namespace; an unknown name falls back to `minecraft:empty`.
- **Builds.** 1.26.36.1 (26.30) sends only `minecraft:empty` and `minecraft:unit_cube` unless the world has the experiment
  `voxel_shapes`; from 1.26.45.1 on the vanilla shapes are always sent and the experiment changes nothing. 1.26.45.1,
  1.26.52.3 and 1.26.63.1 send the same vanilla registry.

## Content log findings

- Blocks need both `geometry` and `material_instances`, or neither; `flower_pottable` and `item_visual` need both.
- `sticky: "same"` only works with `push_pull`; an item needs at least one component.
- A block and an item with the same identifier make BDS rename the block item to `<namespace>:item.<name>`, unless the
  item uses `block_placer.replace_block_item`.
- Custom items in the `minecraft` namespace, and groups without a namespace, are rejected.
