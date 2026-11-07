# Paletter

Bedrock item and block palettes, ready for the wire. Paletter bundles vanilla data for every supported version, compiles
it together with layers of custom content, and hands out Cloudburst protocol types: definition registries for the codec,
the item component packet, the creative content packet and block properties for start game.

A consumer such as Geyser keeps exactly one thing: a mapping from its own (Java) items and blocks to Bedrock variants of the
**newest** version. Everything else (runtime ids, palette ordering, older versions, the creative inventory, custom item and
block NBT) is Paletter's job.

```java
Paletter paletter = Paletter.bundled();

PaletteLayer geyser = PaletteLayer.builder("geyser")
    // One to one Java mapping; a second claim on the same Bedrock variant fails compilation
    .attach(ItemVariant.of("minecraft:hopper_minecart"), JAVA_ITEM, Items.HOPPER_MINECART)
    .item(CustomItem.builder("geysermc:furnace_minecart")
        .components(ItemComponents.builder().icon("minecart_furnace").displayName("item.minecartFurnace.name").build())
        .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minecart")
            .after(ItemMatcher.id("minecraft:hopper_minecart")))
        .attach(JAVA_ITEM, Items.FURNACE_MINECART)
        .build())
    .overrideItem("minecraft:cake", item -> item.components(c -> c.maxStackSize(1)))
    .overrideItem("minecraft:structure_block", ItemOverride::noBlock)
    .creative(editor -> editor.remove(ItemMatcher.id("minecraft:empty_map").and(ItemMatcher.damage(2))))
    .build();

Palette palette = paletter.palette(codec, geyser);       // once per client version, shared
Palette forSession = palette.extend(serverLayer);         // optional, per session

palette.applyTo(codecHelper);                             // item + block definition registries
palette.applyTo(startGamePacket);                         // block properties
session.sendPacket(palette.itemComponentPacket());
session.sendPacket(palette.creativeContentPacket());

Item javaItem = palette.items().attachment(itemDataFromClient, JAVA_ITEM);   // Bedrock -> Java
ResolvedItem bedrock = palette.items().resolve(ItemVariant.of("minecraft:poplar_boat")); // Java -> Bedrock, any version
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

**Layers.** A `PaletteLayer` is a patch: custom items and blocks, overrides of vanilla items, creative edits, fallbacks and
attachments. Layers do not know protocol versions; the same layer compiles against every version. Stack them to split
ownership, e.g. Geyser's own layer, then extensions, then a per-session layer. `Paletter#palette` caches every prefix of a
layer list; `Palette#extend` is meant for short lived layers. Definitions a layer does not touch are shared with the palette
it extends, so `==` between a session's definitions and the shared ones keeps working. A layer that adds custom blocks
does renumber block states, as the client sorts its palette by name hash.

**Attachments.** Layers attach typed values (`AttachmentKey<T>`) to item variants or block states, e.g. the Java item a
Bedrock item translates back to. Lookups go from the most to the least specific variant (damage and block state, then
damage, then block state, then the plain identifier), so a value on `minecraft:potion` is the default for all potions and a
value on `minecraft:potion@5` overrides it for one. Keys created with `AttachmentKey.create` fail compilation on duplicate
claims (`Conflict.ERROR`). That turns bugs like "hopper minecarts from the creative menu become furnace minecarts"
(GeyserMC/Geyser#6668) into a startup error that names both claims.

**One mapping for all versions.** Map against the newest version only. For older versions, `BlockPalette#resolve` adapts
states automatically (states added or removed between versions, e.g. stair corners) and applies bundled fallbacks for
blocks that do not exist (or are experimental) there; `ItemPalette#resolve` does the same for items. Values attached to
something that falls back land on the replacement as weak values: they never override or conflict with direct ones.

**Block items.** The item palette knows which block each block item is sent with (from the creative inventory, a layer
override, or the block of the same name), and ignores the meaningless block definitions clients send with other items.

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
