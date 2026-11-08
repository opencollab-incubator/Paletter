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
        .components(ItemComponents.builder()
            .icon("ruby")
            .displayName("item.ruby.name")
            .maxStackSize(16)
            .tags("minecraft:gems")
            .build())
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

The API is stable: semantic versioning, a deprecation cycle and a binary compatibility check. See [API.md](API.md) for
what is API, what `@Experimental` and `@Internal` mean, and the policy.

## Modules

| Module      | What                                                                                           |
|-------------|------------------------------------------------------------------------------------------------|
| `paletter`  | The library. Depends on Cloudburst `bedrock-codec` (its types are the public API) and fastutil. No JSON, no network. |
| `generator` | Imports palette dumps into the bundled format and checks version coverage. Not shipped.        |
| `bds-addon` | A behavior pack that dumps what the Script API knows (item tags and properties, block states, defaults and tags). |
| `codegen`   | Infers component shapes from wire NBT and generates the typed components. Not shipped.          |
| `capture`   | A headless Bedrock client that records what BDS sends (palette data, custom item/block NBT). Not shipped; see `capture/WIRE-NOTES.md`. |
| `spec`      | The component model: add-on JSON side parsed from Microsoft's creator docs, a capture add-on generated from it, and the JSON -> wire mapping inferred from BDS (`codegen/model/`). Not shipped; see `spec/README.md`. |

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
The tables are built when a palette is compiled; canonical content a version lacks and that has no fallback (sent as
`minecraft:unknown`, see below) is listed in the palette's report.
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
fallback never shadows an item's own variant, and a stack that is only the fallback of several canonical ids (e.g. the
shared `minecraft:unknown`) matches none of them (-1).

**Fallbacks.** Canonical content an older version lacks resolves, in this order, to: a fallback a layer set for that
version (`PaletteLayer.Builder#blockFallback(version, identifier, fallback)`, `#itemFallback(version, ...)`), a layer
fallback for every version (`#blockFallback(identifier, fallback)`), the bundled fallback (`generator/fallbacks/`,
curated replacements such as poplar wood to pale oak), and otherwise the default: the block `minecraft:unknown` and the
item `minecraft:unknown` (its block item), which every client knows and shows as a placeholder
(`FallbackDefaults.UNKNOWN`). A layer changes the default for itself and the layers above it with
`#fallbackDefaults(...)`; `FallbackDefaults.NONE` restores air and missing items. `BlockFallback#force` /
`ItemFallback#force` also replace content that exists in a version but only behind an experiment.

```java
PaletteLayer.builder("server")
    .blockFallback("minecraft:calcite_stairs", BlockFallback.of("minecraft:stone_stairs"))       // every version
    .blockFallback("26.40", "minecraft:poplar_log", BlockFallback.of("minecraft:birch_log"))     // one version
    .itemFallback("26.40", "minecraft:poplar_boat", ItemFallback.of("minecraft:birch_boat"))
    .fallbackDefaults(new FallbackDefaults(BlockFallback.of("minecraft:barrier"), ItemFallback.UNKNOWN))
    .build();
```

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

## Vanilla constants

`org.geysermc.paletter.vanilla` has generated constants for the vanilla content of the newest bundled **release**:
`BedrockBlocks` (one `BedrockBlockType` per block), `BedrockStates` (one typed constant per state name) and
`BedrockItems` (one `BedrockItemType` per item). They are objects, not `int` compile time constants, so they stay binary
compatible across Paletter releases, and they are resolved against the newest data of `Paletter.bundled()` the first
time a state or id is needed; referencing them loads nothing.

