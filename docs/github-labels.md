# GitHub labels

Edit names, colors and descriptions in [labels.yml](../.github/labels.yml).
The Sync labels workflow applies changes after they reach `master`, or when run manually there.
It creates missing labels and updates existing ones, leaving labels outside the file in place, so
a label taken out of the file is deleted by hand.

## What an open issue carries

| Group | How many | Example |
| --- | --- | --- |
| Type | one | `bug`, `enhancement` |
| Status | one | `status: ready` |
| Priority | at most one, once triaged | `priority: medium` |
| Effort | at most one, once it is clear what to do | `effort: small` |
| Topic | any that apply | `moderation`, `battery`, `security` |

There are no labels for parts of the app: the title says which part an issue is about, and a
label on nearly everything helps nobody filter.

## Status

An open issue has one status at a time. Swap it as the issue moves on:

1. `status: needs triage` — new. The bug and feature forms set it.
2. `status: needs info` — waiting for details from the person who opened it.
3. `status: needs decision` — wanted, but what it should do or how it should look is open.
4. `status: ready` — clear what to do; anybody can start.
5. `status: in progress` — somebody works on it. Set it when starting, so two people (or two
   agents) do not take the same issue.

`status: blocked` replaces any of these while the issue waits for another change or an outside
service. Say in a comment what it waits for. A closed issue needs no status.

## Effort

A rough guess, not a promise. It helps pick something that fits the time at hand.

- `effort: small` — one sitting, a few files: a fix, a new option, a wording change.
- `effort: medium` — about a day: a new setting with its page, a new Twitch API call, a test.
- `effort: large` — several days: a new screen or service, new scopes, measuring on a phone.
  Worth splitting into smaller issues first.

Set it once the issue is `status: ready` or close to it; before that the guess is too rough.

## Priority

Use at most one `priority: ...` label. Priority is a maintainer decision.

## Other labels

- Add `regression` when a report describes something that worked in an earlier version.
  It can be combined with `bug`.
- Use `performance` for speed or memory work, and `battery` for power use.
- Use `accessibility`, `moderation` and `security` when those topics apply.
- Use `good first issue` for a small task with a clear starting point, and `help wanted` to invite
  help. A good first issue is usually also `effort: small`.

## Automatic labels

PRs get `build`, `github_actions`, `documentation` and `maintenance` from the files they change,
and the PR labeler removes them when they no longer match. Edit these rules in
[labeler.yml](../.github/labeler.yml). App code gets no file-based label: nearly every PR touches
the UI and its strings, and the PR title already says what kind of change it is.

Dependabot sets `dependencies` for its library and Action updates.
Blank issues stay available; add their labels when reviewing them.
