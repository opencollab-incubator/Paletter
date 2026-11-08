# Where BDS puts custom content in the creative inventory

Observed on Bedrock Dedicated Server **1.26.63.1, Windows** (protocol 2225), by capturing the `CreativeContentPacket`
with the headless client. Paletter implements the result as `CreativeOrdering.BDS`, the default of every layer.

## The rule

BDS builds the vanilla creative inventory first (including content of experiments), then adds custom content in two
passes:

1. **Block pass.** For each data driven block in the iteration order of BDS's block map (below), if it is a custom
   block whose menu category is not `none`, its item is added. If a data driven item with the *same identifier* exists
   (it replaces the block item, e.g. `minecraft:block_placer` with `replace_block_item`), that item is added here, with
   the *item's* menu category.
2. **Item pass.** Every other custom item whose menu category is not `none`, in **registration order** (= item runtime
   id order = the order BDS loaded the files; with one file per item, file name order).

Each entry is added with its menu category `(category, group)`:

- **group set**: the `minecraft:` namespace is dropped (`minecraft:itemGroup.name.sword` -> `itemGroup.name.sword`;
  other namespaces stay, `exp:itemGroup.g1` stays `exp:itemGroup.g1`). If the category has a group of that name, the
  stack goes to the **end of that group**. Otherwise a **new group is appended** (at the end of the packet's group list,
  so at the end of the category in the client) with an **air icon**. Groups are per category: a group of the same name
  in another category does not count (`menu_category {items, minecraft:itemGroup.name.sword}` created a second
  `itemGroup.name.sword` group in *items*).
- **no group**: loose, **at the end of the category**: into the category's last group if that is unnamed (construction
  and items end with loose runs), else into a new unnamed group appended to the list.
- Category `none`, or no `menu_category` at all (also: a group without a category): not in the creative inventory.
  `is_hidden_in_commands` has no effect on the creative inventory.
- Groups without a namespace (`"group": "itemGroup.name.sword"`) are rejected by the item parser (content log:
  *string must be prefixed with a namespace*), and custom items in the `minecraft` namespace are rejected (*no 1.16.100+
  Vanilla format version exists*).

### The block map order

BDS keeps all data driven blocks (vanilla ones like `minecraft:yellow_wool_slab` and custom ones) in an MSVC
`std::unordered_map<std::string, ...>`, and the start game `blockProperties` list and the block pass both iterate it.
Its iteration order is reproduced exactly by simulating MSVC's implementation:

- key hash `std::hash<std::string>` = FNV-1a 64 of the identifier bytes; bucket = hash & (buckets - 1);
- 8 buckets initially, max load factor 1; growing x8 while below 512 buckets, x2 from 512;
- one list of all elements, each bucket a contiguous range: a new element goes to the **front of its bucket's range**,
  or to the end of the list if its bucket is empty; a rehash walks the list in order and moves each element to the front
  of its new bucket's range (or leaves it in place if the bucket is still empty);
- insertion order: **custom blocks by block id** (10000, 10001, ...), then the **vanilla data driven blocks in descending
  identifier order**.

Custom block ids (`vanilla_block_data.block_id`, 10000+) follow **descending file name order** of the block files,
not identifier order: in `creative-probe-file-order` the files `b14.json` .. `b01.json` (names unrelated to the
identifiers) got 10000 .. 10013. With one file per block named after the block, the usual add-on layout and the one
of the capture add-on, this is descending identifier order.

Paletter has no files, so it numbers custom blocks by **identifier, descending** (`BlockPalette#withCustomBlocks`),
and `CreativeOrdering.BDS` inserts them into the simulated map in that order, then the version's vanilla data driven
blocks (`VanillaData#dataDrivenBlocks`). This is exact for add-ons whose block file names sort like their identifiers;
for others BDS's block ids, and so the order of block items in the creative inventory, differ. Registration order
would be no better a proxy (it is not what BDS uses either), and identifier order is the one consumers converting add-on
packs get right without doing anything. Item order does not depend on this: items keep registration order.

## Evidence

All captures are in `captures/1.26.63.1/` (`creative_items.json.gz` is the full creative packet; `custom_*.nbt` the
custom item registry and block properties, which carry the menu categories, item runtime ids and block ids).

| Capture | Content | Result |
|---|---|---|
| `addon` | the capture add-on (`addon/`, 66 blocks, 84 items, experiments on) | 3 independent captures (hashed, sequential, probe run) identical |
| `creative-probe-file-order` | first version of ``addon --kind creative``: 14 blocks, 22 items, every kind of placement, file names unrelated to identifiers, namespaces `aaa`/`exp`/`zzz` | 2 server starts identical; evidence for the file name order of block ids (not compared with Paletter) |
| `creative-probe` | ``addon --kind creative``: the same content, block files named by identifier rank | 2 server starts identical; block ids in descending identifier order |
| `creative-large` | `addon --kind creative-large`: 700 blocks, 40 items, random identifiers, categories and groups; block files named by identifier rank, item files random | 2 server starts identical; the block map grows to 1024 buckets |

