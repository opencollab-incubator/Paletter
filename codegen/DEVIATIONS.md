# Deliberate deviations from Geyser's encoders

Paletter keeps NBT parity with what Geyser sends today (`CustomItemRegistryPopulator`, `CustomBlockRegistryPopulator`)
unless wire NBT contradicts it. Wire NBT is the bundled vanilla data plus the BDS 1.26.63.1 capture of an add-on using
every component (`codegen/captures/bds-1.26.63.1-addon`, findings in `capture/WIRE-NOTES.md`). `spec/inferred.json`
records each Geyser sample as `compatible` or `DIFFERS`; `needs-capture.txt` lists the `differs` ones.

## Where Paletter's NBT differs from Geyser's

| Component | Geyser sends | Paletter sends | Wire evidence |
|---|---|---|---|
| `minecraft:damage` | `{value: byte}` | `{value: short}` | vanilla spears, add-on `damage_full` (300) |
| `minecraft:compostable` | `{composting_chance: int}` | `{composting_chance: byte}` | vanilla, add-on |
| item `minecraft:tags` | only the bare `item_tags` list | `item_tags` **and** `minecraft:tags {tags}` | vanilla and BDS send both for every tagged item |
| `minecraft:durability` | `damage_chance {min: 1, max: 1}` | `damage_chance {min: 100, max: 100}` by default | BDS default for custom items (percent; 1/1 means a 1% chance) |
| `minecraft:material_instances` | material `{..., isotropic: byte, packed_bools: 0/1}` | no `isotropic`; `packed_bools` = 4 \| face_dimming (bit 0) \| isotropic (bit 1) | every captured material; JSON defaults give 5, no dimming + isotropic gives 6. Bit 2 is always set, meaning unknown |
| `minecraft:material_instances` | `mappings: {}` | face aliases as `mappings {face: {value: target}}` (empty without aliases) | add-on `material_instances` |
| `minecraft:placement_filter` | `conditions[].block_filters`, tags with `tags_version: 6` | `conditions[].block_filter`, `tags_version: 13` | add-on `placement_filter`; Geyser's key is not on the wire, so its filters are likely ignored |
| block property data | `molangVersion: 1` | `molangVersion: 13` (`CustomBlock.Builder#molangVersion`) | vanilla 26.50+ and every BDS custom block |
| block property data | tags only as `tag:<name>` components | `CustomBlock.Builder#tag` sends top level `blockTags` (`BlockComponents.Builder#tag` still writes `tag:<name>`) | BDS; `tag:<name>` is rejected by 1.26.60 JSON |

Typed-only differences (no convenience method writes them): `minecraft:kinetic_weapon` is nested in
`{"minecraft:kinetic_weapon": {...}}` like vanilla and BDS (Geyser sends it flat); `minecraft:dyeable.default_color` is a
list of ints (Geyser sends an int array).

## Geyser should change

Ordered by likely impact. All of these are wire facts from vanilla data or the BDS 1.26.63.1 capture.

1. **`minecraft:placement_filter`**: the key is `block_filter` (singular) and tag filters use `tags_version: 13`.
   Geyser's `block_filters` never appears on the wire, so custom block placement filters probably do nothing.
2. **`minecraft:kinetic_weapon`**: nest the payload in `{"minecraft:kinetic_weapon": {...}}` (vanilla spears and BDS
   both do). `delay` is a short inside it; conditions are `{max_duration: short (-1 default), min_speed, min_relative_speed}`;
   omit `creative_reach` and each `*_conditions` when absent; `reach` defaults to `{min: 0, max: 3}`.
3. **`minecraft:damage`**: `{value: short}`, not a byte (values above 127 overflow).
4. **`minecraft:material_instances`**: drop the `isotropic` key and pack the flags: `packed_bools = 4 | (face_dimming ? 1 : 0)
   | (isotropic ? 2 : 0)`. Today isotropic is ignored and bit 2 is missing.
5. **`minecraft:durability`**: `damage_chance` defaults to `{min: 100, max: 100}`; Geyser's 1/1 tells the client the item
   takes damage 1% of the time.
6. **`minecraft:dyeable`**: `default_color` is a list of three ints (`TAG_List` of `TAG_Int`), not an int array.
7. **`minecraft:compostable`**: `composting_chance` is a byte.
8. **Block property data**: `molangVersion` 13; tags as top level `blockTags` (the legacy `tag:<name>` components are
   rejected by 1.26 JSON, though the client may still read them); BDS also sends `vanilla_block_data.material`
   (`"dirt"` for custom blocks).
9. **Item tags**: also send `minecraft:tags {tags: [...]}` next to `item_tags`.
10. **`minecraft:render_offsets`**: no longer an add-on JSON item component in 1.26, so BDS never sends it; whether the
    client still reads it is unverified. Same for the bare `render_offsets` string Geyser adds for armor.
11. **`minecraft:on_player_placing`** (Geyser's `geyser:place_event`): rejected by 1.26 JSON (replaced by script custom
    components); it has no 1.26 wire shape. Verify the client still fires it.
12. Smaller things BDS always sends that Geyser omits; Geyser works without them, listed for completeness:
    `cooldown.type` (`"use"`), all six `throwable` fields, `use_modifiers.start_using` (`"if_first"`) and
    `emit_vibrations`, `wearable.hides_player_location` (0), `projectile.minimum_critical_power`,
    `block_placer.alignedPlacement`/`replaceBlockItem` plus a sibling `minecraft:publisher_on_use_on
    {autoSucceedOnClient: 0}`, full `use_on` descriptors `{name, states, tags}`, entity identifiers with a `<>` spawn
    event suffix (`minecraft:snowball<>`), the full `minecraft:geometry` key set, transformation pivots and
    `hasJsonVersionBeforeValidation`, `item_properties.hidden_in_commands` (2 = not set).

No longer a deviation: `minecraft:repairable.repair_amount` is a float for numbers and a Molang string otherwise (both on
the wire), so Geyser's float `1.0` is valid; the generated `Repairable` keeps it as `Object`.

## Not deviations, but worth knowing

- Fields Geyser omits are optional in the generated types because Geyser proves the client accepts the omission; the
  convenience methods omit them too.
- `block_placer.use_on` is an empty list from the convenience method; on the wire, entries are `{name, states, tags}`.
- `selection_box` and `collision_box` conveniences match Geyser and the wire (`false` sends the full cube / `boxes: []`).
