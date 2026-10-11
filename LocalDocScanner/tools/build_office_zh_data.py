"""Generate an offline Chinese dialog overlay from the pinned Collabora core catalogs.

Catalog source: browser/po/core-zh_CN.po and core-zh_TW.po, MPL-2.0.
No runtime binary or existing browser bundle is modified.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path


def catalog(path):
    entries = {}
    current = {}
    field = None
    fuzzy = False
    def finish():
        if not fuzzy and current.get('msgid') and current.get('msgstr'):
            entries[current['msgid']] = current['msgstr']
    for raw in path.read_text(encoding='utf-8').splitlines() + ['']:
        line = raw.strip()
        if not line:
            finish(); current = {}; field = None; fuzzy = False
        elif line.startswith('#,') and 'fuzzy' in line:
            fuzzy = True
        elif line.startswith('#'):
            continue
        elif line.startswith(('msgid ', 'msgstr ', 'msgctxt ')):
            field, value = line.split(' ', 1)
            current[field] = ast.literal_eval(value)
        elif line.startswith('"') and field:
            current[field] += ast.literal_eval(line)
        else:
            raise ValueError(f'Unsupported PO entry in {path}: {line}')
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Pinned Collabora browser/po directory')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    data = {}; sources = []
    for locale, name in [('zh-CN', 'core-zh_CN.po'), ('zh-TW', 'core-zh_TW.po')]:
        path = args.source / name
        data[locale] = catalog(path)
        sources.append({'file': f'browser/po/{name}', 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'translated_entries': len(data[locale])})
    output = project / 'app/src/main/assets/localdoc-office-zh-data.js'
    output.write_text('// Chinese core dialog catalogs from Collabora Online, MPL-2.0. See docs/OFFICE_ZH_SOURCES.json.\nwindow.LocalDocOfficeChinese = ' + json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + ';\n', encoding='utf-8')
    manifest = {'repository': 'https://github.com/CollaboraOnline/online', 'commit': '20a46c332c380925803a1fe538a545c6f9b8fce7', 'license': 'MPL-2.0', 'files': sources, 'generated_sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    (project / 'docs/OFFICE_ZH_SOURCES.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=True))


if __name__ == '__main__': main()
