"""Restore Apache-2.0 SentencePiece v0.2.1 source without replacing local edits."""
import hashlib,io,tarfile
from pathlib import Path
from urllib.request import urlopen
ROOT=Path(__file__).resolve().parents[1]/'tokenizer-native/src/main/cpp/vendor'
SHA='c1a59e9259c9653ad0ade653dadff074cd31f0a6ff2a11316f67bee4189a8f1b'
def main():
 if (ROOT/'CMakeLists.txt').is_file() and (ROOT/'src/sentencepiece_processor.h').is_file():
  print('Existing SentencePiece source preserved');return
 with urlopen('https://codeload.github.com/google/sentencepiece/tar.gz/refs/tags/v0.2.1',timeout=120) as response:data=response.read(32*1024*1024+1)
 if hashlib.sha256(data).hexdigest()!=SHA:raise ValueError('source archive SHA mismatch')
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
  for member in archive.getmembers():
   parts=Path(member.name).parts
   if not parts or parts[0]!='sentencepiece-0.2.1' or any(p in ('.','..') for p in parts):raise ValueError('unexpected archive path')
   if len(parts)<2 or member.issym() or member.islnk():continue
   target=ROOT.joinpath(*parts[1:])
   if not target.resolve().is_relative_to(ROOT.resolve()):raise ValueError('unsafe archive path')
   if member.isdir():target.mkdir(parents=True,exist_ok=True)
   elif member.isfile():
    target.parent.mkdir(parents=True,exist_ok=True)
    with archive.extractfile(member) as source:payload=source.read()
    if target.exists() and target.read_bytes()!=payload:raise ValueError('existing local source differs')
    target.write_bytes(payload)
 print('SentencePiece 0.2.1 source restored')
if __name__=='__main__':main()
