# Security policy

If you found a way to get at a Chatter user's Twitch token, to act as somebody else, or to make the
app or this repository do something it should not, please tell us privately first. A public issue
is read by everybody before it can be fixed.

## What is covered

- **The app** — the latest release, from GitHub or Google Play, and the `master` branch. Anything
  that reads or leaks a login, sends messages or whispers as somebody else, runs code or opens
  links nobody tapped, or reaches a service the [privacy policy](https://derlesh.github.io/Chatter/privacy-policy.html)
  does not list.
- **The workflows** in `.github/workflows` and the scripts they run: anything that lets somebody
  outside the repository change a release, reach a secret, or write to the supporter list for
  another account.
- **The published APK and bundle** — a release that was not built by the release workflow from
  this repository, or that does not match its checksum or [attestation](README.md#install).

Not covered: Twitch, 7TV, BetterTTV, FrankerFaceZ and the recent-messages service themselves.
Report a problem in one of them to its own maintainers; if Chatter makes it worse, tell us as well.

## How to report

Open a private report under
[Security → Report a vulnerability](https://github.com/derLesh/Chatter/security/advisories/new).
Only the maintainer sees it. Say what you found, how to make it happen, and which version or
commit you tried.

Please do not open a public issue, and do not try it on accounts that are not yours.

## What happens next

- You get an answer within a week, saying whether we can reproduce it.
- While it is being fixed, the report stays private and you are kept up to date in it.
- The fix ships in a release, the changelog says that a security problem was fixed, and the
  advisory is published afterwards — naming you, unless you would rather not be named.
