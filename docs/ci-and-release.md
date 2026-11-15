# CI, dependencies and releases

What runs on GitHub, how the build checks what it downloads, and how a release and a nightly come
about. Read it before changing anything in `.github/`, adding or updating a dependency, or
releasing.

## CI

`.github/workflows/ci.yml` runs on every push and pull request, in two stages. `Checks` needs no
Gradle and is done in seconds: the Gradle wrapper, the commit messages and the workflow scripts.
Then three jobs run side by side, each with one Gradle call: `checkChangelog` with the unit tests,
Android lint on the release variant, and a release bundle it throws away. Lint and R8 get a job
each because in one Gradle call they share the runner's cores and both take twice as long.

A push that only touches Markdown or `docs/` skips the second stage and still counts as green.
Lint is part of the gate, so a new lint error fails the branch — and so is the release bundle,
because R8, the resource shrinker and resource linking only ever run there, and a release is a bad
time to find out one of them is unhappy. Release builds skip lint's own cut-down check
(`checkReleaseBuilds`), since CI runs the full one and a release only builds a commit CI passed.

CI checks every new commit's subject and that a `feat`, `fix` or `perf` commit brings its file in
`pending-changelog/`. Since master takes pushes directly, that check comes after the push.
Dependabot and the workflows that commit write subjects in the same form as everyone else.

## Code scanning

`.github/workflows/codeql.yml` replaces GitHub's default setup, which built every variant and wrote
2.4 GB of cache per commit. It compiles only debug and release, and reads CI's dependency cache
without writing one. Default setup must stay off (Settings → Code security), or GitHub refuses the
results.

## Pinned actions

Every action in the workflows is named by a commit, not by a tag, with the version in a comment
next to it. A tag can be moved by whoever controls the action, and the release job hands actions
the upload key and the Play credentials. Dependabot (`.github/dependabot.yml`) opens a pull request
when one of them has a new version; read what changed before merging it, and pin a new action the
same way.

## Dependencies and their checksums

The build checks what it downloads. `gradle/wrapper/gradle-wrapper.properties` carries the
checksum of the Gradle distribution, CI compares `gradle-wrapper.jar` with the ones Gradle
publishes, and `gradle/verification-metadata.xml` holds the SHA-256 of every plugin and library.
A new or updated dependency fails the build until its checksum is in there:

```sh
./gradlew --write-verification-metadata sha256 build :app:bundleRelease :app:assembleMicrobenchmarkAndroidTest :baselineprofile:assemble
```

Read what it adds before committing it — a checksum that changed for a version that did not is
exactly what the file is there to catch. The release job builds without a Gradle cache from earlier
runs, so nothing another run wrote ends up in a signed build.

Dependabot updates the libraries and Gradle plugins in `gradle/libs.versions.toml` once a week,
grouped so that what is released together arrives together: Compose with its BOM, Kotlin with its
plugins, the Android Gradle plugin, the other AndroidX libraries, and every other minor or patch
update in one. Such a pull request would fail on the missing checksums, so
`.github/workflows/dependabot-checksums.yml` runs the command above on it, adds the file and starts
CI again.

It only ever adds checksums: `.github/scripts/check-verification-metadata.py` reads both files and
lets through nothing but a SHA-256 for an artifact that was not listed before. A changed or extra
checksum, a removed entry or anything new in `<configuration>` stops it and leaves it to you; its
rules are tested in `.github/scripts/test_check_verification_metadata.py`. It pushes to
Dependabot's branch, after which Dependabot no longer rebases it on its own — comment
`@dependabot recreate` to start the pull request over.

## The release

`.github/workflows/release.yml` is started by hand from the Actions tab. Nobody picks a version
there — it runs `releaseVersion`, so the pending entries decide it. It then builds and signs both
the APK and the Play bundle, pushes the release commit and the `v<version>` tag, and publishes a
GitHub release carrying that version's changelog section, grouped by level in English, the APK,
its SHA-256 and its R8 mapping. GitHub attests the APK and the bundle as built by that run, and the
README lists the signing certificate's SHA-256; a new upload key means a new line there.

Building and signing come before the push, so a failed one leaves the repository untouched, and
`dry_run` does everything except push and publish — and is the only run allowed off `master`.

It is five jobs:

- **`preflight`** checks everything that could be wrong before anything is built: every missing
  secret at once, that the keystore opens, holds the alias and that `KEY_PASSWORD` unlocks the key,
  that CI passed, that there is something to release, that the tag is free and that the versionCode
  is above the one the last tag released. The release does not test again: it asks for a green CI
  run of the newest commit that changed more than Markdown, `docs/`, `version.properties` and
  `pending-changelog/`. The release commit touches only those, and CI never runs for it: a push
  with the workflow token starts no workflow.
- **`build`** runs Gradle with `-Punsigned` and no key.
- **`sign`** is the only job with the signing secrets, and it runs no Gradle: `apksigner` and
  `jarsigner` from the runner's SDK sign what `build` made, so no plugin or library can ever reach
  the key.
- **`publish`** pushes and creates the GitHub release.
- **`play`** uploads the bundle to the track the `play_track` input names, after the release is
  out; `none`, the default, uploads nowhere. When Play refuses it, re-run that job alone instead
  of releasing again.

## Nightly

`.github/workflows/nightly.yml` builds Chatter Nightly from master every night: the newest commit
CI passed for that the last nightly did not have, checked the way the release checks it. A red or
missing CI run on the newest commit only means an older one is built, and a run still going is
waited for; with nothing green since the last nightly it builds nothing and stays green. Starting
it by hand builds even when master has not moved.

Nightly is a release build made with `-Pnightly=<hash>`: its own app (`me.lesh.chatter.nightly`),
signed with a key of its own in a job without Gradle, the way the release is, only on GitHub. Its
versionCode is the number of commits on master, because Android refuses an update to a lower one;
the hash is in the version name, `0.9.0-nightly+6d134cb`.

There is one release, tagged `nightly`, which every run deletes and creates again on the new
commit, as a pre-release so the update check never sees it. Release tags are `v*` for that reason;
anything reading the last release asks for `--match 'v*'`.

## Secrets

What the workflows read from Settings → Secrets and variables → Actions. The four keystore secrets
are not repository secrets: they live in the `release` and `nightly` environments (Settings →
Environments), under the same names, each with its own key, and both environments only let
`master` in.

| Secret | What it is | Missing |
| --- | --- | --- |
| `TWITCH_CLIENT_ID` | what `local.properties` holds on a dev machine | the release stops, rather than ship an APK that cannot log in |
| `KEYSTORE_BASE64` | the keystore of that environment, as `base64 -w0 chatter-release.jks` | the release stops; only a `dry_run` signs with a throwaway key instead |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | how to open that keystore | same |
| `PLAY_SERVICE_ACCOUNT_JSON` | the service account allowed to release to Play, as a repository secret | only needed for a `play_track` other than `none`, which stops without it |

## Play Console

`docs/play-store/` holds what the Play Console needs — the data safety answers, the foreground
service justification and the listing text. It is in `.gitignore` and lives only on the
maintainer's machine, so a fresh clone does not have it.
