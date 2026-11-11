# Code generation

Both generators write into `paletter/src/generated/java`; the matching `check` task (part of `build`) fails if the
committed sources differ. After an intended change of generator output: `./gradlew :tools:test -PupdateGolden=true`,
then review the golden diff.

## The component API

`./gradlew :tools:generateComponentApi` (check: `checkComponentApi`) writes the custom item and block component API and
its encoders from `data/model`. Code: `tools.codegen.component`, in the `componentApi` source set, which needs neither
paletter nor its sources, so it can regenerate them when they do not compile. Java is emitted with JavaPoet.

| Input (`data/model`) | Kind | Used for |
|---|---|---|
| `components.json` | generated from the creator docs | components, fields, types, defaults, enums, ranges, docs text, format versions, experiments |
| `fixtures.json` | hand | `errata` (what the docs omit), `probeOnly` and `skip`, `replacedBy` (a replacement no data says, with its evidence) |
| `bds-feedback.json` | generated from BDS's content log | required fields and ranges BDS enforces (they win over the docs), fields BDS does not know |
| `mappings.json` | inferred from BDS captures | per component and protocol window: status, value form and field rules, `whenAbsent`, `constants` |
| `HandTransforms.java` | hand | fields or whole components (path `""`) compiled by a hand transform, as a Java table next to the generator; whether BDS bore it out is derived (`verified` / `unverified` in `mappings.json`, listed in `coverage.md`) |
| `api.json` | hand | Java shaping only: names, union resolution, value forms, descriptors, `keyedExtras`, open enums, `since` |

Output: a record per component in `item.component` / `block.component` (nested records, enums, builder, `of(...)`
factories, vanilla-constant overloads), the sealed `ItemComponent` / `BlockComponent` / `BlockTrait`, and the encoders
`ItemWire`, `BlockWire`, `TraitWire`, `ComponentIndex` in `internal.component`. The compile core (`ItemCompiler`,
`BlockCompiler`, `TraitStates`, `WireNode`, ...) and `Transforms` are hand-written. `HandTransforms` maps each hand
transformed field to a public static method of `Transforms`, whose Javadoc says why no inferred rule explains it and what
evidence backs it; a method the table names and `Transforms` lacks fails the compile of the generated encoders
(`HandTransformsTest` checks the table on its own).

Every component of the docs is generated, including those BDS never sends (server only in every protocol, or rejected;
for add-on parsing): their encoders write nothing. Omitted, and listed in the interfaces' javadoc with the reason, are
the ones `skip`, `api.json` `exclude` / `handWritten` or the definition core cover, and the never-sent ones whose
shape the docs leave open (an object without documented fields).

Links and annotations, all from the data:

- **`@see` links**: the Microsoft Learn page of a component is its `components.json` `page` below `creator/`, lowercased,
  without `.md`, plus `?view=minecraft-bedrock-stable` (`Docs.learnUrl`). Records link their page, the sealed interfaces
  the list pages (`lists` in `components.json`, written by `docs`) and every omitted component its page. The hand-written
  wire-only component with a docs page (`RenderOffsets`) carries the same link by hand; `GeneratedMetadataTest` checks it.
