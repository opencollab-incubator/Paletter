# Architecture

How Paletter fits together, for someone who is about to change it. What it does for a user is in the [README](README.md),
what is API in [API.md](API.md), the commands in [tools/README.md](tools/README.md).

## Compile stages

`Paletter#palette(codec, mode, layers...)` compiles one `Palette` per client version and layer stack. The stages
(`PaletteCompiler`, vanilla first, then one `extend` per layer, each result cached):

| Stage | Does | Owned by |
|---|---|---|
| 1. blocks | Vanilla states of the version, custom blocks and their properties, block fallbacks, network ids (sequential or hashed), canonical table | `BlockPalette` (+ `internal.CanonicalBlocks`, `BlockInterner`, `BlockNetworkIds`); custom blocks are encoded by `internal.component.BlockCompiler` |
| 2. items | Vanilla item definitions, custom items, overrides, item fallbacks, block items (`ItemPalette#blockOf`, decided from the bundled block to item relation first), tags, canonical table | `ItemPalette` (+ `internal.CanonicalItems`); components are encoded by `internal.component.ItemCompiler` |
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
  per Bedrock version plus `versions.txt`, read at runtime by `BundledDataSource` / `PaletteDataCodec`. No JSON, no network
  in the library.
- Paletter bundles no fallbacks: what an older version lacks is `minecraft:unknown` unless a consumer defines a
  fallback in a layer. The data carries the block to item relation of the Script API (`block_items`) instead; versions
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

## Ids and handles

Consumers never see ids. The public lookups return interned handles, `BedrockBlockState` and `BedrockItemVariant`
(`Paletter#block(NbtMap)`, `#item(ItemVariant)`, `#blockOf(ItemVariant)`, the same on `Palette` including custom content of
its layers, and `Palette#match(ItemData)`); `Palette#runtimeId/block/item(handle)` turn them into what a client needs.
Everything below is plumbing in `org.geysermc.paletter.internal` (`CanonicalBlocks`, `CanonicalItems`, `Canonicals`,
`Forwarding`) and the members of `BlockPalette` / `ItemPalette` marked `@Internal`.

- **Canonical ids** are dense ints for the newest Bedrock version: a block state's position in its vanilla palette; an
  item variant's position in `CanonicalItems` (each item's base variant in runtime id order, then the other creative
  variants such as potions). The same for every protocol version. A handle keeps its id in a hidden field, so
  `Palette#runtimeId(handle)` is a field read and an array load without allocation (`HandleTest` checks it); only a
  handle of a source that is not the bundled one, or custom content, takes one more lookup.
- **Custom content** of a layer lineage gets ids appended after the vanilla ones, so the same layer stack gives the same ids
  in every version. `PaletteLayer.Builder#variant` gives ids to further variants. Its handles are made from the custom
  block's `BedrockBlockType` (interned per state) and, for items, once per id.
- **Runtime ids** are per palette. `BlockNetworkIds.SEQUENTIAL` uses palette positions (and shifts once custom blocks
  exist); `HASHED` uses the state hash, which is the same in every version, so vanilla `PaletteBlock`s and canonical table
  pages are shared between palettes.
- **Resolution** turns a canonical id into what an older version sends: exact, adapted (state values added or removed),
  fallback (a layer's), or the default (`minecraft:unknown`). `Palette#explain` shows which, and `inspect` prints it.
- **Forwarding** is the reverse and makes the handle lookups version independent: `CanonicalBlocks#forward` /
  `CanonicalItems#forward` map a state or variant of any bundled version to the newest version's canonical id (exact,
  renamed through `renames.txt`, or adapted: values the newest block still has are kept, new keys get the default of
  the newest data, removed keys are dropped) and say why when nothing matches (`Forwarding`, surfaced as
  `explainUnknown`). A newly bundled version redefines the canonical table, so consumers keep mapping with the content
  they know and Paletter maps it forward.

## Layers and extend

A `PaletteLayer` is a patch (custom items and blocks, overrides, creative edits, fallbacks, extra variants) that knows no
protocol version. `Paletter#palette` compiles and caches every prefix of a layer list; `Palette#extend` adds a short
lived layer (per session) on the parent without recompiling it.

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

1. Add the data to `PaletteLayer` (builder method, accessor) with javadoc and `@since`.
2. Apply it in the matching stage of `PaletteCompiler#extend` (blocks, items or creative) and in the stage's class.
3. Record what it changed in `PaletteReport`; add a test next to `LayerTest` / `CustomContentTest` that compiles it against
   every bundled version.
4. If it adds API, check `apiCompat` (additions pass; never update the baseline for them within a minor line) and note it in
   `API.md`.
