#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
out="$root/cloud-results"
mkdir -p "$out"
exec > >(tee "$out/environment.txt") 2>&1
printf 'Environment probe: %s\n' "$(date -u +%FT%TZ)"
printf 'Kernel and architecture: '
uname -sm
if [[ $(uname -s) != Linux ]]; then
  printf 'BLOCKED: native Office compilation requires Linux.\n'
  exit 2
fi
[[ ! -r /etc/os-release ]] || cat /etc/os-release
printf '\nCPU and memory\n'
getconf _NPROCESSORS_ONLN
free -h || true
for limit in /sys/fs/cgroup/memory.max /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory/memory.limit_in_bytes; do
  if [[ -r $limit ]]; then printf '%s: ' "$limit"; cat "$limit"; fi
done
printf '\nWorkspace space\n'
df -h "$root"
printf '\nTools\n'
for tool in git java javac gcc g++ clang make cmake autoconf automake libtool pkg-config python3 node npm unzip curl; do
  if command -v "$tool" >/dev/null 2>&1; then printf '%s: %s\n' "$tool" "$(command -v "$tool")";
  else printf '%s: missing\n' "$tool"; fi
done
if command -v java >/dev/null 2>&1; then java -version; fi
if [[ ${1:-} == --network ]]; then
  printf '\nDependency network probes (no account data)\n'
  for url in https://dl.google.com/android/repository/repository2-1.xml https://services.gradle.org/distributions/gradle-8.10.2-bin.zip.sha256 https://repo.maven.apache.org/maven2/ https://gerrit.collaboraoffice.com/online; do
    printf '%s\n' "$url"
    curl --head --location --silent --show-error --connect-timeout 10 --max-time 15 "$url" | head -n 5 || true
  done
fi
printf '\nProbe completed. A probe is not a successful native build.\n'

