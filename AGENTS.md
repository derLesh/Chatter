# Working on Chatter

Chatter is an Android Twitch chat client: Kotlin, Jetpack Compose, one Gradle module (`app`), no
dependency injection framework — everything long-lived is built and wired in `AppContainer`, and the
UI reaches it through `MainViewModel`.

Answer in the language the user writes in. What goes into the repository or onto GitHub — code,
comments, commits, changelog entries, pull requests, docs — is in English.

## When a change is done

1. **It has a changelog entry** if its commit is a `feat`, `fix` or `perf` (see below).
2. **The checks pass:** `./gradlew checkChangelog :app:testDebugUnitTest :app:lintRelease`. CI runs
   the same plus a release bundle, and a new lint error fails the branch.
3. **It runs on the phone.** Compiling confirms nothing: install it and open the screen it touches.
   ```sh
   ./gradlew :app:installDebug && adb shell am start -n me.lesh.chatter.dev/me.lesh.chatter.MainActivity
   ```

## Changelog entries

Every change a user can notice leaves one file in `pending-changelog/`, written in the same commit
as the change, the way [Changesets](https://github.com/changesets/changesets) does it:

```text
pending-changelog/nicknames-per-channel.txt
```

```text
minor: Let chatters be given a nickname in one channel only
```

The level says what the change does to someone using the app: `major:` takes something away,
`minor:` gives them something new, `patch:` fixes or polishes. A release moves the version by the
largest level among the pending entries.

Write the line for the person who reads it in the app: what they can now do, one line, no trailing
dot, under 120 characters. `./gradlew checkChangelog` says when a line does not hold up, and
`pending-changelog/README.md` has the full format.

## Commit messages

Every subject is `type(scope): what changed`, in the imperative, with no trailing dot and at most
100 characters. After the type it describes the change to the app — "move the block list into a
page of its own", not "add blocklist page":

```text
feat(chat): count down in the input while timed out
fix(emotes): keep a wrapped emote from covering the line above
ref(irc): move tag parsing into its own class
build!: move the app id and packages to me.lesh.chatter
```

| Type | For | Changelog entry |
| --- | --- | --- |
| `feat` | something users can now do | yes |
| `fix` | something that was broken for users | yes |
| `perf` | faster, lighter on the battery or memory | yes |
| `ref` | code changed, behaviour kept | no |
| `docs` | README, AGENTS.md, docs/, comments only | no |
| `test` | tests only | no |
| `build` | Gradle, dependencies, checksums, the app id | no |
| `ci` | workflows and their scripts | no |
| `chore` | anything else nobody using the app notices: releases, the supporter list | no |
| `revert` | undoing an earlier commit | no |

The scope is optional and names the part of the app in lower case: `chat`, `irc`, `emotes`,
`settings`, `notifications`, `release`, `deps`. A `!` before the colon marks a change that takes
something away. CI checks every subject, but master takes pushes directly, so that check comes
after the push: get the subject right before committing.

## Never edit these by hand

- `version.properties` — `./gradlew releaseVersion` writes it, from the pending entries.
- `CHANGELOG.md` — the same task writes it. The app ships this file and shows it under Settings →
  About → Changelog, so its shape matters: `## <version> — <date>` opens a release and
  `- <level>: <text>` is one entry.

## How Chatter behaves

- **It stays connected in the background.** Every joined channel keeps receiving everything while
  the app is in the background, so it is all there on return. Battery work never parts channels,
  pauses the connection or falls back to fetching recent messages; it saves on per-message work,
  drawing and other wakeups instead. History is fetched on the first join and after a real
  reconnect only.
- **Fast, but small.** A cache on the phone is fine when it stays small and bounded, is deleted
  with what it belongs to (a removed channel takes its files along), and shows up under Settings →
  Storage. Say how big it gets when proposing one.
- **Sheets, not dialogs.** Confirmations and questions are bottom sheets built on `FormSheet`, like
  `ConfirmUnblockSheet`, never an `AlertDialog`.
- **Wide screens show one chat.** On foldables, tablets and in landscape nothing opens beside the
  chat by default; a second pane only appears when the user opens one. App-wide buttons such as
  the inbox and settings sit at the top right.
- **Short texts, with examples.** Write in short sentences and everyday words: "Show images from
  links", not "Enable inline media rendering". A hint is one or two short sentences, an option
  title a noun, and where a feature needs explaining a concrete example ("e.g. Write gg in xqc")
  does it. A placeholder names what goes there (`<text>`), never a sample word like `test`.
  Use a technical word only when no plain one fits, and a Twitch word only when people who use
  Twitch know it (emote, mod, sub). The same goes for changelog lines, comments, commits and docs.
- **The declarations follow the app.** When the app's network or storage behaviour changes,
  `docs/privacy-policy.html` and `docs/play-store/data-safety.md` change with it; a stale one is a
  policy violation.

### Switched off for now

- **Asking for support**, until GitHub Sponsors is set up: `sponsoring` in `app/build.gradle.kts`
  takes the settings category and its link out of every build. The supporter badge itself works:
  `net/ChatterApi.kt` asks `api.lesh.me` for the supporters' Twitch ids (without the user's token,
  so guests see them too) and for the badge picture. A new supporter goes into the API's database,
  not into a release.
