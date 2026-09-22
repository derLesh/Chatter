<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/wordmark-dark.svg">
    <img src="docs/brand/wordmark-light.svg" alt="Chatter" width="360">
  </picture>
</p>

<p align="center">
  A fast, native Twitch chat client for Android — for reading and writing chat, not watching.
</p>

<p align="center">
  <a href="https://github.com/derLesh/Chatter/actions/workflows/ci.yml"><img src="https://github.com/derLesh/Chatter/actions/workflows/ci.yml/badge.svg?branch=master" alt="CI"></a>
  <a href="https://github.com/derLesh/Chatter/releases/latest"><img src="https://img.shields.io/github/v/release/derLesh/Chatter?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" alt="Android 13+">
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <a href="https://derlesh.github.io/Chatter/privacy-policy.html"><img src="https://img.shields.io/badge/tracking-none-2ea44f" alt="No tracking"></a>
</p>

<p align="center">
  <img src="docs/screenshots/chat.png" alt="A busy channel with badges, emotes and reply threads" width="24%">
  <img src="docs/screenshots/emotes.png" alt="The emote picker with Twitch, 7TV, BTTV and FFZ tabs" width="24%">
  <img src="docs/screenshots/settings.png" alt="The settings overview" width="24%">
  <img src="docs/screenshots/appearance.png" alt="Appearance settings: theme, highlight colour, name colours and app icon" width="24%">
</p>

## What it does

**Read chat the way you like it**

- Several channels at once — renamed, reordered, or combined into one chat that marks where each
  message came from
- 7TV, BetterTTV and FrankerFaceZ emotes, animated, with a picker and autocomplete
- Recent messages loaded as you join, so you never walk into an empty room
- Text size, density, timestamps and name colours under your control; light and dark themes,
  Material You, and a second app icon to match

**Never miss being mentioned**

- Mentions and whispers arrive as notifications the moment they are sent, and can be answered
  right there
- An inbox collecting everything addressed to you across all your channels
- Your own highlight words and rules for what deserves an alert, with a sound and vibration per
  channel

**Stay in the conversation**

- Chat bubbles keep a channel floating over whatever else you are doing
- Reply threads, whispers, and the moderator actions you already have on Twitch
- Several Twitch accounts, switched from the account page
- Nicknames for chatters, mute filters, blocking and reporting

**Yours, and nobody else's**

- No account beyond your Twitch login, and no Chatter server behind it
- No ads, no tracking, no analytics — the app talks to Twitch and the emote providers, not to us
  ([privacy policy](https://derlesh.github.io/Chatter/privacy-policy.html))

## Install

Download the APK from the [latest release](https://github.com/derLesh/Chatter/releases/latest) and
compare it with the SHA-256 published next to it. Chatter needs Android 13 or newer and a Twitch
account.

## Build it yourself

Register an application in the [Twitch developer console](https://dev.twitch.tv/console/apps) with
`http://localhost` as its OAuth redirect URL, then put its client ID into `local.properties`:

```properties
twitch.clientId=your-client-id
```

```sh
./gradlew :app:installDebug          # build and install on the connected phone
./gradlew :app:testDebugUnitTest     # unit tests
```

## Contributing

Every change someone using the app can notice comes with a one-line entry in
[`pending-changelog/`](pending-changelog/README.md); releases fold those into
[`CHANGELOG.md`](CHANGELOG.md) and pick the version from them. [`AGENTS.md`](AGENTS.md) describes
how the project is put together and the conventions worth matching.

---

<sub>Chatter is an independent app and is not affiliated with, endorsed by or sponsored by Twitch
Interactive, Inc.</sub>
