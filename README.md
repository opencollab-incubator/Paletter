# Paletter

Paletter builds the item and block palettes a Minecraft: Bedrock Edition client expects. It bundles the vanilla data of
each supported Bedrock version, adds your custom items and blocks on top, and gives you the Cloudburst protocol objects
to send: the definition registries for the codec, the block properties for the start game packet, the item component
packet, the creative content packet and the voxel shapes packet. You describe content once, as version independent
keys; each palette turns those keys into what one client version needs.

It is written for projects that talk to Bedrock clients directly:

- [Geyser](https://github.com/GeyserMC/Geyser), which translates Java Edition content to Bedrock;
- anticheats such as Boar, which need the same block and item behaviour values as the server;
- Bedrock-only servers and proxies.

## Quick start

```java
// Content, described once for every client version
PaletteLayer content = PaletteLayer.builder("server")
    .item(CustomItem.builder("example:ruby")
        .component(Icon.of("ruby"))
        .component(DisplayName.builder().value("item.example.ruby.name").build())
        .component(MaxStackSize.of(16))
        .creative(CreativePlacement.group(CreativeCategory.ITEMS, "itemGroup.name.minerals"))
        .build())
    .voxelShape("example:post", VoxelShape.ofBoxes("example:post", 6 / 16f, 0, 6 / 16f, 10 / 16f, 1, 10 / 16f))
    .block(CustomBlock.builder("example:post")
        .component(Geometry.builder().identifier("geometry.post").cullingShape("example:post").build())
        .component(MaterialInstances.builder()
            .all(MaterialInstances.Instance.builder().texture("post").build()).build())
        .component(Friction.of(0.1f))
        .creative(CreativePlacement.loose(CreativeCategory.CONSTRUCTION))
        .build())
    .overrideItem(BedrockItems.CAKE, item -> item.modifyComponents(MaxStackSize.of(1)))
    .build();

// One Paletter per application: policy and global content
Paletter paletter = Paletter.builder()
    .fallbacks(Fallbacks.builder().item(BedrockItems.POPLAR_BOAT, BedrockItems.OAK_BOAT).build())
    .layer(content)
    .build();

// One palette per protocol version, compiled once and shared by every session on that version
Palette palette = paletter.palette(session.getCodec());
palette.applyTo(session.getPeer().getCodecHelper());   // item and block definitions
palette.applyTo(startGamePacket);                      // block properties, hashed block ids

session.sendPacket(palette.voxelShapesPacket());       // always, before start game
session.sendPacket(startGamePacket);
session.sendPacket(palette.itemComponentPacket());
session.sendPacket(palette.creativeContentPacket());

// Content written down elsewhere becomes a key: exactly as this palette's version has it, or also under renamed names
BedrockBlockState state = palette.blockState(nbt, Lookup.EXACT);   // null if absent
BedrockItemVariant variant = palette.itemVariant("minecraft:potion", 5, Lookup.RENAMED);

// Blocks and items are keys; the palette gives the ids for its version
int stone = palette.runtimeId(BedrockBlocks.STONE.defaultState());
ItemData apples = palette.encode(BedrockItemStack.of(BedrockItems.APPLE, 3));

// Behaviour values BDS uses but never sends to clients, of what the palette resolved
ResolvedBlock block = palette.resolve(BedrockBlocks.STONE.defaultState());
float friction = block.behaviour().get(BlockBehaviour.FRICTION);
BlockShape collision = block.behaviour().get(BlockBehaviour.COLLISION_SHAPE);
```

Runnable versions of this are in [`examples/`](examples) (`./gradlew :examples:run`): a Bedrock server, a translator
and an anticheat.

## Concepts

**Keys, registered entries and stacks.** A key names content and does not depend on a version: `BedrockBlockType`,
`BedrockBlockState`, `BedrockItemType` and `BedrockItemVariant` (a type plus an aux value; both are an `ItemKey`, a type standing for its base variant). Vanilla keys are generated
constants (`BedrockBlocks.STONE`, `BedrockItems.APPLE`); custom content gets its keys from `CustomBlock#type()` and
`CustomItem#type()`. A registered entry (`RegisteredItem`, `RegisteredBlockState`) is a key inside one palette, with its
runtime id and components; these implement Cloudburst's `ItemDefinition` and `BlockDefinition`. A stack
(`BedrockItemStack`) is a variant with a count, NBT and an optional block state. Cloudburst's `ItemData` only appears
where a packet needs it, through `Palette#encode` and `Palette#decode`.

**Paletter, palettes and layers.** `Paletter` holds the settings shared by every version: the data source, fallbacks,
which vanilla items are removed, which experiments are enabled, creative ordering and the global layers. `paletter.palette(...)`
compiles one immutable, thread safe `Palette` per data version and caches it. A `PaletteLayer` is content only (custom
items and blocks, overrides of vanilla items, creative edits, tags, voxel shapes) and compiles against every version.
`palette.extend(layer)` adds a short-lived layer, for example per session, on top of a shared palette; anything the
layer does not change stays the same object as in the base palette.

**Hashed block ids.** Paletter always uses hashed block network ids (`StartGamePacket#blockNetworkIdsHashed`). A block
state has the same id in every version, so chunk data encoded for one version can be reused for another.

**Mapping against the newest version.** Map your content against the newest bundled version only. Older palettes adapt
block states that changed between versions and apply your `Fallbacks` to content they lack. Paletter ships no fallbacks
of its own: without one, missing content becomes `minecraft:unknown`.

[docs/api.md](docs/api.md) covers all of this in more detail: lookups, components, the creative inventory, behaviour,
voxel shapes, fallbacks, experiments and previews.

## Where the data comes from

Every bundled file is generated by the tools in this repository from Bedrock Dedicated Server (BDS) captures, or from
third-party data checked against them, and says which command wrote it. Third-party data is used only where no BDS
capture exists yet, and it is labelled as such wherever it shows up. What is kept by hand are the decisions around the
data, each with the BDS evidence or reason next to it: the component model's API choices and errata
(`data/model/api.json`, `fixtures.json`), the hand transforms (`Transforms`), the behaviour key table and the
wire-only components listed under Status.

Where the data of each version comes from is in the generated reports, not repeated here:

- `data/bds/<build>/update-report.md`: what the update that imported a build captured, checked and changed. Versions
  imported before these reports existed descend from CloudburstMC/Data and Geyser's files, verified against BDS
  captures.
- [data/model/coverage.md](data/model/coverage.md): the component API, generated from Microsoft's creator
  documentation, against what BDS sends for every variant of every component.
- [data/behaviour/REPORT.md](data/behaviour/REPORT.md): which source decides each behaviour key in each version.
- `Palette#explain`, or `./gradlew :tools:run --args="inspect ..."`: one block state or item.

[docs/data-pipeline.md](docs/data-pipeline.md) explains how data is captured and generated, and how to update it for
a new Bedrock version.

## Status

Paletter is at 1.0.0-SNAPSHOT, not released yet. Until the first release the API can change without a deprecation period. The build
already tracks API changes (`paletter/api/paletter.api`), so every change is visible in review.

Bundled versions ([versions.txt](paletter/src/main/resources/paletter/versions.txt)): 26.30, 26.40, 26.50, 26.60 and
the 26.70 preview (served only with `-Dpaletter.previews=true`). Known gaps:

- Biome definitions, entity identifiers and other data that is not about items or blocks are out of scope.
- `MiningSpeed`, `FrameCount`, `RenderOffsets` and `OnPlayerPlacing` are hand-written wire-only components that Geyser
  needs. They have no add-on JSON form and no BDS capture to check them against.
- Some behaviour keys only have third-party sources so far; a native BDS capture is designed but not built
  ([docs/native-capture.md](docs/native-capture.md)).
- `renames.txt` is empty: no identifier renames between the bundled versions are known yet.

## Documentation

- [docs/api.md](docs/api.md): using the library, and what counts as API.
- [docs/architecture.md](docs/architecture.md): how the library is put together, for contributors.
- [docs/data-pipeline.md](docs/data-pipeline.md): the tools, BDS captures, code generation and CI.
- [docs/wire-notes.md](docs/wire-notes.md): what BDS sends and how it orders custom content.
- [docs/native-capture.md](docs/native-capture.md): design notes for capturing BDS internals.
- [docs/releasing.md](docs/releasing.md): repository settings and secrets the workflows need, and the release steps.
- Javadoc of `main`: https://opencollab-incubator.github.io/Paletter/latest/

## Building

```
./gradlew build                 # library, tools, tests, Javadoc, examples, and the generated-code and data checks
./gradlew :paletter:javadoc
```

Java 21. The library depends on Cloudburst's `bedrock-codec` and fastutil. The `tools` module is never published.

Attribution for the creator documentation the component API is derived from is in
[data/model/ATTRIBUTION.md](data/model/ATTRIBUTION.md).
