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
blocked by the Cloudburst dependencies, which are snapshots.

### Publishing setup

`gradle/publishing.gradle.kts` is applied by `:paletter` and `:paletter-recipes` (a new module is published by adding
that `apply(from = ...)` line). It publishes the jar, the sources jar, the Javadoc jar and a POM (name, description, URL,
MIT license, organization, developers, SCM, issues). Everything configurable is in the root `gradle.properties`:

- `group` and `version` (`org.geysermc.paletter`, `1.0.0-SNAPSHOT`), the POM metadata (`paletter.pom.*`) and the
  repository URLs (`paletter.repo.*`). The artifact id is the Gradle project name: `paletter`, `paletter-recipes`.
- **The coordinates and metadata are pending the user's confirmation.** Change them only in `gradle.properties`.
- Developers are a generic organization entry; credentials are never in the repository (`paletterRepoUser` and
  `paletterRepoPassword`, from `~/.gradle/gradle.properties`). CI has no publishing job and no secrets for it.

### Guards

- `./gradlew publishToMavenLocal` always works: use it for local consumption.
- Every publish to the remote repository (`publish`, `publish*ToRemoteRepository`) runs `checkPublishAllowed` first and
  fails unless `-Ppaletter.deploy=true` is given.
- A SNAPSHOT version goes to the snapshots repository, a release version to the releases repository; the repository
  follows the version. A release also fails unless the working tree is clean (tracked files).
- Not done yet: signing, and a `checkReleaseDependencies` task that fails a release on any `-SNAPSHOT` dependency.

### Steps

1. On a green `main`, set `version` in the root `gradle.properties` to `X.Y.Z` (no `-SNAPSHOT`) and commit.
2. Tag the commit `vX.Y.Z` and publish a GitHub release from the tag. `javadoc.yml` publishes its Javadoc to
   `/X.Y.Z/` on Pages; it refuses a tag that is not `v` followed by dot separated numbers.
3. Publish the artifacts: `./gradlew publish -Ppaletter.deploy=true` (clean tree, credentials set).
4. Write `X.Y.Z` into `paletter/api/released.version`. From then on `apiCompat` (part of `check`) fails on binary
   incompatible changes of the exported API against that release (see [api.md](api.md)).
5. Set `version` to the next `-SNAPSHOT` and commit.
