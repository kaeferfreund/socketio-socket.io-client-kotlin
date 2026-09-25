#!/usr/bin/env python3
"""Check the JavaScript API mapping; optionally against the pinned upstream source.

Documentation/JavaScriptApiMapping.json maps every public member of the pinned
socket.io-client API (the io() entry point, Socket, Manager, their inherited
Emitter methods and the options interfaces) to Kotlin symbols or a reviewed
reason. This script verifies that

- every implemented or adapted member names Kotlin symbols that exist in the
  checked-in api/*.api dumps (so a rename or removal cannot go unnoticed);
- adapted members explain the difference and every other status has a reason;
- with --upstream, the mapped members are exactly the members declared in the
  pinned TypeScript sources: a new or removed upstream member fails the check.

--summary writes the counts that README.md and PARITY.md quote.
"""
import argparse
import collections
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STATUSES = ('implemented', 'adapted', 'not-applicable', 'not-implemented')
MEMBER = re.compile(r'^\s+public (?:(?:final|static|abstract|synthetic|open) )*(?:fun|field) ([A-Za-z0-9_$<>]+)')


def kotlin_api(root):
    """Class name -> member names from the api dumps; mangled suffixes (`-FghU774`) are removed."""
    api = collections.defaultdict(set)
    for dump in root.glob('*/api/*.api'):
        current = None
        for line in dump.read_text().splitlines():
            header = re.match(r'^public .*?\bclass ([^ ]+)', line)
            if header:
                current = header.group(1)
                api[current]
                continue
            member = MEMBER.match(line)
            if member and current:
                api[current].add(member.group(1).split('-')[0])
    return api


def typescript_members(source, kind, declaration):
    """Public member names of a top-level TypeScript class or interface."""
    lines = source.splitlines()
    pattern = re.compile(r'^export (?:declare )?(?:abstract )?(?:class|interface) ' + re.escape(declaration) + r'\b')
    start = next((i for i, line in enumerate(lines) if pattern.match(line)), None)
    if start is None:
        raise SystemExit('declaration not found: ' + declaration)
    # The header (with its generic parameters) ends at the first line ending in '{'.
    body_start = next(i for i in range(start, len(lines)) if lines[i].rstrip().endswith('{')) + 1
    body = []
    for line in lines[body_start:]:
        if line == '}':
            break
        body.append(line)
    code = [line for line in body if line.strip() and not line.strip().startswith(('/*', '*', '//'))]
    indent = min(len(line) - len(line.lstrip()) for line in code)
    names = set()
    for line in body:
        if len(line) - len(line.lstrip()) != indent:
            continue
        text = line.strip()
        if kind == 'interface':
            m = re.match(r'^([A-Za-z_]\w*)\??:', text)
        else:
            if re.match(r'^(private|protected)\b', text):
                continue
            m = re.match(r'^(?:public |static |get |set |readonly |override |async )*([A-Za-z_]\w*)\??[:(<]', text)
        if m and not m.group(1).startswith('_') and m.group(1) != 'constructor':
            names.add(m.group(1))
    return names


def exported_names(source):
    names = set()
    for block in re.findall(r'Object\.assign\(lookup, \{(.*?)\}\)', source, re.S):
        names |= set(re.findall(r'(\w+)(?:\s*:\s*\w+)?\s*,', block + ','))
    for block in re.findall(r'export \{(.*?)\}', source, re.S):
        names |= {n.strip().split(' as ')[-1] for n in block.split(',') if n.strip() and not n.strip().startswith('type ')}
    if re.search(r'export \{\s*lookup as io', source) or 'lookup as default' in source:
        names.add('lookup')
    names.add('lookup')
    return names


def validate(root=ROOT, upstream=None, summary=None):
    mapping = json.loads((root / 'Documentation/JavaScriptApiMapping.json').read_text())
    api = kotlin_api(root)
    errors = []
    counts = {}
    if upstream is not None:
        sha = subprocess.run(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
        if sha != mapping['upstream_sha']:
            errors.append('upstream checkout is at %s, the mapping at %s' % (sha, mapping['upstream_sha']))
    for surface in mapping['surfaces']:
        name, members = surface['name'], surface['members']
        tally = collections.Counter()
        for member, entry in members.items():
            status = entry.get('status')
            where = '%s.%s' % (name, member)
            if status not in STATUSES:
                errors.append(where + ': unknown status ' + str(status))
                continue
            tally[status] += 1
            if status in ('implemented', 'adapted'):
                if not entry.get('kotlin'):
                    errors.append(where + ': needs Kotlin symbols')
                for symbol in entry.get('kotlin', []):
                    cls, _, fn = symbol.partition('#')
                    if cls not in api:
                        errors.append(where + ': no class ' + cls + ' in api/*.api')
                    elif fn and fn != '<init>' and fn not in api[cls]:
                        errors.append(where + ': no member ' + symbol + ' in api/*.api')
                if status == 'adapted' and not entry.get('note', '').strip():
                    errors.append(where + ': an adapted member needs a note')
            elif not entry.get('reason', '').strip():
                errors.append(where + ': needs a reviewed reason')
        if upstream is not None:
            source = (Path(upstream) / surface['source']).read_text()
            declared = exported_names(source) if surface['kind'] == 'exports' else typescript_members(source, surface['kind'], surface['declaration'])
            if surface['kind'] == 'exports':
                missing = sorted(set(members) - declared)
                if missing:
                    errors.append(name + ': not exported upstream: ' + ', '.join(missing))
            elif declared != set(members):
                errors.append(name + ': upstream members differ; unmapped %s, not upstream %s'
                              % (sorted(declared - set(members)), sorted(set(members) - declared)))
        counts[name] = {status: tally.get(status, 0) for status in STATUSES}
    if summary is not None:
        total = collections.Counter()
        for c in counts.values():
            total.update(c)
        members = sum(total.values())
        result = {
            'upstream_sha': mapping['upstream_sha'],
            'checked_against_upstream_source': upstream is not None,
            'members': members,
            'totals': {status: total.get(status, 0) for status in STATUSES},
            'available': total['implemented'] + total['adapted'],
            'applicable': members - total['not-applicable'],
            'surfaces': counts,
        }
        Path(summary).write_text(json.dumps(result, indent=2) + '\n')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--upstream', help='socketio/socket.io checkout at the pinned commit')
    parser.add_argument('--summary', help='write the counts as JSON')
    args = parser.parse_args()
    errors = validate(upstream=args.upstream, summary=args.summary)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('PASS: every mapped JavaScript API member resolves to Kotlin symbols or a reviewed reason'
          + ('; the mapping matches the pinned upstream declarations.' if args.upstream else '.'))


if __name__ == '__main__':
    main()
