# Architecture

How Paletter fits together, for someone who is about to change it. What it does for a user is in the [README](README.md),
what is API in [API.md](API.md), the commands in [tools/README.md](tools/README.md).

## The model

Three concepts, kept apart:

| Concept | Classes | Versioned | Lives in |
|---|---|---|---|
| Key (identity) | `BedrockItemType`, `BedrockItemVariant` (type + aux), `BedrockBlockType`, `BedrockBlockState` (type + values). Vanilla keys are the generated constants, custom ones `X.of("namespace:id")` (blocks with states: `BedrockBlockType.of(CustomBlock)`), same classes | never; renames and splits (`renames.txt`) map to the same or a new key | `vanilla` |
| Registered | `RegisteredItem`, `RegisteredBlock` (type level) with its `RegisteredBlockState`s: the key inside one palette, with components, `Origin` (vanilla or the id of the custom layer) and runtime id. They implement Cloudburst's `ItemDefinition` / `BlockDefinition` | per palette | `item`, `block` |
| Stack | `BedrockItemStack`: variant, count, NBT, optional block state | never | `item` |

A `Paletter` holds the policy that is the same for every palette (`Paletter.builder()`: data source, versions, block
network ids, `Fallbacks`, vanilla content, creative ordering, reports) and the global layers. `PaletteLayer` is content
only: custom items and blocks, overrides, creative edits, tags. `Paletter#palette(protocol)` compiles a `Palette` once per
data version and caches it; `Palette#encode(BedrockItemStack)` applies the fallbacks and gives a Cloudburst `ItemData`,
`#decode(ItemData)` gives the stack back, `#runtimeId(BedrockBlockState)` the block id. Cloudburst `ItemData` and
definitions only appear at that boundary. Properties (components, wire properties) are versioned: `RegisteredItem#properties`
and friends, or `Paletter#properties(key, version)` without a palette.

## Compile stages

`Paletter#palette(protocol)` compiles one `Palette` per data version. The stages (`PaletteCompiler`, vanilla first, then
one `extend` per global layer, each result cached):

| Stage | Does | Owned by |
|---|---|---|
| 1. blocks | Vanilla states of the version, custom blocks and their properties, block fallbacks, network ids (sequential or hashed), canonical table | `BlockRegistry` (+ `internal.CanonicalBlocks`, `internal.block.BlockInterner`, `BlockNetworkIds`); custom blocks are encoded by `internal.component.BlockCompiler` |
| 2. items | Vanilla item definitions, custom items, overrides, item fallbacks, block items (`ItemRegistry#blockOf`, decided from the bundled block to item relation first), tags, canonical table | `ItemRegistry` (+ `internal.CanonicalItems`); components are encoded by `internal.component.ItemCompiler` |
| 3. creative | The creative layout (vanilla order from the data, then placements and edits of the layers) and the packet content | `CreativeLayout`, `CreativeEditor`, `CreativePlacement`, `CreativeContent` |
| 4. output | The immutable `Palette`: definition registries for the codec, `StartGamePacket`, item component and creative content packets | `Palette`, `PaletteReport` (what was adapted, replaced or dropped) |

Blocks come before items (block items need the blocks, custom blocks renumber every state) and creative last (it needs
the item definitions). An unchanged stage is shared with the parent palette, so `==` on definitions keeps working across
`extend`.

## Data flow

```
BDS (download, run)  ->  tools: update / capture / spec-capture / import  ->  data/ + paletter/src/main/resources/paletter
creator docs         ->  tools: docs                                       ->  data/model/components.json
data/model/*         ->  tools: infer, components generate, constants generate  ->  paletter/src/generated/java
```

- `data/bds/<bds version>/` keeps what each BDS build gave (captures, Script API dump, report). `tools.update` and
  `tools.data.Importer` turn captures into the bundled format: one `paletter/src/main/resources/paletter/<version>.nbt`
  per Bedrock version plus `versions.txt`, read at runtime by `PaletteDataSource#bundled()` (`data.FileDataSource`, which also reads a directory in the same
  format, `PaletteDataSource#directory`) and `PaletteDataCodec`; item components and data driven blocks are stored once with
  the version range they are valid for in `properties.nbt` (`PropertyStore`). No JSON, no network in the library.
