# Maintainer update guide

A runbook for the recurring jobs. It only points at commands that exist; the reasons and details are in
[data-pipeline.md](data-pipeline.md). All tools run as `./gradlew :tools:run --args="<command> ..."` (`--help` lists
the commands). Captures need Windows BDS: the committed captures are from Windows, and `update` refuses another platform.

Every command names things the same way: `--version` is a BDS build number, `--channel release|preview` its channel, and
`--data-version` a bundled data version such as `26.60`. A check that finds committed output out of date, and any command that
compares, exits with 1.

The Gradle tasks are the commands with their usual arguments:

| Gradle task | Command |
|---|---|
| `inferMappings` | `infer` |
| `checkMappings` | `check-mappings --strict` |
| `generateModel` | `docs` (`-Pdocs=<minecraft-creator checkout>`) |
| `generateAddon` | `addon --out build/spec-addon` |
| `generateComponentApi`, `checkComponentApi` | `components generate`, `components check` |
| `generateConstants`, `checkConstants` | `constants generate`, `constants check` |
| `generateBehaviour`, `checkBehaviour` | `behaviour generate`, `behaviour check` |
| `generateBlocks`, `checkBlocks` | `blocks generate`, `blocks check` (`blocks export --out <dir>` writes the palettes as data) |
| `checkVoxelShapes` | `voxel-shapes check` |

## a. A new BDS release or preview appeared

