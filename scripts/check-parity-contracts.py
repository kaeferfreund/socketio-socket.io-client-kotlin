#!/usr/bin/env python3
"""Validate the traceable parity contracts; optionally require passed JUnit executions.

Adapted from kaeferfreund/socket.io-client-swift 17.1.0 (scripts/check-parity-contracts.py):
the same rules, with Kotlin test symbols (`package.Class.method`) and Gradle's JUnit XML
reports as run evidence instead of an XCTest log.

A green default check only protects reviewed contracts and the explicit backlog.
--strict fails while any supported runtime row is uncertified. Reviewed exclusions
are scope boundaries, never counted as executed tests. With --junit, every test a
contract names must appear as passed in the given reports: a mapping without a
passing run is not evidence.
"""
import argparse
import collections
import csv
import glob
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]

CERTIFYING_KINDS = ('assertion-port', 'native-assertion-equivalent')
REVIEWED_STATUSES = ('api-difference', 'platform-specific')
ALL_STATUSES = ('focused-regression', 'api-difference', 'platform-specific', 'unsupported-feature', 'typescript-only', 'unmapped')
TEST_ROOT_PATTERN = re.compile(r'^[a-z0-9-]+/src/(test|androidTest)/kotlin/')


def read_junit(patterns):
    """Symbols (`package.Class.method`) of test cases that passed, and those that did not."""
    passed, failed = set(), set()
    files = sorted({path for pattern in patterns for path in glob.glob(pattern, recursive=True)})
    for path in files:
        root = ElementTree.parse(path).getroot()
        suites = [root] if root.tag == 'testsuite' else root.iter('testsuite')
        for suite in suites:
            for case in suite.iter('testcase'):
                name = case.get('name', '')
                method = name.split('(')[0].strip()
                symbol = case.get('classname', '') + '.' + method
                if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                    failed.add(symbol)
                else:
                    passed.add(symbol)
    return passed - failed, failed, files


