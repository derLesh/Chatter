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

`.github/workflows/endpoints.yml` runs once a day and asks every service outside Twitch that the
app reads from — the three emote providers, the badge lists, the supporter list, the history and
the 7TV event stream — whether it is still there and still answering in the shape the app reads.
It takes the addresses out of `ThirdPartyApis.kt` rather than keeping a list of its own, and an
address it knows nothing about fails rather than being skipped. Two of them had died unnoticed
before it existed. It is out of CI on purpose: a provider having a bad minute is not a reason to
fail a branch. Run it yourself with `.github/scripts/check-endpoints.sh`.

`.github/workflows/sponsors.yml` folds what GitHub Sponsors knows into `docs/supporters.json`
every six hours — GitHub has no trigger for "somebody sponsored", so it asks. It writes every
field but one: **the Twitch id is filled in by hand**, because GitHub does not know which Twitch
account a sponsor has and a badge in front of the wrong name is worse than none. New entries
arrive with an empty `twitch`, the app skips them, and the run's summary says whose is missing.
Private sponsorships are left out of the public list on purpose. Nothing is ever removed: a
one-time sponsorship on top of an earlier one counts up, and somebody who stops keeps their entry
without the monthly mark. Those rules are the one thing here with tests of their own
(`.github/scripts/test_sync_sponsors.py`), which CI runs as well.

`.github/workflows/release.yml` is the release itself, started by hand from the Actions tab. Nobody
picks a version there either — it runs `releaseVersion`, so the pending entries decide it. It then
builds and signs both the APK and the Play bundle, pushes the release commit and the `v<version>`
tag, and publishes a GitHub release carrying that version's changelog section and the APK. The
build comes before the push, so a failed one leaves the repository untouched, and `dry_run` does
everything except push and publish.

Everything that could be wrong is checked before anything is built: the Client ID, that the
keystore opens and holds the alias, that there is something to release, that the tag is free and
that the versionCode is above the one the last tag released. The `play_track` input uploads the
bundle to that Play track; `none`, the default, uploads nowhere.

`docs/play-store/` holds what the Play Console needs — the data safety answers, the foreground
service justification and the listing text. When the app's network or storage behaviour changes,
`docs/privacy-policy.html` and `docs/play-store/data-safety.md` change with it: they are
declarations, and a stale one is a policy violation.

What it reads from Settings → Secrets and variables → Actions:

| Secret | What it is | Missing |
| --- | --- | --- |
| `TWITCH_CLIENT_ID` | what `local.properties` holds on a dev machine | the release stops, rather than ship an APK that cannot log in |
| `KEYSTORE_BASE64` | the upload keystore, as `base64 -w0 upload.jks` | the release stops; only a `dry_run` falls back to the debug key |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | how to open that keystore | same |
| `PLAY_SERVICE_ACCOUNT_JSON` | the service account allowed to release to Play | only needed for a `play_track` other than `none`, which stops without it |
| `SPONSORS_TOKEN` | a classic token of the sponsored account with `read:user`, for the sponsor sync | only that workflow, which fails without it; nothing else is affected |

## Commands

```sh
./gradlew changelogStatus            # what is pending, and which version it would release
./gradlew checkChangelog             # are the pending entries well formed?
./gradlew releaseVersion             # fold them into CHANGELOG.md, bump the version, clear pending
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:installDebug          # build and install on the connected phone
```

A change is not confirmed by compiling. Install it and open the screen it touches:

```sh
./gradlew :app:installDebug && adb shell am start -n dev.chatter.app/.MainActivity
```

## Conventions worth matching

- **Both locales.** Every user-visible string lives in `app/src/main/res/values/strings.xml` *and*
  `values-de/strings.xml`. Adding one and not the other leaves the app half-translated.
- **Comments say why, not what.** The codebase explains the reason a thing is the way it is —
  the Twitch quirk, the Compose limitation, the choice between two designs. KDoc goes on types and
  on any function whose purpose is not obvious from its name.
- **A new setting touches four places:** the `Settings` data class, a key and setter in
  `SettingsRepository`, a setter on `MainViewModel`, and a row in `SettingsScreen`.
- **Parsing gets a test.** Anything that reads a format — IRC tags, 7TV events, the changelog —
  belongs in `app/src/test/`, where the existing tests show the style.
- **Commit subjects describe the change to the app**, in the imperative and without a prefix:
  "Move the block list into a page of its own", not "feat(settings): add blocklist page".
