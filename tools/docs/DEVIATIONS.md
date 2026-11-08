# Paletter vs historic Geyser output

Migration notes. Paletter's generated component encoders (`internal.component`, from `data/model`, checked against
BDS by the conformance suite) are the single source of truth for the custom item and block wire format; consumers
describe content with the generated components and never write these shapes themselves. This file lists where the
output differs from what Geyser's encoders (`CustomItemRegistryPopulator`, `CustomBlockRegistryPopulator`) used to send,
so a migration can review the golden file changes it causes. The "Semantic API" column names the API of the time
(`ItemComponents` / `BlockComponents`, since replaced by the generated components of the same names: `Durability`,
`PlacementFilter`, ...). Every row is a wire fact from the bundled vanilla data or a BDS 1.26.63.1 capture.

## Fixes folded into the encoders

Ordered by likely impact. Geyser gets these by moving to the semantic API; there is nothing to configure.

| # | Component | Geyser sent | Paletter sends | Semantic API | Evidence |
|---|---|---|---|---|---|
| 1 | `minecraft:placement_filter` | `conditions[].block_filters`, tag filters with `tags_version: 6` | `conditions[].block_filter` (singular), `tags_version: 13` | `placementFilter(PlacementFilter.allowOn(Face.UP).blocks(...).tags(...))` | add-on `placement_filter`. Geyser's key is not on the wire, so its filters were probably ignored by the client |
| 2 | `minecraft:kinetic_weapon` | flat payload; `delay` | nested `{"minecraft:kinetic_weapon": {...}}`; `delay` a short inside; conditions `{max_duration: short, min_speed, min_relative_speed}`; `creative_reach` and each `*_conditions` omitted when absent; `reach` defaults to `{min: 0, max: 3}` | `kineticWeapon(KineticWeapon.builder()...)` | vanilla spears, BDS |
| 3 | `minecraft:damage` | `{value: byte}` (overflowed above 127) | `{value: short}`, plus `item_properties.damage` | `attackDamage(int)` | vanilla spears, add-on `damage_full` (300) |
| 4 | `minecraft:material_instances` | material `{..., isotropic: byte, packed_bools: 0/1}`, `mappings: {}` | no `isotropic`; `packed_bools = 4 \| face_dimming (bit 0) \| isotropic (bit 1)`; face aliases as `mappings {face: {value: target}}` | `materials(Map<String, Material>, aliases)`, `Material.of(...).faceDimming(b).isotropic(b)` | every captured material: JSON defaults give 5, no dimming + isotropic gives 6. Bit 2 is always set, meaning unknown |
| 5 | `minecraft:durability` | `damage_chance {min: 1, max: 1}` (a 1 % chance) | `damage_chance {min: 100, max: 100}` by default; `damageChance(min, max)` in percent | `durability(Durability.of(max).damageChance(min, max))` | BDS default for custom items |
| 6 | `minecraft:dyeable` | `default_color` as an int array | `default_color` as a list of three ints (`TAG_List` of `TAG_Int`) | `dyeable(Color.of(0xRRGGBB))` | vanilla, add-on `dyeable_*` |
| 7 | `minecraft:compostable` | `{composting_chance: int}` | `{composting_chance: byte}` | `compostable(percent)` | vanilla, add-on |
| 8 | block property data | `molangVersion: 1`; tags as `tag:<name>` components; no `vanilla_block_data.material` | `molangVersion: 13`; tags as top level `blockTags`; `vanilla_block_data.material: "dirt"` | `CustomBlock.Builder#tags`, `BlockComponents.Builder#tags` | vanilla 26.50+, every BDS custom block. `tag:<name>` is rejected by 1.26 JSON |
| 9 | item tags | only the bare `item_tags` list | `item_tags` **and** `minecraft:tags {tags}` | `ItemComponents.Builder#tags` | vanilla and BDS send both for every tagged item |

## Other differences in Paletter's output

- `allow_off_hand`, `hand_equipped`, `can_destroy_in_creative`, `stacked_by_data`, `liquid_clipped`, `should_despawn` are
  sent both as item properties and as `minecraft:*` components, like BDS. Geyser sent the first two as properties only.
- `minecraft:cooldown.type` is sent only for attack cooldowns (`"attack"`); the client default is `"use"`, which BDS
  writes explicitly.
- Block tags set in a permutation's components cannot be expressed (`blockTags` belong to the block): they are left out
  with a `PaletteReport` warning. Geyser wrote `tag:<name>` components into permutations.
- `CustomBlock` no longer has `molangVersion`/`material` settings and `BlockComponents` no longer writes `tag:<name>`:
  these are encoder decisions now (per Bedrock version).

## Unverified, kept as Geyser sends them

Both are `@Experimental` in the semantic API.

- **`minecraft:render_offsets`** (`ItemComponents.Builder#renderOffsets`): no longer an add-on JSON item component in
  1.26, so BDS never sends it; whether the client still reads it is unverified. Same for the bare `render_offsets` string
  Geyser adds for armor, which has no semantic method.
- **`minecraft:on_player_placing`** (`BlockComponents.Builder#onPlayerPlacing`, Geyser's `geyser:place_event`): rejected
  by 1.26 JSON (replaced by script custom components); it has no 1.26 wire shape. Verify the client still fires it.

## Smaller things BDS always sends that Paletter (like Geyser) omits

Geyser works without them; listed for completeness: all six `throwable` fields (Paletter sends the ones set),
`use_modifiers.start_using` (`"if_first"`) and `emit_vibrations`, `wearable.hides_player_location` (0),
`projectile.minimum_critical_power`, `block_placer.alignedPlacement`/`replaceBlockItem` plus a sibling
`minecraft:publisher_on_use_on {autoSucceedOnClient: 0}`, full `use_on` descriptors `{name, states, tags}`, entity
identifiers with a `<>` spawn event suffix (`minecraft:snowball<>`), the full `minecraft:geometry` key set,
`hasJsonVersionBeforeValidation`, the full 18 key `item_properties` (`hidden_in_commands` 2 = not set, `frame_count`,
`mining_speed`).

## Not deviations, but worth knowing

- `minecraft:repairable.repair_amount` is a float for numbers and a Molang string otherwise (both on the wire);
  `Repairable.Entry#amount(float)` and `#amount(String)` produce them.
- Fields Geyser omits are optional in the generated shapes because Geyser proves the client accepts the omission; the
  encoders omit them too unless a semantic value sets them.
- `selection_box` and `collision_box` match Geyser and the wire (an empty selection box sends the full cube with
  `enabled: 0`, no collision sends `boxes: []`).
