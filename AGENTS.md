# Working on Chatter

Chatter is an Android Twitch chat client: Kotlin, Jetpack Compose, one Gradle module (`app`), no
dependency injection framework — everything long-lived is built and wired in `AppContainer`, and the
UI reaches it through `MainViewModel`.

## Every change gets a changelog entry

Chatter keeps its changelog the way [Changesets](https://github.com/changesets/changesets) keeps
one: **one file per change** in `pending-changelog/`, instead of everyone editing a shared list. Two
branches then never collide over the changelog, and a release can work out its own version.

A change is not finished until it has an entry. Write it in the same commit as the change itself:

```text
pending-changelog/nicknames-per-channel.txt
```

```text
minor: Let chatters be given a nickname in one channel only
```

Pick the level by what the change does to someone using the app — `major:` takes something away,
`minor:` gives them something new, `patch:` fixes or polishes. A release moves the version by the
largest level among the pending entries. `pending-changelog/README.md` has the full format.

Write the line for the person who will read it in the app, not for whoever reviews the diff: what
they can now do, one line, no trailing dot, under 120 characters. Run `./gradlew checkChangelog` and
it will tell you if the line does not hold up.

The only changes that need no entry are the ones nobody using the app can notice: a refactor that
keeps behaviour, a test, a build tweak, a doc. Say so in the commit message when you skip one.

## Never edit these by hand

- `version.properties` — `./gradlew releaseVersion` writes it. Describe the change in
  `pending-changelog/` and let the level decide the version.
- `CHANGELOG.md` — the same release task writes it, folding in the pending entries. The app ships
  this very file as an asset and shows it under Settings → About → Changelog, so its shape matters:
  `## <version> — <date>` opens a release and `- <level>: <text>` is one entry.

## CI does the releasing

`.github/workflows/ci.yml` runs on every push and pull request: `checkChangelog`, the unit tests,
Android lint on the release variant, a debug APK it keeps as an artifact, and a release bundle it
throws away. Lint is part of the gate, so a new lint error fails the branch — and so is the release
bundle, because R8, the resource shrinker and resource linking only ever run there, and a release
is a bad time to find out one of them is unhappy.

Every action in the workflows is named by a commit, not by a tag, with the version in a comment
next to it. A tag can be moved by whoever controls the action, and the release job hands actions
the upload key and the Play credentials. Dependabot (`.github/dependabot.yml`) opens a pull request
when one of them has a new version; read what changed before merging it, and pin a new action the
same way.

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

Dependabot does the same for the libraries and Gradle plugins in `gradle/libs.versions.toml`, once
a week, grouped so that what is released together arrives together: Compose with its BOM, Kotlin
with its plugins, the Android Gradle plugin, the other AndroidX libraries, and every other minor
or patch update in one. Such a pull request would fail on the missing checksums, so
`.github/workflows/dependabot-checksums.yml` runs the command above on it, adds the file and starts
CI again. It only ever adds checksums: if one that was there changes, it stops and leaves it to
you. It pushes to Dependabot's branch, after which Dependabot no longer rebases it on its own —
comment `@dependabot recreate` to start the pull request over.

Supporting Chatter is **switched off** everywhere until GitHub Sponsors is set up: `sponsoring`
in `app/build.gradle.kts` takes the settings category out of every build and keeps the app from
asking for a supporter list nobody serves, and the two workflows below have their triggers
commented out. Everything is built and tested and waiting; turning it on is that one line and
those two triggers.

`.github/workflows/sponsors.yml` folds what GitHub Sponsors knows into `docs/supporters.json`.
Its schedule is commented out for now, so it only runs when started by hand; put it back once
`SPONSORS_TOKEN` exists. It is meant to run every few hours — GitHub has no trigger for "somebody sponsored", so it asks. It writes every
field but one: **the Twitch id is filled in by hand**, because GitHub does not know which Twitch
account a sponsor has and a badge in front of the wrong name is worse than none. New entries
arrive with an empty `twitch`, the app skips them, and the run's summary says whose is missing.
Private sponsorships are left out of the public list on purpose. Nothing is ever removed: a
one-time sponsorship on top of an earlier one counts up, and somebody who stops keeps their entry
without the monthly mark. Those rules are the one thing here with tests of their own
(`.github/scripts/test_sync_sponsors.py`), which CI runs as well.

`.github/workflows/supporter-claim.yml` is how the one field the sync cannot fill gets filled.
Chatter opens an issue from the supporter form with the Twitch id already in it; the workflow
checks the **author** against the supporter list and writes the id into that account's own entry,
then says so and closes the issue. Somebody who does not sponsor changes nothing. Nothing from the
issue ever reaches a shell — the body goes to the script as an environment variable, because it is
whatever somebody typed. Its rules are tested in `.github/scripts/test_claim_supporter.py`.

`.github/workflows/benchmark.yml` measures what one message costs on its way in — parsing,
building, the mute list, the rules, the buffer — for the branch it is started on and for master,
one after the other on the same emulator, and puts both into the job summary with the change
between them. It is started by hand; run it on a pull request that touches `irc/` or `chat/`. The
benchmark itself is `MessageCostBenchmark` in `app/src/androidTest`, fed a busy channel's worth of
made-up lines by `BusyChannel`. It runs against the `microbenchmark` build type: the debug build,
not debuggable (a debuggable app runs unoptimized and would measure nothing a user runs), with an
application id of its own, because a connected test uninstalls what it tested afterwards.

`.github/workflows/release.yml` is the release itself, started by hand from the Actions tab. Nobody
picks a version there either — it runs `releaseVersion`, so the pending entries decide it. It then
builds and signs both the APK and the Play bundle, pushes the release commit and the `v<version>`
tag, and publishes a GitHub release carrying that version's changelog section, the APK and its
SHA-256. GitHub attests the APK and the bundle as built by that run, and the README lists the
signing certificate's SHA-256; a new upload key means a new line there. The build comes before the push, so a failed one leaves the repository untouched, and
`dry_run` does everything except push and publish — and is the only run allowed off `master`.

It is three jobs: `preflight` and `verify` side by side, then `release`. Everything that could be
wrong is checked before anything is built: every missing secret at once, that the keystore opens,
holds the alias and that `KEY_PASSWORD` unlocks the key, that there is something to release, that
the tag is free and that the versionCode is above the one the last tag released. The signing
secrets reach exactly one step, the one that builds; tests and lint never see them. The
`play_track` input uploads the bundle to that Play track; `none`, the default, uploads nowhere.

`docs/play-store/` holds what the Play Console needs — the data safety answers, the foreground
service justification and the listing text. It is in `.gitignore` and lives only on the
maintainer's machine, so a fresh clone does not have it. When the app's network or storage
behaviour changes, `docs/privacy-policy.html` and `docs/play-store/data-safety.md` change with it:
they are declarations, and a stale one is a policy violation.

What it reads from Settings → Secrets and variables → Actions:

| Secret | What it is | Missing |
| --- | --- | --- |
| `TWITCH_CLIENT_ID` | what `local.properties` holds on a dev machine | the release stops, rather than ship an APK that cannot log in |
| `KEYSTORE_BASE64` | the upload keystore, as `base64 -w0 upload.jks` | the release stops; only a `dry_run` falls back to the debug key |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | how to open that keystore | same |
| `PLAY_SERVICE_ACCOUNT_JSON` | the service account allowed to release to Play | only needed for a `play_track` other than `none`, which stops without it |
| `SPONSORS_TOKEN` | a classic token of the sponsored account with `read:user`, for the sponsor sync | only that workflow, which fails without it; nothing else is affected |

## The baseline profile

The release carries two lists of what ART compiles ahead of time. `app/src/release/generated/` is
what `./gradlew :app:generateReleaseBaselineProfile` records on the connected phone: the login
screen of a fresh install, the start into the chat and a busy channel being read and scrolled.
`app/src/main/baseline-prof.txt` is written by hand and names whole packages — the message path
that runs all night in the background and the chat drawing — so it does not go stale when a method
is renamed. A new hot package goes into the hand-written file.

The journeys read a busy channel as a guest, so they need no Twitch login, and the phone's own
Chatter is never touched: the builds the profile is recorded and measured on are an app of their
own (`dev.chatter.app.profiling`), and the only ones that can be told from outside to read a
channel. `-Pandroid.testInstrumentationRunnerArguments.chatterChannel=<login>` picks another
channel when the default is offline — a chat with nothing arriving profiles next to nothing.

Generate it again before a release that changed what the start or the chat runs through: a new
screen on the way, a new kind of message, a change to the list. Then run
`./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` and put what `ChatBenchmark`
says about the cold start and the chat frames, with the profile and without, into the commit.

## Commands

```sh
./gradlew changelogStatus            # what is pending, and which version it would release
./gradlew checkChangelog             # are the pending entries well formed?
./gradlew releaseVersion             # fold them into CHANGELOG.md, bump the version, clear pending
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:installDebug          # build and install on the connected phone
./gradlew :app:connectedMicrobenchmarkAndroidTest   # what a message costs, on the connected phone
./gradlew :app:generateReleaseBaselineProfile       # record the baseline profile on the connected phone
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # what the profile is worth
```

A change is not confirmed by compiling. Install it and open the screen it touches:

```sh
./gradlew :app:installDebug && adb shell am start -n dev.chatter.app/.MainActivity
```

## Conventions worth matching

- **Both locales.** Every user-visible string lives in `app/src/main/res/values/strings.xml` *and*
  `values-de/strings.xml`. Adding one and not the other leaves the app half-translated.
- **Names explain what.** Variables, functions and types are named so the code reads without a
  comment: `unreadMentions`, `markChannelRead`, `retryMissing` rather than `count`, `update`,
  `handle`. If a name needs a comment to say what it holds or does, rename it first; a comment is
  for what no name can carry.
- **Comments say why, not what.** The codebase explains the reason a thing is the way it is —
  the Twitch quirk, the Compose limitation, the choice between two designs. KDoc goes on types and
  on any function whose purpose is not obvious from its name.
- **Comments are short.** One line where one does, three at most for a single point. No history
  ("used to", "before this change") — that is what the commit message is for. No retelling of
  the code below, no story of what would go wrong without it beyond the one fact that matters,
  and no rhetorical flourish. A comment that only repeats a name is deleted, not shortened.
- **A new setting touches four places:** the `Settings` data class, a key and setter in
  `SettingsRepository`, a setter on `MainViewModel`, and a row in `SettingsScreen`.
- **Parsing gets a test.** Anything that reads a format — IRC tags, 7TV events, the changelog —
  belongs in `app/src/test/`, where the existing tests show the style.
- **Commit subjects describe the change to the app**, in the imperative and without a prefix:
  "Move the block list into a page of its own", not "feat(settings): add blocklist page".