```java
// Block states, typed. One instance per state: with() does not allocate, == works
BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.state()
    .with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH)   // EnumState<CardinalDirection>
    .with(BedrockStates.OPEN_BIT, true)                                // BoolState: byte 0/1 in NBT
    .build();
BedrockBlockState open = door.with(BedrockStates.UPPER_BLOCK_BIT, true);
CardinalDirection facing = open.get(BedrockStates.CARDINAL_DIRECTION);
List<Integer> growth = BedrockBlocks.WHEAT.values(BedrockStates.GROWTH);  // [0..7]: what this block accepts
door.with(BedrockStates.GROWTH, 3);                                      // IllegalArgumentException: not a door state

int canonical = door.id();                         // canonical id in Paletter.bundled(), valid in every version
int runtimeId = palette.runtimeId(door);           // = palette.runtimeId(canonical)
NbtMap nbt = door.toNbt();                         // {name, states}
BedrockBlockState back = BedrockBlockState.vanilla(canonical);   // same instance

// Items and their damage variants (the data has no names for those, so they are variant(int), not constants)
ItemData apple = palette.item(BedrockItems.APPLE).toItemData().count(3).build();
ItemData potion = palette.item(BedrockItems.POTION.variant(5)).toItemData().build();

// Everywhere an identifier is accepted, a constant is too
PaletteLayer layer = PaletteLayer.builder("server")
    .overrideItem(BedrockItems.CAKE, item -> item.components(c -> c.maxStackSize(1)))
    .blockFallback(BedrockBlocks.ICICLE, BedrockBlocks.STONE)
    .item(CustomItem.builder("example:ruby")
        .components(ItemComponents.builder()
            .repairable(Repairable.of(Repairable.Entry.items(BedrockItems.DIAMOND)))
            .digger(Digger.of().speed(BlockDescriptor.block(BedrockBlocks.STONE), 4))
            .build())
        .creative(CreativePlacement.loose(CreativeCategory.ITEMS).after(BedrockItems.DIAMOND))
        .build())
    .creative(editor -> editor.remove(ItemMatcher.of(BedrockItems.EMPTY_MAP.variant(2))))
    .build();
```

**Custom content is used alike.** `CustomBlock#type()` and `CustomItem#type()` (and `CustomBlock#itemType()`) return
the same handle types. Their canonical ids depend on the layer stack, so look them up on a palette:
`palette.runtimeId(lamp.type().defaultState().with(lit, true))`, `palette.canonicalBlock(state)`,
`palette.item(ruby.type())`. A vanilla constant matches custom properties by name, so a custom
`minecraft:cardinal_direction` property works with `BedrockStates.CARDINAL_DIRECTION`. Custom properties are typed as
`BoolState.of(name)`, `IntState.range(name, min, max)` or `StringState.of(name, values...)` (or `EnumState.of(name,
MyEnum.class)` with an enum implementing `StateValue`).

**Typing.** Byte states with the values 0 and 1 are `BoolState`, integer states `IntState` (a range or a list), string
states `EnumState` with a generated enum in `org.geysermc.paletter.vanilla.state`. States with identical value sets
share an enum (`minecraft:block_face` and `minecraft:facing_direction` share `Direction`; the four
`wall_connection_type_*` and `pale_moss_carpet_side_*` states share `WallConnectionType`). The current release has 124
state names, 24 enums, 1480 blocks and 2080 items.

**Arithmetic state ids.** Bedrock enumerates the states of every block as the full cartesian product of its values. The
generator verifies this for every block of the bundled release and derives each property's stride, so a state's
canonical id is `first state of the block + sum(value index * stride)`, computed once per state; a block that is not a
full product in a stride order would get a lookup table instead and be listed by the generator (none in 26.60).
States are created per block on first use and interned, so `with()` only indexes an array.

**Versions.** Constants describe the newest bundled release; for older clients the palette resolves them like any
canonical id (adapted states, fallbacks). A constant whose content the data no longer has (Bedrock removed it) stays,
`@Deprecated(forRemoval = true)`, and resolves to its fallback (`minecraft:unknown` unless `codegen/names.json` names
another); `isAvailable()` is false. A renamed block or item keeps its old constant as a deprecated alias of the new one.
Content only in a bundled preview goes to `BedrockPreviewBlocks` / `BedrockPreviewItems` (`@Experimental`, generated only
while a preview is bundled); without `paletter.previews` those resolve to `minecraft:unknown` too.

