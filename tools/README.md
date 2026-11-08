# tools

All tooling in one module, never shipped. Packages under `org.geysermc.paletter.tools`:

| Package   | What |
|-----------|------|
| `bds`     | Downloading and running Bedrock Dedicated Server, the Script API dump (`data/addons/dump`) |
| `client`  | The headless Bedrock client that records what BDS sends (NetherNet and RakNet), `level.dat` experiments |
| `data`    | Importing palette dumps into the bundled format, `Diff` (the one diff engine), `Nbt` (the one NBT helper), `WireShapes`, version coverage checks |
| `model`   | The component model: creator docs parser, the add-on generators (`model.addon`: the spec add-on and the fixed capture/creative add-ons), mapping inference (`model.infer`) |
| `codegen` | The generators: the component API (`codegen.component`, its own `componentApi` source set) and the vanilla constants (`codegen.constants`) |
| `update`  | The `update` pipeline and its report, the spec capture, experiment discovery and attribution |

One command line, picocli (`Cli`): `./gradlew :tools:run --args="<command> ..."`; `--help` lists the commands, each takes
`--help`. Paths are relative to the repository root (the working directory of `:tools:run`; `--root` overrides).

| Command | Does |
|---------|------|
| `update --channel release\|preview [--verify-only] ...` | The whole BDS update pipeline (see the README, "Data") |
| `import`, `bds-run`, `bds-compare`, `verify`, `check-data` | Importing and checking bundled data (`check-data` lists what older versions lack of the newest) |
| `block-items`, `rewrite`, `data-diff` | Maintaining bundled data without a capture: add the block to item relation (from a stored dump, or derived from a newer version), rewrite files without fallbacks, diff two data files |
| `spec-capture` | Runs the spec add-on on BDS, writes `data/bds/<version>/spec` |
| `creative-probe` | Runs a creative add-on on BDS and records the creative order |
| `capture`, `compare`, `experiments` | The capture client: record a server, diff it with a bundled version, switch experiments in a `level.dat` |
| `docs`, `addon [--kind spec\|capture\|creative\|creative-large]`, `infer`, `check-mappings` | The component model ([docs/model.md](docs/model.md)) and the add-on packs |
| `inspect --version <v\|all> --block '<id[states]>' \| --item '<id[:damage]>'` | Prints `Palette#explain` of a block state or item variant for one or all bundled versions: canonical, runtime and hashed id, how it resolves (exact, adapted, fallback, default), the block sent with an item, the creative location |
| `constants generate\|check`, `components generate\|check` | The generators ([docs/codegen.md](docs/codegen.md)) |

Gradle tasks (run from anywhere in the build): `generateConstants`, `checkConstants`, `generateComponentApi`,
`checkComponentApi`, `generateComponentApiPreview`, `generateModel`, `generateAddon`, `inferMappings`, `checkMappings`.
The `check` tasks are part of `./gradlew build`; the generated sources they guard are in `paletter/src/generated/java`.
Golden files: `./gradlew :tools:test -PupdateGolden=true` after an intended change of generator output, then review.

Other documents: [docs/DEVIATIONS.md](docs/DEVIATIONS.md) (migration notes of the generated encoders),
[../data/bds/WIRE-NOTES.md](../data/bds/WIRE-NOTES.md) (what BDS sends), [../data/bds/CREATIVE-ORDER.md](../data/bds/CREATIVE-ORDER.md).
