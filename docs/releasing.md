# Repository setup and releases

## Repository setup

What the workflows in `.github/workflows` need from the GitHub repository settings.

**Actions permissions** (Settings > Actions > General):

- Workflow permissions can stay at the read-only default: every workflow states the permissions of each job.
- Enable "Allow GitHub Actions to create and approve pull requests": `bds-data.yml` and `recapture.yml` open their
  pull requests with `GITHUB_TOKEN`, then start `build.yml` on them by hand, since such a pull request starts no
  workflow itself.

**Publishing** (`publish.yml`, Settings > Secrets and variables > Actions). Set exactly these; the names are Geyser's:

| Name | Where | Value | Required |
|------|-------|-------|----------|
| `DEPLOY_USER` | Variables tab (a variable, not a secret) | the repo.opencollab.dev deploy user | yes |
| `DEPLOY_PASS` | Secrets tab | that user's password | yes |
| `PALETTER_SIGNING_KEY` | Secrets tab | an ASCII-armored PGP private key | no |
| `PALETTER_SIGNING_PASSWORD` | Secrets tab | the key's passphrase | no, but needed with the key |

The deploy user needs write access to `dev/opencollab/paletter/` in `maven-snapshots` (and in `maven-releases`, for the
first release). Without `DEPLOY_USER` or `DEPLOY_PASS` the publish job fails; it never succeeds without uploading.
Without both signing secrets nothing is signed, which repo.opencollab.dev accepts.

No other secrets are used: `javadoc.yml` pushes with `GITHUB_TOKEN`.

**Pages** (Settings > Pages): source "Deploy from a branch", branch `gh-pages`, folder `/ (root)`. The branch is
created by the first run of `javadoc.yml` (a push to `main`).

**Branch protection** for `main`: require the `build` check of `build.yml`. Its `./gradlew build` runs the tests, the
Javadoc, the examples, the API dump check and the checks of the generated code and data.

**Dependabot** (`.github/dependabot.yml`) proposes action and Gradle updates weekly. The Cloudburst and opencollab
snapshots are left out: they follow Geyser's `gradle/libs.versions.toml` by hand.

**Labels**: `bds-data`, `release` and `preview` are created by `bds-data.yml` when it first needs them.

## Releasing

Paletter is published as snapshots only: every push to `main` uploads `1.0.0-SNAPSHOT` to
`https://repo.opencollab.dev/maven-snapshots`. There is no release yet, and there can be none while a dependency is a
snapshot: Cloudburst's protocol library (`bedrock-codec`, `common`) has no 3.0.0 release, only timestamped snapshot
builds, and `checkReleaseDependencies` fails a release version on any snapshot dependency. The release path below is
set up and waits for that release.

### How publishing is built

Two convention plugins in `build-logic` (applied by `:paletter` and `:paletter-recipes`; a module is published by
applying both):

- `paletter.publish-conventions`: [Indra](https://github.com/KyoriPowered/indra) 4.0.0 (`net.kyori.indra`,
  `net.kyori.indra.publishing`, as in Geyser). The publication `maven` holds the jar, the sources jar, the Javadoc jar
  (all with `META-INF/LICENSE`) and a POM with name, the module's `description` (set in its build script), URL, MIT
  license, organization, developers, SCM, issues and CI. It also sets the bytecode level (`paletter.javaTarget` in
  `gradle.properties`, built with JDK 21 everywhere), the strict Javadoc (every doclint group including `missing`,
  warnings are errors) and the checks below.
- `paletter.api-conventions`: `module-info.java` validation, the API dump (`apiDump`/`apiCheck`) and `apiCompat`.

Coordinates: `group` and `version` in the root `gradle.properties`; the artifact id is the project name (`paletter`,
`paletter-recipes`).

