#!/usr/bin/env python3
"""Regression tests for the parity validator (check-parity-contracts.py) on synthetic checkouts.

Each test builds a minimal inventory, manifest, Kotlin test file and JUnit report,
then checks that the validator accepts the consistent case and rejects each way
evidence can be faked or lost.
"""
import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('parity', Path(__file__).with_name('check-parity-contracts.py'))
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)

SHA = 'aaf2af36ec8ad05910f357a788e0e358bad32738'
HEADER = 'id,package,scope,file,line,suite,title,modifier,conditional_or_parameterized,status,kotlin_tests,review_note,upstream_sha\n'
SYMBOL = 'pkg.demo.DemoTest.works'
TEST_PATH = 'demo/src/test/kotlin/pkg/demo/DemoTest.kt'


def row(id, status, tests='', note='', scope='runtime-declaration'):
    return f'{id},socket.io-client,{scope},test/x.ts,1,suite,title {id},,False,{status},{tests},{note},{SHA}\n'


BASE_ROWS = [row('JS-001', 'focused-regression', SYMBOL), row('JS-002', 'platform-specific', note='Node only'),
             row('JS-003', 'unsupported-feature', note='WebTransport')]
BASE_MANIFEST = {
    'upstream_sha': SHA, 'scope': 'test', 'remaining_unmapped_ids': [],
    'contracts': [{'id': 'JS-001', 'kind': 'assertion-port', 'upstream_ids': ['JS-001'], 'assertions': 'it works',
                   'tests': [{'path': TEST_PATH, 'symbol': SYMBOL}]}],
    'excluded_unsupported_features': [{'upstream_ids': ['JS-003'], 'reason': 'not on Android'}],
}
PASSED = f'<testsuite name="x"><testcase classname="pkg.demo.DemoTest" name="works()"/></testsuite>'


