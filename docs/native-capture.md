# Native capture (design, not built)

The Script API dump and the behaviour probe cover what BDS exposes or what can be measured from outside. Some behaviour
only exists in BDS's own classes: `BlockType` flags such as `Climbable`, material, friction, destroy speed, explosion
resistance, flame and burn odds, translucency, thickness, "requires correct tool", and the per-class virtuals of `Item`
(attack damage, armour, toughness, enchant value, fuel). Until a native capture exists, these keys come from
third-party dumps. They are the `NATIVE:` sources in `BehaviourKeys` and in
[data/behaviour/REPORT.md](../data/behaviour/REPORT.md).

## Output

Both options below would write the same file, `data/bds/<build>/native.json`, so a CI capture and a local one can be
diffed. The reader is `NativeCapture` in `tools` (`behaviour import` merges it); its Javadoc is the schema:

```
{
  "schema": 1,
  "bdsVersion": "1.26.63.1",
  "campaign": "campaign-2026-12-01",
  "dumper": {"name": "levilamina-dump", "version": "..."},
  "status": "captured",
  "blockTypes":  {"minecraft:stone": {"BlockType.mTranslucency": 0.0, "BlockType.mProperties": ["CubeShaped"]}},
  "blockStates": {"<state hash>": {"BlockComponentDirectData.mFriction": 0.6}},
  "items":       {"minecraft:diamond_sword": {"Item.getAttackDamage": 8}}
}
```

- The file records the build (it must be the name of its directory), the campaign tag and the dumper's name and version.
- A build the dumper does not support has `"status": "not captured"` and a `"reason"`, and no sections. `behaviour import`
  logs it, and the keys stay as they are.
- A member is named like a key's source in `BehaviourKeys`, `<Type>.<member>` (BDS's own names, as the LeviLamina headers
  spell them), so `NATIVE:BlockType.mThickness` names the member `BlockType.mThickness`. A flag such as
  `NATIVE:BlockType.mProperties.Climbable` is true when the list `BlockType.mProperties` has `Climbable`.
- A block type's members hold for every state of the block; a state's own members win. Block states are keyed by their
  network hash, like every other capture. A key without a member has no native value.
- `behaviour import` reads the file as the `NATIVE` source, ranked with the Script API above the third-party dumps. Until
  a file exists, the `NATIVE:` keys stay unverified. The tests use synthetic files in `tools/src/test/resources/native`;
  they are marked as such and are not data.

The file is independent of the add-on, the spec and the other captures, so it can be written later for any build that is
already captured (same build, same campaign tag), without a full recapture.

## Linux, in CI

Checked on 2026-11-14:

- Endstone runs on the Linux `bedrock_server` by `LD_PRELOAD`ing its runtime and finding hooks by byte signatures. It
  builds on Ubuntu, so it could run in GitHub Actions.
- Each Endstone release pins one BDS build. v0.11.15 and `main` pin 1.26.60.6, not 1.26.63.1, so an Endstone plugin
  cannot capture the builds Paletter captures. Its plugin API also exposes nothing behavioural for blocks.
- Endstone's data dumps (EndstoneMC/bedrock-data) follow every build, but have no licence, so Paletter cannot bundle
  them. At most they are a cross-check.

A CI job would download the Linux BDS, build a small `LD_PRELOAD` library that walks `BlockTypeRegistry` and the item
registry after startup, write `native.json` and stop the server. Members would be found by per-build signatures, and a
signature that does not match fails the job. The `BlockType` fields are not in BDS's reflection data, so those
signatures need upkeep for every build. Nothing was prototyped: there was no Linux machine to run `bedrock_server` on.

## Windows, locally

LeviLamina is a Windows BDS mod loader whose headers are generated from Mojang's symbols, so member names and layouts
are BDS's own. v26.60.7 is current; whether `lip` installs BDS 1.26.63 under it still needs to be checked.

```
lip install github.com/LiteLDev/LeviLamina        # installs BDS and LeviLamina
lip install <the dump mod>
bedrock_server_mod.exe
```

On `ServerStartedEvent`, the mod would write, for every `BlockType`, its flags and type-level members; for every block
permutation, `mDirectData` and the state hash; and for every `Item`, the virtuals listed above. Run on the same build as a
CI capture, the two files should match member for member. A difference is a bug in one of the dumpers.

## Workflow

`.github/workflows/native-capture.yml` is a placeholder for the Windows job. It is disabled twice: it only starts by hand
(`workflow_dispatch`, with the build and the campaign tag), and its job has `if: false`. Its steps are TODOs until the
dump mod exists. To enable it, write the mod, fill the steps and delete the `if: false` line.
