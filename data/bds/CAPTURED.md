# Captured from BDS

Everything under `data/bds/<build>/` was captured from the Bedrock Dedicated Server build named by the directory (for
example `1.26.63.1`), by a tool of this repository. None of it is written by hand and none of it is edited: a capture is
replaced by running the command again (usually through `update`, see the README, "Data"). The binary captures (`.nbt`,
`.gz`) carry no marker of their own, so this file is their marker; the JSON captures also name their build in `bdsVersion`.
`CapturedMarkersTest` fails if a build directory is not listed here.

| Path in `data/bds/<build>/` | Captured from BDS by |
|-----------------------------|----------------------|
| `dump.json.gz` | `./gradlew :tools:run --args="bds-run ..."` (the Script API dump pack `data/addons/dump`), baseline of `update` |
| `experiments.json` | `update` (experiment discovery on BDS) |
| `education.json` | `education-capture` |
| `vanilla/` | `capture` (the headless client against vanilla BDS; `compare-*.txt` is the output of `compare`) |
| `addon/` | `capture` against BDS with the generated pack `data/addons/capture` |
| `creative-probe/`, `creative-probe-file-order/`, `creative-large/` | `creative-probe` / `capture` with the generated packs `data/addons/creative` and `addon --kind creative-large` |
| `spec/` | `spec-capture` (the spec add-on generated from `data/model`, run on BDS); input of `infer` |

## Builds

Each build directory below holds captures of that BDS build only.

- `1.26.36.1`
- `1.26.45.1`
- `1.26.52.3`
- `1.26.63.1`
