# Paletter

Bedrock item and block palettes, ready for the wire. Paletter bundles vanilla data for every supported version, compiles
it together with layers of custom content, and hands out Cloudburst protocol types: definition registries for the codec,
the item component packet, the creative content packet and block properties for start game.

Paletter is Bedrock only. It knows nothing about Java Edition, so it serves Bedrock server software, proxies and tools as
well as translators like Geyser. A consumer maps its own content to **canonical ids** of the **newest** Bedrock version,
dense ints that Paletter defines once per release; everything else (runtime ids in either network id mode, palette
ordering, older versions, the creative inventory, custom item and block NBT) is Paletter's job.

```java
Paletter paletter = Paletter.bundled();

PaletteLayer layer = PaletteLayer.builder("server")
    .item(CustomItem.builder("example:ruby")
        .components(ItemComponents.builder().icon("ruby").displayName("item.ruby.name").build())
        .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minerals"))
        .build())
    .overrideItem("minecraft:cake", item -> item.components(c -> c.maxStackSize(1)))
    .creative(editor -> editor.remove(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))))
    .build();

Palette palette = paletter.palette(codec, BlockNetworkIds.HASHED, layer);   // once per client version, shared
Palette forSession = palette.extend(sessionLayer);                          // optional, per session

palette.applyTo(codecHelper);                  // item + block definition registries
palette.applyTo(startGamePacket);              // block properties, blockNetworkIdsHashed
session.sendPacket(palette.itemComponentPacket());
session.sendPacket(palette.creativeContentPacket());
```

## Modules

| Module      | What                                                                                           |
|-------------|------------------------------------------------------------------------------------------------|
| `paletter`  | The library. Depends on Cloudburst `bedrock-codec` (its types are the public API) and fastutil. No JSON, no network. |
| `generator` | Imports palette dumps into the bundled format and checks version coverage. Not shipped.        |
| `bds-addon` | A behavior pack that dumps what only the running game knows (item tags, default block states). |

## Concepts

**Palette.** Vanilla data of one version plus a stack of layers, compiled. Immutable and thread safe. Item definitions are
`PaletteItem`, block definitions `PaletteBlock`; both are compared by identity.

**Layers.** A `PaletteLayer` is a patch: custom items and blocks, overrides of vanilla items, creative edits and fallbacks.
Layers do not know protocol versions; the same layer compiles against every version. Stack them to split ownership, e.g.
a base layer, then plugins or extensions, then a per-session layer. `Paletter#palette` caches every prefix of a layer list;
`Palette#extend` is meant for short lived layers. Definitions a layer does not touch are shared with the palette it
extends, so `==` between a session's definitions and the shared ones keeps working; without custom blocks or block
fallbacks the whole block palette, including its canonical table, is shared.

**Canonical ids.** Every block state of the newest bundled version has a dense canonical id (`CanonicalBlocks`), in that
version's vanilla palette order; every item variant of the newest version has one too (`CanonicalItems`: each item's base
variant in runtime id order, then the other variants of the creative inventory, i.e. damage values like potions). They
are stable for a Paletter release and the same for every protocol version. Custom blocks and items of a layer get ids
appended after the vanilla ones, per lineage of layers: the same layer stack gives the same ids in every version.
A layer can also give ids to further variants of the newest version (`PaletteLayer.Builder#variant`), e.g. damage values
or block states a consumer maps to that are not in the creative inventory; `Palette#match` recognizes them too.
The tables are built when a palette is compiled; canonical content a version lacks (sent as air, or `item` is null) is
listed in the palette's report.
Each palette turns them into what its version needs with flat arrays built once per palette and shared where possible:

| Lookup                          | Cost                               | Result                                              |
|---------------------------------|------------------------------------|-----------------------------------------------------|
| `Paletter#canonicalBlock(state)` | hash lookup (state normalized)    | canonical id or -1                                  |
| `Palette#runtimeId(canonical)`  | array                              | runtime id in the palette's network id mode         |
| `Palette#block(canonical)`      | array                              | `PaletteBlock`: exact, adapted, fallback, else air  |
| `BlockPalette#canonical(definition)` | array                         | canonical id of a block the client sent             |
| `Paletter#canonicalItem(variant)` | hash lookup                      | canonical id or -1                                  |
| `Palette#item(canonical)`       | array                              | `ResolvedItem`: definition, damage, block, fallback flag; null if missing |
| `Palette#match(itemData)`       | hash lookup by runtime id + a few candidates | canonical id of a stack the client sent  |

