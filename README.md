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
| `bds-addon` | A behavior pack that dumps what the Script API knows (item tags and properties, block states, defaults and tags). |
| `codegen`   | Infers component shapes from wire NBT and generates the typed components. Not shipped.          |
| `capture`   | A headless Bedrock client that records what BDS sends (palette data, custom item/block NBT). Not shipped; see `capture/WIRE-NOTES.md`. |

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

## Components

Data driven item and block components are typed records in `org.geysermc.paletter.component.item` and `...block`,
**generated from NBT captured off the wire** (bundled vanilla data plus BDS captures), never from add-on JSON schemas,
which differ from the network format. Each type has `TYPE` (name, confidence, min format version, experimental flag),
`fromNbt` and `toNbt`:

```java
ItemComponents components = ItemComponents.builder()
    .with(Damage.of((short) 5))                        // typed, exact wire types
    .with(Cooldown.builder().category("geyser:pearl").duration(1).build())
    .maxStackSize(16)                                  // conveniences also set the mirrored item_properties
    .component("minecraft:rarity", rawNbt)             // raw escape hatch for components without a type
    .build();
Damage damage = components.get(Damage.TYPE);
```

Types inferred from vanilla NBT are `Confidence.HIGH` and round-trip every vanilla data driven item and block in the
bundled data unchanged. Types known only from Geyser's production encoders are `MEDIUM` and annotated `@Unverified`.
Components never seen on the wire have no type; `codegen/needs-capture.txt` lists them for the capture pipeline.
Regenerate with `./gradlew :codegen:generateComponents`; `./gradlew check` fails if the committed sources are stale.
See `codegen/README.md`, and `codegen/DEVIATIONS.md` for where Paletter's NBT differs from Geyser's.

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
26.60, about 470 KB instead of about 2.9 MB) combines the network data Geyser shipped with BDS dumps of the same versions:

| Version | Network data (block palette, items, components, creative) | BDS dump (item tags, block defaults) |
|---------|-----------------------------------------------------------|--------------------------------------|
| 26.40   | Geyser `*.26_40.*`                                        | BDS 1.26.45.1                        |
| 26.50   | Geyser `*.26_50.*`                                        | BDS 1.26.51.1                        |
| 26.60   | Geyser `*.26_60.*`                                        | BDS 1.26.63.1                        |

The generator writes maps sorted, so an import is byte for byte reproducible.

### Data sources

What each source can provide, and whether the generator gets it automatically:

