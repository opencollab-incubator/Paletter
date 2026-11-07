# Component code generation

The typed Bedrock components in `paletter/src/main/java/org/geysermc/paletter/component/{item,block}` are generated.
Their network shape comes from **wire NBT only**: what the vanilla server actually sends. Add-on JSON, the wiki and the
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
   - **Geyser samples** ([`geyser-samples.json`](geyser-samples.json), confidence `medium`): what Geyser's production
     encoders write. A sample shapes a component only when no wire NBT shows it; such types are annotated `@Unverified`.
     For components with wire NBT a sample is checked against the wire shape: a compatible sample may only make the
     fields it omits optional (Geyser proves the client accepts the omission); an incompatible one is reported
     (`DIFFERS`) and ignored. See [`DEVIATIONS.md`](DEVIATIONS.md).
2. **Generate** (`Generator`): `spec/inferred.json` + [`overrides.json`](overrides.json) produce one immutable record per
   component (typed fields, `fromNbt`, `toNbt`, `TYPE`, a builder for two or more fields, nested records for compounds),
   `ItemProperties` for `item_properties`, and the `ItemComponentTypes` / `BlockComponentTypes` registries.
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

## Capture files

`*.nbt` / `*.dat`, big or little endian, optionally gzipped. Either
- a compound `{items: [{name, components: {...}}...], blocks: [{name, components, permutations: [{components}]}...]}`
  (item component packet entries and start game block properties; both lists optional),
- a single item component entry `{name, components}`, or
- a whole palette in the bundled `PaletteDataCodec` format,
- the `capture` module's `custom_item_registry.nbt` (`{items: [{name, id, componentBased, version, data: {components}}]}`)
  and `custom_block_properties.nbt` (`{blocks: [{name, properties: {block property data}}]}`).

`codegen/captures/bds-1.26.63.1-addon/` holds the BDS 1.26.63.1 capture of `capture/addon` (87 items, 66 blocks).

Captures in `codegen/captures/` are part of the committed spec; directories passed with `-Pcaptures` are not, so
`checkComponents` fails until they are copied in.

## overrides.json

Never a source of shape. Keys are `<kind>:<name>` (`item:minecraft:damage`, `block:minecraft:geometry`,
`item:item_properties`).

| Key | Meaning |
|---|---|
| `class` | Java class name (default: PascalCase of the name without namespace) |
| `since`, `experimental`, `deprecated` | min add-on format version and flags, from bedrock-samples schema metadata (javadoc and `ComponentType` only) |
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
