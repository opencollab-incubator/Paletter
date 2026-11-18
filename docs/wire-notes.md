# Wire notes

What Paletter learned about BDS from captures, beyond what the component model records. Unless stated otherwise, the
observations are from BDS 1.26.63.1 on Windows (protocol 2225), captured with the headless client (`capture`).

Per-component wire shapes are not repeated here: `data/model/mappings.json` is their record and the conformance suite
holds the encoders to it. Raw captures are under `data/bds/<build>/`, for example `spec/custom_item_registry.nbt`
and `spec/custom_block_properties.nbt` (gzipped NBT in wire order).

## What is on the wire

The item registry packet carries each item's identifier, runtime id, `componentBased` flag, version and component NBT.
The creative content packet carries groups and stacks, and start game carries the block properties of data driven
blocks, vanilla and custom. The vanilla block palette is not sent: the client has it built in. Item tags only appear per
item as `item_tags`, and default block states are not sent at all; the Script API dump provides both. Server-only
components are never sent.

**Block hashes.** With `block-network-ids-are-hashes=true`, a block's network id is FNV-1a 32 over the little endian NBT
(empty root name) of `{name, states}`, with states sorted by key and no `version` tag. This was verified against every
creative block id. Custom blocks hash the same way, including their trait states, which `properties` does not list. The
default state is the first value of every property, then every trait state at its first value.

**Item runtime ids** depend on the server's content. BDS numbers data driven items after the last legacy item, so custom
items shift vanilla data driven items up. A custom block item gets `id = 255 - block_id`, with version NONE.

An item that replaces a block's item (same identifier, `block_placer` with `replace_block_item`) is an ordinary data driven
item with a sequential id. In the `addon` capture `paletter:plain` is 908, `paletter:plain_block` (the replacing item of the
block of that name) 909 and `paletter:projectile` 910: one sequence with the custom items, not in block order. Its creative
stack has no block state and sits in the item's category (`items`), while the block's own `menu_category` is `construction`
(`addon/custom_creative_items.json`, `custom_block_properties.snbt`). `CreativeOrderCheckTest` compares the id distances of all
data driven items of that capture with Paletter's. The captured ids look alphabetical by identifier; whether BDS numbers by
identifier or by file name was not told apart, so Paletter numbers items in registration order, which is its own rule, not
BDS evidence.

Not captured, so **unverified**: a replacing item whose block has no menu category (the block's presence in creative, and
whether BDS still adds the item in the block pass), and a replacing item for a block that is hidden in the creative inventory.
Paletter places the item by its own category either way, and by the block's order.

## Items

Custom items are sent as `{components: {item_properties: {...}, item_tags: [...], <component>: {...}}}`.
`item_properties` always has all 18 keys. Shorthand components are sent as `{value: X}`.

- `creative_category`: 1 construction, 2 nature, 3 equipment, 4 items, 5 none.
- `creative_group` keeps the `minecraft:` prefix; the creative packet drops it.
- `hidden_in_commands` is 2 when not set and 1 when true. It is not a boolean.
- `hover_text_color` becomes a formatting code (`"red"` becomes `"§c"`), only when set.
- `max_stack_size` is forced to 1 by `minecraft:wearable`.
- `minecraft:icon` only appears in `item_properties`; the string shorthand becomes `textures.default`.
- `use_animation` and `use_duration` are int enums; seconds become ticks.
- `mining_speed` and `frame_count` are always 1.0 and 1 for custom items.

Items without `minecraft:display_name` send no display name.

## Blocks

Custom blocks are sent as `{components, menu_category: {category, group, is_hidden_in_commands}, molangVersion: 13,
vanilla_block_data: {block_id: 10000+, material}, properties?, traits?, permutations?, blockTags?}`.

- `properties` is a list of `{name, enum}`. Integer states become int lists, booleans byte lists, ranges are expanded.
  Wire order is not JSON order.
- `minecraft:tags` is not a component on the wire; it becomes the top level `blockTags`.

Traits add block states on the client. The generated `TraitStateTable` has them, with defaults verified by hash where a
capture has one:

| Trait | States added (first value is the default) |
|---|---|
| `minecraft:placement_direction` | `minecraft:cardinal_direction`: south, west, north, east |
| `minecraft:placement_position` | `minecraft:block_face` (default `down`), `minecraft:vertical_half` (default `bottom`) |
| `minecraft:multi_block` | `minecraft:multi_block_part`, 0 to parts - 1; requires `minecraft:movable` |
| `minecraft:connection` | `minecraft:connection_north/east/south/west`, byte, default 0 |

