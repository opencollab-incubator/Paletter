# Vanilla item override probe

BDS 1.26.63.1 (protocol 2225, Windows), vanilla world, no experiments, with `addon/` as the only behaviour pack: three
items, each in the format version the file names (apple 1.26.60, feather 1.10, control 1.26.60).

`raw_item_registry.nbt` is the item registry the headless client captured from the start game packet, as written by
`tools` (`capture`, `CaptureWriter`): gzip compressed typed NBT, `{items: [{name, id, componentBased, version, data}]}`,
all 2082 items. The entries to look at are `minecraft:apple`, `minecraft:feather` and the custom control item
`paletter:override_item_control`. Read it with `Nbt.readGzip` of the tools module.

The same pack also held overrides BDS rejected (`minecraft:stick` at 1.26.60, `minecraft:bread` at 1.16.100) and some block
overrides; they are not kept here, and they are not in the registry. See `docs/wire-notes.md`, "Overriding vanilla
items".

Captured once, by hand. Only the apple entry is used by a test (`ItemOverrideTest`).
