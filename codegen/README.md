# Component code generation

The wire shapes of Bedrock components in `paletter/src/main/java/org/geysermc/paletter/internal/wire/{item,block}` are
generated. They are **internal**, not Paletter's API: consumers use the semantic `ItemComponents` / `BlockComponents`, and
the hand written encoders in `org.geysermc.paletter.internal.encode` map those onto the generated shape of each palette's
Bedrock version (see `API.md`). Their network shape comes from **wire NBT only**: what the vanilla server actually sends. Add-on JSON, the wiki and the
bedrock-samples JSON schemas differ from the wire format in places (shorthands wrapped in `{value: ...}`, camelCase keys,
`kinetic_weapon` nested in itself, `packed_bools` instead of separate flags, ...), so they never decide a field name, type
or nesting.

```
./gradlew :codegen:generateComponents                 # infer + regenerate (writes the files below)
./gradlew :codegen:generateComponents -Pcaptures=../Paletter-capture/capture/captures   # also read extra captures
./gradlew :codegen:checkComponents                    # part of ./gradlew check: fails on any diff
```

## Pipeline

1. **Infer** (`Inference`): unions the shape of every component observed in wire NBT into
   [`spec/inferred.json`](spec/inferred.json) (committed, generated). Per component: the NBT type of every field,
   whether a byte only ever held 0/1, nested compounds and list element shapes, and which fields were absent in some
   observations (optional). Inputs, in evidence order:
   - **Wire NBT** (confidence `high`): the bundled vanilla data (`paletter/src/main/resources/paletter/*.nbt`: item
     component entries of data driven items, data driven block components and permutations) and every capture under
     `codegen/captures/` (recursive; plus `-Pcaptures=dir1,dir2`). Rerunning on new captures regenerates the types.
     Every source has a protocol version: a bundled palette its `DataVersion`, a capture the `protocol` of the nearest
     `capture.json` in its directory or a parent (unknown without one; observations of unknown protocol count for the
     newest shape of a component with `protocolSplits`).
   - **Geyser samples** ([`geyser-samples.json`](geyser-samples.json), confidence `medium`): what Geyser's production
     encoders write. A sample shapes a component only when no wire NBT shows it; such types are annotated `@Unverified`.
     For components with wire NBT a sample is checked against the wire shape: a compatible sample may only make the
     fields it omits optional (Geyser proves the client accepts the omission); an incompatible one is reported
     (`DIFFERS`) and ignored. See [`DEVIATIONS.md`](DEVIATIONS.md).
2. **Generate** (`Generator`): `spec/inferred.json` + [`overrides.json`](overrides.json) produce one immutable record per
   component shape in `org.geysermc.paletter.internal.wire.{item,block}` (typed fields, `fromNbt`, `toNbt`, `TYPE` with
   the protocol window, a builder for two or more fields, nested records for compounds), `ItemProperties` for
   `item_properties`, and the `ItemComponentTypes` / `BlockComponentTypes` registries (`byName(name, protocol)`,
   `windows(name)`).
3. **needs-capture** ([`needs-capture.txt`](needs-capture.txt), generated): components without (consistent) wire
   evidence. `none` = no evidence, raw escape hatch only; `not-sent` = server only, BDS accepts the JSON but sends
   nothing (no type); `rejected` = BDS 1.26.63 rejects the JSON (no type); `not-a-component` = sent elsewhere
   (`item_properties`, top level block data); `removed` = no longer an add-on component; `geyser-only` = typed
   `@Unverified`; `differs` = typed from wire NBT but Geyser sends something else.

Besides the components, two pseudo components are inferred and generated: `item:item_properties` (`ItemProperties`) and
`block:block_definition` (`BlockDefinition`: the block property data around `components` and `permutations`:
`blockTags`, `menu_category`, `molangVersion`, `properties`, `traits`, `vanilla_block_data`), used by `CustomBlock`.

The javadoc of each type pairs the add-on JSON with the captured wire form (from `capture/WIRE-NOTES.md` and the add-on
files in `capture/addon/`, as `doc` overrides); the shape itself still comes only from the wire.

## Protocol windows: when Bedrock changes a shape

Each spec entry is the shape of one component for a window of protocol versions. Normally there is one, unbounded. When
a Bedrock version sends a component differently:

