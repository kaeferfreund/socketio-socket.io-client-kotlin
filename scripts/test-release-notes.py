#!/usr/bin/env python3
"""Unit tests of release-notes.py: notes come only from a dated CHANGELOG section
and a CI run whose jobs and parity gate all passed."""
import importlib.util
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("release_notes", ROOT / "scripts" / "release-notes.py")
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)

CHANGELOG = """# Changelog

## Unreleased

- Not yet.

## 2.0.0 — 2026-10-01

Second.

### Fixed

- A bug.

## 1.0.0 — 2026-09-01

First.
"""
JOBS = [{"name": "build", "conclusion": "success"}, {"name": "e2e", "conclusion": "success"}]


def evidence():
    parity = json.loads((ROOT / "Documentation/ReviewEvidence/ParitySummary.json").read_text())
    coverage = json.loads((ROOT / "Documentation/ReviewEvidence/CoverageSummary.json").read_text())
    return parity, coverage


def render(version="2.0.0", jobs=JOBS, changelog=CHANGELOG, parity=None, coverage=None):
    recorded_parity, recorded_coverage = evidence()
    return notes.render(
        version, "abc123", "https://ci/run", jobs, changelog,
        parity or recorded_parity, coverage or recorded_coverage,
    )


class ReleaseNotesTest(unittest.TestCase):
    def test_uses_only_the_section_of_the_version(self):
        text = render()
        self.assertTrue(text.startswith("Second.\n\n### Fixed\n\n- A bug."))
        self.assertNotIn("First.", text)
        self.assertNotIn("Not yet.", text)
        self.assertIn('socketio-android:2.0.0")', text)
        self.assertIn("`v2.0.0` → `abc123`", text)
        self.assertIn("all 2 jobs passed", text)

    def test_the_last_section_ends_at_the_end_of_the_file(self):
        self.assertTrue(render(version="1.0.0").startswith("First.\n\n## Install"))

    def test_rejects_a_version_without_a_dated_section(self):
        with self.assertRaisesRegex(ValueError, "no dated section"):
            render(version="3.0.0")
        with self.assertRaisesRegex(ValueError, "no dated section"):
            render(version="2.0", changelog=CHANGELOG)

    def test_rejects_an_empty_section(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            render(changelog="## 2.0.0 — 2026-10-01\n\n## 1.0.0 — 2026-09-01\n\nFirst.\n")

    def test_rejects_a_failed_or_missing_job(self):
        with self.assertRaisesRegex(ValueError, "e2e"):
            render(jobs=[JOBS[0], {"name": "e2e", "conclusion": "failure"}])
        with self.assertRaisesRegex(ValueError, "no jobs"):
            render(jobs=[])

    def test_rejects_parity_without_passing_evidence(self):
        parity, _ = evidence()
        for change in ({"junit_evidence": False}, {"junit_failed_or_skipped_tests": 1},
                       {"uncertified_supported_rows": ["row"]}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                render(parity={**parity, **change})

    def test_the_current_changelog_has_a_section_for_its_latest_release(self):
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        latest = re.search(r"^## (\d+\.\d+\.\d+) — ", changelog, re.M).group(1)
        self.assertIn(f"`v{latest}`", render(version=latest, changelog=changelog))


if __name__ == "__main__":
    unittest.main()
