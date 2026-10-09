<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/wordmark-dark.svg">
    <img src="docs/brand/wordmark-light.svg" alt="Chatter" width="360">
  </picture>
</p>

<p align="center">
  A fast, native Twitch chat client for Android - based on Material 3, with full emote support for a rich chatting experience. 
</p>
<p align="center">
  For reading and writing chat, not watching.
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

- Open several chats and switch seamlessly between them
- Combine multiple chats into one
- 7TV, BetterTTV and FrankerFaceZ emotes, animated, with a picker and autocomplete
- Emoji in the picker too, or typed by shortcode: `:smi` offers 😄
- 7TV badges and paints on names, and cheers as animated cheermotes in their tier's colour
- [Recent messages](https://recent-messages.robotty.de/) loaded as you join, so you never walk into an empty room
- Text size, density, timestamps and name colours under your control
- Choose between light and dark themes, Material You, and different app icons

**Never miss being mentioned**

- Mentions and whispers arrive as notifications the moment they are sent, and can be answered
  right there
- An inbox collecting everything addressed to you across all your channels
- Your own highlight words and rules for what deserves an alert, with a sound and vibration per
  channel
- A notification when a channel goes live, with its title and category: your channels, and any
  followed channel you pick

**Stay in the conversation**

- Chat bubbles keep a channel floating over whatever else you are doing
- Dedicated whisper and mention inbox
- Switch between several Twitch accounts
- Nicknames for chatters, mute filters, blocking and reporting

**Yours, and nobody else's**

- No account beyond your Twitch login, and no Chatter server behind it
- No ads, no tracking, no analytics - the app only talks to Twitch and the emote providers 
  ([privacy policy](https://derlesh.github.io/Chatter/privacy-policy.html))

## Install

Download the APK from the [latest release](https://github.com/derLesh/Chatter/releases/latest).
Chatter needs Android 13 or newer and a Twitch account.

Three ways to check the download:

- The SHA-256 next to the APK says it arrived whole.
- `gh attestation verify chatter-<version>.apk --repo derLesh/Chatter` says GitHub saw the release
  workflow build it from this repository.
- `apksigner verify --print-certs chatter-<version>.apk` names the certificate it is signed with,
  and every APK released here is signed with this one:

  ```text
  dc53f30003ee71b0bbbdbd910dd92ce22da1bd346f4da59d985fc6133b29390e
  ```

## Security

Found a way to get at somebody's Twitch login, or a hole in a release or a workflow? Please report
it privately, as the [security policy](SECURITY.md) describes, not in a public issue.

## License

The source code is public so you can read it, check what the app does and contribute, but Chatter
is not open source. You may build it for your own use; you may not publish it, or use its code in
an app of your own, without asking first. The [LICENSE](LICENSE) has the details.

---

<sub>Chatter is an independent app and is not affiliated with, endorsed by or sponsored by Twitch Interactive, Inc.</sub>
