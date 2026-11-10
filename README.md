<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/wordmark-dark.svg">
    <img src="docs/brand/wordmark-light.svg" alt="Chatter" width="360">
  </picture>
</p>

<p align="center">
    A fast and native Twitch chat client built on Material 3, with support for emotes from 7TV, BetterTTV and FrankerFaceZ
</p>

<p align="center">
  <a href="https://github.com/derLesh/Chatter/actions/workflows/ci.yml"><img src="https://github.com/derLesh/Chatter/actions/workflows/ci.yml/badge.svg?branch=master" alt="CI"></a>
  <a href="https://github.com/derLesh/Chatter/releases/latest"><img src="https://img.shields.io/github/v/release/derLesh/Chatter?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white" alt="Android 13+">
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.3">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
</p>

<p align="center">
  <img src="docs/screenshots/chat.png" alt="A busy chat with emotes, badges and channel tabs at the top" width="24%">
  <img src="docs/screenshots/combined.png" alt="Several channels combined into one chat" width="24%">
  <img src="docs/screenshots/user-card.png" alt="A user card with badges, actions and recent messages" width="24%">
  <img src="docs/screenshots/settings.png" alt="The settings overview with search" width="24%">
</p>

> [!TIP]
> **Help test Chatter on Google Play.** Chatter is in closed testing. Before it can be released
> there, at least 12 testers need to stay in the test for 14 days in a row.
>
> 1. Join the [Chatter testers group](https://groups.google.com/g/chatter-dev-group/about). Only
>    members can join the test.
> 2. Then [join the test on Google Play](https://play.google.com/apps/testing/me.lesh.chatter)
>    with the same Google account, and install Chatter from there.
> 3. Download the App from the [Play Store](https://play.google.com/store/apps/details?id=me.lesh.chatter).
> 4. Stay in the test for at least 14 days in a row.

## Install

**[Download the APK from the latest release](https://github.com/derLesh/Chatter/releases/latest)**
and open it on your phone. Android may ask for permission to install apps from your browser.

Chatter needs Android 13 or newer. You can read chat without an account. Log in with Twitch to
send messages.

Chatter is coming to Google Play. It is in closed testing now, and the steps above show how to join.

[Chatter Nightly](https://github.com/derLesh/Chatter/releases/tag/nightly) is built every night
from the newest tested changes. It may have bugs. You can install it next to Chatter.

## Features

**Reading chat**

- Open your channels as tabs and swipe between them
- Combine several channels into one chat and see which channel each message came from
- Use Twitch, 7TV, BetterTTV and FrankerFaceZ emotes, including animated ones
- Find emotes in a picker sorted by channel, or with autocomplete
- Pick emoji, or type `:smi` to see suggestions like 😄
- See 7TV badges and name paints, and cheers as animated cheermotes
- See giant emotes and message effects, as on Twitch
- View images, GIFs and posts from X and Bluesky in the chat
- Load [recent messages](https://recent-messages.robotty.de/) when you join a channel
- Jump back to where you stopped reading
- Tap a reply to read the whole conversation
- Read as a guest and log in when you want to send messages

**Appearance**

- Change text size, line spacing, timestamps and name colors
- Pick which badges and special messages you see
- Choose a light, dark or pure black theme, with Material You colors
- Choose an app icon in one of eight colors or a Pride design

**Notifications**

- Get notified about mentions and whispers, and reply from the notification
- Find everything sent to you from all your channels in one inbox
- Set your own highlight words and notification rules
- Choose a sound and vibration for each channel
- Follow a conversation to get notified about new replies
- Get notified when one of your channels or a followed channel you picked goes live, with its
  title and category

**Messages and accounts**

- Keep a channel open over other apps with chat bubbles
- Schedule messages for later
- Send one of your last three messages again
- Get the channel's bot commands suggested while you type one, like `!discord` or `$ping`
- Read whispers in a separate chat for each person
- Open user cards to see badges and recent messages, or add a private note
- Give chatters nicknames, mute words or users, and block or report people
- Change chat modes and pin messages in channels you moderate
- Switch between several Twitch accounts

**Privacy and data**

- No Chatter account needed. Your Twitch login is enough.
- No ads. Chatter sends nothing about you to us. See the
  [privacy policy](https://chatter.lesh.me/privacy).
- Save your settings, rules and channels to a file, or move them to a new phone
- Save mobile data with smaller emotes, no animations and no linked images

## Security

If you find a security problem, for example a way to get someone's Twitch login, please don't open
a public issue. Report it privately as described in the [security policy](SECURITY.md).

## License

The source code is public, so you can read it, check what the app does and contribute. Chatter is
not open source, though. You can build it for your own use. You need written permission before you
publish it or use its code in your own app. See the [LICENSE](LICENSE) for details.

---

<sub>Chatter is an independent app and is not affiliated with, endorsed by or sponsored by Twitch Interactive, Inc.</sub>
