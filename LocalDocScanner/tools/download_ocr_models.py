"""Restore only pinned medium OCR assets; verify length and SHA before replacement."""
import hashlib,json,os,shutil
from pathlib import Path
from urllib.request import urlopen
PROJECT=Path(__file__).resolve().parents[1]
ROOT=PROJECT/'ppocr-sdk/src/main/assets/models'
def valid(p,r):
 return p.is_file() and p.stat().st_size==r['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==r['sha256']
def main():
 for r in json.loads((PROJECT/'ocr-medium-sources.json').read_text('utf-8')):
  target=ROOT/r['path'];target.parent.mkdir(parents=True,exist_ok=True)
  if valid(target,r):continue
  staged=target.with_suffix('.part');errors=[]
  for url in r['urls']:
   try:
    with urlopen(url,timeout=120) as source,staged.open('wb') as out:
     while data:=source.read(1024*1024):out.write(data)
    if not valid(staged,r):raise ValueError('size/SHA mismatch')
    os.replace(staged,target);break
   except Exception as e:errors.append(str(e));staged.unlink(missing_ok=True)
  else:raise RuntimeError(r['path']+': '+str(errors))
  print(r['path'],'verified')
 for name in ('tiny','small'):
  legacy=ROOT/name
  if legacy.exists():
   if not legacy.resolve().is_relative_to(ROOT.resolve()):raise RuntimeError('unexpected model directory')
   shutil.rmtree(legacy)
if __name__=='__main__':main()
