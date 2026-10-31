#!/usr/bin/env python3
"""Checks commit subjects against the convention in AGENTS.md, and that a change users notice
brings its changelog entry along.

    check-commits.py message <file>       the commit-msg hook: the message being written
    check-commits.py range <base> <head>  CI: every commit after base up to head, merges left out

A subject is `type(scope): what changed`; the scope is optional, and `!` before the colon marks a
change that takes something away. feat, fix and perf change what users get, so they must add or
change a file in pending-changelog/ in the same commit.

Exits 0 when every commit holds up, 1 with the reasons when not.
"""

import re
import subprocess
import sys

TYPES = ('feat', 'fix', 'perf', 'ref', 'docs', 'test', 'build', 'ci', 'chore', 'revert')
NEEDS_ENTRY = ('feat', 'fix', 'perf')
SUBJECT = re.compile(r'^(?P<type>[a-z]+)(?:\((?P<scope>[a-z0-9-]+)\))?!?: (?P<text>\S.*)$')
MAX_SUBJECT = 100
# Subjects git writes itself, for a merge and for `git revert`.
GIT_SUBJECTS = ('Merge ', 'Revert "')
# Left by `git commit --fixup` and friends; a rebase folds them away before they are pushed.
LOCAL_ONLY = ('fixup! ', 'squash! ', 'amend! ')
NOT_ENTRIES = ('pending-changelog/README.md', 'pending-changelog/.gitkeep')
NO_COMMIT = '0' * 40


def problems(subject, changed):
    """What is wrong with one commit. `changed` holds (status, path) pairs as name-status gives them."""
    if subject.startswith(GIT_SUBJECTS):
        return []
    match = SUBJECT.match(subject)
    if not match:
        return ['"%s" is not "type(scope): what changed", for example "feat(chat): show pinned messages"'
                % subject]
    found = []
    kind = match['type']
    if kind not in TYPES:
        found.append('"%s" is not a type. Use one of: %s' % (kind, ', '.join(TYPES)))
    if len(subject) > MAX_SUBJECT:
        found.append('the subject has %d characters; keep it to %d' % (len(subject), MAX_SUBJECT))
    if subject.endswith('.'):
        found.append('the subject ends with a dot')
    if kind in NEEDS_ENTRY and not has_entry(changed):
        found.append('a %s commit needs its entry in pending-changelog/ (see its README). '
                     'Nothing a user can notice? Then it is ref, chore, build, docs, test or ci.' % kind)
    return found


def has_entry(changed):
    return any(path.startswith('pending-changelog/') and path not in NOT_ENTRIES and not status.startswith('D')
               for status, path in changed)


def parse_name_status(output):
    """`R100<tab>old<tab>new` names the file twice; the new name is the one that counts."""
    pairs = []
    for line in output.splitlines():
        parts = line.split('\t')
        if len(parts) >= 2:
            pairs.append((parts[0], parts[-1]))
    return pairs


def subject_of(message):
    for line in message.splitlines():
        if line.strip() and not line.startswith('#'):
            return line.strip()
    return ''


def git(*args):
    return subprocess.run(('git',) + args, check=True, capture_output=True, text=True).stdout


def check_message(path):
    with open(path, encoding='utf-8') as f:
        subject = subject_of(f.read())
    if not subject or subject.startswith(LOCAL_ONLY):
        return []
    changed = parse_name_status(git('diff', '--cached', '--name-status'))
    # The hook cannot tell an amend from a new commit, and an amend only stages what it adds. The
    # same subject as HEAD is almost always an amend, so HEAD's own files count as well.
    if exists('HEAD') and git('log', '-1', '--format=%s', 'HEAD').strip() == subject:
        changed += parse_name_status(git('diff-tree', '--no-commit-id', '-r', '--root', '--name-status', 'HEAD'))
    return problems(subject, changed)


def exists(commit):
    return subprocess.run(('git', 'cat-file', '-e', commit + '^{commit}'), capture_output=True).returncode == 0


def check_range(base, head):
    """Without a known base (a manual run, a force push that dropped it) only head is checked."""
    if base and base != NO_COMMIT and exists(base):
        commits = git('rev-list', '--no-merges', '--reverse', '%s..%s' % (base, head)).split()
    else:
        is_merge = len(git('log', '-1', '--format=%P', head).split()) > 1
        commits = [] if is_merge else [head]
    found = []
    for commit in commits:
        subject = git('log', '-1', '--format=%s', commit).strip()
        changed = parse_name_status(git('diff-tree', '--no-commit-id', '-r', '--root', '--name-status', commit))
        found += ['%s %s' % (commit[:7], problem) for problem in problems(subject, changed)]
    return found


def main(argv):
    if len(argv) == 3 and argv[1] == 'message':
        found = check_message(argv[2])
    elif len(argv) == 4 and argv[1] == 'range':
        found = check_range(argv[2], argv[3])
    else:
        print(__doc__, file=sys.stderr)
        return 2
    for problem in found:
        print('commit message: ' + problem, file=sys.stderr)
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
