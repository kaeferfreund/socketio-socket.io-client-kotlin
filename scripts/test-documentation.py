#!/usr/bin/env python3
"""Regression tests for check-documentation.py on a temporary checkout."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('checkdocs', Path(__file__).with_name('check-documentation.py'))
checkdocs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checkdocs)

QUICK = '<!-- quick-start:begin -->\n```kotlin\nval x = 1\n```\n<!-- quick-start:end -->\n'


class DocumentationCheckTest(unittest.TestCase):
    def tree(self, files):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text)
        return root

    def test_valid_links_and_anchors_pass(self):
        root = self.tree({'README.md': QUICK + '[a](docs/A.md#a-b--c) [top](#title)\n# Title\n', 'docs/A.md': '## A, b & c\n'})
        self.assertEqual([], checkdocs.check(root))

    def test_missing_file_and_heading_fail(self):
        root = self.tree({'README.md': QUICK + '[a](docs/B.md) [b](docs/A.md#nope)\n', 'docs/A.md': '# A\n'})
        errors = checkdocs.check(root)
        self.assertTrue(any('missing file' in e for e in errors))
        self.assertTrue(any('missing heading' in e for e in errors))

    def test_duplicate_headings_are_numbered(self):
        root = self.tree({'README.md': QUICK + '[x](#notes-1)\n# Notes\n# Notes\n'})
        self.assertEqual([], checkdocs.check(root))

    def test_links_inside_code_are_ignored_and_urls_not_fetched(self):
        root = self.tree({'README.md': QUICK + '`[x](nope.md)`\n```\n[y](nope.md)\n```\n[z](https://example.invalid/x)\n'})
        self.assertEqual([], checkdocs.check(root))

    def test_quick_start_marker_is_required(self):
        root = self.tree({'README.md': '# Readme\n'})
        self.assertIn('README.md: expected exactly one marked Kotlin quick start', checkdocs.check(root))

    def test_build_output_is_skipped(self):
        root = self.tree({'README.md': QUICK, 'm/build/x.md': '[a](missing.md)\n'})
        self.assertEqual([], checkdocs.check(root))


if __name__ == '__main__':
    unittest.main()