A reference model of the rule above (a Python script first, now `CreativeOrderCheck` in tools) reproduces the
**entire packet** of all three captures: every group in packet order, its icon, and every stack in order.

Key observations of the probe (`creative-probe-file-order`, items listed with their file name):

- Sword group, items `exp:sword_a` (f01), `aaa:sword_d` (f06), `exp:sword_c` (f09), `zzz:sword_b` (f12): appended to
  the end of `itemGroup.name.sword` in exactly that order, after the block `exp:block_sword` (block pass first). Not
  sorted by identifier, nor by namespace: by file order. Item runtime ids were assigned in the same order (853..).
- Loose items: `zzz:loose_a` (f03), `exp:hidden_items` (f05), `exp:placer_item` (f07), `aaa:loose_z` (f11),
  `exp:loose_m` (f17) at the end of *items*, after the block pass entries `exp:block_with_item`, `exp:block_items`.
- New groups were appended to the group list in creation order: `exp:itemGroup.g3` (nature, block), `exp:itemGroup.g1`
  (equipment, block `exp:block_g1`, then items `exp:g1_b` f08 and `exp:g1_a` f13 joined it), `exp:itemGroup.g0` (f04),
  a new unnamed nature group (`exp:nature_loose`, f10; nature ends with a named group), `exp:itemGroup.g2` (f16), a new
  unnamed equipment group (f18), `itemGroup.name.sword` in *items* (f21), `exp:itemGroup.g1` in *nature* (f22). Every
  new named group had an air icon. `exp:construction_loose` (f19) joined construction's trailing unnamed group.
- `exp:block_with_item`: block with menu category *construction*, item of the same name with *items*: it is in *items*,
  in the block pass. Its block item was renamed `exp:item.block_with_item` in the item registry.
- Not in the creative inventory: `exp:no_menu` (no menu_category), `exp:cat_none`, `exp:group_only` (group, no
  category), `exp:block_none`, `exp:block_nomenu`. In it: `exp:hidden_items`, `exp:block_hidden` (hidden in commands).
- Blocks in construction: `exp:block_hidden`, `zzz:block_c`, `exp:block_b`, `exp:block_placed`, `aaa:block_d`,
  `exp:block_a`: neither identifier nor file order, but the block map order (see `CreativeOrderingTest`).
- Item properties keep the group as written: `creative_group` is `minecraft:itemGroup.name.sword` while the creative
  packet names the group `itemGroup.name.sword`. Paletter writes `creative_group` the same way (a group without a
  namespace gets `minecraft:`).
- Without components an item fails to parse and BDS still registers it, but drops its menu category: it lands loose in
  *items* (first probe run; the probe add-on gives every item a component).

## Paletter

`CreativeOrdering.BDS` (default, `PaletteLayer.Builder#creativeOrdering`) applies this rule to a layer's content:
block items first in `blockOrder` order (items of the layer's blocks and those registered with
`PaletteLayer.Builder#blockItem`), then custom items in registration order (`PaletteLayer#itemEntries`); placements without an anchor go into the group of
their category (created at the end of the category if missing, `minecraft:` prefix ignored) or loose at the end of the
category. A placement with an anchor (`CreativePlacement#after`) is an explicit override and goes after the anchor.
Blocks without a creative placement but with a menu category (other than `none`) are placed by it, like BDS.
`CreativeOrdering.APPEND` is the behaviour before: registration order, then items of `block`s; groups looked up by
name in any category.

What Paletter does differently on purpose (the client shows the same):

- groups stay in category order in the packet (BDS appends new groups to the end of the list);
- a new group gets the default icon (its first stack) instead of air.

Limits:

- **Windows BDS only.** The block map order comes from MSVC's `std::unordered_map` and `std::hash`; the Linux build uses
  another standard library, so its block order is likely different (not captured: no Linux host). Item order, group
  rules and loose placement do not depend on hashing.
- Layers: each layer runs both passes for its own content, after what earlier layers placed (the block map order is
  computed over all custom blocks of the palette). Content split over several layers is therefore ordered layer by
  layer; it matches BDS exactly when the content is in one layer.

## Verification

- `tools` test `CreativeOrderCheckTest` compiles each capture (`addon`, `creative-probe`, `creative-large`)'s custom content through Paletter (blocks by block id,
  items by runtime id, menu categories from the wire) and compares the creative inventory with the captured packet;
  `APPEND` is checked to differ.
- `update` captures the add-on and the creative order probe (`creative-addon/`, an extra world without experiments) and
  runs the same comparison; a difference is a DIFF plus a TODO in the report (BDS changed its rule).
- By hand: `./gradlew :tools:run --args="creative-probe --addon <dir> --out <dir> --starts 2"` runs an add-on on its
  own BDS copy (`<cache>/servers/creative-probe-<platform>-<version>`) and captures each start.
