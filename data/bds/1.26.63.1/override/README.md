# Vanilla item override probe

BDS 1.26.63.1 (protocol 2225, Windows), vanilla world, no experiments. `addon/` is the behaviour pack that was loaded:
three items, each in the format version the file names (apple 1.26.60, feather 1.10, control 1.26.60). `registry.json`
holds the three entries of the item registry that the headless client captured from the start game packet (typed NBT
converted to JSON with a small script, byte values as numbers): `minecraft:apple`, `minecraft:feather` and the custom
control item `paletter:override_item_control`.

The pack also contained overrides that BDS rejected (`minecraft:stick` at 1.26.60, `minecraft:bread` at 1.16.100) and
some block overrides; they are not kept here. See `docs/wire-notes.md`, "Overriding vanilla items".

Captured once, by hand; the run is not reproducible from the repository. Only the apple entry is used by a test.
