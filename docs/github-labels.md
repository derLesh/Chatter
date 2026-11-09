# GitHub labels

Edit names, colors and descriptions in [labels.yml](../.github/labels.yml).
The Sync labels workflow applies changes after they reach `master`, or when run manually there.
It creates missing labels and updates existing ones, leaving labels outside the file in place.

## Automatic labels

PRs get `area: ...` labels from the files they change. Tests in an app area get both its area
label and `tests`. A PR can touch more than one area. `documentation`, `translations`, `build`,
`github_actions` and `maintenance` also follow changed files.
Edit these rules in [labeler.yml](../.github/labeler.yml).

The PR labeler removes these file-based labels when they no longer match. Use its area labels
manually on issues; on PRs they belong to the file rules.

Bug and feature forms set `bug` or `enhancement`, plus `status: needs triage`.
Blank issues stay available; add their labels when reviewing them.
Dependabot sets `dependencies` for its library and Action updates.

## Labels chosen during review

- Remove `status: needs triage` once a report or idea has been reviewed.
- Use `status: needs info` while waiting for details, or `status: blocked` for an external blocker.
  Remove the status once it no longer applies.
- Use at most one `priority: ...` label. Priority is a maintainer decision.
- Add `regression` when a report describes something that worked in an earlier version.
  It can be combined with `bug`.
- Use `performance` for speed or memory work, and `battery` for power use.
- Use `accessibility`, `moderation` and `security` when those topics apply.
- Use `good first issue` for a small task with a clear starting point, and `help wanted` to invite help.

Change types are not guessed from filenames: a chat change can be a feature, a fix or a refactor.
PR title validation checks the commit convention separately.
