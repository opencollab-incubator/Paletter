# Code generation

## The component API

`./gradlew :tools:generateComponentApi` writes Paletter's custom item and block component API and its encoders from the
component model in `data/model`; `./gradlew :tools:checkComponentApi` (part of `check`) fails if the committed
sources differ. Code: `org.geysermc.paletter.tools.codegen.component` in the `componentApi` source set, which needs neither
paletter nor its sources, so it can regenerate them when they do not compile. Java is emitted with JavaPoet
(`com.palantir.javapoet`, the maintained fork of square/javapoet; a codegen-only dependency).

Inputs (all in `data/model`):

| File | Kind | What the generator takes from it |
|---|---|---|
| `components.json` | generated from the creator docs | components, fields, JSON types, defaults, enums, ranges, docs text, minimum format versions, experiments, status |
| `fixtures.json` | hand | `errata` (types and fields the docs omit; added to the documented ones), `probeOnly` and `skip` (left out) |
| `bds-feedback.json` | generated from BDS's content log | required fields and ranges BDS enforces (they win over the docs), fields BDS does not know (left out) |
| `mappings.json` | inferred from BDS captures | per component and protocol window: status, value form and field rules, `whenAbsent`, `constants` |
| `transforms.json` | hand | fields (or whole components, path `""`) compiled by a named hand transform, with why |
| `api.json` | hand | Java shaping only: class, field and type names, union resolution, value form fields, descriptors, `keyedExtras` (a map for the keys a keyed set leaves to the caller), open enums, `since` |

Outputs: per generated component a record in `org.geysermc.paletter.item.component` / `block.component` (nested records
for object types, merged when identical; enums; `Vec3`; builder; `of(...)` factories for value forms and shorthands;
vanilla-constant overloads for descriptors), the sealed `ItemComponent` / `BlockComponent` / `BlockTrait`, and in
`org.geysermc.paletter.internal.component` the encoders `ItemWire`, `BlockWire`, `TraitWire` and `ComponentIndex`.
Hand-written there: the compile core (`ItemCompiler`, `BlockCompiler`, `TraitStates`, `WireNode`, `WireContext`,
`Wire`, `Check`) and `Transforms`, whose public static methods are the transform names (camelCase); the generator reads
them from its source and fails on a name transforms.json uses that it does not implement.

Which components: those BDS sends (`sent`) or that only failed for want of a companion (`untestable`, e.g.
`minecraft:geometry`). Server-only and rejected components are listed in the interfaces' javadoc instead. The
descriptions' `menu_category` is generated as the standalone record `MenuCategory`; states, permutations and the
description itself are the definition core (`CustomItem`, `CustomBlock`, `BlockProperty`).

Rules of the generated code:

- **Defaults** are the documented defaults (an object's default names its fields' defaults), unless BDS sends something
  else when the field is omitted; such a field stays unset (nullable) and the encoder writes BDS's value. The javadoc
  says which.
- **Required** fields (docs or BDS) are checked by `build()`; a BDS-only requirement is dropped when BDS accepted the
  omission. **Ranges** BDS reports win over documented ones; both are checked by the record constructor.
- **Encoders** follow each window's rules (see `WireSource`): constants first; per field, set: its value rules
  (identity, linear, table, affix, bitset, count) and the constants its presence switches on for leaves no other field
  owns; unset: its `whenAbsent` leaves, never another field's. Lists of objects become element loops. Hand transforms
  run after the generic fields of their record. A field with no rule in a window is dropped with a report warning when
  set.
- **Protocol windows**: each mapping in `mappings.json` holds from its protocol (`protocols`, or `protocol<N>` for a
  different mapping) until the next different one; the oldest also below. Components with several windows dispatch on
  `ctx.protocol()`.

Generation fails, naming component and field, on: an unknown transform, two mappings for one protocol, name collisions
(two components, a `java.lang` name, keywords, two nested types), two fields deciding one wire leaf, an object or list
of objects without documented fields, an ambiguous union, value forms without fields, a rule at the wrong list depth.

Tests: `ComponentGeneratorTest` (golden sources of a synthetic model covering every field kind, determinism with
shuffled inputs, every failure message; `-PupdateGolden=true` rewrites `src/test/resources/component-golden`),
`GeneratedApiTest` (the committed API: required fields, ranges, defaults equal the docs or are explained),
`ComponentConformanceTest` (every accepted variant of the newest capture rebuilt through the generated API and compared
with BDS's NBT; outcomes other than pass are committed in `data/model/conformance-known.txt`), and in paletter
`TransformsTest` (every hand transform) and `CustomContentTest` (the definition core, every bundled version).

When a new BDS version changes the wire: ``spec-capture``, `./gradlew :tools:inferMappings` (a different mapping
lands as a new protocol window), `./gradlew :tools:generateComponentApi`, review, and run the conformance suite.
## Wire shapes (change detection)

There is no separate wire shape spec any more: the `update` pipeline reads the observed NBT paths and types of the add-on
capture and of the vanilla data driven items and blocks (`WireShapes`), reports added, removed or retyped fields and
components that are no longer sent as wire format changes (`Diff.shapes`), and checks that every captured path is a wire
path of `data/model/mappings.json`. Paths the model does not cover yet are listed in `data/model/wire-gaps.txt`; `UpdateTest`
fails when an entry became covered or a captured path is neither mapped nor listed.
splits.

## Vanilla constants

`./gradlew :tools:generateConstants` writes `org.geysermc.paletter.vanilla.BedrockBlocks`, `BedrockStates`,
`BedrockItems`, the enums in `org.geysermc.paletter.vanilla.state` and, while a preview is bundled,
`BedrockPreviewBlocks` / `BedrockPreviewItems`; `./gradlew :tools:checkConstants` (part of `check`) fails if they, or
the history, differ from what the committed inputs give. The generator's `update` pipeline runs the same code on the data
it stages (see the README). Code: `org.geysermc.paletter.tools.codegen.constants`.

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
- **New content** gets `@since` of the current Paletter version when it is newer than the class (1.0.0).

`ConstantsGoldenTest` runs the generator on a small synthetic bundle (bool, int and string states, a shared value set, a
name collision settled by names, a lookup table block, a removed block, a rename, a removed item with a curated fallback,
a preview-only block and item) and compares every output byte for byte with `src/test/resources/constants-golden`
(after an intended change: `./gradlew :tools:test -PupdateGolden=true`, then review the diff). It also checks that
generating twice, and with blocks, items and JSON keys shuffled, gives identical bytes, and the error message of every
failure: broken data (non-contiguous, duplicate or incomplete states, mixed value kinds), invalid or colliding names,
inconsistent history entries. `BlockLayoutTest` (paletter) covers stride derivation and the lookup table fallback.
`ConstantsGeneratorTest` runs on the real bundle: reproducibility of the committed output, deprecation with a synthetic
history, naming failures and previews, and compiles what it generates; `VanillaConstantsTest` (paletter) round-trips every block state of the release, compares 200k random `with()`
chains with palette lookups and checks that `with()` does not allocate.