**Regenerating.** `./gradlew :codegen:generateConstants` (`check` fails if the committed constants are stale), and the
`update` pipeline does it whenever it imports data, listing added and deprecated constants in its report. See
[codegen/README.md](codegen/README.md#vanilla-constants).

## Components

Custom items and blocks are described with **semantic** builders: Java types for what an item or block is and does, in
the units of add-on JSON. They contain no NBT, no network key names and no encoding quirks.

```java
ItemComponents sword = ItemComponents.builder()
    .icon("example_sword")
    .displayName("item.example.sword.name")
    .maxStackSize(1)
    .handEquipped(true)
    .attackDamage(7)
    .durability(Durability.of(1561).damageChance(60, 100))   // percent
    .enchantable(Enchantable.of("sword", 10))
    .repairable(Repairable.of(Repairable.Entry.items("minecraft:diamond").amount("query.max_durability * 0.25")))
    .tags("minecraft:is_sword")
    .build();

ItemComponents spear = ItemComponents.builder()
    .kineticWeapon(KineticWeapon.builder()
        .delayTicks(12)
        .reach(Reach.of(2, 4.5f))
        .damageConditions(KineticWeapon.Conditions.of(300, 0, 4.6f))
        .build())
    .useAnimation(UseAnimation.SPEAR)
    .dyeable(Color.of(0xA06540))
    .build();

BlockComponents lamp = BlockComponents.builder()
    .geometry(Geometry.of(Geometry.FULL_BLOCK))
    .materials(Map.of(Material.ALL_FACES, Material.of("example_lamp").faceDimming(false).isotropic(true)))
    .placementFilter(PlacementFilter.allowOn(Face.UP).blocks("minecraft:grass_block", "minecraft:dirt"))
    .collisionBoxes(new Box(-8, 0, -8, 16, 8, 16))        // pixels, relative to the bottom center
    .rotation(0, 90, 0)                                   // degrees
    .lightEmission(10)
    .tags("minecraft:stone")
    .build();
```

Item values: icon, display name, stack size, off hand, hand equipped, glint, creative destroy, stacked by data, liquid
clipped, despawn, fire resistance, durability, attack damage, enchantability, dyeable colour, kinetic and piercing
weapons, swing duration, food, use animation and modifiers, cooldown, block and entity placers, shooter, throwable,
projectile, compostable, fuel, repairable, digger, rarity, wearable, render offsets, tags. Block values: display name,
selection and collision boxes, geometry, materials, placement filter, mining time, friction, light emission and
dampening, transformation, tags. Anything else can be set with the `@Experimental` raw escape hatch
(`ItemComponents.Builder#raw`, `#rawProperty`, `BlockComponents.Builder#raw`): you then own the wire format, see
[API.md](API.md#experimental).

### Versioned encoding

Paletter encodes the semantic values when a palette is compiled, for that palette's Bedrock version
(`internal.encode.ItemEncoder` / `BlockEncoder`, the only code that knows the wire format). Everything Bedrock sends in two
places is written in both: the stack size as a component and an item property, tags as `item_tags` and `minecraft:tags`
for items and as the top level `blockTags` for blocks, the use animation as a name and an id, the use duration in
seconds and ticks.

The network shapes are generated records in `org.geysermc.paletter.internal.wire` (not API), **generated from NBT captured
off the wire** (bundled vanilla data plus BDS captures), never from add-on JSON schemas, which differ from the network
format. Every shape carries a protocol window (`ComponentType#sinceProtocol`/`untilProtocol`). When a Bedrock version
changes a component's shape, the spec gains an entry for the new window, the records are regenerated, and the encoder
maps the same semantic value to the shape of each palette's version: no API change. A value a version cannot express
(the component does not exist there, or a shape the encoder does not produce yet) is left out with a warning in
`Palette#report()`; compilation never fails because of it.

Shapes inferred from vanilla NBT round-trip every vanilla data driven item and block in the bundled data unchanged.
Regenerate with `./gradlew :codegen:generateComponents`; `./gradlew check` fails if the committed sources are stale. See
`codegen/README.md`, and `codegen/DEVIATIONS.md` for how Paletter's output differs from what Geyser used to send.

The next API generation mirrors the add-on component model itself (typed `minecraft:durability {max_durability, damage_chance}`,
traits, states, permutations, `menu_category`) and compiles it to the wire per protocol. Its inputs are in `codegen/model/`:
`components.json` (from the official creator docs, CC BY 4.0, see `codegen/model/ATTRIBUTION.md`), `mappings.json` (inferred from BDS
captures of a generated add-on) and `coverage.md`; see `spec/README.md`.

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

Custom items and blocks declare a `CreativePlacement` (a block may instead only have a `menu_category`): after an anchor
if present, else in a named group, else loose at the end of the category. Without an anchor, the layer's
`CreativeOrdering` decides the rest. `BDS` (default) puts content exactly where Bedrock Dedicated Server puts add-on
content: block items first, in the iteration order of BDS's data driven block map, then items in definition order, each
at the end of the group of its category (created at the end of the category if missing; `minecraft:` prefix ignored) or
loose at the end of the category. `APPEND` is the old behaviour. The rule, its evidence and the checks against BDS
captures are in [`capture/CREATIVE-ORDER.md`](capture/CREATIVE-ORDER.md). The same placement is written into the item's
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

### Updating data

One command pulls the newest Bedrock Dedicated Server of a channel, runs everything against it and writes a report:

```
./gradlew :generator:run --args="update --channel release"                  # update the repository
./gradlew :generator:run --args="update --channel release --verify-only"    # CI: write nothing, exit 1 on any difference
./gradlew :generator:run --args="update --channel preview --verify-only --report reports/preview.md"
```

| Option | Meaning |
|---|---|
| `--channel release\|preview` | which BDS to resolve with the official links API (`net-secondary.web.minecraft-services.net/api/v1.0/download/links`); the Windows or Linux build, by host OS |
| `--version 1.26.63.1` | a specific build instead of the newest (old builds stay downloadable, but are not listed anywhere) |
| `--verify-only` | write nothing to the repository; exit 1 if anything differs from the committed data or spec, or a check fails |
| `--import-preview` | allow a preview to be imported (as a version marked `preview` in `versions.txt`, see below) |
| `--keep-server` | keep (and reuse) the extracted server; by default it is removed after the run, the zip stays cached |
| `--cache DIR` | BDS cache; default `$PALETTER_BDS_CACHE`, else `<gradle user home>/caches/paletter/bds`. Never in the repository |
| `--report FILE` | where to write the markdown report (always also `<cache>/runs/<platform>-<version>/report.md`) |
| `--name 26.70`, `--protocol N` | name of a new bundled version (default from the BDS version: `1.26.70.23` -> `26.70`); protocol if the RakNet pong does not carry it |
| `--experiments a,b` | manual override: use exactly these toggles instead of discovering them (see "Experiments" below) |
| `--experiment-attribution each\|all\|none` | captures per experiment toggle (default `each`: one with all toggles and one per current toggle; `all` only the first) |
| `--port 19260`, `--timeout 300` | base port (port + 1 for IPv6), seconds per server start |

Downloading BDS means accepting the [Minecraft EULA](https://minecraft.net/eula). A run takes about a minute plus the
download (about 100 MB, once per build). What it does, in `<cache>/runs/<platform>-<version>/` (logs, captures):

1. **Download** the zip into `<cache>/zips/`, extract it into `<cache>/servers/`.
2. **Script API dump**: `bds-addon` on a fresh flat world, BDS runs until the addon printed `PALETTER-DONE`
   (`bds-dump.log`, `bds-dump.json`).
3. **Vanilla world**, `transport=raknet`, `online-mode=false`, `block-network-ids-are-hashes=true`: a RakNet ping reads the
   protocol (LAN visibility is on for this, otherwise the pong is empty), the headless capture client (`capture` module)
   records the item registry, creative inventory and block properties, every creative block id is checked against the
   FNV-1a 32 hashes of the palette. Then once more with sequential ids.
4. **Experiments**: the toggles of this build are discovered, verified and attributed (see "Experiments" below).
   **Add-on world**: `capture/addon` installed, the world created once, the current toggles switched on in `level.dat`
   (start game must report exactly them), then the same two captures. The content log of that start (definitions BDS rejects) is kept.
5. **Assertions** on every run, first confirmed by hand on 1.26.63.1: sequential block ids are positions in the palette
   sorted by the FNV-1 64 hash of the block name (vanilla and with 66 custom blocks, checked through the hashed run's
   states); custom `block_id`s are 10000+ in descending identifier order (really descending file name order; the capture add-on names files after blocks, see `capture/CREATIVE-ORDER.md`); custom block items have id `255 - block_id`;
   the bundled palette is in name hash order.
6. **Compare**: the Script API dump against the committed baseline of the newest release (`generator/bds-dumps/`); for a
   protocol that is already bundled, the capture (imported like `import` would) against the bundled version (items,
   runtime ids, components, data driven blocks, creative layout) and the refreshed item tags and block defaults; for a
   new protocol, everything against the newest bundled release; the add-on content log against
   `capture/addon/content-log.txt`; component wire shapes of the add-on capture (against the newest
   `codegen/captures/bds-*-addon`) and of the vanilla data driven items and blocks (against the bundled version), plus
   coverage by `codegen/spec/inferred.json`. An added, removed or retyped field, or a component that is no longer sent,
   is a **WIRE FORMAT CHANGE** at the top of the report.
7. **Write** (not with `--verify-only`): a new protocol is imported as a new bundled version (its palette starts from the
   newest release, with blocks whose Script API states changed and new blocks generated from the Script API, then
   verified by the hash and sequential checks); for an existing one only item tags and block defaults are refreshed
   (network differences become a TODO); a new add-on capture goes to `codegen/captures/bds-<version>-addon/` and the
   component types are regenerated; the Script API baseline and the content log are updated.
8. **Constants**: the vanilla constants (`BedrockBlocks`, `BedrockStates`, `BedrockItems`, ...) are regenerated from the
   staged data with `codegen/constants-history.json`; the report lists the constants added and deprecated. A name
   collision or a state that changes its kind fails this step until `codegen/names.json` settles it.

The report (`reports/preview-1.26.70.23.md` is an example) lists checks with PASS/DIFF/FAIL, every difference, and a
**TODO** list of what needs a decision.

### Experiments

Bedrock has no list of its experiment toggles anywhere: not in `server.properties`, the Script API, the BDS files, the
docs or the protocol (start game and the resource pack stack only echo the toggles a world has *on*). `update` therefore
discovers them for every build, so captures include experimental content without a hand-maintained list:

1. **Candidates** (`ExperimentDiscovery`): every identifier-like string of the server binary (memory mapped), plus the
   suffixes after `_` of each (the Windows linker merges `y_2026_drop_4` into the tail of
   `behavior_packs/experimental_y_2026_drop_4`), plus the ASCII of x86-64 `mov r64, imm64` instructions and joins of
   neighbouring ones (MSVC builds `gametest` from an immediate), plus the `experimental_*` pack folders, `DEFAULT_EXPERIMENTS`,
   `KNOWN_RETIRED` and every id of the committed records. About 50 000 on Windows, 86 000 on Linux (where every id is a
   plain string).
2. **Oracle**: all candidates go into the `experiments` compound of a fresh world's `level.dat`. BDS reads it, logs
   `Experiment(s) active: ucft, gtst, ...` (one four letter code per toggle it knows) and writes the file back with only
   the keys it knows; unknown keys are dropped silently, nothing is rejected or logged. The keys left are the toggles of
   the build; the count must match the log line.
3. **Current vs retired**: a capture with all of them on. Start game streams only toggles that still do something
   (current); the others are retired (kept in `level.dat`, logged active, never streamed, no content). The resource pack
   stack must list the same ones, and the binary has one `createWorldScreen.experimental<Name>` UI string per current
   toggle: a different count is a DIFF (a current toggle the candidates missed; pass it with `--experiments`). The UI
   keys give the English names (`resource_packs/vanilla/texts/en_US.lang`), which map the creator docs' experiment names
   (`codegen/model/components.json`) to ids.
4. **Applied and attributed**: a capture and a Script API dump with each current toggle alone (start game must report
   exactly that toggle, which also yields its log code), compared with the vanilla ones without any; plus all together, to
   find content only several toggles add. `--experiment-attribution all` skips the single toggle runs, `none` both
   (automatic above 12 current toggles).
5. **Use**: the add-on world gets the current toggles (start game must report exactly them). The record
   `generator/experiments/<bds version>.json` (toggles, status, code, UI key, name, content per toggle) is committed, and
   the bundled version gets **experiment marks** (`VanillaData#experiments`, bundle key `experiments`, only written when
   present): per toggle the items and blocks it adds or turns on and its creative stacks. The bundle itself stays a
   capture without experiments. `check` uses the marks: content an older version has only behind a toggle but the newest
   has without one needs a `force` fallback (or an `unmapped` entry) there, and a `force` fallback for content that is
   not gated is reported as unused.

`--experiments a,b` still overrides discovery (no oracle; the toggles are verified against start game).

BDS 1.26.63.1 (Windows, 49 563 candidates): 23 toggles kept by the oracle on the first run, 31 with `KNOWN_RETIRED`
(the candidates of the Linux binary, 85 653, contain all 31 without help; the Linux server itself was not run here).
**7 current**: `experimental_creator_cameras` (exca, "Experimental Creator
Camera Features"), `gametest` (gtst, "Beta APIs"), `poi` (epoi, "Points of Interest (POI)"), `upcoming_creator_features`
(ucft), `villager_trades_rebalance` (vtra), `voxel_shapes` (vavs, "Experimental Voxel Shape Features"),
`y_2026_drop_4` (26d4, "Drop 4 of 2026"). **24 retired**, among them `data_driven_biomes`, `experimental_molang_features`,
`caves_and_cliffs`, `y_2025_drop_1..3`, `y_2026_drop_1..3`. The old hand-written list had `experimental_vanilla_shapes`,
which is no toggle (BDS drops it; the toggle is `voxel_shapes`), and lacked `poi` and `villager_trades_rebalance`.
Content: only `y_2026_drop_4` changes items, blocks or the creative inventory: it adds `minecraft:ice_ball`, makes
`minecraft:icicle` and `minecraft:ice_crystal` data driven (version 2 -> 1, component based), adds those three and
`minecraft:frostbite_spawn_egg` to the Script API item types and 12 creative stacks (the four items, arrows 48/49 and
potions 47/48 of every kind). Its blocks (`minecraft:icicle`, `minecraft:ice_crystal`) are registered without the toggle
too. All toggles together add nothing more. The step takes 90 to 125 s (18 server starts: create, discover, all,
its dump, then a capture and a dump per current toggle) of a 3 minute run (Windows, cached zip).

**Previews** never replace release data. Without `--import-preview` nothing of a preview is written. With it, the version
is imported and marked in `versions.txt` (`26.70 2250 preview`); the library skips such versions unless the system
property `paletter.previews` is `true`, and `check` ignores them.

**Still manual**, each listed in the report's TODO section:

- Nothing is required for new content: blocks and items an older bundled version lacks fall back to `minecraft:unknown`.
  The report lists them per version (INFO) as candidates for a curated fallback in `generator/fallbacks/<old version>.json`
  (a target; `null` just records that unknown is intended). Re-import the older versions after adding some; `check`
  verifies the targets.
- The state order *within* blocks generated from the Script API (only creative stacks verify it; other blocks' ids do
  not depend on it). BDS cannot be asked for it; `Update#keyOrder` infers it from the previous release (existing states
  keep varying fastest, new ones follow in the order other blocks use, directions north, east, south, west, up, down),
  which reproduces 126 of the 127 blocks generated in 26.40 -> 26.50 -> 26.60 (not `minecraft:trip_wire`). Check
  generated blocks against CloudburstMC/Data with `verify` once it has the version.
- Network data that differs for an already bundled protocol (a hotfix that changes content): re-import deliberately.
- Wire format changes: review the regenerated component types and `codegen/overrides.json`.
- A new protocol needs a codec in the Cloudburst protocol library (`codec.vNNNN.Bedrock_vNNNN` is picked up by name);
  without it the capture is decoded with the newest codec and the check is flagged.
- Transports: 1.26.63.1 still accepts RakNet with `transport=raknet` (logging that NetherNet is the only supported
  transport); the 1.26.70 previews only speak NetherNet. The capture client uses RakNet when the server answers a RakNet
  ping and NetherNet otherwise (CloudburstMC/Network's `netty-transport-nethernet` with `libdatachannel-java`): HTTP
  signaling on `server-port`, the protocol from NetherNet LAN discovery sent to `127.0.0.1:7551` (needs
  `enable-lan-visibility=true`), and a self signed offer identity using the login chain's key, since BDS refuses
  unsigned offers (`MultiplayerDisabled`) and chains not bound to the transport identity (`NOT_AUTHENTICATED`). No Xbox
  sign in is involved with `online-mode=false`.

CI (`.github/workflows/bds-data.yml`) runs `update --verify-only` daily for both channels on Linux BDS, opens or comments on
an issue with the report when something differs, and for the release channel opens a pull request with the updated data.

The lower level commands still exist:
```
./gradlew :generator:run --args="bds-run --download /tmp/bds --log /tmp/bds/bds.log"       # Script API dump only
./gradlew :generator:run --args="import --source DUMP_DIR --name 26.70 --protocol 2250 --bds-dump /tmp/bds/bds.log"
./gradlew :generator:run --args="bds-compare --bds-dump /tmp/bds/bds.log --name 26.70"
./gradlew :generator:run --args="check"
./gradlew :generator:run --args="verify --source DIR --name 26.60"                          # see below
./gradlew :capture:run --args="capture --out DIR --bds-version 1.26.63.1"                    # see capture/WIRE-NOTES.md
```
`import --source` also reads Geyser's resource layout (`block_palette.26_70.nbt`, ...), i.e. `core/src/main/resources/bedrock`.

The current bundle was produced with:
```
G=<Geyser>/core/src/main/resources/bedrock
bds-run --download DIR --version 1.26.45.1 --log bds-1.26.45.1.log    (and 1.26.51.1, 1.26.63.1)
import --source $G --name 26.40 --protocol 2168 --bds-dump bds-1.26.45.1.log
import --source $G --name 26.50 --protocol 2193 --bds-dump bds-1.26.51.1.log
import --source $G --name 26.60 --protocol 2225 --bds-dump bds-1.26.63.1.log
```
and is verified by `update --channel release --verify-only` against BDS 1.26.63.1: the capture reproduces 26.60, item
tags and block defaults match. (Block defaults are now stored with the palette's state key order, so the Script API's
varying order no longer changes the file.)

### Verifying against other sources

```
./gradlew :generator:run --args="verify --source DIR --name 26.60 [--bundled FILE] [--report FILE] [--max 25]"
```
reads a directory in the CloudburstMC/Data layout (`block_palette.nbt`, `runtime_item_states.json`,
`item_components.nbt`, `creative_items.json`, optional `data_driven_blocks.nbt`; Geyser's `block_palette.26_60.nbt`
names work too) in memory, with the importer's readers, and diffs it against the bundled version (or any Paletter data
file with `--bundled`). It writes nothing but the report (markdown, also on stdout) and exits with 1 on any difference.
Sections, each with counts and the first `--max` differences:

| Section | Compared |
|---|---|
| Block states | blocks and states only on one side, duplicate states |
| Block order (FNV-1 64 name sort) | order of the blocks, whether each side is in name hash order, blocks whose states are not contiguous |
| State order within blocks | blocks with the same states in another order (the sequential runtime ids within the block) |
| Hashed block network ids | FNV-1a 32 of every state against the source's `network_id`, FNV-1 64 against `name_hash`, collisions |
| Items | items only on one side, runtime id, version, componentBased, duplicate ids |
| Item components | the NBT of every item, path by path, including tag types |
| Data driven blocks | blocks only on one side, property NBT, order (skipped when the source has no file) |
| Creative layout | per category: entries only on one side and moved entries (groups, icons, stacks with damage, count, block state, NBT) |

Not compared, since the bundled format does not keep them: the order of `runtime_item_states` (the importer sorts items
by identifier), the per state `version` tag (listed as info), item tags, block defaults and fallbacks (not in the layout),
how the creative inventory is split into wire groups across categories, and the key order inside a state compound.

Results (November 2026):

- Geyser's resources (`core/src/main/resources/bedrock`, `*.26_40/26_50/26_60`, the import source) and
  CloudburstMC/Data at `040a6cc` (26.40), `659ce1e` (26.50) and `0021592` (26.60): identical to the bundled 26.40,
  26.50 and 26.60, including every `network_id` and `name_hash`. Geyser's files are byte copies of those Data commits,
  so this is one independent source, not two. Data has no `data_driven_blocks.nbt` for 26.40, and its 26.60 file is
  still the one of 26.50 (unchanged since `e5c8459`); it matches, so vanilla data driven blocks did not change.
- 26.50 -> 26.60 regenerated by the pipeline (26.60 hidden, the 1.26.51.1 Script API dump as baseline, `update
  --version 1.26.63.1 --verify-only`): the imported 26.60 is byte identical to the committed one, and `verify` finds it
  identical to Geyser's and Data's 26.60. Getting there fixed three generator bugs: the state order within generated
  blocks (see `Update#keyOrder`; fire, redstone wire, chorus plant and icicle were enumerated in the wrong order),
  generated states not having sorted keys like BDS's palette, and imports depending on the source for item order and
  empty item components.

## Status and known gaps

- Block palettes, items, item components and the creative inventory are still the network dumps Geyser shipped; see
  "What still needs a network capture".
- Item tags now come from BDS. Compared with Geyser's `item_tags.26_30.json` (used for every version before), all three
  versions add `minecraft:mushrooms_for_stew` and no longer have `minecraft:observer` and `minecraft:redstone_lamp` in
  `minecraft:sulfur_cube_archetype_slow_bouncy`; 26.50 and 26.60 also add the poplar wood set to the wood tags (boats,
  door, signs, logs, planks, slab) and wool slabs and stairs to `minecraft:vibration_damper`.
- Block tags and block to item are dumped but not bundled.
- `ItemComponents` and `BlockComponents` cover every component Geyser sends today and the common add-on ones; anything
  else goes through the `@Experimental` raw escape hatch until it gets a semantic method.
- Not covered: biome definitions, entity identifiers and other data Geyser ships that is not about items or blocks.
- The BDS addon needs `@minecraft/server` 2.1.0; tested with BDS 1.26.45.1, 1.26.51.1, 1.26.63.1 and the
  1.26.70.23 preview.
- The 1.26.70 previews only accept NetherNet connections; they are captured over NetherNet (see "Updating data"). Their
  protocol (2250) has no codec in the protocol library yet, so the captures are decoded with the 2225 codec.
