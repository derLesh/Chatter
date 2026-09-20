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
