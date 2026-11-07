# Paletter API and compatibility policy

Paletter is consumed by Geyser and by Bedrock-only server software. Consumers describe content in Bedrock terms and
never deal with the wire encoding: NBT shapes, key names, numeric quirks and their changes between Bedrock versions are
Paletter's job alone. This document says what the API is, what Paletter promises about it, and how the promise is
enforced.

## What is API

The API is exactly the packages exported by [`paletter/src/module/java/module-info.java`](paletter/src/module/java/module-info.java):

| Package | Contents |
|---|---|
| `org.geysermc.paletter` | `Paletter`, `Palette`, `PaletteLayer`, `PaletteReport`, `PaletteException` |
| `org.geysermc.paletter.annotation` | `@Experimental`, `@Internal` |
| `org.geysermc.paletter.block` | palettes, canonical ids, `CustomBlock`, `BlockComponents`, `BlockProperty`, ... |
| `org.geysermc.paletter.block.component` | semantic block values: `Box`, `Face`, `Geometry`, `Material`, `PlacementFilter`, `Transformation`, ... |
| `org.geysermc.paletter.creative` | the creative inventory model and editor |
| `org.geysermc.paletter.data` | `DataVersion`, data sources, bundled vanilla data model |
| `org.geysermc.paletter.item` | palettes, canonical ids, `CustomItem`, `ItemComponents`, `ItemOverride`, ... |
| `org.geysermc.paletter.item.component` | semantic item values: `Durability`, `KineticWeapon`, `Food`, `Wearable`, `Color`, ... |

Not API, and free to change in any release:

- everything under `org.geysermc.paletter.internal` (the encoders in `internal.encode`, the generated wire shapes in
  `internal.wire`);
- public types and members of exported packages annotated **`@Internal`** (plumbing shared between Paletter's own
  packages, e.g. `ItemPalette.Builder`, `BlockPalette#withCustomBlocks`, `PaletteDataCodec`);
- the bundled data format, the generator, codegen and capture modules.

Types of dependencies that appear in the API (Cloudburst protocol types such as `NbtMap`, `ItemData`,
`BlockDefinition`, `StartGamePacket`) are part of it; a Cloudburst upgrade that changes them is treated like a change of
Paletter's API.

## Versioning

Paletter follows [semantic versioning](https://semver.org) for the API:

- **patch** (`2.0.x`): bug fixes, new bundled Bedrock versions, encoder updates for changed wire shapes. No API changes.
- **minor** (`2.x.0`): additions (types, methods, enum constants, semantic values), deprecations. Source and binary
  compatible for consumers.
- **major** (`x.0.0`): removals and incompatible changes, only of API deprecated as described below.

A new Bedrock version is never a reason for a major release. When Bedrock changes how a component is sent, Paletter
adds a spec entry for the new shape, regenerates its wire types and maps the same semantic value to it for palettes of
the new version (see "Versioned encoding" in the [README](README.md#components)). Consumer code does not change.

## Deprecation cycle

1. Deprecate in minor release N, with the replacement available in the same release:
   `@Deprecated(since = "2.N.0", forRemoval = true)` plus a javadoc `@deprecated` naming the replacement.
2. Remove no earlier than the next major release **and** at least two minor releases after N (deprecated in 2.3, it
   stays through at least 2.5 and is removed in 3.0 at the earliest).
3. Every deprecation and removal is listed in the release notes.

Bedrock removing a feature does not remove API: the semantic method stays, and palettes of versions that cannot express
it leave the value out with a `PaletteReport` warning (see below). It is deprecated only if no supported version can
express it anymore.

## `@Experimental`

Experimental API is public and supported, but not covered by the compatibility promise: it may change or disappear in
a minor release without a deprecation cycle (changes are still listed in the release notes). Two kinds of API carry it:

- **The raw NBT escape hatches**: `ItemComponents.Builder#raw`, `#rawProperty`, `BlockComponents.Builder#raw` and
  their getters. **With them you own the wire format.** Paletter sends the NBT as given to every Bedrock version and
  does not adapt it when the component's network shape changes. Use them only for components without a semantic method,
  and ask for one.
- **Semantic API whose network behaviour is not verified** against a vanilla server: currently `renderOffsets` (no
  longer an add-on component in 1.26) and `BlockComponents.Builder#onPlayerPlacing` (rejected by 1.26 add-on JSON). They
  lose the annotation, without source changes, once verified, or are deprecated if the client stops reading them.

## What a palette cannot express

Encoding never fails because of a Bedrock version. If a semantic value cannot be expressed for a palette's version (the
component does not exist there, or tags were put on a block permutation), the value is left out of that palette and a
`WARNING` is added to `Palette#report()`, naming the layer, the item or block and the component. Consumers decide what to
log.

## How compatibility is enforced

`./gradlew build` (via `check`) runs:

- **`:paletter:apiCompat`**: [japicmp](https://github.com/siom79/japicmp) compares the exported packages of the current
  jar with the baseline and fails on any binary incompatible change. `@Internal` and `@Experimental` API is excluded. The
  report is `paletter/build/reports/japicmp/api-compat.txt`.
- **`:paletter:compileModuleInfo`**: compiles `module-info.java` against the main classes, so every exported package
  exists.
- **`ApiBoundaryTest`**: fails if a public or protected signature of an exported type (supertypes, fields, methods,
  constructors, record components, type parameters) references a type of `org.geysermc.paletter.internal`, an
  `@Internal` type or a non-exported Paletter package; and if an exported type has no `@since` in its javadoc.

### The baseline

[`paletter/api/baseline.version`](paletter/api/baseline.version) names the API version the current code must stay
compatible with; `paletter/api/paletter-<version>-api.jar` holds the exported classes of that version. There is no
release yet, so the baseline `2.0.0` was generated from the `stable-api` branch. Workflow:

- **Release** `2.N.0`: after tagging, write the released API as the new baseline:
  `./gradlew :paletter:updateApiBaseline`, after setting `baseline.version` to the released version (and deleting the old
  baseline jar), and commit both.
- **Intended break** (major release only, after the deprecation cycle): bump the major version, then update the baseline
  the same way in the same change.
- Never update the baseline to make `apiCompat` pass within a minor line.

### `@since`

Every exported type has `@since` in its javadoc (enforced by `ApiBoundaryTest`); members added later carry their own
`@since 2.N.0`. Members without one have been there since their type.

### JPMS

The jar declares `Automatic-Module-Name: org.geysermc.paletter`. `module-info.java` is the declaration of the API
boundary but is not packaged yet: Cloudburst's dependencies split packages over several jars (fastutil's
`it.unimi.dsi.fastutil.ints` and `...objects` live in a dozen automatic modules), so no module graph containing them
resolves on the module path. It ships once the dependencies are modular.
