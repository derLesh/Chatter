#!/usr/bin/env python3
"""How the benchmark's numbers end up in the job summary.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location(
    'benchmark_summary', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'benchmark-summary.py')
)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def run(**benchmarks):
    """A benchmarkData.json as androidx.benchmark writes it, cut down to what the script reads."""
    return {
        'benchmarks': [
            {
                'name': name,
                'metrics': {key: {'minimum': v * 0.9, 'maximum': v * 3, 'median': v, 'runs': [v]} for key, v in metrics.items()},
            }
            for name, metrics in benchmarks.items()
        ]
    }


class MediansTest(unittest.TestCase):
    def test_the_median_of_every_known_metric_is_read(self):
        data = run(parse={'timeNs': 4626.9, 'allocationCount': 62.6, 'somethingElse': 1})
        self.assertEqual({'parse': {'timeNs': 4626.9, 'allocationCount': 62.6}}, bench.medians(data))


class TableTest(unittest.TestCase):
    def test_without_master_the_numbers_stand_alone(self):
        text = bench.summary(run(parse={'timeNs': 4626.9, 'allocationCount': 62.6}))
        self.assertIn('| `parse` | 4.6 µs | 62.6 |', text)
        self.assertIn('no benchmark to compare with', text)

    def test_with_master_each_number_says_how_far_it_moved(self):
        branch = run(parse={'timeNs': 5000, 'allocationCount': 50})
        master = run(parse={'timeNs': 4000, 'allocationCount': 60})
        text = bench.summary(branch, master)
        self.assertIn('| `parse` | 5.0 µs (+25.0 %) | 50.0 (-16.7 %) |', text)

    def test_a_benchmark_master_does_not_have_is_shown_without_a_change(self):
        branch = run(parse={'timeNs': 5000}, newOne={'timeNs': 1000})
        master = run(parse={'timeNs': 5000})
        text = bench.summary(branch, master)
        self.assertIn('| `newOne` | 1.0 µs | – |', text)
        self.assertIn('| `parse` | 5.0 µs (+0.0 %) | – |', text)

    def test_nothing_allocated_before_and_after_is_no_change(self):
        self.assertEqual(' (±0 %)', bench.change(0, 0))
        self.assertEqual(' (new)', bench.change(3, 0))


if __name__ == '__main__':
    unittest.main()