| Datum | Source | Automated |
|-------|--------|-----------|
| Block list, block states and their valid values | BDS Script API (`BlockTypes`, `BlockPermutation.getAllStates`, `withState`) | dumped and cross-checked; the bundle still takes them from the network palette (see below) |
| Default block state | BDS Script API (`BlockPermutation.resolve`) | **yes, bundled**. Only defaults that are not a block's first palette state are stored (26 to 28 per version); the first state is the default of every other block |
| Block palette order (runtime ids) | not observable from scripts; client and server derive it from the block registry | no: BDS internals |
| Item tags | BDS Script API (`ItemStack.getTags`) | **yes, bundled** (before: Geyser's 26.30 tags for every version) |
| Item list | BDS Script API (`ItemTypes.getAll`) | dumped and cross-checked only: the Script API omits about 455 items the client knows (block-only items such as double slabs and wall signs, legacy ids such as `minecraft:boat`, education items) |
| Item max stack size, durability, localization key, Script API components | BDS Script API (`ItemStack.maxAmount`, `minecraft:durability`, `localizationKey`) | dumped and cross-checked against the item components (no differences) |
| Block tags, block to item | BDS Script API (`BlockPermutation.getTags`, `getItemStack`) | dumped, not bundled (no consumer yet) |
| Item runtime ids, item version, component based flag | `ItemRegistryPacket` | no: network capture |
| Item component NBT (vanilla data driven items) | `ItemRegistryPacket` | no: network capture |
| Creative inventory (groups, order, icons, damage, block states, NBT) | `CreativeContentPacket` | no: network capture |
| Data driven block properties | `StartGamePacket` block properties | no: network capture |
| Item and block ids, `raw_id`, command names | `Mojang/bedrock-samples` `metadata/vanilladata_modules/mojang-items.json`, `mojang-blocks.json` | not used: `raw_id` is the item runtime id for most items but not all (34 of 1627 differ for 26.60), 450+ network items are missing, and blocks only list state *names* (with the same legacy alias states as the Script API) plus one global value list per state; no defaults, no order |
| State value lists | `mojang-blocks.json` `block_properties`, Script API `BlockStates.getAll` | dumped (`state_types`); global per state, not per block |
| Vanilla item and block JSON incl. `menu_category`, `minecraft:tags` | `bedrock-samples` `behavior_pack/items`, `behavior_pack/blocks` | not used: only the ~80 items and ~100 blocks vanilla defines in JSON; everything else is hard coded |
| Creative catalog | BDS `behavior_packs/vanilla/__brarchive/item_catalog.brarchive` (`crafting_item_catalog.json`) | not used: Mojang's binary `MCB` encoding; it names groups and items per category, but no damage values, block states or NBT, so it cannot replace `CreativeContentPacket` |
| Script API surface | `bedrock-samples` `metadata/script_modules/@minecraft/server-bindings_*.json` | used by hand to pick the addon's module version |
| `definitions/` in the BDS zip | attachables, feature rules, features, spawn groups, persona, SDL layouts, disconnection errors | nothing Paletter needs |

Things to know about the Script API data:

- `getAllStates()` also returns **legacy alias states** that are not part of the network block state: 39 state names on
  about 530 blocks (e.g. `wood_type` on planks, `color` on wool, `direction` next to `minecraft:cardinal_direction`,
  `top_slot_bit` next to `minecraft:vertical_half`). The importer drops every state the block palette does not have.
  After that, every BDS default of 26.40, 26.50 and 26.60 is a palette state.
- `withState` answers with another block for alias values (e.g. `flower_type: poppy` on `minecraft:pink_tulip`) instead
  of throwing, so the addon checks the type and value of the result.
- Apart from `minecraft:chalkboard` (education edition: the palette has `direction` 0-15, the Script API accepts 0-3),
  every block's palette states are exactly all combinations of the values the Script API reports. The *set* of block
  states could therefore be generated from BDS; their order cannot be observed.
- Native arrays such as `BlockStateType.validValues` are not iterable with `for ... of` in BDS 1.26; the addon copies
  them by index.

### What still needs a network capture

The Script API cannot see what the server sends a client, so these still come from a capture of a vanilla BDS session
in the CloudburstMC/Data layout, e.g. with ProxyPass between a client and BDS (`online-mode=false`). ProxyPass writes
the files from the corresponding packets:

- `runtime_item_states.json`: identifiers, runtime ids, item versions and the component based flag, from the
  `ItemRegistryPacket` (formerly the item list of `StartGamePacket`);
- `item_components.nbt`: component NBT of data driven vanilla items, from the same packet;
- `creative_items.json`: the `CreativeContentPacket` (groups with names and icons, items with damage, block state, NBT);
- `data_driven_blocks.nbt`: block properties from `StartGamePacket`;
- `block_palette.nbt`: the vanilla palette is not sent at all. Both sides build it from the block registry (sequential
  ids sort blocks by the FNV-1a hash of their name; with `block-network-ids-are-hashes` the id is a hash of the state).
  CloudburstMC/Data and pmmp's BedrockData get it from BDS internals (pmmp/bds-mod-mapping is a native BDS mod for this).
  Geyser's copy is the input today. With the Script API dump the set of states is known and cross-checked; only the
  order of the permutations would have to be reproduced to generate it.

### Updating

1. Capture the network data of a BDS session in the CloudburstMC/Data layout (`block_palette.nbt`,
   `runtime_item_states.json`, `item_components.nbt`, `creative_items.json`, optionally `data_driven_blocks.nbt`), see
   above. Files may also be named like Geyser's resources (`block_palette.26_70.nbt`), so `--source` can point at Geyser's
   `core/src/main/resources/bedrock`.
2. Run the addon on BDS. The generator downloads BDS (the newest by default; older releases stay downloadable, but only
   by their exact version), installs `bds-addon` on a fresh flat world, runs BDS headless until the addon prints
   `PALETTER-DONE`, stops it and keeps the console log. Keep the BDS directory outside the repository. Downloading BDS
   means accepting the [Minecraft EULA](https://minecraft.net/eula); BDS asks for nothing else.
   ```
   ./gradlew :generator:run --args="bds-run --download /tmp/bds --log /tmp/bds/bds.log"
   ./gradlew :generator:run --args="bds-run --download /tmp/bds --version 1.26.51.1 --log /tmp/bds/bds-1.26.51.1.log"
   ./gradlew :generator:run --args="bds-run --server /path/to/extracted/bds --log /tmp/bds/bds.log"
   ```
   A run takes well under a minute; the dump itself is a script job of about a second.
3. Import, then cross-check the dump with the imported data (block states, defaults, items, stack sizes, item tags; add
   `--item-tags FILE` to compare with another tag file):
   ```
   ./gradlew :generator:run --args="import --source DUMP_DIR --name 26.70 --protocol 2250 --bds-dump /tmp/bds/bds.log"
   ./gradlew :generator:run --args="bds-compare --bds-dump /tmp/bds/bds.log --name 26.70"
   ```
4. Run `./gradlew :generator:run --args="check"`. Every block and item of the newest version that an older version lacks
   needs an entry in `generator/fallbacks/<old version>.json` (or `null` to acknowledge there is none). Re-import the older
   versions after editing their fallbacks.

The current bundle was produced with:
```
G=<Geyser>/core/src/main/resources/bedrock
bds-run --download DIR --version 1.26.45.1 --log bds-1.26.45.1.log    (and 1.26.51.1, 1.26.63.1)
import --source $G --name 26.40 --protocol 2168 --bds-dump bds-1.26.45.1.log
import --source $G --name 26.50 --protocol 2193 --bds-dump bds-1.26.51.1.log
import --source $G --name 26.60 --protocol 2225 --bds-dump bds-1.26.63.1.log
```

## Status and known gaps

- Block palettes, items, item components and the creative inventory are still the network dumps Geyser shipped; see
  "What still needs a network capture".
- Item tags now come from BDS. Compared with Geyser's `item_tags.26_30.json` (used for every version before), all three
  versions add `minecraft:mushrooms_for_stew` and no longer have `minecraft:observer` and `minecraft:redstone_lamp` in
  `minecraft:sulfur_cube_archetype_slow_bouncy`; 26.50 and 26.60 also add the poplar wood set to the wood tags (boats,
  door, signs, logs, planks, slab) and wool slabs and stairs to `minecraft:vibration_damper`.
- Block tags and block to item are dumped but not bundled.
- `ItemComponents` and `BlockComponents` type the components Geyser uses today; anything else goes through the raw
  `component(...)` escape hatch.
- Not covered: biome definitions, entity identifiers and other data Geyser ships that is not about items or blocks.
- The BDS addon needs `@minecraft/server` 2.1.0; tested with BDS 1.26.45.1, 1.26.51.1 and 1.26.63.1.
