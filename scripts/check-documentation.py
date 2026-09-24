#!/usr/bin/env python3
"""Offline documentation check: relative links, heading anchors and the README quick start.

Checks every tracked Markdown file (outside build output and node_modules):
- a relative link points to an existing file or directory of this checkout;
- a `#fragment` names a heading of the target file (GitHub anchor rules);
- the README contains exactly one marked quick start with one Kotlin block.
External URLs are not fetched.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_PARTS = {'build', 'node_modules', '.gradle', '.git', '.kotlin'}
LINK = re.compile(r'(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)|!\[[^\]]*\]\(([^)\s]+)\)|<img[^>]*\ssrc="([^"]+)"')
FENCE = re.compile(r'^(```|~~~)')


def markdown_files(root):
    return sorted(p for p in root.rglob('*.md') if not SKIP_PARTS.intersection(p.relative_to(root).parts))


def strip_code(text):
    """Removes fenced blocks and inline code so example links are not checked."""
    out, fenced = [], False
    for line in text.splitlines():
        if FENCE.match(line.strip()):
            fenced = not fenced
            out.append('')
            continue
        out.append('' if fenced else re.sub(r'`[^`]*`', '', line))
    return '\n'.join(out)


def anchors(text):
    """GitHub heading anchors: lower case, punctuation removed, spaces to hyphens, duplicates numbered."""
    seen, result = {}, set()
    for line in strip_code(text).splitlines():
        m = re.match(r'^#{1,6}\s+(.*?)\s*#*\s*$', line)
        if not m:
            continue
        title = re.sub(r'<[^>]+>', '', m.group(1))
        title = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', title)
        slug = re.sub(r'[^\w\- ]', '', title.lower()).replace(' ', '-')
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        result.add(slug if n == 0 else f'{slug}-{n}')
    for m in re.finditer(r'<a\s+(?:name|id)="([^"]+)"', text):
        result.add(m.group(1))
    return result


def check(root=ROOT):
    errors = []
    cache = {}
    for path in markdown_files(root):
        text = path.read_text(encoding='utf-8')
        for m in LINK.finditer(strip_code(text)):
            target = next(g for g in m.groups() if g)
            if re.match(r'^[a-z][a-z0-9+.-]*:', target, re.I):
                continue
            file_part, _, fragment = target.partition('#')
            resolved = (path.parent / file_part).resolve() if file_part else path
            where = f'{path.relative_to(root)}: {target}'
            if not resolved.exists():
                errors.append(where + ' (missing file)')
                continue
            if fragment and resolved.suffix == '.md':
                if resolved not in cache:
                    cache[resolved] = anchors(resolved.read_text(encoding='utf-8'))
                if fragment not in cache[resolved]:
                    errors.append(where + ' (missing heading)')
    readme = (root / 'README.md').read_text(encoding='utf-8')
    blocks = re.findall(r'<!-- quick-start:begin -->\n```kotlin\n(.*?)```\n<!-- quick-start:end -->', readme, re.S)
    if len(blocks) != 1:
        errors.append('README.md: expected exactly one marked Kotlin quick start')
    return errors


def main():
    errors = check()
    if errors:
        sys.exit('\n'.join(errors))
    print(f'PASS: links and anchors in {len(markdown_files(ROOT))} Markdown files; README quick start marked.')


if __name__ == '__main__':
    main()