BDS 1.26.63 rejects the block components `unit_cube`, `breathability`, `queued_ticking`, `random_ticking`, `on_fall_on`,
`on_interact`, `on_placed`, `on_player_destroyed`, `on_player_placing`, `on_step_off` and `on_step_on` (replaced by
script custom components, which BDS sends as the three flags of `ScriptHooks`; documented, not captured yet), and the legacy `tag:<name>` components, at every format version tried.

## Overriding vanilla items

Captured facts (BDS 1.26.63.1, probe pack and registry entries in `data/bds/1.26.63.1/override/`; the write-up of the
probe's strings is not in the repository):

- **Data driven vanilla item** (`minecraft:apple`, format 1.26.60): the pack's components **replace** the vanilla
  ones entirely (food, icon, display name, tags and use components are gone). Only `item_properties.creative_category`
  keeps the vanilla value (2). The runtime id moves from 883 to 853, to the front of the data driven range, and with the
  probe's control item also present, 32 vanilla ids changed: apple 883 to 853, 30 items +2 (red_shrub to wind_charge),
  glow_berries +1. The override alone would be +1 on the 30, inferred, not captured.
- **Hard coded item at format 1.10** (`minecraft:feather`): accepted. It becomes a legacy item (version 2 to 0) with
  legacy style component NBT (`{minecraft:max_stack_size: 9}`), its id stays 330.
- **Hard coded item at 1.16.100 or higher** (`minecraft:stick`): rejected ("no 1.16.100+ Vanilla format version
  exists"). A legacy item with components (`minecraft:bread` at 1.16.100 only): rejected, asking for 1.20.0 or higher;
  format 1.20 and up was not captured.
- **Several packs** overriding the same item: the top pack wins (seen in binary strings only, not captured).

Paletter's choices, all **unverified on a client** (no client test exists; vanilla item overrides are `@Experimental`):

- Vanilla runtime ids stay fixed, unlike BDS, which moves an overridden data driven item. A client that expects BDS's
  numbering was not tested.
- `replaceComponents` has BDS's shape: it replaces the vanilla components, with the custom item defaults underneath,
  and keeps the vanilla `creative_category`. `ItemOverrideTest` compares the apple with BDS's captured entry.
- `modifyComponents` merges onto the vanilla data. BDS never sends this shape.
- Hard coded and legacy targets (e.g. `firework_rocket`, which Geyser overrides) are not refused, though BDS rejects
  modern format overrides of the stick (the rocket itself was never probed). The client's result is unverified on a
  client.

## Content log findings

- A block needs both `geometry` and `material_instances`, or neither. `flower_pottable` and `item_visual` need both.
- `sticky: "same"` only works together with `push_pull`. An item needs at least one component.
- A block and an item with the same identifier make BDS rename the block item to `<namespace>:item.<name>`, unless the
  item uses `block_placer.replace_block_item`.
- Custom items in the `minecraft` namespace, and creative groups without a namespace, are rejected.
- To be captured: whether a block whose item is replaced shows in the creative inventory when the block's `menu_category` and
  the replacing item's differ. `paletter:replaced_hidden_block` has block category `none` and item category `items`;
  `paletter:replaced_shown_block` has block category `construction` and item category `none` (the `creative-probe` capture: the creative order probe).

## Creative inventory order

`CreativeOrdering.BDS`, the default, reproduces where BDS puts custom content in the creative inventory. BDS builds the
vanilla inventory first, including experiment content, then adds custom content in two passes:

1. **Blocks.** For each data driven block, in the iteration order of BDS's block map (below), a custom block whose menu
   category is not `none` adds its item. If a data driven item has the same identifier (`block_placer` with
   `replace_block_item`), that item is added here instead, with the item's menu category.
2. **Items.** Every other custom item whose menu category is not `none`, in registration order. That is the order BDS
   loaded the files; with one file per item, file name order.

Each entry is placed by its `(category, group)`:

- With a group (the `minecraft:` prefix is dropped), the stack goes to the end of that group in the same category. If the
  category has no such group, a new group is appended to the end of the packet's group list, with an air icon.
- Without a group, the stack goes loose at the end of the category: into the last group if it is unnamed, else into a
  new unnamed group.
- Category `none`, or no `menu_category`, means not in the creative inventory. `is_hidden_in_commands` has no effect.

**The block map order.** BDS keeps all data driven blocks in an MSVC `std::unordered_map<std::string, ...>`, and both
the start game block property list and the block pass iterate it. Paletter reproduces it by simulating MSVC:

- the hash is FNV-1a 64 of the identifier; the bucket is `hash & (buckets - 1)`;
- there are 8 buckets at first and a max load factor of 1; the table grows 8x below 512 buckets, 2x from 512;
- elements live in one list with each bucket a contiguous range. A new element goes to the front of its bucket's range,
  or to the end of the list if the bucket is empty. A rehash walks the list in order and moves each element the same way;
- custom blocks are inserted first, by block id (10000, 10001, ...), then the vanilla data driven blocks in descending
  identifier order.

Custom block ids follow descending file name order of the block files, not identifier order: in the
`creative-probe-file-order` capture, files `b14.json` to `b01.json` got ids 10000 to 10013. With one file per block named
after the block, which is the usual layout, that equals descending identifier order, compared in upper case: the file listing of Windows BDS sorts names in upper case, so `_` sorts after letters (`b_x` is numbered before `block_x`; every spec capture of 1.26.36.1 to 1.26.70.24 follows this, `CreativeOrderCheckTest.blockMapOrderMatchesTheSpecCapture`). Paletter has no files, so it numbers
custom blocks by identifier, descending, whatever order the layers register them in. For add-ons whose file names do not
sort like their identifiers, block items will be ordered differently than on BDS. Item order is unaffected.

The order is per behavior pack: BDS numbers the blocks of a pack after those of the packs before it in the world's pack list.
In the spec capture of 1.26.36.1 with the engine packs, the main pack's 1338 blocks got 10000 to 11337 in the order above, and
`paletter:engine_0` and `paletter:engine_1`, each alone in a pack listed after the main pack, got 11338 and 11339, although by
name they would come first (`BlockIdOrderTest.capturedBdsOrder`). Which of the pack list order, the pack folder names or the
pack uuids decides is open: all three agree in that capture. Paletter has no packs; the spec capture check numbers the engine
packs' blocks after the main pack (`CreativeOrderCheck.blockOrder`).

**Where Paletter differs on purpose.** Groups stay in category order in the packet, and a new group gets its first
stack as icon instead of air. The client shows the same thing either way. A placement with an anchor
(`CreativePlacement#after`) is an explicit override. `CreativeOrdering.APPEND` is the older behaviour: registration
order, groups matched by name in any category; which content is in the creative inventory is still decided by the menu
categories, as in BDS.

**Limits.** The block map order comes from MSVC's standard library. The Linux BDS uses another one, so its block order
probably differs; it has not been captured. Each layer runs both passes over its own content after earlier layers, so
content split across layers is ordered layer by layer.

**Evidence.** The `creative-probe`, `creative-large` (700 blocks, 40 items, random identifiers; the map grows
to 1024 buckets) and `creative-probe-file-order` captures in `data/bds/1.26.63.1/`. Repeated server starts give identical
packets. `CreativeOrderCheckTest` compiles each capture's custom content through Paletter and compares the whole creative
inventory with the captured packet, and `update` repeats the check on every new build. To probe by hand:
`./gradlew :tools:run --args="creative-probe --addon <dir> --out <dir> --starts 2"`.

## Recipe unlock

**To be captured.** What BDS sends as a recipe's `unlock` depends on the recipe's `format_version`: an add-on recipe with
`unlock: AlwaysUnlocked` at format 1.20.10 was sent as `NONE` (a one-off probe on 1.26.63.1). Nothing else is known. The
creative order probe pack carries one shapeless recipe per unlock form and per recipe format (`UnlockProbe`), so that
`update` records the answer for every build: `data/bds/<build>/recipe-probe/crafting_data.json.gz` has the recipes whose
identifier starts with `paletter:unlock_`, `unlock` raw as BDS sent them, and the content log of the creative order probe
world says which BDS rejected. The identifier of a recipe is `paletter:unlock_<form>_<format with _ for .>`.

- **Forms** (from BDS): no `unlock` key (the control); a list of `{"item"}`, of `{"item", "data"}` and of `{"tag"}`, as BDS's own
  vanilla recipes have them; the object `{"context": ...}` with `None` and `PlayerInWater` (vanilla recipes) and `AlwaysUnlocked`
  and `PlayerHasManyItems` (strings in the BDS binary of 1.26.63.1).
- **Formats** (from BDS): 1.12, 1.14, 1.16, 1.19, 1.20.10, 1.20.30, 1.20.60, 1.21.30, 1.21.40, 1.21.50, 1.21.60, 1.21.80, 1.21.90,
  1.21.100, 1.21.110, 1.26.0, 1.26.30 and 1.26.50: every `format_version` the vanilla packs of BDS 1.26.63.1 give shapeless and
  shaped recipes. A build that does not know one rejects the recipe.
- **Not probed:** shaped recipes (same field on the wire), furnace and smithing recipes.

## Join sequence

The headless client lists, per capture, every packet it received and sent from the login on (`packetOrder` in
`capture.json`: `<- Name` received, `-> Name` sent when queued) and the index of start game in it (`startGameIndex`).
**This is what BDS sends, in the order it sends it. It does not say what a client requires; that is unverified, there
is no client test.** The client never sends `SetLocalPlayerAsInitialized`, so the order after start game also depends on
its chunk radius and sub-chunk requests. Captures made before this field existed do not have it; it appears with the
next recapture of each build. Sample, BDS 1.26.63.1 on Windows, one run (determinism across runs is checked by the
recapture), from a one-off spike capture:

1. Before start game: NetworkSettings, ServerToClientHandshake, PlayStatus (login success), ResourcePacksInfo,
   ResourcePackStack, LevelEventGeneric, PlayerList, SyncWorldClocks, JigsawStructureData, VoxelShapes.
2. **StartGame.**
3. After it: 13 SyncEntityProperty, ItemComponent, SetSpawnPosition, SyncWorldClocks, SetDifficulty, SetCommandsEnabled,
   UpdateAdventureSettings, UpdateAbilities, GameRulesChanged, PlayerList, UpdateAbilities, BiomeDefinitionList,
   AvailableEntityIdentifiers, PlayerFog, CameraPresets, CameraAimAssistPresets, CameraSpline, UpdateAttributes,
   CreativeContent, TrimData, 4 InventoryContent, PlayerHotbar, CraftingData, AvailableCommands, 3 Respawn,
   NetworkChunkPublisherUpdate, then the level chunks.

## Voxel shapes

`VoxelShapesPacket` (id 337, protocol 924 and later) is sent before start game. It carries every voxel shape the server
knows, a name to id map and, since protocol 944, the number of shapes beyond vanilla.

- **Meaning.** Voxel shapes are culling shapes: the faces a block hides of its neighbours. Blocks name them in
  `minecraft:geometry.culling_shape`, per permutation. Vanilla blocks name none on the wire; BDS picks their shapes in
  code. They are not collision shapes; the string form of `collision_box` is refused by every captured build.
- **Format.** A grid: per axis the sorted coordinates in blocks, and one bit per cell between them. Cell
  `(x * ySize + y) * zSize + z` is bit `i % 8` of byte `i / 8`. The grid is minimal. The empty shape has coordinate 0
  on every axis and no cells. The 218 shape files of 1.26.63.1 re-encode to the exact bytes BDS sends.
- **Registry order.** `minecraft:empty` (0), `minecraft:unit_cube` (1), the vanilla shape files by name, custom shape
  files in file system order (NTFS: by upper case name). Then BDS walks all blocks in name hash order, each state in
  order, and registers, without a name, the state's culling shape with its `minecraft:transformation` applied, followed
  by the shapes of its six faces (down, up, north, south, west, east). Equal shapes share an id. A block without a culling
  shape, or with `minecraft:geometry.full_block`, adds nothing. Which vanilla block makes which unnamed shape was
  measured with anchors: custom blocks whose names hash just before each vanilla block.
- **Name order** is the iteration order of MSVC's `std::unordered_map<HashedString, ...>` (FNV-1 64), reproduced by
  `VoxelShapeCompiler#hashMapOrder`.
- **Transformation**, in floats and in this order: scale around `0.5 + pivot` when any factor is not 1; quarter turns
  around x, then y, then z as one change of axes; then translation.
- **Limits** from the content log: at least one box, `min < max`, boxes within -14 to 30 pixels on every axis, at least
  one box overlapping the block, and no `minecraft` namespace. An unknown name falls back to `minecraft:empty`.
- **Builds.** 1.26.36.1 (26.30) only sends `minecraft:empty` and `minecraft:unit_cube` unless the world has the
  `voxel_shapes` experiment. From 1.26.45.1 on the vanilla shapes are always sent and the experiment changes nothing;
  1.26.45.1, 1.26.52.3 and 1.26.63.1 send the same vanilla registry.