1. Add the protocol version at which the shape changed to the component's overrides:
   `"item:minecraft:damage": {"protocolSplits": [2250], ...}`.
2. Add a capture of the new version (with its `capture.json`) and run `./gradlew :codegen:generateComponents`. Inference
   splits the observations by source protocol: the newest window keeps the key (`item:minecraft:damage`, with
   `"sinceProtocol": 2250`) and the class name (`Damage`); older windows get keys like `item:minecraft:damage@..2250`
   (`"untilProtocol": 2250`) and classes like `DamageUntil2250` (`DamageFrom2200Until2250` for a middle window). Overrides
   of the base key apply to every window.
3. Map the semantic value to the new shape in `ItemEncoder` / `BlockEncoder` (`EncodeContext#put` picks the shape whose
   window covers the palette). Until then, palettes of the new version leave the value out with a report warning
   ("has a network shape Paletter does not encode yet"), and `VersionedEncodingTest` fails for the bundled versions.

The public API does not change. `ProtocolWindowTest` covers the split with synthetic captures.

## Capture files

`*.nbt` / `*.dat`, big or little endian, optionally gzipped. Either
- a compound `{items: [{name, components: {...}}...], blocks: [{name, components, permutations: [{components}]}...]}`
  (item component packet entries and start game block properties; both lists optional),
- a single item component entry `{name, components}`, or
- a whole palette in the bundled `PaletteDataCodec` format,
- the `capture` module's `custom_item_registry.nbt` (`{items: [{name, id, componentBased, version, data: {components}}]}`)
  and `custom_block_properties.nbt` (`{blocks: [{name, properties: {block property data}}]}`).

