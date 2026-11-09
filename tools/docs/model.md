# Component model (`spec`)

Paletter's custom item and block API is meant to mirror Bedrock's own add-on component model (`minecraft:durability
{max_durability, damage_chance {min, max}}`, block `traits`, `states`, `permutations`, `menu_category`) and compile it to
the wire format of each protocol. This module builds the machine-readable model that API is generated from, using only
the official creator docs and Bedrock Dedicated Server itself. Not shipped.

```
docs  ──DocsParser──> data/model/components.json      JSON side: every component and field, as documented
                         + fixtures.json (hand: errata, content samples, companions)
                         + bds-feedback.json (generated: what BDS said last time)
      ──AddonGenerator──> spec add-on (~1900 items/blocks, one per varied field value) + variants.json
BDS   ──`spec-capture`──> data/bds/<version>/spec/  (content log, custom item registry, block properties)
      ──MappingInference──> data/model/mappings.json   JSON -> wire rule per field, per protocol window
                            data/model/coverage.md      coverage report
                            data/model/bds-feedback.json
                         + transforms.json (hand: named transforms for what no automatic rule explains)
```

## Commands

```
./gradlew :tools:generateModel -Pdocs=/path/to/minecraft-creator   # docs checkout -> components.json
./gradlew :tools:generateModel                                      # same, pages of the pinned commit downloaded into the gradle cache
./gradlew :tools:generateAddon                                      # -> tools/build/spec-addon (to look at)
./gradlew :tools:run --args="spec-capture --channel release"  # BDS run -> data/model/captures/bds-<version>
./gradlew :tools:inferMappings                                      # -> mappings.json, coverage.md, bds-feedback.json
./gradlew :tools:checkMappings                                      # part of check: committed outputs up to date
```

`spec-capture` takes the `update` options `--version`, `--cache`, `--keep-server`, `--port`, `--timeout`, `--experiments`.
Since the generator reads `bds-feedback.json`, a second `spec-capture` after the first inference gives valid bases for
components whose docs omit a required field or list members BDS does not know (run until `bds-feedback.json` stops changing).

## The docs model (`components.json`)

Source: github.com/MicrosoftDocs/minecraft-creator, `creator/Reference/Content/` (CC BY 4.0, see
`data/model/ATTRIBUTION.md`). The component pages are generated from Mojang's schemas and share one layout, which the
parser reads: notes (minimum format version, experiments), "Alternate Simple Representations" (shorthand forms), the
root property table (Name, Default, Type, Description, Example values) and one section per nested type, linked from the
type column (`[Title](#anchor) item`, `Array of [...] items`, `[...] choices` for enums, "Same structure as").
Descriptions, `menu_category`, states, permutations and traits come from the hand-written pages (`BlockDescription.md`,
`description_component.md`, `BlockStatesAndPermutations.md`, `BlockStateAndTraitListings.md`, `traits/*.md`) and are
modelled as pseudo components (`item:description`, `block:states`, `block:permutations`, `block:trait:minecraft:...`).

Per component: id, kind, category, docs status (`active`, `legacy`, `deprecated`, `unlisted`), description, page, minimum
format version, experiments, value forms (object, shorthands, alternative representations). Per field (flattened path,
`a.b`, `a[]`, `a{}`): JSON types (several when the docs list `name (as String)` rows), default, required, enum values,
range, minimum format version, example values (vanilla content), description.

## The variants (`AddonGenerator`)

Per component: a **control** (only the companion components it needs), a **base** (every field without a documented
default, or that BDS reported as required, set to a sample; members BDS does not know left out), every whole value form,
and per field one variant per value: omitted, `true`/`false`, numbers around the documented and BDS-reported ranges and
the default, every enum value, lists of 0/1/2 elements and every enum value alone (bit sets), maps and objects empty and
filled. Each variant is its own item (`paletter:<component>_<n>`) or block (`paletter:b_<component>_<n>`).

## The inference (`MappingInference`)

A variant counts as accepted when BDS sent it and logged no error for it. The control shows what a component adds to the
wire (`sent`, `server-only`, `rejected`); per field the variants are compared with the base, and every wire leaf that
changes is explained by the first rule that fits all observations:

| kind | meaning |
|---|---|
| `identity` | the leaf is the JSON value (possibly renamed, moved into `item_properties`, another NBT type: see `tags`) |
| `linear` | leaf = scale * value + offset (unit conversions: seconds -> ticks, degrees -> quarter turns, origin -> min) |
| `table` | a value table, for booleans and documented enums (enum -> int, color -> formatting code) |
| `affix` | free strings with a constant prefix/suffix (namespace added, `<>` spawn event suffix) |
| `bitset` | a list of enum values -> an int of bits |
| `element` | (`element: k` on a rule) list element k -> the leaf (vectors flattened into per axis keys) |
| `constant`, `empty-list` | a leaf the field's presence switches on (sibling defaults BDS writes) |

`whenAbsent` gives the leaf when the field is omitted (injected default or `omitted`). A field no leaf follows is
`dropped` (server only); one whose every variant is rejected is `rejected` (with BDS's errors and ranges). Leaves no rule
explains make a field `partial`/`unresolved`; such fields need a named transform in `transforms.json` (`hand-rule`).

One capture per bundled protocol (`data/bds/<version>/spec`). The add-on of a capture asks for the format version of its
build (`min(fixtures.formatVersion, build)`, stored in `capture.json`), since a build refuses packs of a newer engine, and
the capture keeps its own `variants.json`: later steps use the variants that build was given, not today's model. A capture
whose wire relevant part (status, wire keys, constants, per field status, rules, transform and absent values; not BDS's
messages or the variant counts) equals the previous window's joins it (`protocols`); a different one is kept beside the first
as `protocol<N>`. `bds-feedback.json` is read from the newest capture only.