- Paletter bundles no fallbacks: what an older version lacks is `minecraft:unknown` unless a consumer defines a
  fallback in the `Fallbacks` of the Paletter. The data carries the block to item relation of the Script API (`block_items`) instead; versions
  without a dump get the newest relation restricted to what they have (`block-items --from`). `renames.txt` lists
  renamed identifiers for the forward mapping below.
- `data/model/` is the component model: `components.json` (docs), `mappings.json` (inferred from BDS captures),
  `transforms.json`, `api.json`, `fixtures.json` (hand-written), `names.json` / `constants-history.json` (constants).

## Generated vs hand-written

| Where | Kind |
|---|---|
| `paletter/src/generated/java/...` | **Generated, never edited**: the component API (`item/component`, `block/component`), the encoders and `ComponentIndex` (`internal/component`), the vanilla constants (`vanilla`: `BedrockBlocks`, `BedrockItems`, `BedrockStates`, enums). `./gradlew build` fails if they are stale (`checkComponentApi`, `checkConstants`) |
| `paletter/src/main/java/...` | Hand-written library. `internal/component/Transforms.java` holds the hand transforms generated encoders call |
| `paletter/src/main/resources/paletter` | Generated data (`tools` import) |
| `tools/src/main/java/...` | Hand-written tooling, including the generators (`tools.codegen`, `tools.model`) |
| `data/model/transforms.json`, `api.json`, `fixtures.json`, `data/addons/dump`, `paletter/src/main/resources/paletter/renames.txt` | Hand-written inputs |
| `data/addons/capture`, `data/addons/creative`, `data/model/mappings.json`, `coverage.md` | Generated (never edit) |

## Hand-written wire-only components

Fields Bedrock clients read that have no add-on JSON source cannot come from the component model (the "known gaps" of
the README list what that leaves unverified): `minecraft:render_offsets`
(retired from the add-on format; assumed still honoured on the wire), `item_properties.mining_speed` and
`item_properties.frame_count` (always sent by BDS, not settable), and the block component `minecraft:on_player_placing` (retired like render_offsets). They are hand-written records in `item/component`
under the sealed `WireOnlyItemComponent` (blocks: `WireOnlyBlockComponent`), which the generator lists in `ItemComponent`'s `permits` and dispatches in the
generated `ItemWire#write` to `internal/component/WireOnlyWire`. So they are one path with the generated components:
`CustomItem` and overrides take them, `ItemCompiler` writes them in list order over the defaults. Each has a protocol
window (`WireOnlyWire.*_FROM`, the first protocol that carries the field; now all bundled versions). To add one: a record
implementing `WireOnlyItemComponent` (and the `permits` clause), a case in `WireOnlyWire`, a test in
`WireOnlyComponentTest`.

## Ids and keys

Consumers never see canonical ids. The public lookups return interned keys, `BedrockBlockState` and `BedrockItemVariant`
(`Paletter#block(NbtMap)`, `#item(String, int)`, `#blockOf(BedrockItemVariant)`, the same on `Palette` including custom content
of its layers); `Palette#runtimeId(key)`, `#block(key)` (a `RegisteredBlockState`), `#item(key)` (a `RegisteredItem`) and
`#encode` / `#decode` turn them into what a client needs and back. Everything below is plumbing in
`org.geysermc.paletter.internal` (`CanonicalBlocks`, `CanonicalItems`, `Canonicals`, `Forwarding`, `BlockInterner`,
`BlockResolution`) and the members of `BlockRegistry` / `ItemRegistry` marked `@Internal`.

- **Canonical ids** are dense ints for the newest Bedrock version: a block state's position in its vanilla palette; an
  item variant's position in `CanonicalItems` (each item's base variant in runtime id order, then the other creative
  variants such as potions). The same for every protocol version. A key keeps its id in a hidden field, so
  `Palette#runtimeId(key)` is a field read and an array load without allocation (`HandleTest` checks it); only custom
  content takes one more lookup. Every `Paletter` over the bundled data shares the canonical tables of `Paletter.bundled()`,
  which the generated constants are linked to.
- **Custom content** of a layer lineage gets ids appended after the vanilla ones, so the same layer stack gives the same ids
  in every version. `PaletteLayer.Builder#variant` gives ids to further variants. Its keys are made from the custom
  block's `BedrockBlockType` (interned per state) and, for items, once per id.
