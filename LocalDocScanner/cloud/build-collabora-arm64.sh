#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$root/cloud/collabora-upstream.lock"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[[ -n $ANDROID_HOME && -d $ANDROID_HOME ]] || { echo "BLOCKED: set ANDROID_HOME"; exit 2; }
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || {
  echo "BLOCKED: install ndk;$ANDROID_NDK_VERSION from the Android SDK repository"; exit 2;
}

tools="$root/.cloud-tools/collabora"
archive="$tools/online-$UPSTREAM_COMMIT.tar.gz"
source_dir="$tools/online-$UPSTREAM_COMMIT"
mkdir -p "$tools" "$root/cloud-results"

if [[ ! -f $archive ]]; then
  curl -fL --retry 3 -o "$archive.part" "$UPSTREAM_ARCHIVE_URL"
  mv "$archive.part" "$archive"
fi
actual_archive_sha=$(sha256sum "$archive" | cut -d' ' -f1)
if [[ $actual_archive_sha != "$UPSTREAM_ARCHIVE_SHA256" && $actual_archive_sha != "${UPSTREAM_ARCHIVE_ALTERNATE_SHA256:-}" ]]; then
  echo "BLOCKED: archive checksum does not match either verified pinned export"; exit 2
fi
printf 'Verified pinned source archive: %s\n' "$actual_archive_sha"
if [[ ! -x $source_dir/engine/autogen.sh ]]; then
  rm -rf "$source_dir"
  mkdir -p "$source_dir"
  tar -xzf "$archive" -C "$source_dir" --strip-components=1
fi

cat > "$source_dir/engine/autogen.input" <<EOF
--build=x86_64-unknown-linux-gnu
--with-android-ndk=$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION
--with-android-sdk=$ANDROID_HOME
--with-distro=$ANDROID_DISTRO
--enable-sal-log
--enable-dbgutil
EOF

(
  cd "$source_dir/engine"
  ./autogen.sh
  make -j"${OFFICE_JOBS:-2}"
) 2>&1 | tee "$root/cloud-results/collabora-engine-build.log"

(
  cd "$source_dir"
  ./autogen.sh
  ./configure --enable-androidapp \
    --with-lo-builddir="$source_dir/engine" \
    --enable-debug --with-android-abi="$ANDROID_ABI"
  make -j"${OFFICE_JOBS:-2}"
) 2>&1 | tee "$root/cloud-results/collabora-online-build.log"

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME
(cd "$source_dir/android" && ./gradlew :app:assembleDebug --no-daemon --console=plain) \
  2>&1 | tee "$root/cloud-results/collabora-android-build.log"
