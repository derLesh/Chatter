#!/usr/bin/env python3
"""Writes what GitHub Sponsors knows into docs/supporters.json.

GitHub cannot trigger a workflow when somebody sponsors, so this asks instead: it reads the
sponsorships of the account the token belongs to and folds them into the list the app fetches.

What it will not do:

* It never writes a Twitch id. GitHub has no idea which Twitch account a sponsor has, and a badge
  put in front of the wrong name is worse than no badge, so that one field is filled in by hand.
  Until it is, the entry sits in the list and the app skips it.
* It never writes a private sponsorship. The list is public; whoever sponsored privately chose
  otherwise. Those are counted in the summary so that somebody can ask them.
* It never takes anything away. A sponsorship that ended leaves the entry standing, without its
  monthly mark: the support happened, and the badge is a thank-you, not a subscription.
* A one-time sponsorship on top of an earlier one adds to the entry — `oneTime` counts up and
  `since` stays the day it all started.

Run it yourself with a token in GITHUB_TOKEN:

    GITHUB_TOKEN=... .github/scripts/sync-sponsors.py [--write]

Without --write it only says what it would change, which is what a pull request wants.
"""

import json
import os
import sys
import urllib.error
import urllib.request

LIST = os.path.join(os.path.dirname(__file__), '..', '..', 'docs', 'supporters.json')
API = 'https://api.github.com/graphql'

QUERY = """
query($cursor: String) {
  viewer {
    login
    sponsorshipsAsMaintainer(first: 100, after: $cursor, includePrivate: true) {
      pageInfo { hasNextPage endCursor }
      nodes {
        createdAt
        isActive
        isOneTimePayment
        privacyLevel
        sponsorEntity {
          ... on User { login }
          ... on Organization { login }
        }
      }
    }
  }
}
"""


def fetch_sponsorships(token):
    """Every sponsorship of the account the token belongs to, oldest page first."""
    nodes = []
    cursor = None
    while True:
        body = json.dumps({'query': QUERY, 'variables': {'cursor': cursor}}).encode()
        request = urllib.request.Request(
            API,
            data=body,
            headers={
                'Authorization': 'bearer ' + token,
                'Content-Type': 'application/json',
                'User-Agent': 'chatter-sponsor-sync',
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            answer = json.loads(response.read().decode())
        if 'errors' in answer:
            raise SystemExit('GitHub said no: %s' % answer['errors'])
        page = answer['data']['viewer']['sponsorshipsAsMaintainer']
        nodes.extend(page['nodes'])
        if not page['pageInfo']['hasNextPage']:
            return nodes
        cursor = page['pageInfo']['endCursor']


def day(timestamp):
    """2026-09-21T10:11:12Z -> 2026-09-21."""
    return timestamp.split('T')[0]


def fold(sponsorships):
    """What GitHub says, per sponsor: when they started, how often, and whether it is running."""
    by_login = {}
    skipped_private = 0
    for node in sponsorships:
        entity = node.get('sponsorEntity') or {}
        login = entity.get('login')
        if not login:
            # A sponsor who deleted their account, or one this token may not see.
            continue
        if node.get('privacyLevel') != 'PUBLIC':
            skipped_private += 1
            continue
        started = day(node['createdAt'])
        state = by_login.setdefault(login, {'since': started, 'oneTime': 0, 'monthlySince': None})
        state['since'] = min(state['since'], started)
        if node['isOneTimePayment']:
            state['oneTime'] += 1
        elif node['isActive']:
            # Somebody who cancelled and started again counts from the sponsorship running now.
            state['monthlySince'] = max(state['monthlySince'] or started, started)
    return by_login, skipped_private


def merge(existing, found):
    """The list as it should be: what is there, brought up to date, nothing thrown away."""
    entries = {e.get('github', '').lower(): e for e in existing if e.get('github')}
    by_hand = [e for e in existing if not e.get('github')]

    for login, state in found.items():
        entry = entries.get(login.lower())
        if entry is None:
            entry = {'twitch': '', 'github': login}
            entries[login.lower()] = entry
        entry['github'] = login
        entry['since'] = min(entry.get('since') or state['since'], state['since'])
        # Never down: a sponsorship GitHub has stopped reporting still happened.
        entry['oneTime'] = max(int(entry.get('oneTime') or 0), state['oneTime'])
        entry['monthlySince'] = state['monthlySince']

    # A sponsor who is in the list but no longer sponsors monthly keeps the entry, without the mark.
    for login, entry in entries.items():
        if login not in {name.lower() for name in found}:
            entry['monthlySince'] = None

    ordered = sorted(entries.values(), key=lambda e: (e.get('since') or '', e.get('github', '')))
    return by_hand + ordered


def field_order(entry):
    """The same order in every entry, so that a diff of this file reads as a change, not a shuffle."""
    keys = ['twitch', 'github', 'since', 'oneTime', 'monthlySince']
    return {key: entry.get(key) for key in keys if key in entry or key in ('twitch', 'github')}


def main():
    token = os.environ.get('GITHUB_TOKEN') or os.environ.get('SPONSORS_TOKEN')
    if not token:
        raise SystemExit('no token: set GITHUB_TOKEN to one that may read your sponsorships')
    write = '--write' in sys.argv

    with open(LIST, encoding='utf-8') as handle:
        document = json.load(handle)
    before = json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True)

    found, private = fold(fetch_sponsorships(token))
    document['supporters'] = [field_order(entry) for entry in merge(document.get('supporters', []), found)]
    after = json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True)

    missing = [e['github'] for e in document['supporters'] if e.get('github') and not e.get('twitch')]
    print('%d public sponsor(s), %d private one(s) left out on purpose' % (len(found), private))
    if missing:
        print('no Twitch account yet, so the app cannot show their badge: ' + ', '.join(missing))

    if before == after:
        print('nothing to change')
        return
    if not write:
        print('would change the list (run with --write to do it)')
        sys.exit(2)

    with open(LIST, 'w', encoding='utf-8', newline='\n') as handle:
        json.dump(document, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
    print('list written')


if __name__ == '__main__':
    main()
