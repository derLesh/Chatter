#!/usr/bin/env python3
"""The rules a supporter badge is handed out by.

This one runs on whatever somebody types into an issue, so what it refuses matters as much as what
it does: a number that is not one, an account that does not sponsor, and above all an entry that
belongs to somebody else.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import copy
import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location(
    'claim_supporter', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'claim-supporter.py')
)
claim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(claim)

BODY = '### Twitch user id\n\n60579280\n\n### Twitch name\n\nlesh\n'

LIST = {
    'supporters': [
        {'twitch': '', 'github': 'ann', 'since': '2026-01-05', 'oneTime': 1},
        {'twitch': '111', 'github': 'ben', 'since': '2026-02-01', 'oneTime': 0, 'monthlySince': '2026-02-01'},
    ]
}


class ReadingTheIssueTest(unittest.TestCase):
    def test_takes_the_number_from_the_form(self):
        self.assertEqual(claim.twitch_id_in(BODY), '60579280')

    def test_a_name_is_not_a_user_id(self):
        self.assertIsNone(claim.twitch_id_in('### Twitch user id\n\nlesh\n'))

    def test_an_empty_answer_is_none(self):
        self.assertIsNone(claim.twitch_id_in('### Twitch user id\n\n_No response_\n'))

    def test_something_that_is_not_the_form_at_all(self):
        self.assertIsNone(claim.twitch_id_in('hello, I would like a badge'))


class ClaimTest(unittest.TestCase):
    def setUp(self):
        self.document = copy.deepcopy(LIST)

    def test_a_sponsor_gets_the_badge_on_their_own_entry(self):
        changed, message = claim.claim(self.document, 'ann', '60579280')
        self.assertTrue(changed)
        self.assertEqual(self.document['supporters'][0]['twitch'], '60579280')
        self.assertIn('60579280', message)

    def test_the_spelling_of_the_account_does_not_matter(self):
        changed, _ = claim.claim(self.document, 'ANN', '60579280')
        self.assertTrue(changed)

    def test_somebody_who_does_not_sponsor_changes_nothing(self):
        changed, message = claim.claim(self.document, 'stranger', '60579280')
        self.assertFalse(changed)
        self.assertEqual(self.document, LIST)
        self.assertIn('cannot see a sponsorship', message)

    def test_nobody_can_touch_somebody_elses_entry(self):
        # ann claiming ben's Twitch id only ever writes it into ann's own entry.
        claim.claim(self.document, 'ann', '111')
        self.assertEqual(self.document['supporters'][1]['twitch'], '111', 'ben keeps his')
        self.assertEqual(self.document['supporters'][0]['twitch'], '111', 'and ann has said hers is the same')

    def test_a_number_that_is_not_one_is_refused(self):
        changed, message = claim.claim(self.document, 'ann', None)
        self.assertFalse(changed)
        self.assertEqual(self.document, LIST)
        self.assertIn('not a name', message)

    def test_saying_the_same_thing_twice_changes_nothing(self):
        changed, message = claim.claim(self.document, 'ben', '111')
        self.assertFalse(changed)
        self.assertIn('already', message)

    def test_a_sponsor_may_move_their_badge_to_another_account(self):
        changed, message = claim.claim(self.document, 'ben', '222')
        self.assertTrue(changed)
        self.assertEqual(self.document['supporters'][1]['twitch'], '222')
        self.assertIn('111', message)


if __name__ == '__main__':
    unittest.main()