- **Runtime ids** are per palette. `BlockNetworkIds.SEQUENTIAL` uses palette positions (and shifts once custom blocks
  exist); `HASHED` uses the state hash, which is the same in every version, so vanilla `RegisteredBlockState`s and canonical table
  pages are shared between palettes. A shared state does not know its block (the state set of a block differs between
  versions): `BlockRegistry#block(state)` gives the block of the palette, `RegisteredBlock#components(state)` and
  `#properties(state)` what applies to the state.
- **Resolution** turns a canonical id into what an older version sends: exact, adapted (state values added or removed),
  fallback (a `Fallbacks` entry), or the default (`minecraft:unknown`). `Palette#explain` shows which, and `inspect` prints it.
- **Forwarding** is the reverse and makes the key lookups version independent: `CanonicalBlocks#forward` /
  `CanonicalItems#forward` map a state or variant of any bundled version to the newest version's canonical id (exact,
  renamed or split through `renames.txt`, or adapted: values the newest block still has are kept, new keys get the default of
  the newest data, removed keys are dropped) and say why when nothing matches (`Forwarding`, surfaced as
  `explainUnknown`). A newly bundled version redefines the canonical table, so consumers keep mapping with the content
  they know and Paletter maps it forward.
- **Order of custom blocks** is Paletter's: the block ids, the custom entries of the block property list and the runtime
  ids of the block items Paletter registers follow the identifier, descending (the stand-in for BDS's file name order, see
  `data/bds/CREATIVE-ORDER.md`), whatever order the layers register them in. Items registered with `PaletteLayer.Builder#blockItem` are placed among the
  custom items as the consumer registered them: that slot is the consumer's decision.

## Layers and extend

A `PaletteLayer` is content (custom items and blocks, overrides, creative edits, tags, extra variants) that knows no
protocol version and no policy: fallbacks, vanilla content and the creative ordering belong to the `Paletter`, never to a
layer. `Paletter.builder().layer(layer)` applies a layer to every palette it compiles.

`Palette#extend(layer)` is a session patch over the shared palette: added items get ids after the base, lookups check
the patch and then the base, the creative layout is rebuilt from the edited layout, and what the layer does not touch is
the same instance as in the base. Custom blocks in a session layer need `BlockNetworkIds.HASHED` (a clear
`PaletteException` otherwise): in a hashed palette the patch holds only the added states, over the base's table, and has
its own `RegisteredBlock` for every custom block, since the numeric block ids in their property data shift when blocks are
added. The creative ordering of the compiling Paletter is a field of the `Palette`, so extensions use it too.

## Recipes

### Add a new Bedrock version

```
./gradlew :tools:run --args="update --channel release"
```

It downloads BDS, captures everything, imports the data as the new bundled version, regenerates the constants and writes
a report (see README, "Updating data"). Read the report, check the diff, run `./gradlew --max-workers=1 build`
(`--verify-only` in CI). A brand new component shows up as a coverage gap in `data/model/coverage.md`; see the next recipe.

### Add or fix a component

1. Model: `components.json` comes from the creator docs (`docs`); hand corrections go in `fixtures.json` (errata) and
   `api.json` (Java naming and shaping).
2. Capture: `addon --kind spec` builds the spec add-on, `spec-capture` runs it on BDS, `infer` writes `mappings.json`
   (what BDS really sends). See [tools/docs/model.md](tools/docs/model.md).
3. Regenerate: `./gradlew :tools:generateComponentApi` (and `:tools:test -PupdateGolden=true` if generator output changed,
   then review). Commit the generated sources.
4. Only if the wire shape cannot be described by the mappings: add a public static method to `Transforms` and name it in
   `transforms.json` with the reason. See [tools/docs/codegen.md](tools/docs/codegen.md).

### Add a layer feature

1. Add the data to `PaletteLayer` (builder method, accessor) with javadoc and `@since`. Anything that is the same for
   every palette (a policy) goes to `Paletter.Builder` instead.
2. Apply it in the matching stage of `PaletteCompiler#extend` (blocks, items or creative) and in the stage's class.
3. Record what it changed in `PaletteReport`; add a test next to `LayerTest` / `CustomContentTest` that compiles it against
   every bundled version.
4. If it adds API, check `apiCompat` (additions pass; never update the baseline for them within a minor line) and note it in
   `API.md`.
