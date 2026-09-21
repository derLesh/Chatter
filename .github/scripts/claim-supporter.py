#!/usr/bin/env python3
"""Writes the Twitch account a sponsor claims into docs/supporters.json.

GitHub Sponsors knows who sponsored; it has no idea what they are called on Twitch. The app knows
the Twitch account, because Twitch logged the person in — but it cannot prove a sponsorship. This
is where the two halves meet: the sponsor opens an issue from the app with their Twitch id already
in it, GitHub says who opened it, and the supporter list says whether that account sponsors.

Nothing here is taken on trust from the issue except the number itself, and that only ever lands
in the entry of the account that opened it. Somebody who is not a sponsor is told so and changes
nothing; somebody who is can only ever name a Twitch account for their own sponsorship.

    ISSUE_BODY=... ISSUE_AUTHOR=... .github/scripts/claim-supporter.py
"""

import json
import os
import re
import sys

LIST = os.path.join(os.path.dirname(__file__), '..', '..', 'docs', 'supporters.json')


def twitch_id_in(body):
    """The number under the issue form's "Twitch user id" heading, if it is one."""
    # GitHub renders an issue form as "### <label>" followed by the answer.
    match = re.search(r'^###\s*Twitch user id\s*$\s*(.+?)\s*$', body or '', re.M | re.S)
    if not match:
        return None
    answer = match.group(1).strip().splitlines()[0].strip()
    if answer.lower() in ('_no response_', ''):
        return None
    return answer if answer.isdigit() else None


def claim(document, author, twitch_id):
    """Puts the id in the entry of whoever opened the issue. Returns (changed, what to say)."""
    entries = document.get('supporters', [])
    mine = next((e for e in entries if (e.get('github') or '').lower() == author.lower()), None)

    if mine is None:
        return False, (
            'I cannot see a sponsorship for **@%s** yet.\n\n'
            'GitHub Sponsors is read every few hours, so if you have just sponsored, give it a '
            'moment and edit this issue — that checks again. If you sponsored privately, the list '
            'is public and leaves private sponsorships out on purpose; say so here and I will '
            'sort it out by hand.' % author
        )

    if twitch_id is None:
        return False, (
            'That does not look like a Twitch **user id** — it is a number, not a name.\n\n'
            'Opening this from Chatter (Settings → Support → I have sponsored) fills it in for '
            'you. You can also edit this issue and put the number in; that checks again.'
        )

    if mine.get('twitch') == twitch_id:
        return False, 'That is already the Twitch account on your entry, so there is nothing to do.'

    was = mine.get('twitch') or ''
    mine['twitch'] = twitch_id
    return True, (
        ('Done — the badge now goes to Twitch id `%s`.' % twitch_id) if not was
        else ('Changed — the badge goes to Twitch id `%s` now, not `%s`.' % (twitch_id, was))
    ) + '\n\nIt shows up in Chatter once the list has been fetched again, which happens when the app comes back to the foreground.'


def main():
    author = os.environ.get('ISSUE_AUTHOR', '').strip()
    if not author:
        raise SystemExit('no ISSUE_AUTHOR')
    twitch_id = twitch_id_in(os.environ.get('ISSUE_BODY', ''))

    with open(LIST, encoding='utf-8') as handle:
        document = json.load(handle)

    changed, message = claim(document, author, twitch_id)

    if changed:
        with open(LIST, 'w', encoding='utf-8', newline='\n') as handle:
            json.dump(document, handle, indent=2, ensure_ascii=False)
            handle.write('\n')

    with open(os.environ.get('CLAIM_COMMENT', 'claim-comment.md'), 'w', encoding='utf-8') as handle:
        handle.write(message + '\n')

    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a', encoding='utf-8') as handle:
            handle.write('claimed=%s\n' % ('true' if changed else 'false'))

    print(message, file=sys.stderr)


if __name__ == '__main__':
    main()
