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
| `org.geysermc.paletter.annotation` | `@Experimental`, `@Internal`, `@BedrockFormat` |
| `org.geysermc.paletter.block` | palettes, canonical ids, `CustomBlock`, `BlockProperty`, ... |
| `org.geysermc.paletter.block.component` | generated block components and traits (`BlockComponent`, `BlockTrait`, `Geometry`, `MaterialInstances`, `PlacementDirection`, `MenuCategory`, ...) |
| `org.geysermc.paletter.creative` | the creative inventory model and editor |
| `org.geysermc.paletter.data` | `DataVersion`, data sources, bundled vanilla data model |
| `org.geysermc.paletter.item` | palettes, canonical ids, `CustomItem`, `ItemOverride`, ... |
| `org.geysermc.paletter.item.component` | generated item components (`ItemComponent`, `Durability`, `KineticWeapon`, `Food`, `Wearable`, `MenuCategory`, ...) |
| `org.geysermc.paletter.vanilla` | vanilla constants (generated `BedrockBlocks`, `BedrockStates`, `BedrockItems`) and their types: `BedrockBlockType`, `BedrockBlockState`, `BedrockItemType`, `BedrockItemVariant`, `BoolState`, `IntState`, `EnumState`, `StringState` |
| `org.geysermc.paletter.vanilla.state` | generated enums of string block states (`CardinalDirection`, `Direction`, ...) |

Not API, and free to change in any release:

- everything under `org.geysermc.paletter.internal` (the generated encoders, the compile core and the hand transforms in
  `internal.component`);
- public types and members of exported packages annotated **`@Internal`** (plumbing shared between Paletter's own
  packages, e.g. `ItemPalette.Builder`, `BlockPalette#withCustomBlocks`, `PaletteDataCodec`);
- the bundled data format, the tools module.

Types of dependencies that appear in the API (Cloudburst protocol types such as `NbtMap`, `ItemData`,
`BlockDefinition`, `StartGamePacket`) are part of it; a Cloudburst upgrade that changes them is treated like a change of
Paletter's API.

## Versioning

