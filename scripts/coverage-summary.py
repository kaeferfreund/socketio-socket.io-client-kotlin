#!/usr/bin/env python3
"""Summarize Kover XML reports as the coverage numbers README.md quotes.

    python3 scripts/coverage-summary.py --jvm build/reports/kover/report.xml \\
        --android socketio-android/build/reports/kover/report.xml --out coverage.json

--jvm is the merged report of the JVM library modules across every JVM suite,
end-to-end included (`./gradlew :koverXmlReport`); --android is the Robolectric
report of socketio-android (`./gradlew :socketio-android:koverXmlReport`).
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ElementTree

COUNTERS = ('LINE', 'BRANCH', 'METHOD')


def counters(element):
    values = {}
    for counter in element.findall('counter'):
        missed, covered = int(counter.get('missed')), int(counter.get('covered'))
        values[counter.get('type')] = {'covered': covered, 'total': missed + covered,
                                       'percent': round(100 * covered / (missed + covered), 1) if missed + covered else None}
    return {key.lower(): values[key] for key in COUNTERS if key in values}


def summarize(path):
    root = ElementTree.parse(path).getroot()
    return {'total': counters(root), 'packages': {p.get('name').replace('/', '.'): counters(p) for p in root.findall('package')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--jvm', required=True)
    parser.add_argument('--android', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    result = {'jvm_all_suites': summarize(args.jvm), 'android_robolectric': summarize(args.android)}
    Path(args.out).write_text(json.dumps(result, indent=2) + '\n')
    for name, report in result.items():
        total = report['total']
        print('%s: lines %s%%, branches %s%%, methods %s%%' % (name, total['line']['percent'], total['branch']['percent'], total['method']['percent']))


if __name__ == '__main__':
    main()
