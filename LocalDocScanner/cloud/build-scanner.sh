#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
[[ $(uname -s) == Linux ]] || { printf 'Run this script in the Linux cloud environment.\n'; exit 2; }
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[[ -n $ANDROID_HOME && -d $ANDROID_HOME ]] || { printf 'BLOCKED: configure ANDROID_HOME to a Linux Android SDK.\n'; exit 2; }
export ANDROID_HOME
command -v java >/dev/null || { printf 'BLOCKED: install JDK 17.\n'; exit 2; }
command -v javac >/dev/null || { printf 'BLOCKED: a full JDK is required.\n'; exit 2; }
[[ -d "$ANDROID_HOME/platforms/android-35" && -d "$ANDROID_HOME/build-tools/35.0.0" ]] || {
  printf 'BLOCKED: install Android platform 35 and build-tools 35.0.0 with sdkmanager.\n'; exit 2;
}
mkdir -p cloud-results
[[ ! -f cloud/assets.sha256 ]] || sha256sum --check cloud/assets.sha256
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain 2>&1 | tee cloud-results/scanner-build.log
cp app/build/outputs/apk/debug/app-debug.apk cloud-results/LocalDocScanner-scanner-baseline.apk
rm -rf cloud-results/scanner-reports
mkdir -p cloud-results/scanner-reports
cp -a app/build/reports/tests cloud-results/scanner-reports/ 2>/dev/null || true
cp -a app/build/reports/lint-results-debug.* cloud-results/scanner-reports/ 2>/dev/null || true
sha256sum cloud-results/LocalDocScanner-scanner-baseline.apk > cloud-results/scanner-apk.sha256
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging cloud-results/LocalDocScanner-scanner-baseline.apk \
  > cloud-results/scanner-apk-badging.txt
printf 'Scanner baseline built. Embedded Office is not part of this baseline.\n'
