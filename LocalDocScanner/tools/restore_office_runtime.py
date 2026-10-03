"""Restore a pinned upstream native/data payload. This does not install or repack an APK.

The Java bridge and Android resources are separately compiled from corresponding MPL source.
Only the curated manifest's native libraries and engine data are imported, with SHA-256 checks.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, os, urllib.request, zipfile

project = Path(__file__).resolve().parents[1]
module = project / 'office-engine'
manifest = json.loads((module / 'runtime-manifest.json').read_text(encoding='utf-8'))

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024): h.update(block)
    return h.hexdigest()

def target(record):
    relative = PurePosixPath(record['path'])
    if relative.is_absolute() or '..' in relative.parts or not str(relative).startswith(('src/main/assets/', 'src/main/jniLibs/arm64-v8a/')):
        raise ValueError('Unsafe manifest path')
    return module.joinpath(*relative.parts)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, help='Use an existing official APK instead of downloading')
    parser.add_argument('--verify', action='store_true', help='Verify only; never download or modify')
    args = parser.parse_args()
    missing = []
    for record in manifest['files']:
        path = target(record)
        if path.is_file() and path.stat().st_size == record['size'] and sha(path) == record['sha256']: continue
        if path.exists(): raise ValueError(f'Existing payload differs; preserve edits before restoring: {path}')
        missing.append(record)
    if not missing:
        print(f"Office runtime verified: {len(manifest['files'])} files, {manifest['version']}"); return
    if args.verify: raise SystemExit(f'Missing {len(missing)} Office runtime files; run this script without --verify')
    apk = args.apk or project / '.asset-cache/collabora-office-26.04.3.1-arm64.apk'
    if not apk.is_file():
        if args.apk: raise FileNotFoundError(apk)
        apk.parent.mkdir(parents=True, exist_ok=True)
        part = apk.with_suffix('.apk.part')
        print('Downloading verified official runtime (267 MB)...', flush=True)
        with urllib.request.urlopen(manifest['apk_url'], timeout=60) as response, part.open('wb') as out:
            while block := response.read(1024 * 1024): out.write(block)
        if sha(part) != manifest['apk_sha256']: raise ValueError('Official APK checksum mismatch; no payload imported')
        os.replace(part, apk)
    if sha(apk) != manifest['apk_sha256']: raise ValueError('Official APK checksum mismatch')
    with zipfile.ZipFile(apk) as source:
        for record in missing:
            name = record['apk_path']
            if not name.startswith(('assets/', 'lib/arm64-v8a/')) or '/extensions/' in name: raise ValueError('Non-curated payload')
            data = source.read(name)
            if record.get('modified'):
                if name != 'assets/unpack/etc/fonts/fonts.conf': raise ValueError('Unknown payload modification')
                data = data.replace(b'/data/data/com.collabora.libreoffice/fontconfig', b'/data/data/com.localdoc.scanner/fontconfig')
            if len(data) != record['size'] or hashlib.sha256(data).hexdigest() != record['sha256']:
                raise ValueError(f'Payload checksum mismatch: {name}')
            path = target(record)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f'Restored {len(missing)} verified Office runtime files; APK dex and manifest are not imported')

if __name__ == '__main__': main()