`bds-data.yml` runs daily (05:17 UTC) and captures only a build it does not know yet (`daily decide`, see
[data-pipeline.md](data-pipeline.md#the-daily-check)): `update --channel release|preview`, then `generateComponentApi`,
`:paletter:apiDump` and `:paletter-recipes:apiDump`. New data becomes a pull request from `bds-data/<channel>` with data and generated paths only; a
failure becomes an issue, retried once the next day and then left until a new commit or build (`force` re-runs it). If the patch changes an add-on input
(`components.json`, `fixtures.json`, `bds-feedback.json`, `bds-keys.json`) the pull request says so at the top: label it
`recapture` before merging (section b). Nothing recaptures by itself.

Reviewing the pull request:

1. Read the first line of the report (the PR body, also `data/bds/<build>/update-report.md`): `OK`, or counts of
   failures, changes and decisions. Only "Needs attention" matters first; each item says what to do.
2. A new protocol is imported as a new data version (`versions.txt`, `paletter/src/main/resources/paletter/`). Read the
   wire-format changes in `data/model` (`mappings.json`, `coverage.md`) and the regenerated component API: a changed
   wire shape needs your review, it is never automatic. `mappings-evidence.json` changes with it (variant counts, BDS's
   messages and refused values, ranges, unexplained leaves): no generator reads it, it explains `mappings.json`.
3. The palette of a new version is not data: `update` turns it into a change class of the generated block definitions
   (`internal.vanilla.blocks/BlockChanges<version>`) and writes the version file without block states, with the digest of the
   palette, as its last step. BDS sends no palette: `update` builds the new one from the previous palette and the Script API
   dump (`ReferencePalette`), so the state order of a new or changed block is inferred. Those lines say `state order inferred,
   not observed`, and the report lists the blocks ("Inferred state order"); check them against CloudburstMC/Data once it has the
   version, and the sequential block id check ("Block numbering") of the report. A `redefine` line (other values or another
   property order) or a removed block is BDS doing something new. Since `update` builds every block as a cartesian product
   itself, a "Block definitions" FAIL there means a bug of the tools (or a hand-made version file), not BDS doing something new;
   BDS doing something new shows as creative block ids the computed hashes do not resolve.
4. Check the regenerated constants (`BedrockBlocks`, `BedrockItems`, ...) and `data/model/constants-history.json`: they
   are committed together. New names that collide or are invalid fail generation until
   `data/model/constant-names.txt` names them (`blocks|items|states|enums <key> <name>`).
5. The build must be green (`./gradlew build`, which includes `checkConstants`, `checkComponentApi`, `checkBlocks`,
   `checkVoxelShapes`, `checkMappings`, `checkData`, `checkBuilds`). A patch that changes generated code only becomes a PR with
   `BDS_DATA_TOKEN` set.
6. Merge. Decisions the report lists stay manual (below); exit code 2 means "written, decisions open".

When it reports decisions or FAILs:

- Re-run locally to reproduce: `update --channel release --version <build> --verify-only` (writes nothing, exit 1 on
  any difference), or without `--verify-only` to write. `--keep-server` keeps the BDS run for inspection.
- A decision is work for you (new constant names, a linked field, a gap); a FAIL
  stops the import. Fix the model or code, never edit `data/bds/<build>` by hand.
- Hotfix builds between two `update` builds: `builds list --from <build>` then `builds check --from <build> --limit 1`.
  A kept build with data its data version lacks is a gap and needs a data version of its own (an `import`).
- Manual after an import: the state order of newly generated blocks (check with `verify` once CloudburstMC/Data has the
  version), re-importing a protocol whose network data changed in a hotfix, a codec (section e).
- Compare the compiled palette with a CloudburstMC/Data checkout (cb/data keeps one version at HEAD, so check out the
  commit of the wanted version; the one pinned in `recapture.yml` is `0021592b1a4253528bad1e1d8b537e306e097557`, 26.60):

  ```
  git clone --filter=blob:none --no-checkout https://github.com/CloudburstMC/Data.git cb-data
  git -C cb-data checkout 0021592b1a4253528bad1e1d8b537e306e097557
  ./gradlew :tools:run --args="export --data-version 26.60 --out build/export-26.60"
  ./gradlew :tools:run --args="verify --source cb-data --data-version 26.60 --against build/export-26.60 --report build/verify-26.60.md"
  ```

  Exit code 1 on any difference. `CloudburstDataTest` does both steps for every bundled release in the normal build, against
  the cb/data files committed in `tools/src/test/resources/test-data/<version>/` (commits in its `SOURCE.md`), and writes
  `tools/build/cbdata-report-<version>.md`. A mismatch is a finding about the data, not a test to loosen: unresolved ones
  are listed in the test (`KNOWN_FINDINGS`, and the creative layout, reported and not asserted).

## b. A full recapture (add-on or capture code changed)

Captures answer for the add-on they were made with, and history checks refuse to compare spec captures of different
add-ons. After a change to the model, fixtures, `bds-feedback.json` or the add-on generator, every kept build is captured again.

- CI: the `recapture` label (added by someone with write access) on the pull request that changes the add-on, or Actions > Recapture > Run workflow, `ref` =
  the branch (empty: the branch you started it on). Nothing starts it by itself. It runs `plan` (`recapture plan`, the
  committed builds only), one Windows `capture` job per build (`update --capture-only <dir> --version <build>`), `assemble`
  (`recapture assemble`: staging, inference, behaviour, white-box coverage, code generation) and `publish` (one PR
  `recapture/<base>`). Rehearsal: `builds: 1.26.63.1` and `dry-run` (one Windows leg, no pull request).
- Merge order: recapture PR into its branch first (for a data update `bds-data/<channel>`), then that branch into `main`.
- If `assemble` exits with 3 because the inference changed an add-on input (`bds-feedback.json`, `fixtures.json`,
  `components.json` or `bds-keys.json`; the artifact `recapture-guard-diff` has them), commit that change and run again
  until a pass changes nothing.
- Local equivalent: the same commands ([data-pipeline.md](data-pipeline.md#full-recapture)): `recapture plan --output
  plan.json`, `update --capture-only` per leg, `recapture assemble`, `./gradlew :paletter:apiDump :paletter-recipes:apiDump`.
  `./gradlew :tools:generateAddon` rebuilds the add-on.

## c. A new data version line (e.g. 26.80)

1. Nothing is hand-written in `versions.txt` (generated: `<data version> <first protocol> [preview] [codec=...]`).
   `update --channel release|preview` imports a new protocol and writes the line. `--data-version <x.y>` overrides the name.
2. First protocol: the protocol of the first build that spoke it. If the version's first build was a preview, it keeps
   that preview protocol until `builds check`/`update` verify a build; `builds table` flags a data version whose first
   protocol no verified build has.
3. Previews: only the newest preview is bundled, marked `preview`. Importing a newer preview or its release drops the
   bundled preview and its `data/bds/<build>` captures. A preview newer than the newest codec is decoded with the
   newest codec and gets `codec=<protocol>`.
4. When the preview becomes a release: run `update --channel release`. Content only the preview had was marked
   `@SincePreview("<version>")` and the mark goes away on regeneration (`generateConstants`, `generateComponentApi`);
   `First in Bedrock <release>` and `@BedrockSince("<data version>")` are derived by the generators from the captures,
   never typed. Review the diff, then commit `constants-history.json` with the classes.
5. Run `builds table` (or `builds check`); `checkBuilds` fails if `data/bds/builds.md` is stale.
6. Behaviour values: `checkBehaviour` fails while a bundled release has no data in `data/behaviour/captures.nbt` (it
   would silently get the values of the version before), and when a bundled palette of a version with data changed (the
   daily `update` does not run the generator). Run `behaviour import` on the pinned checkouts (it writes the store, the
   generated classes and `REPORT.md`), or `generateBehaviour` when only the palette changed; review the
   "Version changes to check" section of the report.

## d. A new or changed component

1. `generateModel` re-reads the creator docs (`-Pdocs=<minecraft-creator checkout>`), then `generateAddon`,
   `spec-capture --channel release`, `inferMappings`, repeated until `bds-feedback.json` stops changing.
2. Inference explains each field by a rule (identity, linear, table, ...). Fields no rule explains need a hand transform:
   a method in `internal/component/Transforms.java` and an entry in `HandTransforms.java` (tools); the Javadoc must say why
   no inferred rule fits and what evidence backs it.
3. `data/model/api.json` (hand) names the Java shape only: names, unions, value forms, open enums. A union field also
   needs a hand transform until type-dispatched rules exist.
4. Run `check-mappings --strict` (Gradle `checkMappings`): integer bits no field explains, unmapped wire paths and
   `linked` fields fail. Capture again so the generator links them; if that is done, add a rule or hand transform.
5. `components generate`, then `:paletter:apiDump` for an intended API change. Commit `data/model/api-history.json` with
   the sources: it keeps the existing Java names (docs/data-pipeline.md); a component or field it reports with a
   `warning:` line left the model and keeps a deprecated stand-in, to review. `ComponentConformanceTest` rebuilds every
   accepted variant through the generated API and has no exceptions: close gaps in the model or a transform.
6. The two remaining golden files (synthetic variants, API dump fixture): `./gradlew :tools:test -PupdateGolden=true`
   if their tests fail on an intended change, then review the diff. Any model change that
   alters the add-on needs a recapture (section b).

## e. The Cloudburst protocol needs a new codec

A build with a protocol newer than the newest codec is captured with the newest one (recorded as `codec=`). To get its
own codec, bump `protocol` in `gradle/libs.versions.toml` to a build that has it, then `update` again so the capture is
decoded natively. `Capture.NEWEST` follows by itself: it is the highest `Bedrock_vNNNN` codec the library ships, and the
tools fail at start-up with a clear message if the library's codec package moves. A pinned timestamped
snapshot is allowed for releases; keep it in sync with Geyser ([releasing.md](releasing.md)). The codec itself
is added in CloudburstMC/Protocol, not here.

## f. Releasing

See [releasing.md](releasing.md): snapshots publish from `main` through `publish.yml`; a release (tag `vX.Y.Z`) needs
`checkReleaseDependencies` to pass, the deploy credentials, and `paletter/api/released.version`.

## g. Troubleshooting `update`

| Message or symptom | Meaning |
|---|---|
| `update` refuses to run, "captures of different platforms are not compared" | The committed captures are another platform than your BDS. Use Windows. |
| `--against checks release builds only`, `--capture-only ... not data/bds, not with --verify-only` | Option misuse; give a directory of its own. |
| `No kept build in data/bds to compare` | `--against` names a build with no captures. |
| `BDS answers neither a RakNet ping nor NetherNet LAN discovery with a protocol` | BDS did not start or does not speak either (every command that starts a BDS says this); read `<cache>/runs/<platform>-<version>/` logs. |
| `No experiments for the capture: no committed experiments.json at or below ...` | No earlier record; pass `--experiments a,b` (to `update` or `spec-capture`). |
| `BDS's documentation generator wrote no docs (see bds-docgen.log)` | The schema and potion step failed. |
| "The add-on's script did not register all its components" | The spec add-on's script failed in BDS; see the content log. |
| "linked", "residual bits", "unclassified wire paths" | `check-mappings --strict`; see section d, step 4. |
| Spec captures differ between builds | Different add-ons; recapture (section b). |
| `checkConstants` / `checkComponentApi` / `checkBuilds` / `checkBehaviour` / `checkBlocks` / `checkVoxelShapes` stale | Run the matching `generate` (`voxel-shapes import` for the shapes) or `builds table`; commit. `checkBlocks` also fails on a version file that still has its block states: `generateBlocks` moves them into the definitions. |
| `checkBlocks` fails: a palette is not a cartesian product, has no unique property order or is out of name hash order | A version file was imported (`import --source`) whose palette the generated definitions cannot say. Never edit the generated classes by hand: extend `BlockDefinitions` (and `BlockModel`, `BlockDiff`, `BlockCode` in the tools) so they can, with a test in `BlockCodeTest`. |
| Reading a version fails: "The block states the library derives for ... are not the ones its data file was written with" | The generated definitions and the version file are of different palettes: a hand edit of the generated classes, a merge of two data changes that each touched the chain of change classes, or a version file of another library. Take the definitions and the file from one commit (or import the version again). |
| The generated block classes do not compile after a change of `BlockDefinitions` | They are the only copy of the palettes. Before changing `BlockDefinitions`, `blocks export --out <dir>` writes every version with its block states. To recover: `git checkout <last green commit> -- paletter/src/generated/java/dev/opencollab/paletter/internal/vanilla/blocks`, or copy the exported version files into `paletter/src/main/resources/paletter`, delete the package (the library compiles without it) and run `generateBlocks`, which reads the states from the files. |
| `generateBehaviour` does not start: the generated behaviour classes do not compile (a behaviour key was renamed or removed by `generateComponentApi`, or the runtime changed) | The tools run on the compiled library, and the library compiles without those classes (`VanillaBehaviour` loads them by name). Delete `paletter/src/generated/java/dev/opencollab/paletter/internal/behaviour/vanilla` and run `generateBehaviour` again; it writes the whole directory. |
| Exit code 1 from Gradle on a run with decisions | Gradle maps every non-zero code to 1. Run the launcher instead, `./gradlew :tools:installDist` then `tools/build/install/tools/bin/tools update ...`: it exits with the tool's own code (2 = decisions open), as the workflows do. |

## h. Retiring a protocol

When Paletter stops bundling a data version, its protocol leaves the component model:

1. `retire <window protocol>` (the `windowProtocol` of its captures in `data/model/mappings.json`). It drops the
   captures of that protocol, removes it from every window of `mappings.json` and `mappings-evidence.json` (a window left
   without protocols goes), joins neighbouring windows whose wire shape is now the same (the older keeps its content, as
   `infer` does) and does the same to the envelope.
2. It prints what else belongs to the protocol and exists: the `versions.txt` row and bundled data of its data version
   (`<version>.nbt`, the recipes bundle), its `data/bds/<build>` directories, its CloudburstMC/Data test data and
   `SOURCE.md` row, its block id order file. A data version or build another capture still has is not listed. Delete or
   edit those by hand. A build directory can also hold one-off evidence that exists nowhere else and that
   [wire-notes.md](wire-notes.md) and tests pin: for `1.26.63.1` that is `creative-large`, `creative-probe-file-order`,
   `override`, `spec-keys` and `behaviour-probe` (`BlockHashesTest`, `CreativeOrderCheckTest`, `FixedAddonsTest`,
   `AbsentComponentTest`). Keep those files, or move the evidence and its tests first; the tests on committed builds
   (`CommittedBuilds`) follow a deleted directory by themselves.
3. Then `builds table`, `inferMappings` (it must change nothing: the captures are gone), `generateComponentApi`,
   `generateConstants`, `generateBehaviour` and `generateBlocks`, each in its own Gradle run; the build must be green.
   `generateBlocks` makes the next version `BlockBase` of the block definitions, with what it inherited folded in, and drops
   its change class (the next version's palette comes from the definitions as committed, which still start at the retired
   version). `retire` has marked the
   data version retired in `data/behaviour/captures.nbt` (its captures stay: newer versions inherit from them), so
   `generateBehaviour` writes the next version as `BehaviourBase` with everything it inherited folded in, and drops the
   retired version's class, and the "Version changes to check" section of `data/behaviour/REPORT.md`; the rest of the
   report lists the version until the next `behaviour import`. That import keeps the retired version's captures once its
   palette is deleted (read back from `captures.nbt`, they cannot be read from the sources without it). A component no remaining window has leaves
   `mappings.json` and its API keeps a stand-in (`api-history.json`).

## Pins

Every version, commit or credential that someone bumps by hand. Nothing else moves without a code change; what moves by
itself is listed below the table.

| Pin | Where | When and how to bump |
|---|---|---|
| Cloudburst protocol snapshot (`protocol`) | `gradle/libs.versions.toml` | A BDS protocol without its own codec (section e). Follow Geyser's `gradle/libs.versions.toml`; then `update` again. |
| CloudburstMC/Network snapshot (`network`, NetherNet) | `gradle/libs.versions.toml` | With Geyser, or when the capture client cannot reach a new BDS over NetherNet. |
| libdatachannel snapshot (`libdatachannel`) | `gradle/libs.versions.toml` | With `network`, whose NetherNet transport needs it. |
| MicrosoftDocs/minecraft-creator commit (`DocsFetcher.PINNED_COMMIT`) | `tools/.../model/docs/DocsFetcher.java` | When the creator docs document new components: run `docs --commit <sha>` (without it the commit already in `components.json` is used, and `PINNED_COMMIT` only when there is none), set `PINNED_COMMIT` to the same commit, review `data/model`, recapture (section b). |
| CloudburstMC/Data and AllayMC/Allay commits | `data/behaviour/sources.json` | When they have dumps of a newer version: move both pins, run `behaviour import` on checkouts at them, commit `data/behaviour/captures.nbt`, the generated behaviour classes (`paletter/src/generated/.../internal/behaviour/vanilla`) and `data/behaviour/REPORT.md` with the pins; review the `SOURCE ONLY` changes the report lists. The import fails on a checkout at another commit. |
| Test data of `CloudburstDataTest` (CloudburstMC/Data files and commits) | `tools/src/test/resources/test-data/` and its `SOURCE.md` | With the CloudburstMC/Data pin above (a test ties the newest snapshot's commit to it), and when the bundled data of a version is refreshed from another BDS build (a test compares the build in `SOURCE.md`): `git show <commit>:<file>` from a clone of CloudburstMC/Data into the version's folder, then update its row. |
| Format version of the fixed add-ons (`FixedAddons.FORMAT_VERSION`) | `tools/.../model/addon/FixedAddons.java` and `data/model/fixtures.json` `formatVersion` | When BDS retires the format: change both (a test ties them), then recapture (section b). |
| Recipe unlock formats (`UnlockProbe.FORMATS`) | `tools/.../model/addon/UnlockProbe.java` | Copied from the vanilla packs of 1.26.63.1: add the formats a new build's `behavior_packs/**/recipes` use. |
| Engine versions of the extra spec packs (`AddonWriter.ENGINE_VERSIONS`) | `tools/.../model/addon/AddonWriter.java` | When BDS refuses a `min_engine_version`: drop it, recapture (section b). |
| `@minecraft/server` of the Script API dump add-on | `data/addons/dump/manifest.json` | When BDS no longer ships `2.4.0` (the dump fails to load): set a stable version the build's `script-modules.json` lists. The behaviour dump add-on's beta version is set per run from that file. |
| Script module of the script custom components (`scriptComponents.module`) | `data/model/fixtures.json` | Like the dump add-on's: a stable version the build ships. |
| Windows runner image (`windows-2025`) | `.github/workflows/bds-data.yml`, `recapture.yml`, `native-capture.yml` | Deliberately, when GitHub retires the image: bump all three, then run `bds-data.yml` with `verify-only` on the newest kept build; any difference is the image's, not BDS's. |
| GitHub Actions (`uses: ...@<sha>`) and Gradle dependencies | `.github/workflows/*.yml`, `gradle/libs.versions.toml` | Dependabot proposes them weekly; the Cloudburst and opencollab snapshots above are left out. |
| `BDS_DATA_TOKEN` | Repository secret | Before it expires (a fine-grained token lasts at most a year): [releasing.md](releasing.md#repository-setup), "Rotation". |

What follows by itself: `Capture.NEWEST` (the highest codec of the protocol library), the newest build of each channel
(the download links API, `builds resolve`), the list of release builds (Bedrock-OSS/BDS-Versions at its newest commit,
recorded in `data/bds/builds.json`), the zip checksums of kept builds (`builds check`, `builds record`) and the community
wiki pages `KeyDiscovery` reads (`WikiSource`, the moving `wiki` branch of Bedrock-OSS/bedrock-wiki; not pinned).
