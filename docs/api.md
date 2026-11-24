# Using Paletter

This page goes deeper than the [README](../README.md). Method-level details are in the
[Javadoc](https://opencollab-incubator.github.io/Paletter/latest/).

## Installation

`dev.opencollab.paletter:paletter`, and `dev.opencollab.paletter:paletter-recipes` for the vanilla recipes, from the
repository `https://repo.opencollab.dev/main` (it also serves the Cloudburst protocol builds Paletter depends on). Only
`1.0.0-SNAPSHOT` is published so far ([releasing.md](releasing.md)).

```kotlin
repositories {
    maven("https://repo.opencollab.dev/main")
}

dependencies {
    implementation("dev.opencollab.paletter:paletter:1.0.0-SNAPSHOT")
    implementation("dev.opencollab.paletter:paletter-recipes:1.0.0-SNAPSHOT") // only for the recipes
}
```

```xml
<repository>
    <id>opencollab</id>
    <url>https://repo.opencollab.dev/main</url>
</repository>

<dependency>
    <groupId>dev.opencollab.paletter</groupId>
    <artifactId>paletter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## What is API

The API is the packages exported by
[`module-info.java`](../paletter/src/module/java/module-info.java): `dev.opencollab.paletter` and its `annotation`,
`behaviour`, `block`, `block.component`, `creative`, `data`, `item`, `item.component`, `property`, `vanilla` and
`vanilla.state` packages. Cloudburst types that appear in signatures (`NbtMap`, `ItemData`, `StartGamePacket`, ...) are
part of it too.

Simple names are unique across the public API (a test fails on a duplicate). When two types would share one, the generated
one gets a `Block` or `Item` prefix: `BlockDisplayName`/`ItemDisplayName`, `BlockMenuCategory`/`ItemMenuCategory`. The
generator finds those names itself, and never renames an existing type for a new one: the newcomer gets the prefix
([Evolution](#evolution)).

Not API:

- anything under `dev.opencollab.paletter.internal`;
- public members marked `@Internal` (plumbing that has to be public, such as the `BlockValues` constructor);
- the bundled data format and the `tools` module.

`@Experimental` means only that the API itself is unstable. `@BedrockExperiment("<toggle>")` is separate: it marks a
component (or field) Bedrock only accepts with that experiment toggle enabled, as the captured servers call the toggle; the
ids are the constants of `BedrockExperiments` (`@BedrockSince`/`@BedrockUntil` give the bundled data versions whose captures have the
toggle current), and `v.experiments(BedrockExperiments.UPCOMING_CREATOR_FEATURES)` enables one. `@Experimental` marks
vanilla item overrides (`PaletteLayer.Builder#overrideItem`, `ItemOverride`): BDS replaced the components of
an overridden data driven item (apple), accepted the hard coded feather at format 1.10 as a legacy item and rejected the
stick at 1.26.60. The client's behaviour for Paletter's overrides is unverified on a client (see
[wire-notes](wire-notes.md#overriding-vanilla-items)). `replaceComponents` has BDS's shape, `modifyComponents` merges.

Paletter is only published as `1.0.0-SNAPSHOT` so far, so any build may change or remove API. The build keeps API
changes visible:

- `:paletter:apiCheck` compares the compiled API with the committed dump
  [`paletter/api/paletter.api`](../paletter/api/paletter.api). After an intended change, run `:paletter:apiDump` and
  commit the file.
- `:paletter:apiCompat` runs japicmp against the release named in `paletter/api/released.version`. The file does not
  exist yet, so the check is skipped.
- `ApiBoundaryTest` checks that no exported signature uses an internal type, that every exported type has `@since`, and
  that every public member of the hand-written API has one too (a member added later names its own release).

The jar declares `Automatic-Module-Name: dev.opencollab.paletter`. `module-info.java` is compiled to check the exports but
is not packaged, because Cloudburst's dependencies split packages across jars.

## Evolution

One rule: **what Bedrock defines is open, and what Paletter defines is closed.** A Bedrock update should cost a user
deprecation warnings, not compile errors. The API is shaped so that the compiler holds users to that:

- **Components and recipes are final classes, not records.** A record's canonical constructor is public and its
  components are its deconstruction pattern, so a field Bedrock adds would break both. The generated components, their
  objects, the hand-written wire-only components and the recipes (`Recipe.*`, `Result`, `Unlock`) have a private
  constructor, a `builder()` (or `of(...)`), record-style accessors, `toBuilder()` and value `equals`/`hashCode`/`toString`.
  A new field is a new accessor and builder setter. Records stay only where the shape is fixed by definition, not by
  Bedrock: the vectors (`Vec3`, with `Vec3.of`), `BlockShape.Box`, `PotionMix`, `ContainerMix`, the
  one-value forms of a union and the `Ingredient` forms.
- **Documented string values are open value classes, not enums.** `Wearable.Slot`, `ItemMenuCategory.Category` and
  every other generated one have a constant per documented value, `of(String)` for any value (a known one returns its constant, so `==`
  works for those), `value()`, `isKnown()` and `known()`. A value Bedrock adds can be built and sent before Paletter has a
  constant for it; Paletter's encoder leaves a value it has no wire mapping for out, with a report warning. There are no
  switch labels: switch over `value()` with a `default`.
- **Sealed types Bedrock adds variants to need a `default`.** `ItemComponent`, `BlockComponent`, `BlockTrait`,
  `WireOnlyItemComponent`, `WireOnlyBlockComponent`, every generated union (`UvLock`, `ColorValue`, `ItemsOrTags`, ...),
  `Recipe`, `Ingredient` and `CreativeNode` each permit a class that is not public and has no instances
  (`FutureItemComponent`, ...). No `case` can name it, so `javac` rejects a `switch` over one of them without a `default`;
  a new variant in a minor release then lands in that `default` instead of failing the build or throwing
  `MatchException`. Pattern matching and `instanceof` work as before. The types Paletter defines (`ItemKey`,
  `BedrockState`, `ItemMatcher`, `CreativePlacement`, ...) stay plainly sealed: they are closed by definition.
- **Generated names are locked.** `data/model/api-history.json` records every Java name of the component API by a stable
  identity (component id, JSON path, value). A recorded name is reused, never recomputed: a restructure of the creator
  docs or a new component renames nothing, and a new name collision gives the `Block`/`Item` prefix to the newcomer. A
  field whose add-on JSON key Bedrock renames keeps its old accessor and builder setter as `@Deprecated` delegates.
  The Java type and nullability of a recorded field are locked too: a change fails generation until it is undone or,
  in a major release, recorded deliberately. A documented value that leaves the docs keeps its constant in the value
  class, `@Deprecated` and out of `known()`. Vanilla constants have the same in `constants-history.json`: a removed one stays, deprecated.
- **Removed components and fields stay as stand-ins.** A field Bedrock removes keeps a `@Deprecated(forRemoval = true)`
  accessor that returns its default and a builder setter that does nothing; a removed component keeps a
  `@Deprecated(forRemoval = true)` class of the same shape, which every palette leaves out with a report warning. They go
  only in a major release, when their `api-history.json` entries are deleted.
- **Vanilla state enums stay Java enums** (`vanilla.state`): they are read and switched, and the bundled data always
  matches them. They gain constants in minor releases, so a switch over one needs a `default`.
  `BedrockBlockState#raw(BedrockState)` reads a state's value as Bedrock sends it and never throws.
  Paletter's own enums (`Lookup`, `PaletteReport.Level`, `CreativeCategory`, whose ids are fixed by the protocol) are closed.

Tests hold the generator to this: `ApiEvolutionTest` (no public record in the component packages but the vectors and the
union forms, no enum of documented values, a sentinel on every sealed type Bedrock extends), `RecipesEvolutionTest` (the
same for `Recipe` and `Ingredient`) and `ApiHistoryTest` (the committed `api-history.json` loses no entry, renames keep
their delegates over later runs, and stand-ins compile).

### Builders

Every builder, generated or hand-written (`Recipe.*`, `Result`), follows the same rules:

- A setter of a `@NonNull` field throws `NullPointerException` at once when given null. A setter of a `@Nullable` field
  takes null to unset it, and `setter(null)` never needs a cast: a nullable field has no other overload of one reference
  parameter, now or in a later release. Its other forms are set through the value type's factories
  (`uvLock(UvLock.bones(bones))`, `all(InstanceOrAlias.define(instance))`, `usingConvertsTo(BedrockItems.BOWL.identifier())`); overloads of a primitive
  (`uvLock(true)`) or of several parameters (`uvLock("a", "b")`) stay.
- `build()` throws `IllegalStateException` ("X is required") for a required field that was never set, and
  `IllegalArgumentException` for a value out of range.
- A list field has a `List` setter and a varargs setter of at least one value (`first, rest...`). `setter(null)` and
  `setter(List.of())` therefore always mean the `List` overload: no cast is needed to clear an optional list after
  `toBuilder()`.

### The promise

From 1.0, a minor release is binary and source compatible, with two exceptions that users handle with a `default`, which
the compiler enforces for the sealed types: a new variant of a sealed type Bedrock extends, and a new constant of a
vanilla state enum. API marked `@SincePreview`, `@Experimental` or `@Internal` is outside the promise. Once
`paletter/api/released.version` names a release, `:paletter:apiCompat` (japicmp) checks it with the same exclusions.

### Cloudburst types

Paletter is built on Cloudburst's protocol library (`bedrock-codec`, `common`, with its `nbt`), an `api` dependency:
its types are in Paletter's signatures (`NbtMap`, `ItemData`, packets) and in its hierarchy (`RegisteredBlockState`
is a `BlockDefinition`, `RegisteredItem` an `ItemDefinition`, the palette's registries are `DefinitionRegistry`s, so
`Palette#applyTo(BedrockCodecHelper)` can hand them to the codec). The rule:

- Paletter's promise covers Paletter's types. The Cloudburst types keep Cloudburst's own compatibility, which for the
  `3.0.0.Beta` snapshots Paletter depends on is none.
- Each Paletter release names one Cloudburst build, the one Geyser uses (`gradle/libs.versions.toml`); a consumer gets it
  transitively and should not pick another.
- A minor Paletter release moves to a newer Cloudburst build only when the Cloudburst types in Paletter's signatures
  stay binary compatible. A change that breaks them goes into a major release, or, while Cloudburst has no release,
  into a minor one named in its release notes.
- To keep that surface small, Paletter implements Cloudburst interfaces only where the codec needs its objects
  (definitions and registries), and hands out finished, immutable Cloudburst values (`ResolvedItem#toItemData()`
  returns an `ItemData`, not a builder; `toBuilder()` changes it).

No Paletter release is made while the dependency is a snapshot ([releasing.md](releasing.md)).

### Lifecycle of versioned content

Annotations describe where Bedrock has something; `@Deprecated` and the Paletter version describe the Java API.

| Event | Java API | Runtime |
|---|---|---|
| Content appears in a preview | `@SincePreview("26.70")`, outside the promise | served only with `previews(true)` |
| It ships in a release | `@SincePreview` goes, `@BedrockSince("26.70")` where older bundled versions lack it (a vanilla constant: the Javadoc line `First in Bedrock 26.70`) | served from 26.70 |
| Bedrock removes or renames it | `@Deprecated` and `@BedrockUntil("26.90")` (a renamed field: a delegate, a renamed component: `@ReplacedBy`; a vanilla constant: `@Deprecated` only, a renamed one an alias) | older palettes still serve it; newer ones leave it out (or resolve a constant through the fallbacks) with a report warning |
| It leaves the component model (no bundled version has it) | `@Deprecated(forRemoval = true)` stand-in: a field returns its default and ignores its setter, a component keeps its class with such fields | every palette leaves a stand-in component out with a report warning |
| The last bundled version that has it is retired | `@Deprecated(forRemoval = true)` | as above |
| Next major Paletter release | removed | - |

`@BedrockUntil` annotates the experiment constants, components (generated for a component BDS rejects from a bundled
version on) and members. The block and item constants carry neither annotation: their Javadoc names the first Bedrock
release, and `@Deprecated(since)` the Paletter version that saw them go. Setting `forRemoval` when the last bundled version that has the content is retired is a follow-up: a `tools
retire` command will do it, so users get a `javac` warning at every use well before the major release. Until then it is
set by hand.

## Loading

`Paletter.builder()...build()` compiles the palette of every served version, in parallel, before it returns: the vanilla
data, the voxel shapes, the canonical, item, creative and resolution tables, the keys of the blocks a preview version adds or changes, and
the behaviour values with every behaviour column (unless `behaviour(false)`; the values are generated classes, loaded the
first time a Paletter with behaviour builds, and never loaded with `behaviour(false)`). After that no lookup on those palettes reads or
builds data: `palette(...)` is a map read, and `resolve`, `behaviour().get`, `encode`, `decode` and the packet builders
only use what is there. Narrow `versions(...)` to compile, and keep, fewer. A client whose protocol needs a version that
is not served is refused, never served an older version: `palette(protocol)` and `version(protocol)` throw
`IllegalArgumentException`, and `supports(protocol)` tells beforehand. `palette(DataVersion)` returns the palette of a served
version.

What still happens on first use, and is thread safe (built once under a lock, read without one):

- Once every served palette is compiled, what only compiling uses is dropped (about 2.8 MB). A lookup that needs part of
  it builds that part again on its first call and keeps it: one that maps a state of an older version forward
  (`Lookup.RENAMED`, `explain`, `explainUnknown`) the forwarding tables, `Paletter#properties` the properties.

- `Paletter.bundled()` builds the shared instance on its first call: every bundled release with behaviour, kept for the
  life of the JVM. It is for tools and tests; a server builds its own Paletter with the versions it serves.
  `vanilla(version)` and `palette(version)` only take served versions, so nothing else compiles later.
- `Palette#extend(layer)` compiles the extension when called (and caches it per layer); the behaviour columns of the
  states it adds are built on the first value asked for, from data already loaded.
- A `ResolvedBlock` is made, once, the first time its state is resolved (one small object, nothing is read).
- `Ingredients.tagOf` indexes a palette's tags on its first call for that palette; `VanillaRecipes.of(version)` reads the
  recipes of a version.

## Keys and lookups

Keys (`BedrockBlockType`, `BedrockBlockState`, `BedrockItemType`, `BedrockItemVariant`) are identities. `equals` always
works. `==` works too for vanilla keys, which are singletons, and for registered custom content: a `CustomItem` or
`CustomBlock` creates its types, their base item variant and its block states once and owns them, and a palette it is
registered in hands back exactly those (`decode`, `block(NbtMap)`, `RegisteredItem#type`, `Palette#blockState`,
...). Other variants of a custom item (`layer.variant(ruby.type().variant(5))`) are equal to the one the layer was
given, not identical: compare them with `equals`. A key for an identifier Paletter does
not know is a plain value, equal by identifier and not interned, so memory is bounded by registered content. Equal custom
items or blocks in different layers each own their keys, which are equal but not identical. Registered entries (`RegisteredItem`, `RegisteredBlockState`) are compared by identity, like
Cloudburst definitions: there is one instance per entry per palette.

Methods named `blockState`, `itemVariant`, `itemType`, `blockType` or `...Of` return keys; methods named `...Definition`
return definitions (what is sent); `resolve` returns a resolution (`ResolvedBlock`, `ResolvedItem`). One rule per kind of
argument:

- **A key** (`resolve(BedrockBlockState | ItemKey)`, `blockDefinition(BedrockBlockState)`, `runtimeId(BedrockBlockState)`):
  what this palette sends for it, with fallbacks applied. Never null: throws `IllegalArgumentException` where nothing can
  be sent (a custom block of a layer the palette lacks, an item the version lacks with `Fallbacks.NONE`), and the
  `orElse` overload returns its default there instead. `blockDefinition(state)` and `runtimeId(state)` are
  `resolve(state).definition()` and its runtime id, without the resolution.
- **An identifier, NBT, state values or a runtime id** (`blockState(...)`, `itemVariant`, `itemType`, `blockType`,
  `blockDefinition(int)`, `itemDefinition(String | int)`, `resolveBlock(int)`): what this palette itself has, never a
  fallback; null where it has nothing. Data a client sent never makes a lookup throw.
- **A relation** (`blockOf`, `itemOf`, `experimentOf`): null where there is none.

| Lookup | Returns |
|---|---|
| `Palette#blockState(NbtMap, Lookup)`, `Palette#blockState(identifier, values, Lookup)`, `Palette#itemVariant(identifier, aux, Lookup)`, `Palette#itemType(identifier, Lookup)` | The key (or item type) if the `Lookup` accepts the content in this palette's version, else null. |
| `Palette#blockState(identifier, values, Lookup)` | The same as the NBT overload, from state values as `Map<String, Object>` of `Boolean`, `Integer` or `String`, without building NBT. All of the block's states must be given; a value of another type is no match. `BedrockBlockState#values()` has the same shape (unmodifiable, in state order), so `palette.blockState(s.type().identifier(), s.values(), Lookup.EXACT)` is `s`. Without a palette, `type.stateOf(values)` finds the state of a constant (the newest bundled release) or of a custom block's type. The generated `states` of `PlacementFilter.BlockFilter`, `BlockPlacer.UseOn` and the other block descriptors use it too. |
| `Palette#runtimeId(BedrockBlockState)` | The runtime id (the state hash). Throws if the palette lacks the state; `runtimeId(state, orElse)` returns `orElse` instead. |
| `Palette#blockDefinition(BedrockBlockState)` | The `RegisteredBlockState` this version sends, after adapting and fallbacks. Throws if the palette lacks the state. Only a custom block of a layer the palette does not have can be lacking: with the bundled data, every version has each vanilla state. |
| `Palette#blockDefinition(BedrockBlockState, RegisteredBlockState)` | Like `blockDefinition(state)`, but returns `orElse` (may be null) where that throws; as `runtimeId(state, orElse)` does for the runtime id. |
| `Palette#resolve(BedrockBlockState)` | A `ResolvedBlock`: the `definition()` sent (as `blockDefinition(state)`), `kind()` (a `ResolutionKind`: `EXACT`, `ADAPTED` to the version's state values, `FALLBACK`, `DEFAULT` or `MISSING`; `fallback()` is true for the last three) and `behaviour()` (`BlockValues`, see Behaviour). Throws like `blockDefinition(state)`. Made once per state and kept. |
| `Palette#resolve(BedrockBlockState, ResolvedBlock)` | Like `resolve(state)`, but returns `orElse` (may be null) where that throws. |
| `Palette#resolveBlock(int)` | The `ResolvedBlock` of a block this palette sends, by runtime id, or null; never a fallback. |
| `Palette#itemDefinition(String)` | The `RegisteredItem` for an identifier (`type.identifier()` of a key), or null if the version lacks it. Fallbacks are not applied. `itemDefinitions()` lists all items in runtime id order, `itemTags()` the item tags (tag to identifiers). |
| `Palette#blockState(BlockDefinition)` | The key of a block this palette sends, looked up by runtime id: `blockDefinition(blockState(d))` is `d`. A definition of another palette gives the key of the block with its runtime id here, or null if there is none. |
| `Palette#blockState(int)` | The key of the block with a runtime id (the hash), or null if this palette has no such block. For ids a server stores itself, e.g. chunk palettes; ids decoded from packets already come as definitions. |
| `Palette#blockDefinition(int)` | The `RegisteredBlockState` with a runtime id, or null. |
| `Palette#itemDefinition(int)` | The `RegisteredItem` with an item runtime id (negative for the plain item of a custom block), or null. |
| `Palette#resolve(ItemKey)` | What `encode` would send, without building an `ItemData`. `kind()` is `EXACT`, `FALLBACK` or `DEFAULT`. `behaviour()` gives the `ItemValues` of the item sent. `variant()` is the variant this palette sends for the requested one: the requested variant itself unless `fallback()`. Throws only where nothing is sent (`Fallbacks.NONE`); `resolve(key, orElse)` returns `orElse` there. |
| `Palette#blockOf(ItemKey)` | The key of the block an item is sent with, or null. `blockDefinition(blockOf(v))` is what is sent. |
| `Palette#itemOf(BedrockBlockType)` | The item the Script API gives for the block's default permutation: the block's own item for a custom block, else the bundled block to item relation. Null if there is none here. Whether the client's pick block gives this item is unverified. |
| `Palette#encode(BedrockItemStack)` | The `ItemData` for the client, with fallbacks applied. |
| `Palette#decode(ItemData)` | The stack a client sent, or null if unknown. Its variant is always one the palette knows: an aux that names no variant gives the base variant, and the stack keeps the client's value in `BedrockItemStack#aux()`, so client input never makes keys of its own. |
| `Palette#explain(...)` | A readable explanation of how a block state or item resolves. |

Lookups are on `Palette`, because the palette is the version. `Palette#blockState(NbtMap, Lookup)`, `blockState(identifier, values, Lookup)`, `itemVariant(identifier, aux, Lookup)`,
`itemType(identifier, Lookup)` and `blockType(identifier, Lookup)` take the `Lookup` explicitly: `Lookup.EXACT` accepts only
what exists as written in the palette's own version, `Lookup.RENAMED` also content of an older bundled version, mapped
forward (renamed identifiers following `renames.txt`, changed state keys); only content that is unchanged is checked
against the palette's version. A changed shape is adapted (a key the newest block added gets its default, a removed one is
dropped), but a value the newest block does not have for a key it still has, or a combination it lacks, is no match, as an
aux the item lacks is none: untrusted input is not reset to defaults. Each returns null if the lookup does not accept the content, and
`explainUnknown(..., Lookup)` says why, naming the keys that differ from the closest known state (a block state key or value,
the aux of an item). Content of the palette's layers is always accepted.

A palette sees exactly what it has: a lookup returns null for content newer than the palette's version, content removed
with `Paletter.Builder#removeItems`, and content behind an experiment the palette does not enable. To get keys that resolve through
fallbacks on every version, look them up on the newest palette, with the experiments you need (`allExperiments()` enables
all), or use the constants (`BedrockBlocks`, `BedrockItems`). There is no lookup without a `Lookup`: a mapping written
against 26.50 keeps working when 26.60 becomes the newest version by using `Lookup.RENAMED` on the newest palette.

Paletter decides which block an item carries (`Palette#blockOf`, a key). A layer override wins, then the block to item relation
captured from the Script API, then the block states in the creative inventory. An item that places a block but whose captured
creative stacks carry none (doors, signs, beds, seeds, redstone, hoppers, campfires, ...) carries no block, as BDS sends it.

A custom block owns its item, as in BDS, so a layer never registers it separately: `CustomBlock.Builder#item` chooses
`BlockItem.SIMPLE` or `NONE`. An item of its own that replaces the block item is an ordinary `CustomItem` with the block's
identifier and a `block_placer` with `replace_block_item` (BDS takes the flag only for that identifier), registered in the
same layer with `layer.item(...)` (in another layer, or for a block with `BlockItem.NONE`, compiling throws a `PaletteException`
naming the cause; a flag for a block that does not exist is reported and the item stays an ordinary one): its category, components and id come from the item, and `Palette#itemOf` gives it for
the block. In the creative inventory it is a plain stack (no block state) in the item's own category, placed in the block
pass; the block keeps its own `menu_category`, which is independent of the item's (the `paletter:plain_block` capture: block
construction, item items). Plain items are numbered by their block (`255 - block_id`) and placed in the order of the
blocks; replacing items share the sequence of the custom items.

Vanilla items keep the runtime ids of the bundled data in every palette: custom items (replacing items included) get
sequential ids after them, in registration order (Paletter's rule: the BDS capture's ids look alphabetical, which
would be the file name order of an add-on, but that is not shown to be the rule). A custom block's plain item (`BlockItem.SIMPLE`) has the runtime id `255 - block_id`, as in
BDS: negative, and it changes when a later layer adds blocks, because BDS numbers custom blocks by identifier in
descending order. Read ids from the palette, never keep them across layers.

## Vanilla constants

`dev.opencollab.paletter.vanilla` has generated constants for the newest bundled release: `BedrockBlocks`, `BedrockStates`,
`BedrockItems`, `BedrockItemVariants` and `BedrockExperiments` (the experiment toggle ids, as Strings), plus enums for string states in `vanilla.state`. They are objects, not `int` constants, and load
nothing until used.

```java
BedrockBlockState door = BedrockBlocks.WOODEN_DOOR.state()
    .with(BedrockStates.CARDINAL_DIRECTION, CardinalDirection.NORTH)
    .with(BedrockStates.OPEN_BIT, true)
    .build();
CardinalDirection facing = door.get(BedrockStates.CARDINAL_DIRECTION);
int runtimeId = palette.runtimeId(door);
ItemData potion = palette.encode(BedrockItemStack.of(BedrockItems.POTION.variant(5)));
```

States are interned per block, so `with()` does not allocate. Byte states with values 0 and 1 are `BoolState`, integer
states `IntState` and string states `EnumState`. Custom block properties can use the same state constants when they have
the same name.

When Bedrock removes content, its constant stays, deprecated (`isAvailable()` is false): palettes that still have it send
it, the others resolve it through the fallbacks (`minecraft:unknown` by default). A removed block constant keeps the states
it last had. A renamed identifier keeps its old constant as a deprecated alias. `@since` on a constant is the Paletter version that
added it; the line `First in Bedrock` names the first bundled Bedrock release that has the content without an experiment (none for content older than the oldest bundled release). `@Education` marks Education Edition content.

An `ItemKey` is a `BedrockItemType` or a `BedrockItemVariant`, and the methods that take a variant (`Palette#resolve`,
`blockOf`, `BedrockItemStack.of`, `PaletteLayer.Builder#variant`) take one: a type stands for its base variant, so
`palette.resolve(BedrockItems.APPLE)` works. `type.variant()` is the base variant and `type.variant(aux)` the variant of
an aux, never null (an aux the data does not name gives a plain variant without a canonical id). A key is a type and an
aux, so creating or using one loads no bundled data, and a key made before any palette resolves exactly like one made
after. The base variant and the named constants of `BedrockItemVariants` are the same object whenever they are asked for,
so `==` holds for them; other auxes are new variants until the data is loaded (then the data's own for the auxes it
lists), so compare those with `equals`. An `ItemKey` always means exactly that
variant, and a type means aux 0, in every method that takes one (`ItemFallback.of`, `ItemMatcher.of`,
`CreativePlacement.after` too); where a type means every aux, the name says so (`ItemMatcher.anyVariantOf`,
`CreativePlacement.afterAnyVariantOf`). Keys for an identifier are made with `Palette#itemType` and
`Palette#itemVariant`, or are the type of a `CustomItem`.

`BedrockItemVariants` names the aux variants of potions, splash and lingering potions, tipped arrows, beds and banners.
The names are BDS's own spellings (`POTION_LONG_NIGHTVISION`, `BED_SILVER`), taken from the Script API when the data is
captured. A constant is the very variant of `type.variant(aux)`, and referencing it loads nothing. Variants of other items
(suspicious stew, goat horns, ...) have no names.

```java
ItemData potion = palette.encode(BedrockItemStack.of(BedrockItemVariants.POTION_LONG_NIGHTVISION));
boolean same = BedrockItemVariants.BED_SILVER == BedrockItems.BED.variant(8); // true
```

## Components

Custom items and blocks use Bedrock's add-on component model as generated Java: one final class per component
(`minecraft:durability` is `Durability`), nested classes for objects, open value classes for documented string values,
and builders that apply documented defaults and check the ranges BDS enforces. A component is made with its `builder()`
or an `of(...)` shorthand, never a constructor, and read with record-style accessors (`durability.maxDurability()`).
A documented value is a constant (`Wearable.Slot.SLOT_ARMOR_HEAD`); `Slot.of("slot.armor.new")` takes a value newer than
Paletter, and `isKnown()` tells the two apart. See [Evolution](#evolution) for why these are not records and enums.

```java
CustomBlock lamp = CustomBlock.builder("example:lamp")
    .property(BlockProperty.bool("example:lit"))
    .trait(PlacementDirection.builder().enabledStates(PlacementDirection.EnabledStates.CARDINAL_DIRECTION).build())
    .component(Geometry.of("minecraft:geometry.full_block"))
    .component(MaterialInstances.builder()
        .all(MaterialInstances.InstanceOrAlias.define(MaterialInstances.Instance.builder().texture("example_lamp").build())).build())
    .component(CollisionBox.builder().boxes(CollisionBox.Entry.builder().origin(-8, 0, -8).size(16, 8, 16).build()).build())
    .permutation("q.block_state('example:lit')", LightEmission.of(10))
    .menuCategory(CreativeCategory.CONSTRUCTION)
    .build();
```

Field names are the add-on JSON keys in camelCase. Wherever the model takes a block or item name, the vanilla constants
work too. The Javadoc comes from the creator documentation, with these annotations:

- `@BedrockFormat` names the minimum `format_version` of the add-on JSON. It is informational: the wire has no format
  version, and Paletter writes the shape of each palette's protocol.
- `@ServerOnly` marks components BDS accepts but never sends to clients. Their encoders write nothing.
- `@Deprecated` with `@ReplacedBy` marks components the docs deprecate or BDS rejects.

When a component field cannot be sent in a palette's protocol, it is left out and `Palette#report()` gets a warning.
Compiling a palette never fails because of the Bedrock version.

### Deprecated components

Bedrock deprecates components and BDS rejects them at the current `format_version`. A deprecated component keeps its
generated class (`@Deprecated`, `@ReplacedBy` where there is a replacement, the replacement in the Javadoc) only if BDS
sends something for it at some format. A component that never reaches the wire has no class: the Javadoc of
`BlockComponent` and `ItemComponent` lists it as retired, with its replacement and why. BDS decides, so the formats are
asked from BDS: the format sweep of `update` (`spec-format/`) puts each rejected component at every known format beside a
control of the same format (every build since the recapture of 2026-11-23).

| Component | Replacement | Class | State |
|---|---|---|---|
| block `minecraft:block_light_absorption` | `light_dampening` | kept | BDS upgrades it to `light_dampening` at formats 1.10.0 to 1.16.100 |
| block `minecraft:block_light_emission` | `light_emission` | kept | BDS upgrades it to `light_emission` at formats 1.10.0 to 1.18.10 |
| block `minecraft:bone_visibility` | the `bone_visibility` field of `geometry` | retired | rejected at every format |
| block `minecraft:breathability` | none documented | retired | rejected at every format |
| block `minecraft:destroy_time` | `destructible_by_mining` | kept | BDS upgrades it to `destructible_by_mining` at older formats |
| block `minecraft:explosion_resistance` | `destructible_by_explosion` | retired | not on the wire: the white-box has no component of that name, and `destructible_by_explosion` is server only |
| block `minecraft:queued_ticking` | `tick` | retired | not on the wire: the white-box says `network: false` |
| block `minecraft:random_ticking` | none documented | retired | not on the wire: the white-box says `network: false` |
| block `minecraft:unit_cube` | none documented | retired | rejected at every format |
| item `minecraft:chargeable` | `use_modifiers` | retired | BDS accepts it at formats 1.20.30 and 1.20.40 and sends nothing for it |
| block and item `minecraft:custom_components` (the list of names) | the object form of `custom_components` | not generated | accepted up to format 1.21.80; the API offers only the object form |
| item `minecraft:render_offsets` | none documented | kept (hand-written `RenderOffsets`) | BDS sends nothing for it at any format; kept for what the client takes from it (Geyser's render offsets) |
| item `minecraft:seed` | `block_placer` | retired | BDS sends it only inside an old-style item (formats below 1.16.100), which Paletter does not write |
| item `minecraft:use_duration` | `use_modifiers` | kept | BDS upgrades it to `use_modifiers` from format 1.20.20 on |
| item `minecraft:weapon` | `damage`, `cooldown`, `durability` | retired | BDS accepts it at formats 1.20.30 and 1.20.40 and sends nothing for it |

`MiningSpeed`, `FrameCount`, `RenderOffsets` and `OnPlayerPlacing` are hand-written, not generated. They are wire fields
Geyser needs that have no add-on JSON form, so there is no BDS capture to verify them against.
`ScriptHooks` (block `minecraft:custom_components`: whether any script component handles interaction and placement) and
`UseOnHook` (item `minecraft:publisher_on_use_on`) are hand-written too: the add-on JSON of those is component names, which
never reach the wire; the spec capture will check them against BDS after the next recapture. `RenderOffsets` and
`OnPlayerPlacing` are retired from the add-on format; `MiningSpeed` and `FrameCount` are `@Experimental`.

## The creative inventory

`CreativeLayout` holds, per category, an ordered list of `CreativeGroup`s and loose `CreativeItem`s. Items are stacks, not
definitions, so one layout serves every version. Layers edit it relative to existing content:

```java
PaletteLayer.builder("server").creative(editor -> {
    editor.insert(stack).after(ItemMatcher.id("minecraft:hopper_minecart"));
    editor.move(ItemMatcher.namespace("example")).loose(CreativeCategory.ITEMS);
    editor.remove(ItemMatcher.of(BedrockItems.EMPTY_MAP.variant(2)));
});
```

Custom items and blocks have a `menu_category` (the generated `ItemMenuCategory` and `BlockMenuCategory`: category, group,
`hiddenInCommands`), which maps one to one from add-on JSON. As in BDS it alone decides whether the item or block item is in the
creative inventory and in which category and group, under every ordering: absent or category `none` means not in it.
`menuCategory(CreativeCategory)` and `menuCategory(CreativeCategory, group)` on both builders are shortcuts. A block and the
item that replaces its item each have a menu category of their own. A `CreativePlacement` adjusts only the position within
that category and group: `CreativePlacement.DEFAULT` (the default) or `CreativePlacement.after(ItemMatcher | ItemKey)` /
`afterAnyVariantOf(type)`, set with `creativePlacement(placement)`. An anchor that matches nothing falls back to the default position
and adds a warning to the palette report. `CreativeOrdering` (set on `Paletter.Builder`) only decides the order, never presence. The default, `BDS`, puts
content exactly where BDS puts add-on content; [wire-notes.md](wire-notes.md#creative-inventory-order) has the rule.
`CreativeContent` turns the layout into a valid packet: categories in client order, items the version lacks dropped,
empty groups dropped (they crash the client), and network ids numbered from 1.

## Fallbacks and experiments

Content an older version lacks resolves to, in order: a fallback for that version, a fallback for every version, and
then the default, `minecraft:unknown` for both blocks and items. `Fallbacks.NONE` turns the default off (air and missing
items).

```java
Fallbacks fallbacks = Fallbacks.builder()
    .block(BedrockBlocks.ICICLE, BedrockBlocks.STONE)
    .item(version, BedrockItems.POPLAR_BOAT, ItemFallback.of(BedrockItems.BIRCH_BOAT))
    .block(version, BedrockBlocks.POPLAR_LOG, BlockFallback.of(BedrockBlocks.BIRCH_LOG).forced())
    .build();
```

An item fallback is an exact variant: `ItemFallback.of(BedrockItems.BIRCH_BOAT)` sends the base variant of that type, and
`ItemFallback.of(BedrockItems.POTION.variant(5))` that variant, whichever variant it replaces.

`.forced()` replaces content even in versions that have it. Paletter ships no fallbacks: which replacement is right is
your decision. `./gradlew :tools:run --args="check-data"` lists what each older version lacks.

Content that a Bedrock version only has behind an experiment toggle is missing by default: lookups do not find it, it
resolves through the fallbacks, and the creative content leaves it out. Its items stay in the item registry
(`Palette#itemDefinitions()`, `itemDefinition(int)`), because BDS registers them in a world without experiments too; only
`itemDefinition(String)` treats them as missing.
`Paletter.builder().experiments("y_2026_drop_3")` enables it in every version; `experiments(version, "y_2026_drop_3")`
enables it in one version (a version that is not served makes `build()` throw an `IllegalArgumentException`). Every call adds to
the earlier ones, like `removeItems(...)`, which hides vanilla items. `allExperiments()` enables every toggle
the version gates content with, except the voxel shape experiment, which only comes with an explicit
`experiments("voxel_shapes")`; the palette lists them in `Palette#experiments()`.

## Previews

Preview versions of Bedrock can be bundled next to releases. They are marked `preview` in `versions.txt` (`DataVersion#isPreview()`)
and served only by a Paletter built with `Paletter.builder().previews(true)`, which is off by default. Previews are a
setting of the instance: Paletters with different settings coexist in one JVM. Generated API that only a bundled
preview has (constants, states, enum values, components and fields) sits with the rest and is marked
`@SincePreview("26.70")`; the constants exist either way, but are linked to the newest release, so they are never
`isAvailable()`. Items and blocks behave alike: the preview's palette sends the item or block itself, and every palette
that lacks it (all of them without previews) resolves it through the fallbacks (normally to `minecraft:unknown`). A
preview block constant carries the preview's states, generated into it, so no preview data is loaded for it. The mark
goes away when the release has it. A block or item constant a newer preview drops stays, deprecated and still marked
`@SincePreview`, and resolves through the fallbacks, so code compiled against it keeps linking.

## Behaviour

Behaviour belongs to a palette, not to a definition: `RegisteredBlockState` and `RegisteredItem` are the same in every
version, so they have no `behaviour()`. It is reached through what a palette resolved: `Palette#resolve(BedrockBlockState)`
gives a `ResolvedBlock` and `Palette#resolve(ItemKey)` a `ResolvedItem`, each with `behaviour()`. It returns
values BDS uses but never sends to clients: friction, destroy time, collision and outline shapes, light, max stack size,
attack damage and more.

```java
float ice = palette.resolve(BedrockBlocks.BLUE_ICE.defaultState()).behaviour().get(BlockBehaviour.FRICTION);
BlockShape stairs = palette.resolve(state).behaviour().get(BlockBehaviour.COLLISION_SHAPE);
int eating = palette.resolve(BedrockItems.APPLE).behaviour().get(ItemBehaviour.USE_DURATION);
String why = palette.resolve(BedrockBlocks.BLUE_ICE.defaultState()).behaviour().explain(BlockBehaviour.FRICTION);

ResolvedBlock block = palette.resolve(state);
block.definition();   // the block sent, as blockDefinition(state)
block.kind();         // how it was resolved: EXACT, ADAPTED, FALLBACK, DEFAULT or MISSING
block.behaviour();    // the values of the block that is sent
```

- Keys are the constants of `BlockBehaviour` and `ItemBehaviour`. Number and boolean keys return primitives. A palette
  makes a resolution once and keeps it, but states or items that resolve alike may share one, and a `ResolvedItem`
  hands out a new `ItemValues` handle on each call: compare resolutions and handles with `equals`, not `==`.
- A resolution carries the values of the block or item that is sent. A fallback state has the values of its
  replacement.
- `resolve(BedrockBlockState)` throws `IllegalArgumentException` where `blockDefinition(state)` throws.
  `resolve(state, orElse)` returns `orElse` (may be null) there, as `blockDefinition(state, orElse)` does.
  `resolveBlock(int runtimeId)` takes the runtime id of a block this palette sends (from a packet or a chunk palette) and
  returns null if there is none; it is never a fallback. `resolve(ItemKey)` throws only for an item that is not
  sent at all (`Fallbacks.NONE`), and `resolve(key, orElse)` returns `orElse` there.
- `get` throws `IllegalStateException` when no value is known; `getOrElse(key, fallback)` returns the fallback instead,
  and `has(key)` tells beforehand. A key is typed by whose values it reads (`FloatKey<BlockValues>`), so a block key does
  not compile against `ItemValues` and the other way around.
- Values belong to the palette, not the state: a newer capture can change a value for one version only. An extended
  palette shares the values of its base for what they have in common, and the handles: one item has one handle in a
  palette, whichever variant resolved to it, and an extension has the base's handle for the items it did not change.
- `explain` returns one line for debugging: the value, its source and capture, and whether it was set by the user,
  inherited from an older version, post-processed by its source, or disputed. It is not a format to parse.
- Custom blocks and items answer from their components (`Friction`, `LightEmission`, `CollisionBox`, `MaxStackSize`,
  ...). A key without its component gets the BDS default measured by the behaviour probe.
- Shapes are those of one state. Shapes that depend on neighbours or position, such as plant offsets, are up to you.
- `Paletter.builder().behaviour(false)` switches behaviour values off, for consumers that never read them: the behaviour
  data is never loaded or kept (its generated classes are not even loaded), resolutions work as before, and `get`,
  `getOrElse`, `has` and `explain` throw `IllegalStateException` naming the switch.
- The vanilla values are part of the library: generated Java code (`internal.behaviour.vanilla`), one class per bundled
  version with what changed, built once per JVM. They do not come from the `PaletteDataSource`: a custom data source
  serves its palettes with the bundled values of the same version (by name, else the newest older one by protocol), and
  cannot bring values of its own. A state of such a palette gets a bundled block's values when it has the same properties
  and each value is one the bundled version has.

The sources, their ranking, disputed values and the custom block defaults are listed in the generated
[data/behaviour/REPORT.md](../data/behaviour/REPORT.md).

## Voxel shapes

Voxel shapes are the culling shapes Bedrock uses to hide faces of neighbouring blocks. They are not collision shapes;
collision and selection are the `COLLISION_SHAPE` and `OUTLINE_SHAPE` behaviour keys. A palette has the vanilla shapes
BDS sends for its version; layers add custom ones, which custom blocks name in `minecraft:geometry`:

```java
PaletteLayer.builder("content")
    .voxelShape("example:post", VoxelShape.ofBoxes("example:post", 6 / 16f, 0, 6 / 16f, 10 / 16f, 1, 10 / 16f))
    .block(CustomBlock.builder("example:post")
        .component(Geometry.builder().identifier("geometry.post").cullingShape("example:post").build())
        .build());
```

`palette.voxelShapesPacket()` is byte for byte the packet BDS sends for the same content, including the shapes Bedrock
derives for custom blocks. BDS sends it to every client before start game, also without custom shapes, so send it
always. Every palette has it: a palette of a version whose data has no vanilla shapes fails to build with a
`PaletteException`. `VoxelShape.ofBoxes` rejects what BDS rejects. In 26.30 the vanilla shapes are behind the
`voxel_shapes` experiment. How BDS builds the registry is in [wire-notes.md](wire-notes.md#voxel-shapes).

## Recipes

The optional `paletter-recipes` module has the vanilla crafting, smelting, smithing and brewing recipes of each data version
(`VanillaRecipes.of(version)`) and encodes them for a palette. `:paletter` never depends on it.

```java
VanillaRecipes vanilla = VanillaRecipes.of(palette.version());
CraftingData data = CraftingData.builder(palette)
    .recipes(vanilla.recipes()).potionMixes(vanilla.potionMixes()).containerMixes(vanilla.containerMixes())
    .build();
data.applyTo(craftingDataPacket);
```

`build()` never throws. Items resolve through the palette, layers included, and tags are `palette.itemTags()`. A recipe with an
item the palette does not have, a tag without items, or a result block state it does not have is dropped, as BDS drops it;
`data.dropped()` lists them and `data.netId(recipe)` is -1 for them. Net ids are 1, 2, ... in the order the recipes were
added. UUIDs come from the recipe's id (and station), so they are the same on every run; the multi recipes keep their fixed
UUIDs. Apart from net ids and UUIDs the packet is what BDS sends for the same palette: a test compares the encoding of every bundled
data version's recipes with BDS's committed capture of it. `applyTo` sets `cleanRecipes`, as BDS does. Palettes of protocols below 2168 (26.30) read one recipe list instead of one per
kind, so `applyTo` fills that too, in the order the recipes were added (BDS interleaves the kinds there; the recipes themselves
are compared with BDS's 26.30 capture, the order is not). Equal recipes added twice are encoded once; different recipes with
the same id and station get the same UUID.

`Ingredients.tagOf(palette, items, unobtainable)` finds the palette's item tag (layers included) whose members are exactly a set
of items, for sending an ingredient as one tag instead of one entry per item. Items the palette lacks (an older version) and
items the predicate calls unobtainable are left out on both sides; with several matches the smallest tag wins, then the first
by name; no match gives null.

## Errors and reports

Layers given to `Paletter.builder()` are startup configuration: `build()` compiles every served version, so a problem
throws `PaletteException` from `build()` (the first version's failure, after the palettes that compiled were reported).
`Palette#extend(layer)` throws a
`PaletteException` too, and changes nothing: the palette is unchanged, no palette or failure is cached, and the same palette can
still be extended by another layer. Duplicate identifiers (blocks, items, voxel shapes) and duplicate block runtime ids
are rejected, across layers and within one. `PaletteException#version()` is the data version, `#layer()` the id of the layer
and `#identifier()` the content it is about (each null when it is not about one). The message names them. A configuration
that is wrong in itself (a version that is not served, a predicate that serves none) is an `IllegalArgumentException`. `PaletteReport` also
lists what was adapted, replaced or dropped per version: each `PaletteReport.Entry` has a level, a `code()` to filter by
(the code constants of `PaletteReport`, such as `ANCHOR_UNMATCHED`; a later release may add codes), the `subject()` it is
about (an identifier, or null) and a message for people. `Paletter.Builder#reports` gets the report of every palette the
Paletter compiles; the default logs the entries to the `System.Logger` `dev.opencollab.paletter` (warnings at `WARNING`, the
rest at `DEBUG`). `Paletter.Builder#failOn(predicate)` makes matching entries fatal: `build()` throws a `PaletteException`.
A protocol newer than the newest data gets the newest data's palette itself, and the consumer one note
(`NEWER_PROTOCOL`). A report never changes (a palette extended with a layer has a new
one) and knows its `version()`; `unknownBlocks()` (block types) and `unknownItems()` (item variants, so a missing aux is
exact) are the content of the newest data that the
version lacks and no specific fallback covers (a default fallback does not count; content behind a disabled experiment or
removed with `removeItems` is included).
