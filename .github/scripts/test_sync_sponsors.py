#!/usr/bin/env python3
"""The rules the supporter list is folded together by.

Somebody sponsoring twice, or cancelling, or coming back, must never cost them what they already
gave — and nothing here may invent a Twitch account. Those rules are easy to break while changing
anything else in the script, and nobody would notice until a badge quietly went missing.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

spec = importlib.util.spec_from_file_location(
    'sync_sponsors', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sync-sponsors.py')
)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def sponsorship(login, created, one_time=False, active=True, privacy='PUBLIC'):
    return {
        'createdAt': created,
        'isActive': active,
        'isOneTimePayment': one_time,
        'privacyLevel': privacy,
        'sponsorEntity': {'login': login},
    }


class FoldTest(unittest.TestCase):
    def test_counts_one_time_sponsorships_and_keeps_the_first_day(self):
        found, private = sync.fold([
            sponsorship('ann', '2026-01-05T10:00:00Z', one_time=True),
            sponsorship('ann', '2026-03-09T10:00:00Z', one_time=True),
        ])
        self.assertEqual(found['ann'], {'since': '2026-01-05', 'oneTime': 2, 'monthlySince': None})
        self.assertEqual(private, 0)

    def test_a_running_monthly_sponsorship_says_since_when(self):
        found, _ = sync.fold([sponsorship('ben', '2026-02-01T10:00:00Z')])
        self.assertEqual(found['ben']['monthlySince'], '2026-02-01')

    def test_a_cancelled_monthly_sponsorship_is_no_longer_running(self):
        found, _ = sync.fold([sponsorship('ben', '2026-02-01T10:00:00Z', active=False)])
        self.assertIsNone(found['ben']['monthlySince'])
        self.assertEqual(found['ben']['since'], '2026-02-01', 'the day they first supported stands')

    def test_somebody_who_came_back_counts_from_the_sponsorship_running_now(self):
        found, _ = sync.fold([
            sponsorship('ben', '2026-01-01T10:00:00Z', active=False),
            sponsorship('ben', '2026-06-01T10:00:00Z'),
        ])
        self.assertEqual(found['ben']['monthlySince'], '2026-06-01')
        self.assertEqual(found['ben']['since'], '2026-01-01')

    def test_a_private_sponsorship_is_left_out_of_a_public_list(self):
        found, private = sync.fold([sponsorship('cat', '2026-01-01T10:00:00Z', privacy='PRIVATE')])
        self.assertEqual(found, {})
        self.assertEqual(private, 1)


class MergeTest(unittest.TestCase):
    def test_a_twitch_account_filled_in_by_hand_is_never_touched(self):
        existing = [{'twitch': '60579280', 'github': 'ann', 'since': '2026-01-05', 'oneTime': 1}]
        merged = sync.merge(existing, {'ann': {'since': '2026-01-05', 'oneTime': 2, 'monthlySince': None}})
        self.assertEqual(merged[0]['twitch'], '60579280')
        self.assertEqual(merged[0]['oneTime'], 2, 'sponsoring again adds to it')

    def test_a_new_sponsor_arrives_without_a_twitch_account(self):
        merged = sync.merge([], {'ben': {'since': '2026-02-01', 'oneTime': 0, 'monthlySince': '2026-02-01'}})
        self.assertEqual(merged[0], {'twitch': '', 'github': 'ben', 'since': '2026-02-01', 'oneTime': 0,
                                     'monthlySince': '2026-02-01'})

    def test_a_sponsorship_github_no_longer_reports_is_not_taken_away(self):
        existing = [{'twitch': '1', 'github': 'ann', 'since': '2026-01-05', 'oneTime': 3}]
        merged = sync.merge(existing, {'ann': {'since': '2026-01-05', 'oneTime': 0, 'monthlySince': None}})
        self.assertEqual(merged[0]['oneTime'], 3, 'what was given stays given')

    def test_somebody_who_stopped_sponsoring_keeps_their_entry_without_the_monthly_mark(self):
        existing = [{'twitch': '1', 'github': 'ben', 'since': '2026-02-01', 'oneTime': 0,
                     'monthlySince': '2026-02-01'}]
        merged = sync.merge(existing, {})
        self.assertEqual(merged[0]['twitch'], '1')
        self.assertIsNone(merged[0]['monthlySince'])

    def test_an_entry_added_by_hand_without_a_github_account_is_left_alone(self):
        existing = [{'twitch': '99', 'github': '', 'since': '2026-01-01', 'oneTime': 1}]
        merged = sync.merge(existing, {'ann': {'since': '2026-05-01', 'oneTime': 1, 'monthlySince': None}})
        self.assertIn({'twitch': '99', 'github': '', 'since': '2026-01-01', 'oneTime': 1}, merged)
        self.assertEqual(2, len(merged))

    def test_the_same_account_in_another_spelling_is_the_same_person(self):
        existing = [{'twitch': '1', 'github': 'Ann', 'since': '2026-01-05', 'oneTime': 1}]
        merged = sync.merge(existing, {'ann': {'since': '2026-01-05', 'oneTime': 2, 'monthlySince': None}})
        self.assertEqual(1, len(merged))
        self.assertEqual('1', merged[0]['twitch'])


if __name__ == '__main__':
    unittest.main()
