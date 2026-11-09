# Component model

Paletter's custom item and block API mirrors Bedrock's add-on component model and compiles it to each protocol's wire
format. The machine-readable model it is generated from is built from the official creator docs and Bedrock Dedicated
Server only.

```
docs  --DocsParser--> data/model/components.json   every component and field, as documented
                      + fixtures.json (hand: errata, samples, companions)
                      + bds-feedback.json (generated: what BDS said last time)
      --AddonGenerator--> spec add-on (~1900 items/blocks, one per varied field value) + variants.json
BDS   --spec-capture--> data/bds/<version>/spec/
      --MappingInference--> data/model/mappings.json (JSON -> wire rule per field, per protocol window),
                            coverage.md, bds-feedback.json   (+ transforms.json, hand: what no rule explains)
```

```
./gradlew :tools:generateModel [-Pdocs=/path/to/minecraft-creator]   # docs -> components.json (default: pinned commit)
./gradlew :tools:generateAddon                                        # -> tools/build/spec-addon
./gradlew :tools:run --args="spec-capture --channel release"          # BDS run -> data/bds/<version>/spec
./gradlew :tools:inferMappings                                        # -> mappings.json, coverage.md, bds-feedback.json
./gradlew :tools:checkMappings                                        # part of check: committed outputs up to date
```

`spec-capture` takes the `update` options `--version`, `--cache`, `--keep-server`, `--port`, `--timeout`,
`--experiments`. A second capture after the first inference gives valid bases for components whose docs omit a required
field (run until `bds-feedback.json` stops changing).

## The docs model

Source: github.com/MicrosoftDocs/minecraft-creator, `creator/Reference/Content/` (CC BY 4.0, see
`data/model/ATTRIBUTION.md`). The component pages share one layout, which `DocsParser` reads. Descriptions,
`menu_category`, states, permutations and traits come from hand-written pages and are modelled as pseudo components
(`item:description`, `block:states`, `block:permutations`, `block:trait:minecraft:...`). Per component: id, kind,
category, docs status, description, minimum format version, experiments, value forms. Per field (flattened path
`a.b`, `a[]`, `a{}`): JSON types, default, required, enum values, range, minimum format version, examples.

## The variants

Per component: a **control** (only the companions it needs), a **base** (every field without a documented default, or
that BDS reported as required, set to a sample), every whole value form, and per field one variant per value (omitted,
booleans, numbers around the ranges and the default, every enum value, lists of 0/1/2 elements, empty and filled maps
and objects). Each variant is its own item (`paletter:<component>_<n>`) or block (`paletter:b_<component>_<n>`).

## The inference

A variant is accepted when BDS sent it and logged no error. The control shows what a component adds to the wire
(`sent`, `server-only`, `rejected`); per field the variants are compared with the base, and every changed wire leaf is
explained by the first rule that fits all observations:

| kind | meaning |
|---|---|
| `identity` | the leaf is the JSON value (possibly renamed, moved into `item_properties`, another NBT type) |
| `linear` | scale * value + offset (unit conversions) |
| `table` | a value table, for booleans and enums |
| `affix` | free strings with a constant prefix/suffix |
| `bitset` | a list of enum values -> an int of bits |
| `element` | list element k -> the leaf |
| `constant`, `empty-list` | a leaf the field's presence switches on |

`whenAbsent` gives the leaf when the field is omitted. A field no leaf follows is `dropped` (server only); one whose
every variant is rejected is `rejected`. Leaves no rule explains make a field `partial`/`unresolved`; those need a named
transform in `transforms.json`.

One capture per bundled protocol (`data/bds/<version>/spec`). Its add-on asks for the format version of its build, and
the capture keeps its own `variants.json`. A capture whose wire relevant part equals the previous window's joins it
(`protocols`); a different one is kept beside it as `protocol<N>`. `bds-feedback.json` is read from the newest capture
only.
