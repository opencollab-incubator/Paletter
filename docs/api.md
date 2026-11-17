# Using Paletter

This page goes deeper than the [README](../README.md). Method-level details are in the
[Javadoc](https://opencollab-incubator.github.io/Paletter/latest/).

## What is API

The API is the packages exported by
[`module-info.java`](../paletter/src/module/java/module-info.java): `org.geysermc.paletter` and its `annotation`,
`behaviour`, `block`, `block.component`, `creative`, `data`, `item`, `item.component`, `property`, `vanilla` and
`vanilla.state` packages. Cloudburst types that appear in signatures (`NbtMap`, `ItemData`, `StartGamePacket`, ...) are
part of it too.

Not API:

- anything under `org.geysermc.paletter.internal`;
- public members marked `@Internal` (plumbing that has to be public, such as the `BlockValues` constructor);
- the bundled data format and the `tools` module.

`@Experimental` marks API that follows a Bedrock experiment, such as components Bedrock gates behind a toggle.
It also marks vanilla item overrides (`PaletteLayer.Builder#overrideItem`, `ItemOverride`): BDS replaced the components of
an overridden data driven item (apple), accepted the hard coded feather at format 1.10 as a legacy item and rejected the
stick at 1.26.60. The client's behaviour for Paletter's overrides is unverified on a client (see
[wire-notes](wire-notes.md#overriding-vanilla-items)). `replaceComponents` has BDS's shape, `modifyComponents` merges.

Paletter is at 1.0.0-SNAPSHOT, so any release may change or remove API. The build keeps API changes visible:

- `:paletter:apiCheck` compares the compiled API with the committed dump
  [`paletter/api/paletter.api`](../paletter/api/paletter.api). After an intended change, run `:paletter:apiDump` and
  commit the file.
- `:paletter:apiCompat` runs japicmp against the release named in `paletter/api/released.version`. The file does not
  exist yet, so the check is skipped.
- `ApiBoundaryTest` checks that no exported signature uses an internal type and that every exported type has `@since`.

The jar declares `Automatic-Module-Name: org.geysermc.paletter`. `module-info.java` is compiled to check the exports but
is not packaged, because Cloudburst's dependencies split packages across jars.

## Keys and lookups

Keys (`BedrockBlockType`, `BedrockBlockState`, `BedrockItemType`, `BedrockItemVariant`) are identities. `equals` always
works. `==` works too for vanilla keys, which are singletons, and for registered custom content: a `CustomItem` or
`CustomBlock` creates its types, their base item variant and its block states once and owns them, and a palette it is
registered in hands back exactly those (`decode`, `block(NbtMap)`, `RegisteredItem#key`, `Palette#blockState`,
...). Other variants of a custom item (`layer.variant(ruby.type().variant(5))`) are equal to the one the layer was
given, not identical: compare them with `equals`. A key for an identifier Paletter does
not know is a plain value, equal by identifier and not interned, so memory is bounded by registered content. Equal custom
items or blocks in different layers each own their keys, which are equal but not identical. Registered entries (`RegisteredItem`, `RegisteredBlockState`) are compared by identity, like
Cloudburst definitions: there is one instance per entry per palette.

Methods named `blockState`, `itemVariant` or `...Of` return keys; methods named `...Definition` return what is sent.

| Lookup | Returns |
|---|---|
| `Palette#blockState(NbtMap, Lookup)`, `Palette#itemVariant(identifier, aux, Lookup)`, `Palette#itemType(identifier, Lookup)` | The key (or item type) if the `Lookup` accepts the content in this palette's version, else null. |
| `Palette#runtimeId(BedrockBlockState)` | The runtime id (the state hash). Throws if the palette lacks the state; `runtimeId(state, orElse)` returns `orElse` instead. |
| `Palette#blockDefinition(BedrockBlockState)` | The `RegisteredBlockState` this version sends, after adapting and fallbacks. Throws if the palette lacks the state. Only a custom block of a layer the palette does not have can be lacking: with the bundled data, every version has each vanilla state. |
| `Palette#blockDefinition(BedrockBlockState, RegisteredBlockState)` | Like `blockDefinition(state)`, but returns `orElse` (may be null) where that throws; as `runtimeId(state, orElse)` does for the runtime id. |
| `Palette#resolve(BedrockBlockState)` | A `ResolvedBlock`: the `definition()` sent (as `blockDefinition(state)`), `fallback()` (whether a `BlockFallback` or the default block was used; adapting state values to the version is not one) and `behaviour()` (`BlockValues`, see Behaviour). Throws like `blockDefinition(state)`. Makes a resolution per call. |
| `Palette#resolve(BedrockBlockState, ResolvedBlock)` | Like `resolve(state)`, but returns `orElse` (may be null) where that throws. |
| `Palette#resolveBlock(int)` | The `ResolvedBlock` of a block this palette sends, by runtime id, or null; never a fallback. |
| `Palette#itemDefinition(BedrockItemType)` | The `RegisteredItem`, or null if the version lacks it. Fallbacks are not applied. |
| `Palette#itemDefinition(String)` | The `RegisteredItem` for an identifier, or null. `itemDefinitions()` lists all items in runtime id order, `itemTags()` the item tags (tag to identifiers). |
| `Palette#blockState(BlockDefinition)` | The key of a block this palette sends, looked up by runtime id: `blockDefinition(blockState(d))` is `d`. A definition of another palette gives the key of the block with its runtime id here, or null if there is none. |
| `Palette#blockState(int)` | The key of the block with a runtime id (the hash), or null if this palette has no such block. For ids a server stores itself, e.g. chunk palettes; ids decoded from packets already come as definitions. |
| `Palette#blockDefinition(int)` | The `RegisteredBlockState` with a runtime id, or null. |
| `Palette#itemDefinition(int)` | The `RegisteredItem` with an item runtime id (negative for the plain item of a custom block), or null. |
| `Palette#resolve(ItemKey)` | What `encode` would send, without building an `ItemData`. `behaviour()` gives the `ItemValues` of the item sent. `variant()` is the variant this palette sends for the requested one: the requested variant itself unless `fallback()`. |
| `Palette#blockOf(ItemKey)` | The key of the block an item is sent with, or null. `blockDefinition(blockOf(v))` is what is sent. |
| `Palette#encode(BedrockItemStack)` | The `ItemData` for the client, with fallbacks applied. |
| `Palette#decode(ItemData)` | The stack a client sent, or null if unknown. |
| `Palette#explain(...)` | A readable explanation of how a block state or item resolves. |

Lookups are on `Palette`, because the palette is the version. `Palette#blockState(NbtMap, Lookup)`, `itemVariant(identifier, aux, Lookup)`,
`itemType(identifier, Lookup)` and `blockType(identifier, Lookup)` take the `Lookup` explicitly: `Lookup.EXACT` accepts only
what exists as written in the palette's own version, `Lookup.RENAMED` also content of an older bundled version, mapped
forward (renamed identifiers following `renames.txt`, changed state keys); only content that is unchanged is checked
against the palette's version. Each returns null if the lookup does not accept the content, and
`explainUnknown(..., Lookup)` says why. Content of the palette's layers is always accepted.

A palette sees exactly what it has: a lookup returns null for content newer than the palette's version, content removed
with `VanillaContent.remove`, and content behind an experiment the palette does not enable. To get keys that resolve through
fallbacks on every version, look them up on the newest palette, with the experiments you need (`allExperiments()` enables
all), or use the constants (`BedrockBlocks`, `BedrockItems`). There is no lookup without a `Lookup`: a mapping written
against 26.50 keeps working when 26.60 becomes the newest version by using `Lookup.RENAMED` on the newest palette.

Paletter decides which block an item carries (`Palette#blockOf`, a key). A layer override wins, then the block to item relation
captured from the Script API, then the block states in the creative inventory.

Vanilla items keep the runtime ids of the bundled data in every palette: custom items and data driven block items get
sequential ids after them. A custom block's plain item (`BlockItem.SIMPLE`) has the runtime id `255 - block_id`, as in
BDS: negative, and it changes when a later layer adds blocks, because BDS numbers custom blocks by identifier in
descending order. Read ids from the palette, never keep them across layers.

## Vanilla constants

`org.geysermc.paletter.vanilla` has generated constants for the newest bundled release: `BedrockBlocks`, `BedrockStates`,
`BedrockItems` and `BedrockItemVariants`, plus enums for string states in `vanilla.state`. They are objects, not `int` constants, and load
nothing until used.

```java
BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.state()
    .with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH)
    .with(BedrockStates.OPEN_BIT, true)
    .build();
CardinalDirection facing = door.get(BedrockStates.CARDINAL_DIRECTION);
int runtimeId = palette.runtimeId(door);
ItemData potion = palette.encode(BedrockItemStack.of(BedrockItems.POTION.variant(5)));
```

States are interned per block, so `with()` does not allocate. Byte states with values 0 and 1 are `BoolState`, integer
states `IntState` and string states `EnumState`. Custom block properties can use the same state constants when they have
the same name.

When Bedrock removes content, its constant stays, deprecated, and resolves to `minecraft:unknown` (`isAvailable()` is
false). A renamed identifier keeps its old constant as a deprecated alias. `@since` on a constant is the Paletter version that
added it; the line `First in Bedrock` names the first Bedrock release that has the content without an experiment. `@Education` marks Education Edition content.

An `ItemKey` is a `BedrockItemType` or a `BedrockItemVariant`, and the methods that take a variant (`Palette#resolve`,
`blockOf`, `BedrockItemStack.of`, `PaletteLayer.Builder#variant`) take one: a type stands for its base variant, so
`palette.resolve(BedrockItems.APPLE)` works. `type.variant()` is the base variant and `type.variant(aux)` the variant of
an aux, never null (an aux the data does not name gives a plain variant without a canonical id). A key is a type and an
aux, so creating or using one loads no bundled data, and a key made before any palette resolves exactly like one made
after. The base variant and the named constants of `BedrockItemVariants` are the same object whenever they are asked for,
so `==` holds for them; other auxes are new variants, compare those with `equals`. An `ItemKey` always means exactly that
variant, and a type means aux 0, in every method that takes one (`ItemFallback.of`, `ItemMatcher.of`,
`CreativePlacement.after` too); where a type means every aux, the name says so (`ItemMatcher.anyVariantOf`,
`CreativePlacement.afterAnyVariantOf`). Keys for an identifier are made with `Palette#itemType` and
`Palette#itemVariant`, or are the type of a `CustomItem`.

`BedrockItemVariants` names the aux variants of potions, splash and lingering potions, tipped arrows, beds and banners.
The names are BDS's own spellings (`POTION_LONG_NIGHTVISION`, `BED_SILVER`), taken from the Script API when the data is
captured. A constant is the very variant of `type.variant(aux)`, and referencing it loads nothing. Variants of other items
(suspicious stew, goat horns, ...) have no names.

```java
ItemData potion = palette.encode(BedrockItemStack.of(BedrockItemVariants.POTION_LONG_NIGHTVISION));
boolean same = BedrockItemVariants.BED_SILVER == BedrockItems.BED.variant(8); // true
```

## Components

Custom items and blocks use Bedrock's add-on component model as generated Java: one record per component
(`minecraft:durability` is `Durability`), nested records for objects, enums for documented enums, and builders that apply
documented defaults and check the ranges BDS enforces.

```java
CustomBlock lamp = CustomBlock.builder("example:lamp")
    .property(BlockProperty.bool("example:lit"))
    .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
    .component(Geometry.of("minecraft:geometry.full_block"))
    .component(MaterialInstances.builder()
        .all(MaterialInstances.Instance.builder().texture("example_lamp").build()).build())
    .component(CollisionBox.builder().boxes(CollisionBox.Entry.builder().origin(-8, 0, -8).size(16, 8, 16).build()).build())
    .permutation("q.block_state('example:lit')", LightEmission.of(10))
    .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
    .build();
```

Field names are the add-on JSON keys in camelCase. Wherever the model takes a block or item name, the vanilla constants
work too. The Javadoc comes from the creator documentation, with these annotations:

- `@BedrockFormat` names the minimum `format_version` of the add-on JSON. It is informational: the wire has no format
  version, and Paletter writes the shape of each palette's protocol.
- `@ServerOnly` marks components BDS accepts but never sends to clients. Their encoders write nothing.
- `@Deprecated` with `@ReplacedBy` marks components the docs deprecate or BDS rejects.

When a component field cannot be sent in a palette's protocol, it is left out and `Palette#report()` gets a warning.
Compiling a palette never fails because of the Bedrock version.

`MiningSpeed`, `FrameCount`, `RenderOffsets` and `OnPlayerPlacing` are hand-written, not generated. They are wire fields
Geyser needs that have no add-on JSON form, so there is no BDS capture to verify them against. `RenderOffsets` and
`OnPlayerPlacing` are retired from the add-on format; `MiningSpeed` and `FrameCount` are `@Experimental`.

## The creative inventory

`CreativeLayout` holds, per category, an ordered list of `CreativeGroup`s and loose `CreativeItem`s. Items are stacks, not
definitions, so one layout serves every version. Layers edit it relative to existing content:

```java
PaletteLayer.builder("server").creative(editor -> {
    editor.insert(stack).after(ItemMatcher.id("minecraft:hopper_minecart"));
    editor.move(ItemMatcher.namespace("example")).loose(CreativeCategory.ITEMS);
    editor.remove(ItemMatcher.of(BedrockItems.EMPTY_MAP.variant(2)));
});
```

Custom items and blocks declare a `CreativePlacement`: after an anchor, in a named group, or loose at the end of a
category. Without an anchor, `CreativeOrdering` (set on `Paletter.Builder`) decides the order. The default, `BDS`, puts
content exactly where BDS puts add-on content; [wire-notes.md](wire-notes.md#creative-inventory-order) has the rule.
`CreativeContent` turns the layout into a valid packet: categories in client order, items the version lacks dropped,
empty groups dropped (they crash the client), and network ids numbered from 1.

## Fallbacks and experiments

Content an older version lacks resolves to, in order: a fallback for that version, a fallback for every version, and
then the default, `minecraft:unknown` for both blocks and items. `Fallbacks.NONE` turns the default off (air and missing
items).

```java
Fallbacks fallbacks = Fallbacks.builder()
    .block(BedrockBlocks.ICICLE, BedrockBlocks.STONE)
    .item(version, BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.BIRCH_BOAT))
    .block(version, BedrockBlocks.POPLAR_LOG, BlockFallback.of(BedrockBlocks.BIRCH_LOG).forced())
    .build();
```

An item fallback is an exact variant: `ItemFallback.of(BedrockItems.BIRCH_BOAT)` sends the base variant of that type, and
`ItemFallback.of(BedrockItems.POTION.variant(5))` that variant, whichever variant it replaces.

`.forced()` replaces content even in versions that have it. Paletter ships no fallbacks: which replacement is right is
your decision. `./gradlew :tools:run --args="check-data"` lists what each older version lacks.

Content that a Bedrock version only has behind an experiment toggle is missing by default.
`Paletter.builder().vanilla(v -> v.experiments("y_2026_drop_3"))` enables it; `vanilla(v -> v.remove(...))` hides vanilla
items. `vanilla(v -> v.allExperiments())` enables every toggle the version gates content with, except the voxel
shape experiment; the palette lists them in `Palette#experiments()`.

## Previews

Preview versions of Bedrock can be bundled next to releases. They are marked `preview` in `versions.txt` and skipped
unless the system property `paletter.previews` is `true`. Generated API that only a bundled preview has (constants,
states, enum values, components and fields) sits with the rest and is marked `@SincePreview("26.70")`; without previews
enabled those constants resolve to `minecraft:unknown`. The mark goes away when the release has it.

## Behaviour

Behaviour belongs to a palette, not to a definition: `RegisteredBlockState` and `RegisteredItem` are the same in every
version, so they have no `behaviour()`. It is reached through what a palette resolved: `Palette#resolve(BedrockBlockState)`
gives a `ResolvedBlock` and `Palette#resolve(ItemKey)` a `ResolvedItem`, each with `behaviour()`. It returns
values BDS uses but never sends to clients: friction, destroy time, collision and outline shapes, light, max stack size,
attack damage and more.

```java
float ice = palette.resolve(BedrockBlocks.BLUE_ICE.defaultState()).behaviour().get(BlockBehaviour.FRICTION);
BlockShape stairs = palette.resolve(state).behaviour().get(BlockBehaviour.COLLISION_SHAPE);
int eating = palette.resolve(BedrockItems.APPLE).behaviour().get(ItemBehaviour.USE_DURATION);
String why = palette.resolve(BedrockBlocks.BLUE_ICE.defaultState()).behaviour().explain(BlockBehaviour.FRICTION);

ResolvedBlock block = palette.resolve(state);
block.definition();   // the block sent, as blockDefinition(state)
block.fallback();     // true if a fallback block was used for it
block.behaviour();    // the values of the block that is sent
```

- Keys are the constants of `BlockBehaviour` and `ItemBehaviour`. Number and boolean keys return primitives. A
  Handles and a `ResolvedBlock` are made on each call: do not compare
  them with `==`, use `equals`. A `ResolvedItem` is shared and compared by its parts.
- A resolution carries the values of the block or item that is sent. A fallback state has the values of its
  replacement.
- `resolve(BedrockBlockState)` throws `IllegalArgumentException` where `blockDefinition(state)` throws.
  `resolve(state, orElse)` returns `orElse` (may be null) there, as `blockDefinition(state, orElse)` does.
  `resolveBlock(int runtimeId)` takes the runtime id of a block this palette sends (from a packet or a chunk palette) and
  returns null if there is none; it is never a fallback. `resolve(ItemKey)` is null for an item that does not
  exist and has no fallback.
- `get` throws `IllegalStateException` when no value is known; `getOrElse(key, fallback)` returns the fallback instead.
- Values belong to the palette, not the state: a newer capture can change a value for one version only. An extended
  palette shares the values of its base for what they have in common, and the handles: one item has one handle in a
  palette, whichever variant resolved to it, and an extension has the base's handle for the items it did not change.
- `explain` returns one line for debugging: the value, its source and capture, and whether it was set by the user,
  inherited from an older version, post-processed by its source, or disputed. It is not a format to parse.
- Custom blocks and items answer from their components (`Friction`, `LightEmission`, `CollisionBox`, `MaxStackSize`,
  ...). A key without its component gets the BDS default measured by the behaviour probe.
- Shapes are those of one state. Shapes that depend on neighbours or position, such as plant offsets, are up to you.

The sources, their ranking, disputed values and the custom block defaults are listed in the generated
[data/behaviour/REPORT.md](../data/behaviour/REPORT.md).

## Voxel shapes

Voxel shapes are the culling shapes Bedrock uses to hide faces of neighbouring blocks. They are not collision shapes;
collision and selection are the `COLLISION_SHAPE` and `OUTLINE_SHAPE` behaviour keys. A palette has the vanilla shapes
BDS sends for its version; layers add custom ones, which custom blocks name in `minecraft:geometry`:

```java
PaletteLayer.builder("content")
    .voxelShape("example:post", VoxelShape.ofBoxes("example:post", 6 / 16f, 0, 6 / 16f, 10 / 16f, 1, 10 / 16f))
    .block(CustomBlock.builder("example:post")
        .component(Geometry.builder().identifier("geometry.post").cullingShape("example:post").build())
        .build());
```

`palette.voxelShapesPacket()` is byte for byte the packet BDS sends for the same content, including the shapes Bedrock
derives for custom blocks. BDS sends it to every client before start game, also without custom shapes, so send it
always. Every palette has it: a palette of a version whose data has no vanilla shapes fails to build with a
`PaletteException`. `VoxelShape.ofBoxes` rejects what BDS rejects. In 26.30 the vanilla shapes are behind the
`voxel_shapes` experiment. How BDS builds the registry is in [wire-notes.md](wire-notes.md#voxel-shapes).

## Errors and reports

Layers given to `Paletter.builder()` are startup configuration: a problem throws `PaletteException` on the first
`palette(...)` call, and `Paletter#warmUp()` compiles every version up front. `Palette#extend(layer)` throws a
`PaletteException` too, and changes nothing: the palette is untouched, nothing is cached, and the same palette can
still be extended by another layer. Duplicate identifiers (blocks, items, voxel shapes) and duplicate block runtime ids
are rejected, across layers and within one. `PaletteException#layer()` is the id of the layer and `#identifier()` the
content it is about (null when it is not about one). The message names both, and the version. `PaletteReport` also
lists what was adapted, replaced or dropped per version.
