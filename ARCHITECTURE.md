# Architecture

For someone about to change Paletter. Usage is in the [README](README.md), policy in [API.md](API.md), commands in
[tools/README.md](tools/README.md).

## The model

| Concept | Classes | Versioned |
|---|---|---|
| Key (identity) | `BedrockItemType`, `BedrockItemVariant` (type + aux), `BedrockBlockType`, `BedrockBlockState`. Vanilla keys are the generated constants, custom ones `X.of("namespace:id")` | never; renames and splits (`renames.txt`) map to the same or a new key |
| Registered | `RegisteredItem`, `RegisteredBlock`, `RegisteredBlockState`: a key inside one palette, with components, `Origin` and runtime id | per palette |
| Stack | `BedrockItemStack`: variant, count, NBT, optional block state | never |

A `Paletter` holds the policy shared by every palette (data source, versions, block network ids, `Fallbacks`, vanilla
content, creative ordering, reports) and the global layers. A `PaletteLayer` is content only (custom items and blocks,
overrides, creative edits, tags) and knows neither protocol nor policy. `Paletter#palette(protocol)` compiles one
immutable `Palette` per data version and caches it; `Palette#encode` / `#decode` convert stacks to and from Cloudburst
`ItemData`, which only appears at that boundary. `Palette#extend(layer)` is a session patch over the shared palette:
what the layer does not touch is the same instance as in the base, and custom blocks need `BlockNetworkIds.HASHED`.

## Compile stages

`PaletteCompiler` runs vanilla first, then one `extend` per global layer; an unchanged stage is shared with the parent.

1. **blocks**: vanilla states, custom blocks (`internal.component.BlockCompiler`), block fallbacks, network ids, canonical table.
2. **items**: vanilla definitions, custom items (`ItemCompiler`), overrides, item fallbacks, block items (from the bundled
   block to item relation), tags, canonical table.
3. **creative**: layout (vanilla order, then placements and edits), packet content.
4. **output**: the `Palette`, its packets and `PaletteReport` (what was adapted, replaced or dropped).

Blocks come before items (block items need them) and creative last.

## Ids

Consumers never see canonical ids; lookups return interned keys (`Paletter#block`, `#item`, `Palette#runtimeId(key)`).
Internally:

- **Canonical ids** are dense ints for the newest bundled version (position in its block palette / item table), the same
  for every protocol, held in a hidden field of the key. Custom content gets ids appended after them.
- **Runtime ids** are per palette: `SEQUENTIAL` uses palette positions, `HASHED` the state hash (equal in every version,
  so vanilla states are shared between palettes).
- **Resolution** turns a canonical id into what an older version sends: exact, adapted, fallback, or `minecraft:unknown`
  (`Palette#explain`, `inspect`). **Forwarding** is the reverse: a state or variant of any bundled version maps to the
  newest canonical id (exact, renamed through `renames.txt`, or adapted).
- Custom blocks are numbered by identifier, descending, whatever order layers register them in (the stand-in for BDS's
  file name order, see [data/bds/CREATIVE-ORDER.md](data/bds/CREATIVE-ORDER.md)).

## Data flow

```
BDS (download, run)  ->  tools: update / capture / spec-capture / import  ->  data/ + paletter/src/main/resources/paletter
creator docs         ->  tools: docs                                       ->  data/model/components.json
data/model/*         ->  tools: infer, components generate, constants generate  ->  paletter/src/generated/java
```

`data/bds/<bds version>/` keeps what each BDS build gave. `tools.update` and `tools.data.Importer` turn captures into
the bundled format, one `<version>.nbt` per Bedrock version plus `versions.txt` and `properties.nbt` (item components
and data driven blocks, stored once with their version range), read at runtime by `PaletteDataSource#bundled()`.
Paletter bundles no fallbacks: what an older version lacks is `minecraft:unknown` unless the consumer defines one.

## Generated vs hand-written

| Where | Kind |
|---|---|
| `paletter/src/generated/java` | Generated, never edited: component API, encoders, `ComponentIndex`, vanilla constants. `build` fails if stale (`checkComponentApi`, `checkConstants`) |
| `paletter/src/main/java` | Hand-written library; `internal/component/Transforms.java` holds the hand transforms the encoders call |
| `paletter/src/main/resources/paletter` | Generated data (`tools` import), except `renames.txt` |
| `tools/src/main/java` | Hand-written tooling, including the generators |
| `data/model/transforms.json`, `api.json`, `fixtures.json`, `data/addons/dump` | Hand-written inputs |
| `data/addons/capture`, `data/addons/creative`, `data/model/mappings.json`, `coverage.md` | Generated, never edit |

Wire-only components (fields with no add-on JSON source) are hand-written records under `WireOnlyItemComponent` /
`WireOnlyBlockComponent`, dispatched to `internal/component/WireOnlyWire`. To add one: the record (and the `permits`
clause), a case in `WireOnlyWire`, a test in `WireOnlyComponentTest`.

## Recipes

**New Bedrock version**: `./gradlew :tools:run --args="update --channel release"` downloads BDS, captures, imports the
data, regenerates the constants and prints a report (README, "Updating data"). Check the diff, then
`./gradlew --max-workers=1 build`. A new component shows up as a gap in `data/model/coverage.md`.

**Add or fix a component**:

1. Model: `components.json` comes from the creator docs; hand corrections go in `fixtures.json` and `api.json`.
2. Capture: `addon --kind spec`, `spec-capture`, `infer` ([tools/docs/model.md](tools/docs/model.md)).
3. Regenerate with `./gradlew :tools:generateComponentApi` (and `:tools:test -PupdateGolden=true` if generator output
   changed, then review).
4. Only if the mappings cannot describe the wire shape: a public static method in `Transforms`, named in
   `transforms.json` with the reason ([tools/docs/codegen.md](tools/docs/codegen.md)).

**Add a layer feature**: add the data to `PaletteLayer` (builder method, accessor, javadoc, `@since`; a policy goes to
`Paletter.Builder` instead); apply it in the matching stage of `PaletteCompiler#extend`; record changes in
`PaletteReport`; test it next to `LayerTest` / `CustomContentTest` against every bundled version; run
`:paletter:apiDump` and commit `paletter/api/paletter.api`.
