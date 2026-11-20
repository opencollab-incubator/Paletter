# Repository setup and releases

## Repository setup

What the workflows in `.github/workflows` need from the GitHub repository settings.

**Actions permissions** (Settings > Actions > General):

- Workflow permissions can stay at the read-only default: every workflow states the permissions of each job.
- Enable "Allow GitHub Actions to create and approve pull requests". `bds-data.yml` opens its pull requests with
  `GITHUB_TOKEN` when no `BDS_DATA_TOKEN` is set.

**Secrets** (Settings > Secrets and variables > Actions):

- `BDS_DATA_TOKEN` (optional, recommended): a fine-grained personal access token for this repository only, with
  contents and pull requests read and write. `bds-data.yml` opens its pull requests with it, so `build.yml` runs on
  them; a pull request opened with `GITHUB_TOKEN` starts no other workflow. Without it, a data update that changes
  generated code (`paletter/src/generated`) fails the publish job instead of opening a pull request nobody built. Only
  the publish job sees the secret, never the job that runs BDS and Gradle.

  **Rotation.** A fine-grained token expires after at most a year, and the workflows cannot tell: an expired token still
  counts as set, and the publish job then fails in `create-pull-request` with an authentication error (HTTP 401 or "Bad
  credentials"). Give the token an expiry date, put a reminder a few weeks before it, and rotate it then:

  1. GitHub > Settings > Developer settings > Fine-grained tokens > Generate new token: resource owner the repository's
     owner, only this repository, permissions Contents and Pull requests read and write, an expiry date.
  2. Repository Settings > Secrets and variables > Actions > `BDS_DATA_TOKEN` > Update secret, paste the new token.
  3. Run `bds-data.yml` by hand (Actions > BDS data > Run workflow, `verify-only` off) or wait for the next daily run, and
     check that the publish job opens or updates its pull request.
  4. Revoke the old token.

  A failed publish with an authentication error means this step is due. It is listed with the other manual bumps in
  [updating.md](updating.md#pins).

No other secrets are used: `javadoc.yml` pushes with `GITHUB_TOKEN`.

**Pages** (Settings > Pages): source "Deploy from a branch", branch `gh-pages`, folder `/ (root)`. The branch is
created by the first run of `javadoc.yml` (a push to `main`).

**Branch protection** for `main`: require the `build` check of `build.yml`. Its `./gradlew build` runs the tests, the
Javadoc, the examples, the API dump check and the checks of the generated code and data.

**Dependabot** (`.github/dependabot.yml`) proposes action and Gradle updates weekly. The Cloudburst and opencollab
snapshots are left out: they follow Geyser's `gradle/libs.versions.toml` by hand.

**Labels**: `bds-data`, `release` and `preview` are created by `bds-data.yml` when it first needs them.

## Releasing

Paletter has not been released yet. Publishing is configured, but the deploy step is blocked, and a release is still
blocked by the Cloudburst dependencies, which are snapshots (`checkReleaseDependencies` reports them).

### Publishing setup

`gradle/publishing.gradle.kts` is applied by `:paletter` and `:paletter-recipes` (a new module is published by adding
that `apply(from = ...)` line). It publishes the jar, the sources jar, the Javadoc jar and a POM (name, description, URL,
MIT license, organization, developers, SCM, issues). Everything configurable is in the root `gradle.properties`:

- `group` and `version` (`dev.opencollab.paletter`, `1.0.0-SNAPSHOT`), the POM metadata (`paletter.pom.*`) and the
  repository URLs (`paletter.repo.*`). The artifact id is the Gradle project name: `paletter`, `paletter-recipes`.
  Change them only in `gradle.properties`.
- Developers are a generic organization entry; credentials are never in the repository (`paletterRepoUser` and
  `paletterRepoPassword`, and the signing key, from `~/.gradle/gradle.properties` or the environment). CI has no
  publishing job and no secrets for it.

### Guards

- `./gradlew publishToMavenLocal` always works: use it for local consumption.
- Every publish to the remote repository (`publish`, `publish*ToRemoteRepository`) runs `checkPublishAllowed` first and
  fails unless `-Ppaletter.deploy=true` is given.
- A SNAPSHOT version goes to the snapshots repository, a release version to the releases repository; the repository
  follows the version. A release also fails unless the working tree is clean (tracked files).
- Signing uses Gradle's `signing` plugin with in-memory keys: the properties `signingKey` (ASCII-armored private key)
  and `signingPassword`, or the environment variables `SIGNING_KEY` and `SIGNING_PASSWORD`. Keys are never in the
  repository. Signing is optional: without a key the sign tasks are skipped, so `publishToMavenLocal` and snapshot
  publishes work. A release publish to the remote repository fails in `checkPublishAllowed` without a key.
- `checkReleaseDependencies` (per module) fails if a published runtime dependency (POM `compile`/`runtime` scope of
  `paletter` and `paletter-recipes`) is a floating `-SNAPSHOT` version, and names it. A pinned timestamped snapshot
  (`3.0.0.Beta15-20261116.161745-6`, the Cloudburst protocol library) is allowed: it names one uploaded build, so the release is
  reproducible. `checkPublishAllowed` runs it for releases only. Try it with
  `./gradlew checkReleaseDependencies -Pversion=1.0.0`; it passes while every snapshot dependency is pinned. To bump the pin,
  take the newest timestamped build from the repository's `maven-metadata.xml` and keep it in sync with Geyser.

### Steps

1. On a green `main`, set `version` in the root `gradle.properties` to `X.Y.Z` (no `-SNAPSHOT`) and commit.
2. Tag the commit `vX.Y.Z` and publish a GitHub release from the tag. `javadoc.yml` publishes its Javadoc to
   `/X.Y.Z/` on Pages; it refuses a tag that is not `v` followed by dot separated numbers.
3. Publish the artifacts: `./gradlew publish -Ppaletter.deploy=true` (clean tree, credentials and signing key set,
   no snapshot dependencies).
4. Write `X.Y.Z` into `paletter/api/released.version`. From then on `apiCompat` (part of `check`) fails on binary
   incompatible changes of the exported API against that release (see [api.md](api.md)).
5. Set `version` to the next `-SNAPSHOT` and commit.
