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
and `CreativeOrdering.BDS` inserts them into the simulated map in that order (the custom entries of the start game block property list and the
runtime ids of custom block items use the same order, whatever order the layers register the blocks in), then the version's vanilla data driven
blocks (`VanillaData#dataDrivenBlocks`). This is exact for add-ons whose block file names sort like their identifiers;
for others BDS's block ids, and so the order of block items in the creative inventory, differ. Registration order
would be no better a proxy (it is not what BDS uses either), and identifier order is the one consumers converting add-on
packs get right without doing anything. Item order does not depend on this: items keep registration order.

## Evidence

Captures of the creative content packet (`data/bds/1.26.63.1/`): `addon` (the capture add-on), `creative-probe` (every
kind of placement, block files named by identifier rank), `creative-large` (700 blocks, 40 items, random identifiers,
categories and groups; the block map grows to 1024 buckets) and a first probe with file names unrelated to identifiers
(evidence for the file name order of block ids). Repeated server starts are identical, and a reference model of the
rule above (`CreativeOrderCheck` in tools) reproduces the entire packet of every capture: each group in order, its
icon, and every stack.

Details observed: new groups are appended in creation order with an air icon; items join a group in file order, not
sorted by identifier or namespace; a block and an item with the same identifier are placed in the block pass with the
item's category; items without menu category or with category `none` are absent, `is_hidden_in_commands` has no effect;
`creative_group` in item properties keeps the `minecraft:` prefix while the packet drops it.

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