class ParityValidatorTest(unittest.TestCase):
    def checkout(self, rows=None, manifest=None, junit=PASSED, source=None):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        (root / 'Documentation').mkdir()
        (root / 'Documentation/JavaScriptTestInventory.csv').write_text(HEADER + ''.join(rows or BASE_ROWS))
        (root / 'Documentation/JavaScriptParityContracts.json').write_text(json.dumps(manifest or BASE_MANIFEST))
        test = root / TEST_PATH
        test.parent.mkdir(parents=True)
        test.write_text(source or 'package pkg.demo\n\nclass DemoTest {\n    @Test\n    fun works() {}\n}\n')
        (root / 'junit.xml').write_text(junit)
        return root

    def validate(self, root, **kwargs):
        return parity.validate(root=root, junit=[str(root / 'junit.xml')], strict=True, **kwargs)

    def test_consistent_checkout_passes(self):
        self.assertEqual([], self.validate(self.checkout()))

    def test_a_failed_contract_test_is_not_evidence(self):
        failed = PASSED.replace('/>', '><failure/></testcase>')
        errors = self.validate(self.checkout(junit=failed))
        self.assertIn('JS-001: JUnit did not report a PASS for ' + SYMBOL, errors)

    def test_a_skipped_test_anywhere_fails(self):
        junit = PASSED.replace('</testsuite>', '<testcase classname="pkg.Other" name="later()"><skipped/></testcase></testsuite>')
        self.assertIn('JUnit reports a failed or skipped test: pkg.Other.later', self.validate(self.checkout(junit=junit)))

    def test_parameterized_executions_count_when_named_after_the_method(self):
        junit = ('<testsuite name="x"><testcase classname="pkg.demo.DemoTest" name="works(String) [1] a"/>'
                 '<testcase classname="pkg.demo.DemoTest" name="works(String) [2] b"/></testsuite>')
        self.assertEqual([], self.validate(self.checkout(junit=junit)))

    def test_a_parameterized_execution_named_only_by_its_index_is_not_evidence(self):
        junit = '<testsuite name="x"><testcase classname="pkg.demo.DemoTest" name="[1] a"/></testsuite>'
        self.assertIn('JS-001: JUnit did not report a PASS for ' + SYMBOL, self.validate(self.checkout(junit=junit)))

    def test_missing_reports_fail(self):
        root = self.checkout()
        errors = parity.validate(root=root, junit=[str(root / 'none/*.xml')], strict=True)
        self.assertTrue(any(e.startswith('No JUnit XML reports matched') for e in errors))

    def test_a_renamed_test_method_fails(self):
        source = 'package pkg.demo\n\nclass DemoTest {\n    fun renamed() {}\n}\n'
        self.assertIn('JS-001: missing test method ' + SYMBOL, self.validate(self.checkout(source=source)))

    def test_inventory_tests_must_match_the_contract(self):
        rows = [row('JS-001', 'focused-regression', 'pkg.demo.DemoTest.other')] + BASE_ROWS[1:]
        self.assertIn('JS-001: inventory kotlin_tests differ from the gated contract tests', self.validate(self.checkout(rows=rows)))

    def test_strict_fails_for_an_unmapped_supported_row(self):
        rows = BASE_ROWS + [row('JS-004', 'unmapped')]
        manifest = copy.deepcopy(BASE_MANIFEST)
        manifest['remaining_unmapped_ids'] = ['JS-004']
        errors = self.validate(self.checkout(rows=rows, manifest=manifest))
        self.assertIn('Complete parity NOT established; uncertified supported rows: JS-004', errors)

    def test_the_unmapped_backlog_cannot_change_silently(self):
        rows = BASE_ROWS + [row('JS-004', 'unmapped')]
        self.assertIn('Unmapped backlog changed without explicit review', self.validate(self.checkout(rows=rows)))

    def test_scope_decisions_need_a_reason(self):
        rows = BASE_ROWS[:1] + [row('JS-002', 'platform-specific')] + BASE_ROWS[2:]
        self.assertIn('JS-002: scope decision requires an inventory reason', self.validate(self.checkout(rows=rows)))

    def test_unsupported_rows_need_an_explicit_exclusion(self):
        manifest = copy.deepcopy(BASE_MANIFEST)
        manifest['excluded_unsupported_features'] = []
        self.assertIn('Unsupported-feature rows require explicit reviewed exclusions', self.validate(self.checkout(manifest=manifest)))

    def test_kotlin_extension_contracts_cannot_certify_upstream_rows(self):
        manifest = copy.deepcopy(BASE_MANIFEST)
        manifest['contracts'].append({'id': 'KT-X', 'kind': 'kotlin-extension', 'upstream_ids': ['JS-001'], 'assertions': 'x',
                                      'tests': [{'path': TEST_PATH, 'symbol': SYMBOL}]})
        errors = self.validate(self.checkout(manifest=manifest))
        self.assertIn('KT-X: Kotlin extension contracts are named KT-… and cover no upstream row', errors)

    def test_a_contract_needs_assertions(self):
        manifest = copy.deepcopy(BASE_MANIFEST)
        manifest['contracts'][0]['assertions'] = ' '
        self.assertIn('JS-001: assertions and executable tests are required', self.validate(self.checkout(manifest=manifest)))

    def test_typescript_rows_are_reserved(self):
        rows = BASE_ROWS + [row('JS-005', 'typescript-only', note='types', scope='runtime-declaration')]
        self.assertIn('JS-005: typescript-only is reserved for TypeScript type contracts', self.validate(self.checkout(rows=rows)))

    def test_the_summary_counts_only_validated_numbers(self):
        root = self.checkout()
        summary = root / 'summary.json'
        self.assertEqual([], self.validate(root, summary=str(summary)))
        data = json.loads(summary.read_text())
        self.assertEqual(1, data['certified_runtime_rows'])
        self.assertEqual(1, data['junit_passed_tests'])
        self.assertEqual({'focused-regression': 1, 'api-difference': 0, 'platform-specific': 1, 'unsupported-feature': 1,
                          'typescript-only': 0, 'unmapped': 0}, data['statuses'])


if __name__ == '__main__':
    unittest.main()
