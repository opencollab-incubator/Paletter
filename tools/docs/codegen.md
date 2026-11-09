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
| `transforms.json` | hand | fields or whole components (path `""`) compiled by a named hand transform, with why |
| `api.json` | hand | Java shaping only: names, union resolution, value forms, descriptors, `keyedExtras`, open enums, `since` |

Output: a record per component in `item.component` / `block.component` (nested records, enums, builder, `of(...)`
factories, vanilla-constant overloads), the sealed `ItemComponent` / `BlockComponent` / `BlockTrait`, and the encoders
`ItemWire`, `BlockWire`, `TraitWire`, `ComponentIndex` in `internal.component`. The compile core (`ItemCompiler`,
`BlockCompiler`, `TraitStates`, `WireNode`, ...) and `Transforms` are hand-written; the transform names in
`transforms.json` are `Transforms`' public static methods, which the generator reads from its source.

Generated components are those BDS sends or that only failed for want of a companion; server-only and rejected ones
are listed in the interfaces' javadoc.

Links and annotations, all from the data:

- **`@see` links**: the Microsoft Learn page of a component is its `components.json` `page` below `creator/`, lowercased,
  without `.md`, plus `?view=minecraft-bedrock-stable` (`Docs.learnUrl`). Records link their page, the sealed interfaces
  the list pages (`lists` in `components.json`, written by `docs`) and every omitted component its page. The hand-written
  wire-only component with a docs page (`RenderOffsets`) carries the same link by hand; `GeneratedMetadataTest` checks it.
- **`@ServerOnly(since, until)`**: on a record with a protocol window whose status is `server-only` (the bounds are the
  window's). The javadoc names the BDS versions captured in the range and the one that sends it. One range per component
  (generation fails otherwise). Components server only in every protocol are not generated, only listed.
- **`@Deprecated @ReplacedBy({X.class})`** (`Replacements`): for a component the docs mark `deprecated` or `legacy`, or
  that BDS rejects, the successors come from the first of: a sentence "Use minecraft:x ..." in its docs text, docs notes or
  BDS's own message (`mappings.json` `note`); a "renamed to/from" docs note; an active component with a top level field
  of its name. Otherwise a `fixtures.json` `replacedBy` entry (`by` and a required `evidence`; an entry the data derives
  already fails generation). The annotations land on generated records only; for components that are not generated
  (rejected ones are not) the replacement and its evidence appear in the interface's "Documented but not generated" list.

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
- **Hand transforms per protocol**: a `transforms.json` entry may list `windows` (`{"until": 2225, "transform": "a"},
  {"since": 2225, "transform": "b"}`, gap free, ending with the entry's own transform).

Generation fails, naming component and field, on an unknown transform, two mappings for one protocol, name collisions,
two fields deciding one wire leaf, an object without documented fields, an ambiguous union, or a rule at the wrong list
depth.

When a new BDS version changes the wire, `update` runs `spec-capture` and the inference and lists every differing leaf
(`SpecDiff`) as a wire change. A different mapping lands as a new window; then regenerate, review (a changed hand
transform needs a window), and write `conformance-known-<protocol>.txt` for the new capture (`-PupdateGolden=true`, then
the reasons).

## Vanilla constants

`./gradlew :tools:generateConstants` (check: `checkConstants`) writes `BedrockBlocks`, `BedrockStates`, `BedrockItems`,
the enums in `vanilla.state` and, while a preview is bundled, `BedrockPreviewBlocks` / `BedrockPreviewItems`. Code:
`tools.codegen.constants`; `update` runs the same code on the data it stages.

Inputs: the newest bundled release in `versions.txt` (and a newer preview, whose extra content goes to the preview
classes); `names.json` (names that differ from the default, what removed content becomes);
`constants-history.json` (every constant generated so far with `since`, and once deprecated `deprecated` plus
`replacement` or `fallback`; written by every generation, commit it with the classes).

- **Names**: UPPER_SNAKE of the identifier path (`minecraft:item.bed` -> `ITEM_BED`; other namespaces keep theirs as a
  prefix), of the state name for states, enum values from their string. Enums are named after the PascalCase of their
  first state without a trailing direction. Duplicates and non-identifiers fail; `names.json` settles them.
- **Types**: byte states with values 0/1 are `BoolState`, integer states `IntState`, string states `EnumState` of an enum
  shared by every state with the same value set. A state keeps its enum (new values are appended); a state that changes
  its kind fails generation.
- **Arithmetic ids**: `internal.vanilla.BlockLayout` (shared with the runtime) checks that a block's states are the full
  cartesian product of its values and that each state's offset is `sum(value index * stride)`. Other blocks get a
  lookup table at runtime; the generator prints them and writes the reason into the javadoc.
- **Removed content** stays as a deprecated constant: an alias if its identifier exists under another constant or
  `names.json` `renamed` maps it, otherwise it resolves to `names.json` `fallbacks` or `minecraft:unknown`. `deprecated`
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