Paletter follows [semantic versioning](https://semver.org) for the API:

- **patch** (`2.0.x`): bug fixes, new bundled Bedrock versions, encoder updates for changed wire shapes. No API changes.
- **minor** (`2.x.0`): additions (types, methods, enum constants, semantic values), deprecations. Source and binary
  compatible for consumers.
- **major** (`x.0.0`): removals and incompatible changes, only of API deprecated as described below.

A new Bedrock version is never a reason for a major release. When Bedrock changes how a component is sent, a capture
of the new version adds a protocol window to `data/model/mappings.json` and the encoders are regenerated; the
component records stay the same (see "Versioned encoding" in the [README](README.md#components)). Consumer code does not
change. New documented components and fields are additions.

## Deprecation cycle

1. Deprecate in minor release N, with the replacement available in the same release:
   `@Deprecated(since = "2.N.0", forRemoval = true)` plus a javadoc `@deprecated` naming the replacement.
2. Remove no earlier than the next major release **and** at least two minor releases after N (deprecated in 2.3, it
   stays through at least 2.5 and is removed in 3.0 at the earliest).
3. Every deprecation and removal is listed in the release notes.

Bedrock removing a feature does not remove API: the generated record or field stays, and palettes of versions that
cannot express it leave the value out with a `PaletteReport` warning (see below). It is deprecated only if no supported
version can express it anymore.

## Vanilla constants

The generated classes of `org.geysermc.paletter.vanilla` (`BedrockBlocks`, `BedrockStates`, `BedrockItems`, the enums of
`org.geysermc.paletter.vanilla.state`) are API like any other, with rules that follow from Bedrock changing content
between versions (see the [README](README.md#vanilla-constants) for usage):

- Constants are `static final` **objects**, never compile time constants: consumers do not inline canonical ids, so a
  Paletter release may renumber them freely.
- **Additions** (new constants, enum values, enums) are binary compatible and arrive in minor releases, or in patch
  releases that bundle a new Bedrock version.
- **Removals** go through the deprecation cycle: when the newest bundled data no longer has a block, item, state or
  enum value, the constant stays, `@Deprecated(since = "<the release>", forRemoval = true)`, and resolves to its
  fallback (`minecraft:unknown` unless `data/model/names.json` names another). A rename keeps the old constant as a
  deprecated alias of the new one. `data/model/constants-history.json` is the record of every constant the generator ever
  produced, so a regeneration cannot drop one; deprecated constants are removed from it (and the classes) only in a
  major release.
- A state constant never changes its type (`BoolState`, `IntState`, `EnumState<E>`): if Bedrock changes a state's kind,
  generation fails until the state gets a new name in `data/model/names.json`. Enum assignment is sticky the same way.
- `BedrockPreviewBlocks` and `BedrockPreviewItems` (content of a bundled preview only) are `@Experimental`: they move or
  disappear without deprecation.
- Which values a block accepts, and the canonical ids, are data, not API: they follow the bundled release.

## `@Experimental`

Experimental API is public and supported, but not covered by the compatibility promise: it may change or disappear in
a minor release without a deprecation cycle (changes are still listed in the release notes). The generated components
Bedrock gates behind an experiment (e.g. `minecraft:support`, `minecraft:connection_rule`, the `minecraft:connection`
and `minecraft:multi_block` traits) carry it: Bedrock may still change them before releasing them, and Paletter follows.

## Hand-written wire-only item components

Three wire fields have no add-on JSON source, so they are not generated. They are hand-written `WireOnlyItemComponent`s
(a sealed `ItemComponent` sub-interface, `@since 1.0.0`) that go through the same `CustomItem`, `PaletteLayer` item
override and compile path as the generated components:

- `RenderOffsets` (`minecraft:render_offsets`, `@Deprecated` but not for removal): retired from the add-on format (BDS
  1.26.63.1 rejects it at every `format_version`); Paletter assumes current clients still honour it on the wire and keeps
  sending it for legacy content. It will be removed if clients stop honouring it. `new RenderOffsets(Hand mainHand, Hand offHand)`,
  `Hand(Offset firstPerson, Offset thirdPerson)`, `Offset(Vec3 position, Vec3 rotation, Vec3 scale)` (`Offset.scale(x, y, z)`),
  `Vec3(float x, float y, float z)`; null parts are left out.
- `MiningSpeed.of(float)` (`item_properties.mining_speed`, `@Experimental`, BDS default 1.0).
- `FrameCount.of(int)` (`item_properties.frame_count`, `@Experimental`, BDS default 1, at least 1).

Their `name()` is the wire path (`item_properties.mining_speed`, ...), so the one-per-name rule of `CustomItem` holds.

## What a palette cannot express

Encoding never fails because of a Bedrock version. If a value cannot be expressed for a palette's version (the field is
not sent in that protocol window, it has no known wire mapping, or a descriptor names an item the version lacks), it is
left out of that palette and a `WARNING` is added to `Palette#report()`, naming the layer, the item or block and the
component. Consumers decide what to log.

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
release yet, so the baseline `1.0.0` was generated from the `stable-api` branch. Workflow:

- **Release** `2.N.0`: after tagging, write the released API as the new baseline:
  `./gradlew :paletter:updateApiBaseline`, after setting `baseline.version` to the released version (and deleting the old
  baseline jar), and commit both.
- **Intended break** (major release only, after the deprecation cycle): bump the major version, then update the baseline
  the same way in the same change.
- Never update the baseline to make `apiCompat` pass within a minor line.

The baseline was regenerated once since, when the vanilla constants were added (branch `constants`): `apiCompat`
passed against the previous baseline first (no incompatible change, only additions: the `vanilla` packages, the
constant overloads of `Palette`, `PaletteLayer.Builder`, `ItemMatcher`, `CreativePlacement`, `BlockDescriptor`,
`PlacementFilter`, `Repairable.Entry`, `BlockFallback`, `ItemFallback`, `CustomBlock#type`, `CustomItem#type`,
`CanonicalItems#extendsOrIs`, and the `FallbackDefaults` API of the update pipeline), so the new baseline covers the
new types too, including every generated constant: removing one now fails `apiCompat`.

Additions since the baseline (`apiCompat` passes, baseline not regenerated): `BedrockItemType#hasVariant` / `#variantOf`, and the
debugging aids `Palette#explain` with `BlockExplanation` and `ItemExplanation` (both `@Experimental`: their shape may still grow), and the wire-only item components (`WireOnlyItemComponent`, `RenderOffsets`, `MiningSpeed`, `FrameCount`; `ItemComponent` gains the permitted sub-interface, so code that switches over it exhaustively needs a case for it).

### Pre-release changes

1.0.0 is not released, so these API changes replaced the old API directly, without a deprecation cycle (which applies
from the first release on); the baseline was regenerated with each:

- `BedrockBlockState#id()`, `BedrockItemType#id()` and `BedrockItemVariant#id()` are removed. They returned the canonical
  id in `Paletter.bundled()` and -1 for custom content, a trap for code that handles both. Canonical ids come only from
  `Paletter#canonicalBlock(BedrockBlockState)` / `#canonicalItem(BedrockItemType|BedrockItemVariant)` (vanilla content;
  for vanilla constants still an addition or field read, no hash lookup) and `Palette#canonicalBlock` /
  `#canonicalItem` (any content, custom included).

- `BedrockItemType#variant(int)` returns null for a damage value that is not a known variant of the newest data (it used
  to return a new variant without canonical id). `hasVariant(int)` and `variantOf(int)` (the old behaviour) are added.
  Binary compatible, so the baseline was not regenerated.

- The hand-written semantic component API (`ItemComponents`, `BlockComponents` and the values in `item.component` /
  `block.component`, including the `@Experimental` raw NBT escape hatches) is replaced by the components generated from
  the add-on component model (see the [README](README.md#components)); `CustomBlock.Menu` by the generated
  `MenuCategory`, `ItemOverride#components(UnaryOperator)` by `ItemOverride#components(ItemComponent...)`. The generated
  API carries `@since 1.0.0`.

### `@since`

Every exported type has `@since` in its javadoc (enforced by `ApiBoundaryTest`); members added later carry their own
`@since 2.N.0`. Members without one have been there since their type.

### JPMS

The jar declares `Automatic-Module-Name: org.geysermc.paletter`. `module-info.java` is the declaration of the API
boundary but is not packaged yet: Cloudburst's dependencies split packages over several jars (fastutil's
`it.unimi.dsi.fastutil.ints` and `...objects` live in a dozen automatic modules), so no module graph containing them
resolves on the module path. It ships once the dependencies are modular.
