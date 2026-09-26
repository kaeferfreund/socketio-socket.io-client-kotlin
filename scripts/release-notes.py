#!/usr/bin/env python3
"""Writes the GitHub release notes of a version.

The text is the version's CHANGELOG.md section, followed by the installation
snippet and the evidence of the CI run on the tagged commit: its jobs, the parity
summary and the coverage summary that the run uploaded as `parity-summary`.
"""
import argparse
import json
import re
import sys

REPO = "https://github.com/kaeferfreund/socket.io-client-kotlin"
GROUP = "io.github.kaeferfreund.socketio"


def changelog_section(changelog, version):
    """Returns (date, body) of the '## <version> — <date>' section."""
    match = re.search(
        rf"^## {re.escape(version)} — (\d{{4}}-\d{{2}}-\d{{2}})\n(.*?)(?=^## |\Z)",
        changelog,
        re.S | re.M,
    )
    if not match:
        raise ValueError(f"CHANGELOG.md has no dated section '## {version} — YYYY-MM-DD'")
    body = match.group(2).strip()
    if not body:
        raise ValueError(f"the CHANGELOG.md section of {version} is empty")
    return match.group(1), body


def percent(counts):
    return f"{counts['percent']} % ({counts['covered']:,}/{counts['total']:,})"


def render(version, commit, ci_url, jobs, changelog, parity, coverage):
    _, body = changelog_section(changelog, version)
    failed_jobs = [job["name"] for job in jobs if job["conclusion"] != "success"]
    if not jobs or failed_jobs:
        raise ValueError(f"CI jobs did not all pass: {failed_jobs or 'no jobs'}")
    if not parity.get("junit_evidence") or parity["junit_failed_or_skipped_tests"] != 0:
        raise ValueError("the parity summary has no JUnit evidence or failed/skipped tests")
    if parity["uncertified_supported_rows"]:
        raise ValueError("the parity summary has uncertified supported rows")
    tag = f"v{version}"
    jvm = coverage["jvm_all_suites"]["total"]["line"]
    android = coverage["android_robolectric"]["total"]["line"]
    return f"""{body}

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {{
    repositories {{
        mavenCentral()
    }}
}}

// app/build.gradle.kts
dependencies {{
    implementation("{GROUP}:socketio-android:{version}")
}}
```

JVM applications depend on `socketio-okhttp` instead. The same artifacts are on
[Maven Central](https://central.sonatype.com/artifact/{GROUP}/socketio-android/{version}) and
[GitHub Packages](https://github.com/kaeferfreund?tab=packages&repo_name=socket.io-client-kotlin). See [Getting started]({REPO}/blob/{tag}/Documentation/Guides/GettingStarted.md)
and the [changelog]({REPO}/blob/{tag}/CHANGELOG.md).

## Release evidence

- Tag: `{tag}` → `{commit}`.
- [CI of the tagged commit]({ci_url}): all {len(jobs)} jobs passed.
- Parity gate: {parity['junit_passed_tests']} distinct tests passed on JDK 17 and end-to-end, 0 failed or skipped; all {parity['certified_runtime_rows']} supported runtime test declarations of the upstream client packages have contracts whose tests passed. [PARITY.md]({REPO}/blob/{tag}/PARITY.md) lists every boundary and deliberate difference; this is test parity for a defined scope, not universal JavaScript parity.
- Line coverage: {percent(jvm)} of the JVM modules across every JVM suite, `socketio-android` under Robolectric {percent(android)}.
- The release workflow published every module to Maven Central and GitHub Packages, then an independent build resolved `{version}` from Maven Central and compiled the README quick start.
- Android runs are Robolectric and emulators only; no physical-device validation was made for this release.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--ci-url", required=True)
    parser.add_argument("--jobs", required=True, help="JSON list of {name, conclusion} of the CI run")
    parser.add_argument("--parity", required=True, help="parity-summary.json of the CI run")
    parser.add_argument("--coverage", required=True, help="coverage-summary.json of the CI run")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    def load(path):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)

    with open(args.changelog, encoding="utf-8") as handle:
        changelog = handle.read()
    try:
        notes = render(
            args.version, args.commit, args.ci_url, load(args.jobs), changelog,
            load(args.parity), load(args.coverage),
        )
    except ValueError as error:
        sys.exit(f"FAIL: {error}")
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(notes)
    print(f"PASS: release notes of {args.version} written to {args.out}")


if __name__ == "__main__":
    main()
