"""Restore exact build assets from the checksum-locked baseline, without replacing source.

Run from any directory: python LocalDocScanner/tools/restore_binary_assets.py
The public repository retains checksum-locked asset-bundle.zip.NNN parts.
"""
from pathlib import Path, PurePosixPath
from zipfile import ZipFile
import hashlib, json, tempfile

project = Path(__file__).resolve().parents[1]
repository = project.parent
manifest = json.loads((project/'binary-assets.json').read_text(encoding='utf-8'))
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024*1024): h.update(block)
    return h.hexdigest()

def main():
    missing=[]
    for item in manifest['assets']:
        path=repository.joinpath(*PurePosixPath(item['path']).parts)
        if path.exists():
            if digest(path) != item['sha256']: raise SystemExit('Existing asset differs; preserved: '+item['path'])
        else: missing.append(item)
    if not missing:
        print('All locked binary assets are present and verified.'); return
    for part in manifest['parts']:
        path=repository/part['path']
        if not path.is_file() or digest(path)!=part['sha256']: raise SystemExit('Missing or invalid part: '+part['path'])
    # A temporary archive avoids keeping a redundant full ZIP in the source tree.
    with tempfile.TemporaryFile() as archive:
        for part in manifest['parts']:
            with (repository/part['path']).open('rb') as source:
                while block := source.read(1024*1024): archive.write(block)
        archive.seek(0)
        h=hashlib.sha256()
        while block := archive.read(1024*1024): h.update(block)
        if h.hexdigest()!=manifest['archive_sha256']: raise SystemExit('Archive digest mismatch')
        archive.seek(0)
        with ZipFile(archive) as source:
            if source.testzip() is not None: raise SystemExit('Archive CRC mismatch')
            for item in missing:
                relative=PurePosixPath(item['path'])
                if relative.is_absolute() or '..' in relative.parts or relative.parts[0]!='LocalDocScanner': raise ValueError(str(relative))
                data=source.read(item['path'])
                if hashlib.sha256(data).hexdigest()!=item['sha256']: raise SystemExit('Asset digest mismatch: '+item['path'])
                target=repository.joinpath(*relative.parts)
                target.parent.mkdir(parents=True,exist_ok=True)
                with target.open('xb') as out: out.write(data)
    print(f'Restored {len(missing)} verified assets; readable source left intact.')
if __name__=='__main__': main()
