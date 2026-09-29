#!/usr/bin/env python3
"""Writes what the message benchmark measured as a Markdown table, for a workflow's job summary.

The benchmark leaves one JSON file per run (androidx.benchmark's format). Given the branch's file
and, if there is one, master's, this lists every benchmark with its median time and allocations
per message, and how far the branch is from master in each.

The medians are what is compared: a run of thousands of repetitions has a few that the emulator
stalled on, and a mean would carry those into the table.

    .github/scripts/benchmark-summary.py branch.json [master.json] >> "$GITHUB_STEP_SUMMARY"
"""

import json
import sys

# The metrics worth a column, in the order they are shown, with how they are written.
METRICS = [
    ('timeNs', 'Time', lambda v: f'{v / 1000:,.1f} µs'),
    ('allocationCount', 'Allocations', lambda v: f'{v:,.1f}'),
]


def medians(data):
    """{benchmark name: {metric: median}} from one benchmarkData.json, parsed."""
    result = {}
    for bench in data.get('benchmarks', []):
        metrics = bench.get('metrics', {})
        result[bench['name']] = {key: metrics[key]['median'] for key, _, _ in METRICS if key in metrics}
    return result


def change(branch, base):
    """How far [branch] is from [base], as a signed percentage, or an empty string without a base."""
    if base is None:
        return ''
    if base == 0:
        return ' (±0 %)' if branch == 0 else ' (new)'
    percent = (branch - base) / base * 100
    return f' ({percent:+.1f} %)'


def table(branch, base=None):
    """The Markdown table for [branch]'s medians, each next to [base]'s where base has it."""
    header = ['Benchmark'] + [title for _, title, _ in METRICS]
    lines = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    for name in sorted(branch):
        cells = [f'`{name}`']
        for key, _, show in METRICS:
            value = branch[name].get(key)
            if value is None:
                cells.append('–')
                continue
            before = (base or {}).get(name, {}).get(key)
            cells.append(show(value) + change(value, before))
        lines.append('| ' + ' | '.join(cells) + ' |')
    return '\n'.join(lines)


def summary(branch_data, base_data=None):
    branch = medians(branch_data)
    base = medians(base_data) if base_data is not None else None
    title = '### What a message costs'
    note = (
        'Per message, median of all repetitions; in brackets the change against master, measured on the same emulator.'
        if base is not None else
        'Per message, median of all repetitions. Master has no benchmark to compare with yet.'
    )
    return f'{title}\n\n{note}\n\n{table(branch, base)}\n'


def main(args):
    if not 1 <= len(args) <= 2:
        print(__doc__, file=sys.stderr)
        return 2
    with open(args[0], encoding='utf-8') as f:
        branch = json.load(f)
    base = None
    if len(args) == 2:
        with open(args[1], encoding='utf-8') as f:
            base = json.load(f)
    print(summary(branch, base))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
