# Architecture

For people changing Paletter. Usage is in [api.md](api.md); data, tools and code generation are in
[data-pipeline.md](data-pipeline.md).

## Modules

- `paletter/`: the library, the only published module. Hand-written code is in `src/main/java`. Generated code (the
  component API, encoders, behaviour keys, vanilla constants and vanilla behaviour values) is in `src/generated/java`, in the same packages.
  Bundled data is in `src/main/resources/paletter`.
- `tools/`: everything else: the BDS pipeline, the headless capture client, the component model and the generators.
- `examples/`: runnable examples of the API.
- `data/`: inputs that are not code: BDS captures, the component model and the add-on packs run on BDS.

## Compiling a palette

`Paletter` holds the policy shared by every palette and the global layers. `Paletter#palette(...)` asks
`PaletteCompiler` for one `Palette` per data version and caches it. The compiler starts from vanilla data and runs one
`extend` per global layer. `Palette#extend(layer)` runs the same step for a session layer. Each stage that a layer
does not change is shared with the parent.

1. **Blocks**: vanilla states, custom blocks (`internal.component.BlockCompiler`), block fallbacks, network ids. Voxel
   shapes follow (`internal.shape.VoxelShapeCompiler`): vanilla ones from the generated `internal.vanilla.shapes`, then the layers' shapes,
   then the shapes Bedrock derives for custom blocks.
2. **Items**: vanilla definitions, custom items (`ItemCompiler`), overrides, item fallbacks, block items, tags.
3. **Creative**: the layout (vanilla order, then placements and edits) and the packet.
4. **Output**: the `Palette`, its packets and its `PaletteReport`.

Blocks come before items because block items need them.

## Ids

Consumers only see keys and runtime ids. Internally:

- **Canonical ids** are dense ints for the newest bundled version (positions in its block palette and item table),
  shared by every protocol and stored in a hidden field of the key. Custom content is appended after them.
- **Runtime ids** of blocks are state hashes: FNV-1a 32 of `{name, states}` (`BlockHashes#hash`). They are equal in every
  version, so palettes of different versions share one `RegisteredBlockState` per vanilla state.
- **Resolution** maps a canonical id to what an older version sends: exact, adapted, fallback or `minecraft:unknown`.
  **Forwarding** is the reverse: content of any bundled version maps to the newest canonical id, directly, through
  `renames.txt`, or adapted.
- Custom blocks are numbered by identifier, descending, whatever order the layers register them in. This stands in for
  BDS's file name order ([wire-notes.md](wire-notes.md#creative-inventory-order)).

No static map holds custom keys: a client can send any identifier, so nothing may grow per identifier. Registry caches
only hold canonical entries.

## Bundled data

`paletter/src/main/resources/paletter/` holds `versions.txt`, one gzipped NBT file per version (format in
`internal.data.PaletteDataCodec`), `properties.nbt` (component data stored once with the version range it applies to),
the generated `builds.txt` (the BDS builds each version was verified against, from `data/bds/builds.json`) and the
hand-written `renames.txt`. `PaletteDataSource#bundled()` reads them. Each version file holds the items, the creative
layout, item tags, the block to item relation and experiment marks. The block states and default states of the bundled
versions are generated classes (`internal.vanilla.blocks`, derived at load by `internal.vanilla.VanillaBlocks`; data-pipeline.md
"Block definitions"), as are the vanilla voxel shapes (`internal.vanilla.shapes`) and the behaviour values
(`internal.behaviour.vanilla`, "Captures and generated code"). A directory data source may still carry block states and
`voxel_shapes.nbt` of its own.

## Wire-only components

Wire fields without an add-on JSON source are hand-written final classes (private constructor, `of` or a builder,
accessors, `equals`/`hashCode`/`toString`, as the generated ones; docs/api.md "Evolution") implementing the sealed
`WireOnlyItemComponent` or `WireOnlyBlockComponent`, encoded by `internal.component.WireOnlyWire`. To add one: write the class, add it to the
`permits` clause, add a case to `WireOnlyWire` and a test to `WireOnlyComponentTest`.

## Recipes

**A new Bedrock version.** Run `./gradlew :tools:run --args="update --channel release"`, review the report and the
diff, then `./gradlew build`. New components show up as gaps in `data/model/coverage.md`. See
[data-pipeline.md](data-pipeline.md#updating-for-a-new-bds-build).

**Adding or fixing a component.**

1. Fix the model: hand corrections go in `data/model/fixtures.json` (errata) or `data/model/api.json` (Java shape).
2. Capture and infer: `spec-capture`, then `./gradlew :tools:inferMappings`.
3. Regenerate: `./gradlew :tools:generateComponentApi` and review the diff of `paletter/src/generated`; the generator tests
   assert on fragments and compile their synthetic output.
4. Only when no inferred rule can describe the wire shape: add a public static method to
   `internal.component.Transforms`, with the reason and evidence in its Javadoc, and name it in the tools'
   `HandTransforms`.

**Adding a layer feature.** Add the builder method, accessor, Javadoc and `@since` to `PaletteLayer` (or to
`Paletter.Builder` for a policy). Apply it in the matching stage of `PaletteCompiler`, record changes in `PaletteReport`,
and test it against every bundled version next to `LayerTest` and `CustomContentTest`. Run `:paletter:apiDump` and
commit `paletter/api/paletter.api`.