Repositories, chosen by the version (Indra): a `-SNAPSHOT` goes to
`https://repo.opencollab.dev/maven-snapshots`, anything else to `https://repo.opencollab.dev/maven-releases`. The
repository is named `geysermc` and takes Geyser's credential properties `geysermcUsername` and `geysermcPassword`
(`~/.gradle/gradle.properties`, or `ORG_GRADLE_PROJECT_geysermcUsername` / `..._geysermcPassword` in the environment;
`publish.yml` maps `DEPLOY_USER` / `DEPLOY_PASS` to them). Nothing secret is in the repository.

### Guards

- `./gradlew publishToMavenLocal` always works, without credentials or a clean tree: use it for local consumption
  (`~/.m2/repository/dev/opencollab/paletter/`).
- `./gradlew publish` (and every `publish...ToGeysermcRepository`) fails in `requirePublishCredentials` unless both
  credential properties are set and not empty. Indra on its own would skip the repository and report success.
- Indra's `requireClean` fails a remote publish if the working tree has changes, untracked files included;
  `requireTagged` fails a release version unless `HEAD` carries a tag.
- `checkReleaseDependencies` (per module, part of `check` and run before every remote publish) resolves the module's
  whole runtime graph, transitive dependencies included, which is what a consumer gets. It always fails on a floating
  `-SNAPSHOT`: every snapshot must be pinned to a timestamped build (`3.0.0.Beta15-20261116.161745-6`), so a published
  Paletter names one build of each dependency. For a release version it also fails on a pinned snapshot. Try it with
  `./gradlew checkReleaseDependencies -Pversion=1.0.0`; today it names the Cloudburst protocol builds.
- The Cloudburst codec's own metadata asks for the floating `common:3.0.0.Beta15-SNAPSHOT`, and `common` for
  `org.cloudburstmc.math:immutable:2.0-SNAPSHOT`. `:paletter` declares both itself (`common` at the codec's build, math
  at its `2.0` release) so the pins win in Gradle and Maven. When bumping the protocol, take the newest timestamped
  build from the repository's `maven-metadata.xml` (the one `protocol` version is used for codec, connection and
  common), keep it in sync with Geyser, and run `checkReleaseDependencies`.
- Signing is optional. Only with the properties `paletterSigningKey` (ASCII-armored private key, or a path to a key
  file) and `paletterSigningPassword` (`PALETTER_SIGNING_KEY` / `PALETTER_SIGNING_PASSWORD` in `publish.yml`) are
  release artifacts signed; Indra signs releases only, never snapshots. Without the key nothing is signed.

### Snapshots

Nothing to do: `publish.yml` publishes the `-SNAPSHOT` version of `main` on every push, after a full `build`. A
consumer that wants one fixed build uses the timestamped version from
`https://repo.opencollab.dev/maven-snapshots/dev/opencollab/paletter/paletter/1.0.0-SNAPSHOT/maven-metadata.xml`.

### Releasing a version

Waits for a Cloudburst protocol 3.0.0 release (see above); until then step 2 fails in `checkReleaseDependencies`.

1. On a green `main`, set the protocol pins to the release, set `version` in the root `gradle.properties` to `X.Y.Z`
   (no `-SNAPSHOT`), and update the version in the installation snippets of the [README](../README.md#installation) and
   [api.md](api.md#installation). Commit.
2. Tag the commit `vX.Y.Z` and push the commit and the tag. `publish.yml` skips the branch push (a release version is
   published from its tag only), checks that the tag matches `version`, builds and publishes to `maven-releases`.
3. Publish a GitHub release from the tag. `javadoc.yml` publishes its Javadoc to `/X.Y.Z/` on Pages; it refuses a tag
   that is not `v` followed by dot separated numbers.
4. Write `X.Y.Z` into `paletter/api/released.version` and `paletter-recipes/api/released.version`. From then on
   `apiCompat` (part of `check`) fails on binary incompatible changes of the exported API against that release (see
   [api.md](api.md)).
5. Set `version` to the next `-SNAPSHOT` and commit. The snippets keep naming the release.
