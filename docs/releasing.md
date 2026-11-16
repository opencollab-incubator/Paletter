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

Paletter has not been released yet. Publishing to a Maven repository is not set up: there is no repository, POM
metadata or signing in `paletter/build.gradle.kts`, and the Cloudburst dependencies are snapshots, which a release
cannot depend on. Until then, `./gradlew :paletter:publishToMavenLocal` builds the jar, sources and Javadoc jars for
local use.

The steps, once publishing exists:

1. On a green `main`, set `version` in the root `build.gradle.kts` to `X.Y.Z` (no `-SNAPSHOT`) and commit.
2. Tag the commit `vX.Y.Z` and publish a GitHub release from the tag. `javadoc.yml` publishes its Javadoc to
   `/X.Y.Z/` on Pages; it refuses a tag that is not `v` followed by dot separated numbers.
3. Publish the artifact (`./gradlew :paletter:publish`, once a repository is configured).
4. Write `X.Y.Z` into `paletter/api/released.version`. From then on `apiCompat` (part of `check`) fails on binary
   incompatible changes of the exported API against that release (see [api.md](api.md)).
5. Set `version` to the next `-SNAPSHOT` and commit.
