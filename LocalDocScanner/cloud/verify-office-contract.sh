#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
python3 tools/restore_office_runtime.py --verify
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path('.')
android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root/'office-engine/src/main/AndroidManifest.xml')
activities = manifest.findall('.//activity')
assert any(a.get(android+'name') == 'org.libreoffice.androidlib.LOActivity' and a.get(android+'exported') == 'false' and a.get(android+'process') == ':office' for a in activities), 'Missing isolated embedded Office activity'
bridge = (root/'app/src/main/java/com/localdoc/scanner/office/OfficeEngineBridge.kt').read_text()
assert 'component = ComponentName(context.packageName, EMBEDDED_ACTIVITY)' in bridge, 'Missing same-package editor intent'
for path in ['app/src/main/AndroidManifest.xml', 'office-engine/src/main/AndroidManifest.xml']:
    assert 'MANAGE_EXTERNAL_STORAGE' not in (root/path).read_text(), 'Unexpected all-files permission'
print('Office source/static contract satisfied; device tests remain required.')
PY
if [[ $# -gt 0 ]]; then
  python3 tools/check_embedded_office.py "$1"
fi