None of them allocate.

**Block network ids.** `BlockNetworkIds.SEQUENTIAL` uses palette positions (vanilla order, re-sorted by name hash once
custom blocks exist, so a custom block shifts vanilla ids). `BlockNetworkIds.HASHED` uses what the client expects when
`StartGamePacket#blockNetworkIdsHashed` is set: FNV-1a 32 over the little endian NBT of `{name, states}` with states
sorted by key and no `version` (`minecraft:unknown` is -2). A state then has the same id in every version and palette, so
ids and encoded chunks can be shared between client versions. Paletter does so itself: hashed palettes of all versions
share one `PaletteBlock` per vanilla state and equal pages of their canonical tables. Hash collisions within a palette
fail compilation.
`Palette#applyTo(StartGamePacket)` sets the flag to match.

**One mapping for all versions.** Map against the newest version only. For older versions, `BlockPalette#resolve` adapts
states automatically (states added or removed between versions, e.g. stair corners) and applies bundled fallbacks for
blocks that do not exist (or are experimental) there; `ItemPalette#resolve` does the same for items. The canonical tables
are filled with these results. When matching stacks from the client, a variant that only reached a version through a
fallback never shadows an item's own variant.

**Block items.** The item palette knows which block each block item is sent with (from the creative inventory, a layer
override, or the block of the same name), and ignores the meaningless block definitions clients send with other items.

## Using Paletter from a Bedrock-only project

A server or proxy that speaks Bedrock natively stores blocks and items by canonical id, and converts at the network edge:

```java
Paletter paletter = Paletter.bundled();
Palette palette = paletter.palette(session.getCodec(), BlockNetworkIds.HASHED, serverLayer);
palette.applyTo(session.getPeer().getCodecHelper());
palette.applyTo(startGame);

// World storage uses canonical ids, so it does not care about the client version
int stone = paletter.canonicalBlock(BlockStates.of("minecraft:stone"));
int runtimeId = palette.runtimeId(stone);              // write into the chunk / UpdateBlockPacket

// Items
int ruby = palette.canonicalItem(ItemVariant.of("example:ruby"));
ItemData stack = palette.item(ruby).toItemData().count(3).build();

// What the client sent back
int canonical = palette.match(inventoryAction.getFromItem());   // -1 if unknown
ItemVariant variant = palette.canonicalItems().variant(canonical);
```

## Using it from a Java <-> Bedrock translator

A translator keeps its own mapping, written once against the newest Bedrock version and turned into canonical ids at
startup. The tables are version independent; each client version only adds Paletter's per palette arrays.

```java
Paletter paletter = Paletter.bundled();

// Java block state id -> canonical Bedrock block, once
int[] javaToCanonical = new int[javaStateCount];
for (int javaId = 0; javaId < javaStateCount; javaId++) {
    NbtMap bedrockState = mappings.bedrockStateOf(javaId);      // newest Bedrock version
    int canonical = paletter.canonicalBlock(bedrockState);
    if (canonical == -1) {
        throw new IllegalStateException("Not a " + paletter.latest() + " block state: " + bedrockState);
    }
    javaToCanonical[javaId] = canonical;
}

// Per client version (shared by its sessions); chunk translation is two array lookups per block
Palette palette = paletter.palette(codec, BlockNetworkIds.HASHED, translatorLayer);
int runtimeId = palette.runtimeId(javaToCanonical[javaStateId]);

// Optionally cache per palette: int[] javaToRuntime, built once
int[] javaToRuntime = new int[javaStateCount];
for (int javaId = 0; javaId < javaStateCount; javaId++) {
    javaToRuntime[javaId] = palette.runtimeId(javaToCanonical[javaId]);
}
// With HASHED ids, javaToRuntime is equal between versions for every state they share, so it can be deduplicated

// Items, both directions. Variants the translator maps to that are not in creative get ids from its layer:
//   PaletteLayer.builder("translator").variant(ItemVariant.of("minecraft:banner", 15))...
int[] javaItemToCanonical = ...;                   // from the translator's item mappings, via palette.canonicalItem(variant)
JavaItem[] canonicalToJava = new JavaItem[palette.canonicalItemCount()];   // the reverse, also by canonical id
ItemData toClient = palette.item(javaItemToCanonical[javaItemId]).toItemData().count(count).build();
JavaItem fromClient = canonicalToJava[palette.match(itemDataFromClient)];

// Custom content of the translator's layer has canonical ids appended after the vanilla ones,
// identical in every version: look them up on any palette with that layer
int furnaceMinecart = palette.canonicalItem(ItemVariant.of("geysermc:furnace_minecart"));
```

