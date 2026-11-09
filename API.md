# API and compatibility policy

Consumers (Geyser is one) describe content in Bedrock terms and never deal with wire encoding. Method-level details are
in the javadoc; this file is the policy.

## What is API

The packages exported by [`module-info.java`](paletter/src/module/java/module-info.java):
`org.geysermc.paletter` (`Paletter`, `Palette`, `PaletteLayer`, `Fallbacks`, `VanillaContent`, reports),
`.annotation`, `.block` and `.block.component`, `.item` and `.item.component`, `.creative`, `.property`, `.data`,
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
- Removals go through the deprecation cycle: the constant stays, deprecated, and resolves to its fallback
  (`minecraft:unknown` unless `data/model/names.json` names another); a rename keeps the old constant as an alias.
  `data/model/constants-history.json` records every constant ever generated, so regeneration cannot drop one.
- A state constant never changes its type; generation fails until the state gets a new name in `names.json`.
- `BedrockPreviewBlocks` and `BedrockPreviewItems` (content of a bundled preview only) are `@Experimental`.

## Experiments

Content a Bedrock version has only behind an experiment toggle is missing by default (items unregistered, block states
resolve through the fallbacks); `Paletter.builder().vanilla(v -> v.experiments(...))` enables it. The constants' javadoc
carries `@since <Bedrock release>`, the experiment, and `@Education` for Education Edition only content.

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