`codegen/captures/bds-1.26.63.1-addon/` holds the BDS 1.26.63.1 capture of `capture/addon` (87 items, 66 blocks).
The generator's `update` pipeline compares each new add-on capture with the newest one here (a changed shape is reported
as a wire format change), and in update mode adds it as `codegen/captures/bds-<bds version>-addon/` (with a `capture.json` naming the server's `protocol`) and reruns
`generateComponents`.

Captures in `codegen/captures/` are part of the committed spec; directories passed with `-Pcaptures` are not, so
`checkComponents` fails until they are copied in.

## overrides.json

Never a source of shape. Keys are `<kind>:<name>` (`item:minecraft:damage`, `block:minecraft:geometry`,
`item:item_properties`).

| Key | Meaning |
|---|---|
| `class` | Java class name (default: PascalCase of the name without namespace) |
| `since`, `experimental`, `deprecated` | min add-on format version and flags, from bedrock-samples schema metadata (javadoc and `ComponentType` only) |
| `protocolSplits` | protocol versions at which the wire shape changed; observations are split into one shape per window (see above) |
| `schema` | bedrock-samples schema path, for reference in javadoc |
| `doc`, `notes` | javadoc text |
| `fields.<path>` | per field: `java` (name), `class` (nested record name), `doc`, `type` (`byte`: keep a 0/1 byte numeric, `bool`, `map`: read a compound with arbitrary keys as `Map<String, V>`, `raw`: keep `NbtMap`/`NbtList`), `optional` (reason; only with production evidence) |

Field paths join NBT keys with `.`, list elements as `[]`, map values as `{}`: `repair_items[].items[]`,
`materials{}.packed_bools`.

`needsCapture` lists known components (from the schemas and research) that wire NBT has not shown yet.

## geyser-samples.json typed notation

Compounds are JSON objects, lists JSON arrays. Strings with a prefix are typed: `b:` byte, `s:` short, `i:` int, `l:`
long, `f:` float, `d:` double, `str:` string; other strings are strings. JSON booleans are bytes 0/1. Untyped numbers are
rejected. `{"@list": "STRING"}` is an empty typed list, `{"@int_array": [1, 2]}` an int array.

## Generated type rules

- NBT types are exactly the observed ones: byte, short, int, long, float, double, string, int array, list, compound.
  A byte that only ever held 0 or 1 becomes `boolean` (still a byte on the wire) unless overridden.
- Optional fields are nullable and not written when null; required fields are always written.
- Compounds always empty in the samples stay `NbtMap`; lists always empty stay `NbtList<?>` (element type unknown).
- `fromNbt` is lenient (numbers of another width convert, missing fields take zero values), so NBT from older encoders
  (e.g. a byte `minecraft:damage`) reads and is re-encoded in the wire shape.
- Record components are in NBT key order (sorted); prefer the builders.

## Vanilla constants

`./gradlew :codegen:generateConstants` writes `org.geysermc.paletter.vanilla.BedrockBlocks`, `BedrockStates`,
`BedrockItems`, the enums in `org.geysermc.paletter.vanilla.state` and, while a preview is bundled,
`BedrockPreviewBlocks` / `BedrockPreviewItems`; `./gradlew :codegen:checkConstants` (part of `check`) fails if they, or
the history, differ from what the committed inputs give. The generator's `update` pipeline runs the same code on the data
it stages (see the README). Code: `org.geysermc.paletter.codegen.constants`.

Inputs:

- the newest bundled **release** in `paletter/src/main/resources/paletter/versions.txt` (and the newest preview newer
  than it, if any: its content the release lacks goes to the preview classes, which are not part of the history);
- [`names.json`](names.json): names that differ from the default, and what removed content becomes;
- [`constants-history.json`](constants-history.json): every constant generated so far, with `since` (the Paletter
  version from the root `build.gradle.kts`, without `-SNAPSHOT`) and, once deprecated, `deprecated` plus `replacement`
  (an alias) or `fallback`. Written by every generation; commit it with the classes.

Rules:

- **Names**: UPPER_SNAKE of the identifier path (`minecraft:item.bed` -> `ITEM_BED`; other namespaces keep theirs as a
  prefix), of the state name for states (`minecraft:cardinal_direction` -> `CARDINAL_DIRECTION`), enum values from their
  string. Enums are named after the PascalCase of the first of their states, without a trailing direction
  (`wall_connection_type_east` -> `WallConnectionType`). Generation **fails** on duplicates or names that are no Java
  identifiers; `names.json` (`blocks`, `items`, `states`: identifier -> constant; `enums`: state -> enum) settles them.
  Today: `minecraft:facing_direction` (string) is `MINECRAFT_FACING_DIRECTION` next to the integer `facing_direction`,
  and three enums are named by hand.
- **Types**: byte states with values 0/1 are `BoolState`, integer states `IntState` (`range` if contiguous), string
  states `EnumState` of an enum shared by every state with the same value set. A state keeps its enum once it has one
  (new values are appended to it), and a state that changes its kind fails generation.
- **Arithmetic ids**: `internal.vanilla.BlockLayout` (shared with the runtime) checks every block of the release: its
  states must be the full cartesian product of its values (properties sorted, values in order of first appearance), and
  every state's offset from the block's first state must be `sum(value index * stride)`. Blocks that are not get a lookup
  table at runtime; the generator prints them, writes the reason into the constant's javadoc, and the update report lists
  them. For 26.60: 1480 blocks, none needs a table. Generation fails only if a block's states are not contiguous, are
  duplicated or lack properties other states of the block have.
- **Removed content**: a history entry the data no longer has stays as a deprecated constant. If its identifier still
  exists under another constant name (a `names.json` change), or `names.json` `renamed` maps it to a new identifier, it
  becomes an alias of that constant; otherwise it resolves to `names.json` `fallbacks` or `minecraft:unknown`. Removed
  states and enum values stay deprecated as well. `deprecated` is set once, so later generations keep the version.
- **New content** gets `@since` of the current Paletter version when it is newer than the class (2.0.0).

`ConstantsGoldenTest` runs the generator on a small synthetic bundle (bool, int and string states, a shared value set, a
name collision settled by names, a lookup table block, a removed block, a rename, a removed item with a curated fallback,
a preview-only block and item) and compares every output byte for byte with `src/test/resources/constants-golden`
(after an intended change: `./gradlew :codegen:test -PupdateGolden=true`, then review the diff). It also checks that
generating twice, and with blocks, items and JSON keys shuffled, gives identical bytes, and the error message of every
failure: broken data (non-contiguous, duplicate or incomplete states, mixed value kinds), invalid or colliding names,
inconsistent history entries. `BlockLayoutTest` (paletter) covers stride derivation and the lookup table fallback.
`ConstantsGeneratorTest` runs on the real bundle: reproducibility of the committed output, deprecation with a synthetic
history, naming failures and previews, and compiles what it generates; `VanillaConstantsTest` (paletter) round-trips every block state of the release, compares 200k random `with()`
chains with palette lookups and checks that `with()` does not allocate.
