# API and compatibility policy

Consumers (Geyser is one) describe content in Bedrock terms and never deal with wire encoding. Method-level details are
in the javadoc; this file is the policy.

## What is API

The packages exported by [`module-info.java`](paletter/src/module/java/module-info.java):
`org.geysermc.paletter` (`Paletter`, `Palette`, `PaletteLayer`, `Fallbacks`, `VanillaContent`, reports),
`.annotation`, `.behaviour`, `.block` and `.block.component`, `.item` and `.item.component`, `.creative`, `.property`, `.data`,
`.vanilla` and `.vanilla.state` (the generated constants).

Not API, free to change in any release:

- everything under `org.geysermc.paletter.internal` (generated encoders, compile core, hand transforms);
- public members of exported packages annotated `@Internal`;
- the bundled data format and the tools module.

Types of dependencies that appear in the API (Cloudburst's `NbtMap`, `ItemData`, `BlockDefinition`, `StartGamePacket`)
are part of it: a Cloudburst upgrade that changes them counts as an API change.

`@Experimental` API is public and supported but may change in a minor release without deprecation. Components Bedrock
itself gates behind an experiment carry it.

## Versioning

[Semantic versioning](https://semver.org):

- **patch**: bug fixes, new bundled Bedrock versions, encoder updates for changed wire shapes. No API changes.
- **minor**: additions and deprecations; source and binary compatible.
- **major**: removals of API deprecated before.

The current version is 0.1.0-SNAPSHOT: while the major version is 0, any minor release may also change or remove API
(the policy below applies in full from 1.0.0).

A new Bedrock version is never a reason for a major release: a capture adds a protocol window to
`data/model/mappings.json`, the encoders are regenerated and the component records stay the same.

## Deprecation

1. Deprecate in minor release N with the replacement available, `@Deprecated(since = "...", forRemoval = true)` and a
   javadoc `@deprecated` naming it.
2. Remove no earlier than the next major release and at least two minor releases after N.
3. List every deprecation and removal in the release notes.

Bedrock removing a feature does not remove API: palettes of versions that cannot express a value leave it out and add
a `WARNING` to `Palette#report()`. It is deprecated only if no supported version can express it anymore.

## Vanilla constants

`BedrockBlocks`, `BedrockStates`, `BedrockItems` and the enums of `vanilla.state` follow from Bedrock changing content
between versions:

- Constants are `static final` objects, never compile time constants: consumers hold handles, not ids.
- Additions arrive in minor releases, or in patch releases that bundle a new Bedrock version.
- Removals go through the deprecation cycle: the constant stays, deprecated, and resolves to `minecraft:unknown`;
  a rename keeps the old constant as an alias.
  `data/model/constants-history.json` records every constant ever generated, so regeneration cannot drop one.
- A state constant never changes its type; generation fails until the state gets a new name in the overrides of `ConstantsGenerator`.
- `BedrockPreviewBlocks` and `BedrockPreviewItems` (content of a bundled preview only) are `@Experimental`.

## Identity and equality

- Vanilla keys (the constants) are identity singletons. A key of an identifier Paletter does not know is a value: equal by
  identifier, not interned, never held by Paletter. Compare keys with `equals`; never rely on `==` for a custom key.
  The type of a custom block (`BedrockBlockType.of(CustomBlock)`) is interned per block value, held weakly, and also equal by its states.
- `RegisteredItem`, `RegisteredBlockState` are compared by identity, like Cloudburst definitions: one instance per entry per
  palette, shared by every palette extending it that does not change the entry.
- Lookups that take caller input do not cache it, and an unregistered item is sent as the configured default fallback, never as
  air by one path and `minecraft:unknown` by another.

## Behaviour

`palette.blockBehaviour(...)` and `palette.itemBehaviour(...)` answer what BDS knows about blocks and items but never
sends clients: friction, destroy time, collision shapes, light, max stack size, attack damage. [README](README.md#behaviour)
has the walkthrough; this is the contract.

- **Keys** are the typed constants of `BlockBehaviour` and `ItemBehaviour`: `FloatKey`, `IntKey`, `BooleanKey` and
  `ObjectKey<T>` (`String`, `BlockShape`). A key is a block key or an item key; asking with the wrong kind throws
  `IllegalArgumentException`. New keys arrive in minor releases.
- **Lookups.** `blockBehaviour` takes a runtime id of the palette (hashed or sequential, as the palette sends it), a
  `BedrockBlockState` or a `BedrockBlockType` (its default state); `itemBehaviour` a `BedrockItemType`. Something that is
  not in the palette throws `IllegalArgumentException`. They return a handle (`BlockValues`, `ItemValues`) made once per
  state or item and palette: its `get` of a number or boolean key returns the primitive, unboxed, and reads an array. A
  palette that extends another one returns the other one's handle for a state they share. Vanilla states are shared
  between versions, their values are not: values belong to the palette, not to the state.
- **`get` and `getOrElse`.** `get` returns the value: the user's component, else the vanilla value, else BDS's default
  for custom content. Where no value is known (a key with no source yet, a custom block without the component whose BDS
  default is not captured), `get` throws `IllegalStateException` saying why, and `getOrElse(key, fallback)` returns the
  fallback; it never throws for a missing value. `BlockShape` has value equality (`equals`); shared instances are an
  optimisation, never an identity guarantee.
- **`explain(key)`** returns one line for debugging: the value, the source and its exact reference, the version it was
  captured for, and whether it is user-set, inherited from an older capture, post-processed by its source or disputed
  (with the other values). The text is not a format to parse.
- **Where values come from**, strongest first: a component the user set on custom content (or an override of a vanilla
  item), then Paletter's own captures of BDS (native, Script API, probe) of exactly the asked version, then what BDS sends
  clients, then the newest capture covering the state, a third-party dump (CloudburstMC/Data before Allay) or an older
  version's capture. Direct BDS evidence always beats a third-party dump. Where third-party dumps disagree and no BDS
  evidence settles it, the stronger source's value is returned and `explain` lists the others: nothing is picked silently.
- **Custom content.** Custom blocks, custom items and overridden items are read from the components the palette sends,
  converted to engine units (`minecraft:friction` 0.4 is friction 0.6).
- **Generation.** Vanilla values are data: `behaviour.nbt`, written by `behaviour import` (tools) from BDS captures under
  `data/bds` and third-party dumps read from their git history, with `data/behaviour/REPORT.md` listing which source decides
  each key in each version, the disputes and the known BDS defaults. The generator, the sources and their licences are
  recorded in the file; the same sources give the same bytes. The file format, `BehaviourStore` and the provenance records
  are internal.

## Experiments

Content a Bedrock version has only behind an experiment toggle is missing by default (items unregistered, block states
resolve through the fallbacks); `Paletter.builder().vanilla(v -> v.experiments(...))` enables it. The constants' javadoc
carries `@since <Bedrock release>`, the experiment, and `@Education` for Education Edition only content.

## Format versions

The wire has no add-on `format_version`: Bedrock only sees the component data of the palette's protocol. Paletter emits each
palette's own protocol shape, whatever format version a component was documented for, so components can be mixed freely
(`@BedrockFormat` is informational). What a protocol cannot express is left out with a report warning; a component BDS
accepts but never sends carries `@ServerOnly` with the protocol range.

## Hand-written wire-only components

Four wire fields have no add-on JSON source, so they are not generated: `RenderOffsets` and `OnPlayerPlacing` (retired
from the add-on format, deprecated but not for removal; Paletter assumes clients still honour them), `MiningSpeed` and
`FrameCount` (`@Experimental`). They are sealed `WireOnlyItemComponent` / `WireOnlyBlockComponent` implementations and
take the same path as generated components (`CustomItem`, item overrides, compile). Their shapes come from captures of
BDS defaults, not from the conformance suite, so a client rejecting one would not show up in `build`. Details in their
javadoc.

## How it is enforced

`./gradlew build` runs:

- `:paletter:apiCheck`: the committed dump [`paletter/api/paletter.api`](paletter/api/paletter.api) (a sorted listing of
  every public and protected member of the exported packages, `@Internal` left out) must equal what the compiled
  classes give. Run `:paletter:apiDump` with any API change and commit the file: its diff is the API review (a removed
  line is a break).
- `:paletter:apiCompat`: japicmp against the last released artifact named in `paletter/api/released.version`
  (skipped while the file is absent or empty; write the version into it after each release).
- `:paletter:compileModuleInfo`: every exported package exists.
- `ApiBoundaryTest`: no exported signature references `internal`, an `@Internal` type or a non exported package, and
  every exported type has `@since`.

The jar declares `Automatic-Module-Name: org.geysermc.paletter`; `module-info.java` documents the boundary but is not
packaged, because Cloudburst's dependencies split packages over several jars.
