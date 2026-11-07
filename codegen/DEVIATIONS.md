# Deliberate deviations from Geyser's encoders

Paletter keeps exact NBT parity with what Geyser sends today (`CustomItemRegistryPopulator`,
`CustomBlockRegistryPopulator`) unless vanilla wire NBT contradicts it. The rows below are every place where it does not.
`spec/inferred.json` records each Geyser sample as `compatible` or `DIFFERS`; `needs-capture.txt` lists the `differs` ones.

| Component | Geyser sends | Paletter sends | Evidence | Effect |
|---|---|---|---|---|
| `minecraft:damage` | `{value: byte}` | `{value: short}` | vanilla 26.40-26.60 (21 spears) | damage above 127 no longer overflows |
| `minecraft:compostable` | `{composting_chance: int}` | `{composting_chance: byte}` | vanilla 26.50-26.60 | matches the client's type |
| `minecraft:tags` (item) | only the bare `item_tags` list | `item_tags` **and** `minecraft:tags {tags}` | vanilla sends both for every tagged data driven item | tags reachable through both lookups |
| `minecraft:material_instances` | material `{ambient_occlusion, isotropic: byte, packed_bools: 0/1 face dimming, ...}` | no `isotropic` key; `packed_bools` is a bitset: bit 0 face dimming (same value Geyser sends), bit 1 isotropic | vanilla 26.50+ has no `isotropic` and `packed_bools = 5` | face dimming unchanged; the isotropic bit is a **guess** (JSON field order) and needs a capture |
| `minecraft:repairable` | `repair_amount: float 1.0` | `repair_amount: string` (Molang) | vanilla 26.40-26.60 | only in the typed `Repairable`; Paletter has no repairable convenience, Geyser's form stays possible as raw NBT |
| `minecraft:kinetic_weapon` | the payload flat in the component | `{"minecraft:kinetic_weapon": {payload}}` | vanilla 26.40-26.60 nest it in every spear | only in the typed `KineticWeapon`; needs a capture to confirm the client ignores the flat form |

Not deviations, but worth knowing:

- Fields Geyser omits (e.g. `throwable` sends only `do_swing_animation`, `projectile` omits `minimum_critical_power`,
  `block_placer` omits `alignedPlacement`/`replaceBlockItem`, `cooldown` omits `type`, `geometry` sends only
  `identifier`/`bone_visibility`, `transformation` omits the pivots and `hasJsonVersionBeforeValidation`,
  `use_modifiers` omits `emit_vibrations`/`start_using`) are optional in the generated types for that reason, and the
  convenience methods omit them too.
- `durability` keeps Geyser's `damage_chance {min: 1, max: 1}`; vanilla spears send `{min: 0, max: 100}`
  (`ItemComponents.Builder#durability(int, int, int)` sets other values).
- `block_placer.use_on` stays an empty `STRING` list as Geyser sends it; vanilla lists are always empty, element type
  unknown.
- Block tags stay `tag:<name>` components as Geyser sends them. Vanilla 26.50+ data driven blocks list tags in a top
  level `blockTags` list of the block property data (and `molangVersion` 13, `vanilla_block_data {material,
  block_archetype}`), which is `CustomBlock`'s business, not a component.
- Geyser also sends armor `render_offsets` as a bare string next to the components; it is not a compound component and
  has no generated type.
- Compared to the previous hand written `ItemComponents`, `glint(true)` now also sets the `foil` item property, as Geyser
  does.
