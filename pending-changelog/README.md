# Pending changelog entries

Every change a user of Chatter can notice leaves a file here, the way
[Changesets](https://github.com/changesets/changesets) does it for JavaScript projects: one file per
change instead of everyone appending to one shared list, so two branches never collide over the
changelog. The next release folds all of these files into one `CHANGELOG.md` entry, works out the
version from them, and empties this folder again.

## The format

One file per change, named after it, `kebab-case.txt`:

```text
pending-changelog/nicknames-per-channel.txt
```

Each line in it is one entry, and starts with how far that change moves the version:

```text
minor: Let chatters be given a nickname in one channel only
patch: Keep the nickname dialog from losing what was typed on rotation
```

| Level | Use it for | 1.4.2 becomes |
| --- | --- | --- |
| `major:` | a change that takes something away or breaks a habit | 2.0.0 |
| `minor:` | anything users gain — a feature, a new setting, a new provider | 1.5.0 |
| `patch:` | fixes, wording, performance, anything invisible in the UI | 1.4.3 |

A release moves the version by the **largest** level among the pending entries, so one `minor:` in
a pile of `patch:` entries makes the whole release a minor one.

Write the entry for the person reading the changelog in the app, not for whoever reviews the diff:
what they can now do, in one line, no trailing dot, under 120 characters. `./gradlew checkChangelog`
holds you to that.

## Commands

```sh
./gradlew changelogStatus   # what is pending, and which version it would release
./gradlew checkChangelog    # are the pending entries well formed?
./gradlew releaseVersion    # fold them into CHANGELOG.md, bump the version, empty this folder
```

`.gitkeep` and this README are not entries: the release skips them and leaves them alone.
