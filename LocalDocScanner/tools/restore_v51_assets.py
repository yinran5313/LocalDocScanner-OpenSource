"""Restore pinned KaTeX fonts and the public SentencePiece test fixture."""
import hashlib,json,os,zipfile
from pathlib import Path
from urllib.request import urlopen
ROOT=Path(__file__).resolve().parents[1]
def fetch(url,p,sha,size):
 if p.is_file() and p.stat().st_size==size and hashlib.sha256(p.read_bytes()).hexdigest()==sha:return
 p.parent.mkdir(parents=True,exist_ok=True)
 with urlopen(url,timeout=120) as response:data=response.read(size+1)
 if len(data)!=size or hashlib.sha256(data).hexdigest()!=sha:raise ValueError('asset size/SHA mismatch: '+p.name)
 staged=p.with_suffix(p.suffix+'.part');staged.write_bytes(data);os.replace(staged,p)
def main():
 bundle=ROOT/'v51-assets.zip'
 if bundle.is_file():
  if hashlib.sha256(bundle.read_bytes()).hexdigest()!='50344cb7daf56d224b54789bec615737ecd09d613d5b161bceda6934dcfed86d':raise ValueError('V5.1 asset archive SHA mismatch')
  with zipfile.ZipFile(bundle) as archive:
   for info in archive.infolist():
    relative=Path(info.filename)
    if relative.is_absolute() or '..' in relative.parts:raise ValueError('unsafe asset path')
    target=(ROOT/relative).resolve()
    if not target.is_relative_to(ROOT.resolve()):raise ValueError('unsafe asset path')
    target.parent.mkdir(parents=True,exist_ok=True);payload=archive.read(info)
    if target.exists() and target.read_bytes()!=payload:raise ValueError('local asset differs; preserve local changes')
    target.write_bytes(payload)
  print('V5.1 bundled assets verified');return
 for r in json.loads((ROOT/'katex-fonts.json').read_text('utf8')):
  fetch('https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/fonts/'+r['path'],ROOT/'app/src/main/assets/katex/fonts'/r['path'],r['sha256'],r['bytes'])
 catalog=json.loads((ROOT/'app/src/main/assets/model_catalog.json').read_text('utf8'))
 source=next(f for p in catalog if p['id']=='translate-zh-en' for f in p['files'] if f['name']=='source.spm')
 fetch(source['urls'][0],ROOT/'app/src/androidTest/assets/spm-fixture.spm',source['sha256'],source['bytes'])
 print('V5.1 assets verified')
if __name__=='__main__':main()