- **`@ServerOnly(since, until)`**: on a record with a protocol window whose status is `server-only` (the bounds are the
  window's). The javadoc names the BDS versions captured in the range and the one that sends it. One range per component
  (generation fails otherwise). Server only in every protocol, it is `@ServerOnly` without bounds: its encoder writes
  nothing and adds no warning (using it is no error, it has no client effect).
- **`@Deprecated @ReplacedBy({X.class})`** (`Replacements`): for a component the docs mark `deprecated` or `legacy`, or
  that BDS rejects, the successors come from the first of: a sentence "Use minecraft:x ..." in its docs text, docs notes or
  BDS's own message (`mappings.json` `note`); a "renamed to/from" docs note; an active component with a top level field
  of its name. Otherwise a `fixtures.json` `replacedBy` entry (`by` and a required `evidence`; an entry the data derives
  already fails generation). The annotations land on generated records only; for a component that is omitted the
  replacement and its evidence appear in the interface's "Documented but not generated" list.
- **Rejected components** (BDS rejects them in the newest capture) are `@Deprecated`; the javadoc says since which
  captured BDS (the first capture of the rejecting run, or "every captured BDS (oldest: ...)") with BDS's message. Their
  encoders write nothing and add a report warning.

Rules:

- **Defaults** are the documented ones unless BDS sends something else for an omitted field; that field stays unset and
  the encoder writes BDS's value.
- **Required** fields and **ranges** (BDS's win over the docs) are checked by `build()` and the record constructor.
- **Encoders** follow each window's rules (`WireSource`): constants, then per field its value rule (identity, linear,
  table, affix, bitset, count) and the constants its presence switches on; unset fields write their `whenAbsent` leaves.
  Hand transforms run after the generic fields. A field with no rule in a window is dropped with a report warning.
- **Protocol windows**: each mapping holds for the protocols listed in `protocols`; a different mapping starts
  `protocol<N>` and is compared with the window before it. The oldest window also holds below its capture, the newest
  above (INFO entry, `ComponentIndex.NEWEST_CAPTURED_PROTOCOL`). A window whose status is not `sent` makes the encoder
  leave the component out with a warning.
- **Hand transforms per protocol**: a hand transform checks `ctx.protocol()` itself, against the generated
  `ComponentIndex.BDS_<build>` constant (the first protocol of that capture's window, from `windowProtocol` in
  `mappings.json`).

## Gates

The inference (`inferMappings`; `checkMappings` runs it too) reports:

- **Unknown bits**: a boolean or enum `table` rule onto an integer leaf has bits that every value sets and that no field of the leaf explains
  (`material_instances` `packed_bools` is 4, 5 or 7: bit 2 is in all of them). The rule gets `residualBits`, the component a `findings` entry in
  `mappings.json`, and `coverage.md` lists it under Findings. Bits that the format sweeps (`data/bds/*/spec-format`) see change with
  the `format_version` are explained (Paletter writes the current format); any other is UNEXPLAINED and fails `check-mappings --strict`
  and `update`, until the hand transform names it.
- **Constants**: a constant the model writes is proven by the conformance suite, which reproduces every accepted variant. Values
  of `Transforms.java` that no accepted variant sends are listed in `Coverage.UNREACHED_CONSTANTS` (shown in `coverage.md`), and
  each one's Javadoc says where its value comes from.
- **Hand transforms**: `mappings.json` records per transform `verified: {build, variants}` (accepted variants that set what it compiles; the
  conformance suite reproduces them) or `unverified: <why>`; `coverage.md` lists the unverified ones.

Generation fails, naming component and field, on two mappings for one protocol, name collisions,
two fields deciding one wire leaf, an object without documented fields, an ambiguous union, or a rule at the wrong list
depth.

When a new BDS version changes the wire, `update` runs `spec-capture` and the inference and lists every differing leaf
(`SpecDiff`) as a wire change. A different mapping lands as a new window; then regenerate, review (a changed hand
transform needs a protocol check). `ComponentConformanceTest` runs for the new capture too and has no list of exceptions: every
variant BDS accepted must come out identical, so a form the new build accepts that the API cannot express is fixed in the
model (see "Forms BDS accepts that the docs do not model"), never waived.

## Forms BDS accepts that the docs do not model

BDS wins over the docs. `ComponentConformanceTest` rebuilds every accepted variant through the generated API and compares
the NBT; an add-on JSON the API cannot express, or expresses wrongly, fails the build. The ways to close such a gap, all
data driven:

- **Optional fields** (`api.json` `optional`): a field the docs make required but BDS accepts omitted; the builder applies
  the documented default and BDS's `whenAbsent` is what the encoder writes (the six `min_speed` and `min_relative_speed`
  of `kinetic_weapon`, `item_specific_speeds[].item`).
- **Open enums** (`openEnums`): the field is a string and the javadoc lists the documented values. When the inferred table
  has entries for undocumented samples that all send one value, that value is the `default` of the encoder's switch (BDS
  answers `common` for every unknown `rarity`); undocumented samples that disagree stay cases and the rest is dropped
  with a report warning.
