"""Package a checked development build with complete local sources; keep private audit data out."""
from pathlib import Path
import argparse, hashlib, json, shutil, zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRS = {'.gradle', '.kotlin', '.git', '.idea', 'build', '.cxx', '__pycache__', '.asset-cache', '.cloud-tools', 'cloud-results'}
EXCLUDED_FILES = {'local.properties', 'keystore.properties', 'PUBLIC_README.md', 'adb_audit.py', 'backup_phone_v501.py', 'create_v501_fixtures.py', 'prepare_v5_docs.py', 'stage_v5_public.py', 'package_v5.py'}

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()

def include(path):
    rel = path.relative_to(ROOT)
    if any(part in EXCLUDED_DIRS for part in rel.parts): return False
    if path.name in EXCLUDED_FILES or path.suffix in {'.apk', '.aab', '.jks', '.keystore', '.part', '.pyc', '.log'}: return False
    return not rel.as_posix().startswith('tools/history/v5_')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--public-commit', required=True)
    parser.add_argument('--test-results', type=Path, required=True)
    args = parser.parse_args()
    metadata = json.loads((ROOT / 'app/build/outputs/apk/debug/output-metadata.json').read_text('utf-8'))
    assert metadata['applicationId'] == 'com.localdoc.scanner', 'Cannot deliver an isolated audit package'
    item = metadata['elements'][0]
    results = json.loads(args.test_results.read_text('utf-8'))
    assert results['version'] == item['versionName'] and results['failures'] == 0
    out = ROOT.parent / '交付' / f"V{item['versionName']}"
    out.mkdir(parents=True, exist_ok=True)
    apk = ROOT.parent / f"拾页-V{item['versionName']}-arm64-debug.apk"
    shutil.copy2(ROOT / 'app/build/outputs/apk/debug' / item['outputFile'], apk)
    archive = out / f"拾页-V{item['versionName']}-source.zip"
    records = []
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(ROOT.rglob('*')):
            if not path.is_file() or not include(path): continue
            rel = 'LocalDocScanner/' + path.relative_to(ROOT).as_posix()
            z.write(path, rel)
            records.append({'path': rel, 'bytes': path.stat().st_size, 'sha256': sha(path)})
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and len(z.namelist()) == len(records)
    (out / 'source-inventory.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    manifest = {'version': item['versionName'], 'versionCode': item['versionCode'], 'applicationId': metadata['applicationId'],
                'apk': {'path': str(apk), 'bytes': apk.stat().st_size, 'sha256': sha(apk)},
                'source': {'path': str(archive), 'bytes': archive.stat().st_size, 'sha256': sha(archive), 'files': len(records)},
                'public_commit': args.public_commit, 'verification': results}
    (out / 'delivery-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=True))

if __name__ == '__main__': main()