def validate(root=ROOT, junit=None, upstream_inventory=None, strict=False, summary=None):
    with (root / 'Documentation/JavaScriptTestInventory.csv').open(newline='') as f:
        rows = list(csv.DictReader(f))
    manifest = json.loads((root / 'Documentation/JavaScriptParityContracts.json').read_text())
    errors = []
    by_id = {row['id']: row for row in rows}
    if len(by_id) != len(rows):
        errors.append('Duplicate upstream IDs')
    if {row['upstream_sha'] for row in rows} != {manifest['upstream_sha']}:
        errors.append('Upstream SHA does not match contract manifest')
    for row in rows:
        if row['status'] not in ALL_STATUSES:
            errors.append(row['id'] + ': unknown status ' + row['status'])
        if row['scope'] == 'typescript-type-contract' and row['status'] != 'typescript-only':
            errors.append(row['id'] + ': a TypeScript type contract must be typescript-only')
        if row['scope'] != 'typescript-type-contract' and row['status'] == 'typescript-only':
            errors.append(row['id'] + ': typescript-only is reserved for TypeScript type contracts')
    remaining = sorted(row['id'] for row in rows if row['status'] == 'unmapped')
    if remaining != sorted(manifest['remaining_unmapped_ids']):
        errors.append('Unmapped backlog changed without explicit review')

    passed, failed = set(), set()
    if junit is not None:
        passed, failed, files = read_junit(junit)
        if not files:
            errors.append('No JUnit XML reports matched ' + ', '.join(junit))
        elif not passed:
            errors.append('No passed test cases found in the supplied JUnit reports')
        # The plan forbids skipped or failing tests anywhere, not only in contracts.
        for symbol in sorted(failed):
            errors.append('JUnit reports a failed or skipped test: ' + symbol)

    certified = set()
    seen_contracts = set()
    contract_symbols = collections.defaultdict(set)
    for contract in manifest['contracts']:
        name = contract['id']
        if name in seen_contracts:
            errors.append('Duplicate contract ' + name)
        seen_contracts.add(name)
        if not contract.get('assertions', '').strip() or not contract.get('tests'):
            errors.append(name + ': assertions and executable tests are required')
        if contract.get('kind') not in CERTIFYING_KINDS + ('kotlin-extension',):
            errors.append(name + ': unknown contract kind ' + str(contract.get('kind')))
        upstream_ids = contract.get('upstream_ids', [])
        if contract.get('kind') == 'kotlin-extension':
            if upstream_ids or not name.startswith('KT-'):
                errors.append(name + ': Kotlin extension contracts are named KT-… and cover no upstream row')
        elif not upstream_ids:
            errors.append(name + ': an upstream contract needs upstream IDs')
        for id in upstream_ids:
            row = by_id.get(id)
            if row is None:
                errors.append(name + ': unknown upstream ID ' + id)
            elif row['status'] != 'focused-regression':
                errors.append(id + ': reviewed contract must have focused-regression status')
            if contract.get('kind') in CERTIFYING_KINDS:
                certified.add(id)
        for test in contract.get('tests', []):
            relative = test['path']
            path = (root / relative).resolve()
            if not TEST_ROOT_PATTERN.match(relative) or not path.is_file():
                errors.append(name + ': missing/invalid test file ' + relative)
                continue
            symbol = test['symbol']
            parts = symbol.split('.')
            if len(parts) < 3:
                errors.append(name + ': expected package.Class.method, got ' + symbol)
                continue
            package, cls, method = '.'.join(parts[:-2]), parts[-2], parts[-1]
            source = path.read_text()
            if not re.search(r'^package\s+' + re.escape(package) + r'\s*$', source, re.M):
                errors.append(name + ': test file is not in package ' + package)
            if not re.search(r'\bclass\s+' + re.escape(cls) + r'\b', source):
                errors.append(name + ': missing test class ' + symbol)
            if not re.search(r'\bfun\s+' + re.escape(method) + r'\s*\(', source):
                errors.append(name + ': missing test method ' + symbol)
            if junit is not None and symbol not in passed:
                errors.append(name + ': JUnit did not report a PASS for ' + symbol)
            for id in upstream_ids:
                contract_symbols[id].add(symbol)

    # The inventory's kotlin_tests column is a derived view of the gated contracts,
    # never independent evidence: it names exactly the symbols the contracts certify.
    for row in rows:
        listed = {ref for ref in re.split(r'[;\s]+', row['kotlin_tests']) if ref}
        if row['status'] != 'focused-regression':
            if listed:
                errors.append(row['id'] + ': only focused-regression rows may name tests')
            continue
        if listed != contract_symbols.get(row['id'], set()):
            errors.append(row['id'] + ': inventory kotlin_tests differ from the gated contract tests')
        if row['id'] not in certified:
            errors.append(row['id'] + ': focused-regression row without a certifying contract')

    excluded = set()
    for group in manifest.get('excluded_unsupported_features', []):
        ids = group.get('upstream_ids', [])
        if not ids or not group.get('reason', '').strip():
            errors.append('Unsupported-feature exclusion requires IDs and a reviewed reason')
        for id in ids:
            if id in excluded:
                errors.append(str(id) + ': duplicate unsupported-feature exclusion')
            excluded.add(id)
            row = by_id.get(id)
            if row is None or row['status'] != 'unsupported-feature':
                errors.append(str(id) + ': exclusion must refer to an unsupported-feature row')
    unsupported = {r['id'] for r in rows if r['status'] == 'unsupported-feature'}
    if excluded != unsupported:
        errors.append('Unsupported-feature rows require explicit reviewed exclusions')
    for row in rows:
        if row['status'] in ('unsupported-feature',) + REVIEWED_STATUSES + ('typescript-only',) and not row['review_note'].strip():
            errors.append(row['id'] + ': scope decision requires an inventory reason')

    if upstream_inventory is not None:
        actual = json.loads(Path(upstream_inventory).read_text())
        keys = ('package', 'scope', 'file', 'line', 'suite', 'title', 'modifier')
        identity = lambda row: tuple(str(row[key]) for key in keys)
        if sorted(map(identity, actual)) != sorted(map(identity, rows)):
            errors.append('CSV declarations differ from the freshly generated pinned upstream AST inventory')

    runtime = [r for r in rows if r['scope'] == 'runtime-declaration']
    missing = [r['id'] for r in runtime
               if r['status'] not in REVIEWED_STATUSES and r['id'] not in excluded and r['id'] not in certified]
    if strict and missing:
        errors.append('Complete parity NOT established; uncertified supported rows: ' + ', '.join(missing))

    if summary is not None:
        statuses = collections.Counter(r['status'] for r in rows)
        result = {
            'upstream_sha': manifest['upstream_sha'],
            'declarations': len(rows),
            'runtime_declarations': len(runtime),
            'typescript_type_declarations': len(rows) - len(runtime),
            'statuses': {status: statuses.get(status, 0) for status in ALL_STATUSES},
            'runtime_by_package': dict(collections.Counter(r['package'] for r in runtime)),
            'certified_runtime_rows': len([r for r in runtime if r['id'] in certified]),
            'uncertified_supported_rows': missing,
            'contracts': len(manifest['contracts']),
            'kotlin_extension_contracts': len([c for c in manifest['contracts'] if c.get('kind') == 'kotlin-extension']),
            'contract_tests': len({t['symbol'] for c in manifest['contracts'] for t in c.get('tests', [])}),
            'junit_evidence': junit is not None,
            'junit_passed_tests': len(passed) if junit is not None else None,
            'junit_failed_or_skipped_tests': len(failed) if junit is not None else None,
        }
        Path(summary).write_text(json.dumps(result, indent=2) + '\n')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--junit', action='append', help='glob of JUnit XML reports (repeatable); requires every contract test to have passed')
    parser.add_argument('--upstream-inventory')
    parser.add_argument('--strict', action='store_true')
    parser.add_argument('--summary', help='write the counts as JSON (the only numbers PARITY.md may quote)')
    args = parser.parse_args()
    errors = validate(junit=args.junit, upstream_inventory=args.upstream_inventory, strict=args.strict, summary=args.summary)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('PASS: reviewed parity contracts and explicit backlog are consistent' +
          ('; every contract test passed in the supplied JUnit reports' if args.junit else '') +
          ('; strict: no supported runtime row is uncertified.' if args.strict else '; no full-parity claim.'))


if __name__ == '__main__':
    main()
