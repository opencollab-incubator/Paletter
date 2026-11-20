# Maintainer update guide

A runbook for the recurring jobs. It only points at commands that exist; the reasons and details are in
[data-pipeline.md](data-pipeline.md). All tools run as `./gradlew :tools:run --args="<command> ..."` (`--help` lists
the commands). Captures need Windows BDS: the committed captures are from Windows, and `update` refuses another platform.

## a. A new BDS release or preview appeared

`bds-data.yml` runs daily (05:17 UTC) for both channels: `update --channel release|preview`, then `generateComponentApi`
and `:paletter:apiDump`. A clean day writes nothing and closes the channel's open issue. Otherwise it opens an issue and
a pull request from `bds-data/<channel>` with data and generated paths only. If the patch changes an add-on input
(`components.json`, `fixtures.json`, `bds-feedback.json`, `bds-keys.json`) it calls `recapture.yml` on that branch (section b).

Reviewing the pull request:

1. Read the first line of the report (the PR body, also `data/bds/<build>/update-report.md`): `OK`, or counts of
   failures, changes and decisions. Only "Needs attention" matters first; each item says what to do.
2. A new protocol is imported as a new data version (`versions.txt`, `paletter/src/main/resources/paletter/`). Read the
   wire-format changes in `data/model` (`mappings.json`, `coverage.md`) and the regenerated component API: a changed
   wire shape needs your review, it is never automatic.
3. Check the regenerated constants (`BedrockBlocks`, `BedrockItems`, ...) and `data/model/constants-history.json`: they
   are committed together. New names that collide or are invalid fail generation until
   `data/model/constant-names.txt` names them (`blocks|items|states|enums <key> <name>`).
4. The build must be green (`./gradlew build`, which includes `checkConstants`, `checkComponentApi`, `checkMappings`,
   `checkData`, `checkBuilds`). A patch that changes generated code only becomes a PR with `BDS_DATA_TOKEN` set.
5. Merge. Decisions the report lists stay manual (below); exit code 2 means "written, decisions open".

When it reports decisions or FAILs:

- Re-run locally to reproduce: `update --channel release --version <build> --verify-only` (writes nothing, exit 1 on
  any difference), or without `--verify-only` to write. `--keep-server` keeps the BDS run for inspection.
- A decision is work for you (new constant names, a linked field, a gap); a FAIL
  stops the import. Fix the model or code, never edit `data/bds/<build>` by hand.
- Hotfix builds between two `update` builds: `builds list --from <build>` then `builds check --from <build> --limit 1`.
  A kept build with data its data version lacks is a gap and needs a data version of its own (an `import`).
- Manual after an import: the state order of newly generated blocks (check with `verify` once CloudburstMC/Data has the
  version), re-importing a protocol whose network data changed in a hotfix, a codec (section e).

## b. A full recapture (add-on or capture code changed)

Captures answer for the add-on they were made with, and history checks refuse to compare spec captures of different
add-ons. After a change to the model, fixtures, `bds-feedback.json` or the add-on generator, every kept build is captured again.

- CI: Actions > Recapture > Run workflow, `ref` = the branch (empty: the branch you started it on). It runs `plan`
  (`builds plan`), one Windows `capture` job per build (`update --capture-only <dir> --version <build>`), `assemble`
  (inference, white-box coverage, code generation, `voxel-shapes import`) and `publish` (one PR `recapture/<base>`).
- Merge order: recapture PR into `bds-data/<channel>` first, then the data PR into `main`.
- If `assemble` fails because inference changed `bds-feedback.json`, `fixtures.json`, `components.json` or
  `bds-keys.json`, commit that change and run again until a pass changes nothing.
- Local equivalent, per build: `builds plan --output plan.json` lists the legs, then
  `update --channel <release|preview> --version <build> --capture-only <dir> [--against <build>]`. Copy the files over
  `data/bds/<build>/`, then run `infer`, `whitebox-coverage`, `components generate`, `constants generate`,
  `voxel-shapes import` (the same steps `assemble` runs). `./gradlew :tools:generateAddon` rebuilds the add-on.

## c. A new data version line (e.g. 26.80)

1. Nothing is hand-written in `versions.txt` (generated: `<data version> <first protocol> [preview] [codec=...]`).
   `update --channel release|preview` imports a new protocol and writes the line. `--name <x.y>` overrides the name.
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
5. `components generate`, then `:paletter:apiDump` for an intended API change. `ComponentConformanceTest` rebuilds every
   accepted variant through the generated API and has no exceptions: close gaps in the model or a transform.
6. Generator golden files: `./gradlew :tools:test -PupdateGolden=true`, then review the diff. Any model change that
   alters the add-on needs a recapture (section b).

## e. The Cloudburst protocol needs a new codec

A build with a protocol newer than the newest codec is captured with the newest one (recorded as `codec=`). To get its
own codec, bump `protocol` in `gradle/libs.versions.toml` to a build that has it, then `update` again so the capture is
decoded natively. `Capture.NEWEST` follows by itself: it is the highest `Bedrock_vNNNN` codec the library ships, and the
tools fail at start-up with a clear message if the library's codec package moves. A pinned timestamped
snapshot is allowed for releases; keep it in sync with Geyser ([releasing.md](releasing.md)). The codec itself
is added in CloudburstMC/Protocol, not here.

## f. Releasing

See [releasing.md](releasing.md): guards, `checkReleaseDependencies`, tag `vX.Y.Z`, `publish -Ppaletter.deploy=true`,
`paletter/api/released.version`.

## g. Troubleshooting `update`

| Message or symptom | Meaning |
|---|---|
| `update` refuses to run, "captures of different platforms are not compared" | The committed captures are another platform than your BDS. Use Windows. |
| `--against checks release builds only`, `--capture-only ... not data/bds, not with --verify-only` | Option misuse; give a directory of its own. |
| `No kept build in data/bds to compare` | `--against` names a build with no captures. |
| `BDS answers neither a RakNet ping nor NetherNet LAN discovery` | BDS did not start or does not speak either; read `<cache>/runs/<platform>-<version>/` logs. |
| `No experiments for the capture: no committed experiments.json at or below ...` | No earlier record; pass `--experiments a,b`. |
| `BDS's documentation generator wrote no docs (see bds-docgen.log)` | The schema and potion step failed. |
| "The add-on's script did not register all its components" | The spec add-on's script failed in BDS; see the content log. |
| "linked", "residual bits", "unclassified wire paths" | `check-mappings --strict`; see section d, step 4. |
| Spec captures differ between builds | Different add-ons; recapture (section b). |
| `checkConstants` / `checkComponentApi` / `checkBuilds` stale | Run the matching `generate` or `builds table`; commit. |
| Exit code 1 from Gradle on a run with decisions | Gradle maps every non-zero code to 1; read `exit-code` in the run's work directory (2 = decisions open). |