One to one mappings are the translator's business: two Java items claiming the same canonical id is a check on its own
table (`canonicalToJava[id] != null`), and is what turns bugs like GeyserMC/Geyser#6668 into a startup error.

## The creative inventory

The model is `CreativeLayout`: per category, an ordered list of `CreativeNode`s, each either a `CreativeGroup` or a loose
`CreativeStack` (a sealed interface, so `switch` covers both without `isGroup`/`asGroup` casts). Nodes do not know where they
are; the layout does, so they cannot disagree with it. Stacks are identifiers, damage, block state and NBT, never
definitions, so one layout serves every version.

Edits are relative to existing content, so they survive Bedrock reshuffling vanilla:

```java
editor.insert(stack).after(ItemMatcher.id("minecraft:hopper_minecart"));
editor.insert(stack).after(anchor) || editor.insert(stack).intoGroup("itemGroup.name.minecart");
editor.move(ItemMatcher.namespace("geysermc")).loose(CreativeCategory.ITEMS);
editor.groupOrCreate(CreativeCategory.ITEMS, "geyser:tools").add(stack).placeAfterGroup("itemGroup.name.minecart");
editor.replace(ItemMatcher.id("minecraft:firework_rocket"), s -> s.withTag(lore));
```

`CreativeContent` compiles a layout per palette and enforces the protocol rules: categories in client order and contiguous,
loose runs as unnamed groups with an air icon, items missing in the version dropped, empty groups dropped (they crash the
client), missing icons replaced by the first stack, net ids sequential from 1 (`entry(netId)` is an array lookup). The
category mess (protocol enum offset by `ALL`, numeric `creative_category` item property, `menu_category` names) lives in one
enum, `CreativeCategory`.

Custom items and blocks declare a `CreativePlacement`: after an anchor if present, else at the end of a named group
(created if needed), else loose at the end of the category. The same placement is written into the item's
`creative_category`/`creative_group` and the block's `menu_category`.

## Data

Bundled data lives in `paletter/src/main/resources/paletter/`: `versions.txt` and one gzipped NBT file per version
(format documented in `PaletteDataCodec`). Each file holds block states, items with their components, data driven blocks,
the creative layout, item tags, default block states and the fallbacks for that version. The current bundle (26.40, 26.50,
26.60) was imported from the files Geyser shipped, and is about 480 KB instead of about 2.9 MB.

### Updating

1. Dump the network data of a BDS session in the CloudburstMC/Data layout (`block_palette.nbt`, `runtime_item_states.json`,
   `item_components.nbt`, `creative_items.json`, optionally `data_driven_blocks.nbt`), e.g. with ProxyPass. Runtime ids, item
   components and the creative content are only sent over the network; the Script API cannot read them.
2. Install `bds-addon` as a behavior pack on the BDS world, start BDS with its output redirected to a file, and wait for
   `PALETTER-DONE`. This adds item tags and true default block states.
3. Import:
   ```
   ./gradlew :generator:run --args="import --source DUMP_DIR --name 26.70 --protocol 2250 --bds-dump bds.log"
   ```
4. Run `./gradlew :generator:run --args="check"`. Every block and item of the newest version that an older version lacks
   needs an entry in `generator/fallbacks/<old version>.json` (or `null` to acknowledge there is none). Re-import the older
   versions after editing their fallbacks.

## Status and known gaps

- The current bundle has no default block states yet (it was imported without a BDS dump), so adaptation uses each block's
  first state as default. Item tags are the 26.30 ones Geyser used for every version.
- `ItemComponents` and `BlockComponents` type the components Geyser uses today; anything else goes through the raw
  `component(...)` escape hatch.
- Not covered: biome definitions, entity identifiers and other data Geyser ships that is not about items or blocks.
- The BDS addon targets `@minecraft/server` 2.0.0; adjust `manifest.json` if the BDS version needs another module version.
