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

Paletter is at 0.1.0-SNAPSHOT, so any release may change or remove API. The build keeps API changes visible:

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
`CustomBlock` creates its types once and owns them, and a palette it is registered in hands back exactly those
(`decode`, `block(NbtMap)`, `RegisteredItem#key`, `RegisteredBlockState#key`, ...). A key for an identifier Paletter does
not know is a plain value, equal by identifier and not interned, so memory is bounded by registered content. Equal custom
items or blocks in different layers each own their keys, which are equal but not identical. Registered entries (`RegisteredItem`, `RegisteredBlockState`) are compared by identity, like
Cloudburst definitions: there is one instance per entry per palette.

| Lookup | Returns |
|---|---|
| `Paletter#block(NbtMap)`, `Paletter#item(identifier, aux)` | The key for a state or variant of any bundled version, or null. `explainUnknown` says why. |
| `Palette#runtimeId(BedrockBlockState)` | The runtime id (the state hash). Throws if the palette lacks the state; `runtimeIdOrDefault` does not. |
| `Palette#block(BedrockBlockState)` | The `RegisteredBlockState` this version sends, after adapting and fallbacks. `blockOrNull` returns null instead of throwing. |
| `Palette#item(BedrockItemType)` | The `RegisteredItem`, or null if the version lacks it. Fallbacks are not applied. |
| `Palette#resolve(BedrockItemVariant)` | What `encode` would send, without building an `ItemData`. |
| `Palette#encode(BedrockItemStack)` | The `ItemData` for the client, with fallbacks applied. |
| `Palette#decode(ItemData)` | The stack a client sent, or null if unknown. |
| `Palette#explain(...)` | A readable explanation of how a block state or item resolves. |

`Paletter#block` and `Paletter#item` accept content from any bundled version. Content the newest version lacks is mapped
forward: states are adapted and renamed identifiers follow `renames.txt`. A mapping written against 26.50 therefore keeps
working when 26.60 becomes the newest version. To match strictly, pass a `Lookup`: `Lookup.STRICT` accepts only what
exists as is in the newest data, `Lookup.of(version)` only what exists in that version, and `.withRenames()` also
accepts renamed identifiers.

Paletter decides which block an item carries (`Palette#blockOf`). A layer override wins, then the block to item relation
captured from the Script API, then the block states in the creative inventory.

## Vanilla constants

`org.geysermc.paletter.vanilla` has generated constants for the newest bundled release: `BedrockBlocks`, `BedrockStates`
and `BedrockItems`, plus enums for string states in `vanilla.state`. They are objects, not `int` constants, and load
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
false). A renamed identifier keeps its old constant as a deprecated alias. `@since` on a constant is the first Bedrock
release that has the content without an experiment. `@Education` marks Education Edition content.

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

`.forced()` replaces content even in versions that have it. Paletter ships no fallbacks: which replacement is right is
your decision. `./gradlew :tools:run --args="check-data"` lists what each older version lacks.

Content that a Bedrock version only has behind an experiment toggle is missing by default.
`Paletter.builder().vanilla(v -> v.experiments("y_2026_drop_3"))` enables it; `vanilla(v -> v.remove(...))` hides vanilla
items.

## Previews

Preview versions of Bedrock can be bundled next to releases. They are marked `preview` in `versions.txt` and skipped
unless the system property `paletter.previews` is `true`. Generated API that only a bundled preview has (constants,
states, enum values, components and fields) sits with the rest and is marked `@SincePreview("26.70")`; without previews
enabled those constants resolve to `minecraft:unknown`. The mark goes away when the release has it.

## Behaviour

`Palette#blockBehaviour` and `Palette#itemBehaviour` return values BDS uses but never sends to clients: friction, destroy
time, collision and outline shapes, light, max stack size, attack damage and more.

```java
float ice = palette.blockBehaviour(BedrockBlocks.BLUE_ICE).get(BlockBehaviour.FRICTION);
BlockShape stairs = palette.blockBehaviour(state).get(BlockBehaviour.COLLISION_SHAPE);
int eating = palette.itemBehaviour(BedrockItems.APPLE).get(ItemBehaviour.USE_DURATION);
String why = palette.blockBehaviour(BedrockBlocks.BLUE_ICE).explain(BlockBehaviour.FRICTION);
```

- Keys are the constants of `BlockBehaviour` and `ItemBehaviour`. Number and boolean keys return primitives; lookups by
  runtime id read an array and do not allocate.
- `blockBehaviour` takes a runtime id, a `BedrockBlockState` or a `BedrockBlockType` (its default state). Content that is
  not in the palette throws `IllegalArgumentException`.
- `get` throws `IllegalStateException` when no value is known; `getOrElse(key, fallback)` returns the fallback instead.
- Values belong to the palette, not the state: a newer capture can change a value for one version only. An extended
  palette shares the values of its base.
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
derives for custom blocks. `VoxelShape.ofBoxes` rejects what BDS rejects. In 26.30 the vanilla shapes are behind the
`voxel_shapes` experiment. How BDS builds the registry is in [wire-notes.md](wire-notes.md#voxel-shapes).

## Errors and reports

Layers given to `Paletter.builder()` are startup configuration: a problem throws `PaletteException` on the first
`palette(...)` call, and `Paletter#warmUp()` compiles every version up front. `Palette#extend(layer)` never throws for a
problem with the layer; it returns the base palette unchanged with an `ERROR` entry in `report()`. `PaletteReport` also
lists what was adapted, replaced or dropped per version.