- **Assistants**, because Gemini only calls the AppFunctions of apps in Google's preview:
  `assistantAccess` in `app/build.gradle.kts` hides the switch under Settings → Labs. The functions
  in `assistant/` are tested with `adb shell cmd app_function`. Turning it on is that line plus the
  assistant sections of the privacy policy and `data-safety.md`, as commit 81a8da3 had them.

## Code conventions

- **Both locales.** Every user-visible string lives in `app/src/main/res/values/strings.xml` *and*
  `values-de/strings.xml`.
- **Names explain what.** `unreadMentions`, `markChannelRead`, `retryMissing` rather than `count`,
  `update`, `handle`. If a name needs a comment to say what it holds or does, rename it first.
- **Comments say why, in a line or three.** The Twitch quirk, the Compose limitation, the choice
  between two designs. No history ("used to", "before this change"), no retelling of the code
  below. KDoc goes on types and on any function whose purpose is not obvious from its name.
- **A new setting touches five places:** the `Settings` data class, a key and setter in
  `SettingsRepository`, a setter on `MainViewModel`, a row on its page in `ui/settings/`, and a line
  in `SEARCH_INDEX` in `SettingsScreen` so the search finds it.
- **Log through `AppLog`, names only marked.** It feeds the log under Settings → Debug that users
  put into bug reports, and the last 5,000 lines are stored on the phone. Use `AppLog.v` for what
  happens many times a minute. A name goes in through `AppLog.channel`, `AppLog.user` or
  `AppLog.hidden`, so only the full log shows it; messages never go in. `LogPrivacy` cleans links,
  error pages and secrets from the private log, but cannot know a name.
- **Parsing gets a test.** Anything that reads a format — IRC tags, 7TV events, the changelog —
  gets a test in `app/src/test/`.

## Phone, builds and screenshots

```sh
./gradlew changelogStatus            # what is pending, and which version it would release
./gradlew releaseVersion             # fold the entries into CHANGELOG.md, bump the version, clear pending
./gradlew devLogin                   # log the debug build in where Twitch's login page refuses, e.g. on an emulator
```

The debug build is an app of its own, `me.lesh.chatter.dev` (Chatter Dev), next to the release
(`me.lesh.chatter`, Chatter) and Nightly (`me.lesh.chatter.nightly`, Chatter Nightly).

A screenshot for a pull request or an issue shows the change and nothing else: no status bar,
no other chatters, no messages, no channel names. The pull request text names no channels either.

## Read more when

- **changing `irc/` or `chat/`, the navigation, or what the start runs through** —
  [docs/phone-tests.md](docs/phone-tests.md) has the benchmark, the screen tests and the baseline
  profile to run.
- **adding or updating a dependency, changing `.github/`, or releasing** —
  [docs/ci-and-release.md](docs/ci-and-release.md); a new dependency fails the build until its
  checksum is written.
- **measuring battery** — [docs/power.md](docs/power.md).
