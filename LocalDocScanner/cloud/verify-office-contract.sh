#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
failed=0
require_file() { [[ -e $1 ]] || { echo "FAIL missing: $1"; failed=1; }; }

require_file office-engine/src/main/AndroidManifest.xml
require_file office-engine/src/main/java/org/libreoffice/androidlib/LOActivity.java
require_file office-engine/src/main/cpp/lib/arm64-v8a/liblo-native-code.so
require_file office-engine/src/main/assets/program/services.rdb
require_file office-engine/src/main/assets/unpack/program/offapi.rdb
require_file office-engine/src/main/assets/unpack/program/oovbaapi.rdb
require_file office-engine/src/main/assets/share/config

if rg -n 'com\.collabora\.libreoffice|ACTION_EDIT' app/src/main/java/com/localdoc/scanner/office \
    app/src/main/java/com/localdoc/scanner/external >/dev/null; then
  echo "FAIL external Collabora package/ACTION_EDIT path remains in the primary Office flow"
  failed=1
fi
if rg -n 'MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml office-engine/src/main/AndroidManifest.xml \
    2>/dev/null; then
  echo "FAIL broad all-files permission is present"
  failed=1
fi
if (( failed )); then
  echo "Office-in-one-APK contract: NOT SATISFIED"
  exit 1
fi
echo "Office-in-one-APK static contract: SATISFIED (device tests still required)"
