#!/usr/bin/env python3
"""Which commit subjects the commit-msg hook and CI let through.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location(
    'check_commits',
    os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-commits.py'),
)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

ENTRY = [('A', 'pending-changelog/pinned-messages.txt')]
CODE = [('M', 'app/src/main/java/me/lesh/chatter/chat/ChatRepository.kt')]


class Subjects(unittest.TestCase):
    def test_type_scope_and_text(self):
        self.assertEqual(check.problems('ref(chat): move the buffer into its own class', CODE), [])

    def test_scope_is_optional(self):
        self.assertEqual(check.problems('docs: explain the release environments', []), [])

    def test_bang_marks_a_breaking_change(self):
        self.assertEqual(check.problems('build!: move the app id to me.lesh.chatter', CODE), [])

    def test_old_style_subject(self):
        self.assertEqual(len(check.problems('Hide single kinds of badges', CODE)), 1)

    def test_unknown_type(self):
        found = check.problems('refactor: move the buffer', CODE)
        self.assertEqual(len(found), 1)
        self.assertIn('ref', found[0])

    def test_type_in_capitals(self):
        self.assertEqual(len(check.problems('Feat: show pinned messages', ENTRY)), 1)

    def test_missing_space_after_colon(self):
        self.assertEqual(len(check.problems('fix:keep the nickname', ENTRY)), 1)

    def test_trailing_dot(self):
        self.assertEqual(len(check.problems('docs: explain the hook.', [])), 1)

    def test_too_long(self):
        self.assertEqual(len(check.problems('docs: ' + 'x' * 100, [])), 1)

    def test_subjects_git_writes(self):
        self.assertEqual(check.problems("Merge remote-tracking branch 'origin/dependabot/gradle/x'", []), [])
        self.assertEqual(check.problems('Revert "feat(chat): show pinned messages"', []), [])


class ChangelogEntries(unittest.TestCase):
    def test_feat_fix_perf_need_an_entry(self):
        for kind in ('feat', 'fix', 'perf'):
            with self.subTest(kind):
                self.assertEqual(len(check.problems(kind + '(chat): do it', CODE)), 1)
                self.assertEqual(check.problems(kind + '(chat): do it', CODE + ENTRY), [])

    def test_breaking_feat_needs_one_too(self):
        self.assertEqual(len(check.problems('feat!: drop the old layout', CODE)), 1)

    def test_changing_an_entry_counts(self):
        self.assertEqual(check.problems('fix: say it better', [('M', 'pending-changelog/x.txt')]), [])

    def test_deleting_an_entry_does_not(self):
        self.assertEqual(len(check.problems('fix: undo it', [('D', 'pending-changelog/x.txt')])), 1)

    def test_readme_is_no_entry(self):
        self.assertEqual(len(check.problems('feat: do it', [('M', 'pending-changelog/README.md')])), 1)

    def test_other_types_need_none(self):
        for kind in ('ref', 'docs', 'test', 'build', 'ci', 'chore', 'revert'):
            with self.subTest(kind):
                self.assertEqual(check.problems(kind + ': do it', CODE), [])


class Parsing(unittest.TestCase):
    def test_rename_counts_under_its_new_name(self):
        self.assertEqual(check.parse_name_status('R100\told.txt\tpending-changelog/new.txt\nM\tREADME.md\n'),
                         [('R100', 'pending-changelog/new.txt'), ('M', 'README.md')])

    def test_subject_skips_comments_and_blank_lines(self):
        self.assertEqual(check.subject_of('\n# Please enter the commit message\nfeat: x\n\nbody\n'), 'feat: x')


if __name__ == '__main__':
    unittest.main()