- **A union alternative that is a list** (`unions` `{"array<string>": "bones"}`): `uv_lock` is a boolean or a list of
  bone names (every captured BDS), a second field excluding the first, written by the hand transform.
- **An object that is one entry of the component's list** (`forms` `{"object": {"listElementOf": "boxes"}}`): a `of(Entry)`
  factory and the index entry `"object" -> "boxes[]"`. The javadoc says since which BDS the form is rejected
  (`collision_box` takes `{origin, size}` before 26.60; `{}` is one default box).
- **Hand transforms with protocol checks** for what differs per build (the `block_entity` container: BDS 1.26.52.3 knows
  `slot_count` only; `ammunition[].item`: a descriptor BDS sends as `{name}` or `{}`).

Add-on JSON spellings that mean one API call are mapped by the conformance binder (`ApiBinder`), which reads them from the
generated `ComponentIndex` and is itself checked by the comparison with BDS: an object form of a list (`FORMS`), a
descriptor field (`DESCRIPTORS`: an item or block name) written as `{name}`, as an object without a name or as an empty list
(not set), an empty list for an object (that object with nothing set), and the JSON constants `true` and `false` where a
Molang string is taken.

## Vanilla constants

`./gradlew :tools:generateConstants` (check: `checkConstants`) writes `BedrockBlocks`, `BedrockStates`, `BedrockItems`,
the enums in `vanilla.state` and, while a preview is bundled, `BedrockPreviewBlocks` / `BedrockPreviewItems`. Code:
`tools.codegen.constants`; `update` runs the same code on the data it stages.

Inputs: the newest bundled release in `versions.txt` (and a newer preview, whose extra content goes to the preview
classes); `paletter/.../renames.txt` (the renamed identifiers; names that differ from the default are listed in
`ConstantsGenerator`);
`constants-history.json` (every constant generated so far with `since`, and once deprecated `deprecated` plus
`replacement` or `fallback`; written by every generation, commit it with the classes).

- **Names**: UPPER_SNAKE of the identifier path (`minecraft:item.bed` -> `ITEM_BED`; other namespaces keep theirs as a
  prefix), of the state name for states, enum values from their string. Enums are named after the PascalCase of their
  first state without a trailing direction. Duplicates and non-identifiers fail; the name overrides in `ConstantsGenerator` settle them.
- **Types**: byte states with values 0/1 are `BoolState`, integer states `IntState`, string states `EnumState` of an enum
  shared by every state with the same value set. A state keeps its enum (new values are appended); a state that changes
  its kind fails generation.
- **Arithmetic ids**: `internal.vanilla.BlockLayout` (shared with the runtime) checks that a block's states are the full
  cartesian product of its values and that each state's offset is `sum(value index * stride)`. Other blocks get a
  lookup table at runtime; the generator prints them and writes the reason into the javadoc.
- **Removed content** stays as a deprecated constant: an alias if its identifier exists under another constant or
  `renames.txt` maps it, otherwise it resolves to the `fallback` of its history entry or `minecraft:unknown`. `deprecated`
  is set once.
- **`@since` is a Bedrock version**: the first bundled release that has the content and does not offer it only through
  an experiment (toggles and their content come from `data/bds/<version>/experiments.json`). Content gated in the first
  release that has it is `@since` the next one; the javadoc says "Behind the experiment `x` in 26.40." for gated
  releases. The oldest bundled release cannot say whether something is older. The value is recorded as `bedrock` in the
  history; delete those entries to derive them again after a capture changed.
- **Education Edition**: `data/bds/<version>/education.json`, written by `education-capture`, lists content only an
  education world has. Constants of them carry `@Education`, and `isEducation()` answers at runtime.

`education-capture --version <bds version>` starts one world on that BDS with `educationFeaturesEnabled` off and on,
captures what BDS sends a client both times, and writes the identifiers the education world adds (item registry, block
properties, creative inventory). It fails if the captures do not differ. Content BDS offers to no creative inventory is
not found.
