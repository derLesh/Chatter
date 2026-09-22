# Changelog

Every release of Chatter, newest first. Written from the entries in `pending-changelog/` by
`./gradlew releaseVersion`, and shown in the app under Settings → About → Changelog.

## 0.5.0 — 2026-09-22
- minor: Bring 7TV badges back, the way Chatterino gets them now
- minor: Log in with several Twitch accounts and switch between them on the account page
- minor: Put Copy first on the user card instead of Reply, if you like
- minor: Put emotes into the chat as soon as a provider that was down comes back
- minor: Reach the privacy policy from Settings -> About
- minor: Read several channels in one combined chat, each message marked with its channel, and pick where to write
- minor: Report a chatter from the message sheet, blocking them in the same step
- minor: Say in the chat whether the recent messages are loading or could not be fetched
- minor: Say it in a notification when Chatter stops listening in the background
- minor: See images linked in chat as pictures, from the sites you allow
- minor: See your Twitch picture, name, ID, account age and followed channels under Account
- minor: Show the Chatter wordmark on the onboarding and About screens
- minor: Swipe on past the last channel to come back to the first one
- minor: Tap a message to reply to it and tap a name for the user card, or pick your own in the settings
- patch: Ask the emote providers again only when the lists may have changed, not on every app switch
- patch: Buzz instead of showing a notification for a mention or whisper while Chatter is open
- patch: Fetch badges that were unreachable at start instead of leaving them out until a restart
- patch: Find a setting faster: emotes, filters and blocked users each have a place of their own
- patch: Hide the leading mention of a reply when the chatter's display name is not their login
- patch: Keep a provider's emotes when it is briefly unreachable, and say in the chat what is missing
- patch: Keep typing smooth in channels with thousands of emotes
- patch: Let copies of the same animated emote move in step and load it only once
- patch: Let your own messages be replied to from the user card
- patch: Notice a chat connection that dies while logging in instead of waiting for it forever
- patch: Open a channel without waiting on an emote provider that has gone quiet
- patch: Open the user card half-way even when the chatter wrote a lot, and swipe up for the rest
- patch: Pick the name colors and the timestamp from a sheet instead of a long list
- patch: Pick the same emote as Chatterino and DankChat when BTTV and 7TV share a name
- patch: Reach the credits and the open source licenses from a page of their own
- patch: Say in a popup when a service outside Chatter cannot be reached
- patch: Say on the login screen when the Twitch login ran out instead of just asking again
- patch: Say once that a service cannot be reached, instead of twice per channel
- patch: Scroll more smoothly through the dimmed messages from the chat history
- patch: Shorten and clarify the texts in settings, dialogs and the login screen
- patch: Show a copy icon on the Copy button of the user card
- patch: Show the picture the chat will show when a Twitch emote and a channel emote share a name
- patch: Spend a third of the work on drawing a fast-moving chat
- patch: Stop notifying about a channel that is open in a bubble, and keep reading the right one after
- patch: Stop retrying the chat connection every half minute while the phone has no network
- patch: Stop the background connection from running on after logging out

## 0.4.0 — 2026-09-20
- minor: Answer a mention straight from its notification, without opening the app
- minor: Answer a whisper from the inbox, or send one with /w
- minor: Collect every mention from all channels in one inbox that survives restarts
- minor: Feel a short buzz when a mention arrives, a message is held or a send does not go through
- minor: Get a notification for a whisper and answer it straight from there
- minor: Give every channel its own notification sound and vibration
- minor: Jump straight into a channel or the inbox by holding the app icon
- minor: Keep highlight and muted words as a list you add to and remove from, one word at a time
- minor: Open a mention as a floating chat bubble over other apps
- minor: Read whispers in the inbox, in a tab of its own next to mentions
- minor: Save your settings, rules, nicknames and channels to a file and restore them
- minor: See how much you have written, where and on how many days, in the new stats settings
- minor: Show the Twitch picture of whoever wrote a message in the notification
- minor: Write rules that highlight, announce or hide messages by word, name or pattern
- patch: Come back to the channel you were reading, even after Android stopped the app
- patch: Hold animated emotes still while the battery saver is on
- patch: Keep a chat bubble open when you tap it
- patch: Keep snackbar messages in the language the app switched to
- patch: Keep the message list smooth while a busy channel keeps sending
- patch: Keep the title bar on the channel you are reading when a bubble is open
- patch: Let new messages arrive without a jolt, also when many come in at once
- patch: Make the text in a chat bubble readable in the dark theme
- patch: Open the app faster when it was fully closed
- patch: Open the right channel when a mention notification is tapped
- patch: Scroll the about page instead of being stuck at the top of it
- patch: Show the channel picture in a notification at its real size instead of zoomed in
- patch: Show your role and the chat modes under the channel name, with live as a red ring only
- patch: Start quicker and use less data by reusing emote and badge lists already downloaded
- patch: Use far less battery while the chat keeps running in the background
- patch: Write the chat modes as a quiet subtitle instead of coloured chips

## 0.3.0 — 2026-09-20
- minor: Show the changelog in the settings and after an update

## 0.2.0 — 2026-09-20
- minor: Everything Chatter could do before it started keeping a changelog

