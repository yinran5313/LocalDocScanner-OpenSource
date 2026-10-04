"""Restore the pinned OFL UI font; preserve unexpected local changes."""
from pathlib import Path
import hashlib,json,urllib.request
p=Path(__file__).resolve().parents[1]
m=json.loads((p/'ui-font.json').read_text(encoding='utf-8'))
f=p/m['path']
if f.exists():
    if hashlib.sha256(f.read_bytes()).hexdigest()!=m['sha256']:raise SystemExit('Existing UI font differs; preserved.')
else:
    data=urllib.request.urlopen(m['url'],timeout=120).read()
    if hashlib.sha256(data).hexdigest()!=m['sha256']:raise SystemExit('UI font checksum mismatch')
    f.parent.mkdir(parents=True,exist_ok=True)
    with f.open('xb') as out:out.write(data)
print('Pinned UI font verified.')
