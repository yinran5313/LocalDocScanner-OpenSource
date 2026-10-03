"""Inspect final APK: complete pinned Office payload, JNI entry points and ELF dependency closure."""
from pathlib import Path
import argparse, hashlib, json, re, struct, zipfile

def elf(data):
    if data[:5] != b'\x7fELF\x02': raise ValueError('Expected ELF64')
    h = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, h[6] + i*h[11]) for i in range(h[12])]
    def string(table, offset): return table[offset:table.find(b'\0', offset)].decode('utf-8',errors='replace')
    exports, undefined, needed, alignment = set(), set(), [], []
    for sec in sections:
        if sec[1] == 11:
            linked = sections[sec[6]]
            strings = data[linked[4]:linked[4]+linked[5]]
            for offset in range(sec[4], sec[4]+sec[5], sec[9]):
                name, info, other, index, value, size = struct.unpack_from('<IBBHQQ', data, offset)
                symbol = string(strings, name)
                if index and info >> 4 in (1,2): exports.add(symbol)
                elif not index and info >> 4 == 1: undefined.add(symbol)
        elif sec[1] == 6:
            linked = sections[sec[6]]
            strings = data[linked[4]:linked[4]+linked[5]]
            for offset in range(sec[4], sec[4]+sec[5], 16):
                tag, value = struct.unpack_from('<qQ',data,offset)
                if tag == 1: needed.append(string(strings,value))
    for i in range(h[10]):
        ph = struct.unpack_from('<IIQQQQQQ',data,h[5]+i*h[9])
        if ph[0] == 1: alignment.append(ph[7])
    return {'exports': exports, 'undefined': undefined, 'needed': needed, 'alignment': alignment}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path);p.add_argument('--report',type=Path)
    args=p.parse_args()
    project=Path(__file__).resolve().parents[1]
    manifest=json.loads((project/'office-engine/runtime-manifest.json').read_text(encoding='utf-8'))
    problems=[]; libraries={}
    with zipfile.ZipFile(args.apk) as z:
        names=set(z.namelist())
        for record in manifest['files']:
            apk_path=record['apk_path']
            if apk_path not in names: problems.append(f'Missing {apk_path}');continue
            data=z.read(apk_path)
            if hashlib.sha256(data).hexdigest()!=record['sha256']: problems.append(f'Payload changed: {apk_path}')
        for name in sorted(names):
            if name.startswith('lib/') and name.endswith('.so'):
                if not name.startswith('lib/arm64-v8a/'): problems.append(f'Unexpected ABI: {name}')
                parsed=elf(z.read(name))
                if any(n<16384 for n in parsed['alignment']):problems.append(f'16KB alignment failed: {name}')
                libraries[Path(name).name]=parsed
        if any(n.startswith('assets/unpack/share/extensions/') for n in names):
            problems.append('Unreviewed dictionaries included')
    android_system={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so','libEGL.so','libGLESv2.so','libOpenSLES.so','libjnigraphics.so','libmediandk.so'}
    for name, library in libraries.items():
        for dependency in library['needed']:
            if dependency not in libraries and dependency not in android_system:
                problems.append(f'{name} missing dependency {dependency}')
    bridge=libraries.get('libandroidapp.so',{}).get('exports',set())
    if 'Java_com_localdoc_scanner_cv_BookDewarp_flattenNative' not in libraries.get('libscanner_dewarp.so',{}).get('exports',set()):
        problems.append('Book dewarp JNI entry point missing')
    for source in (project/'office-engine/src/main/java').rglob('*.java'):
        text=source.read_text(encoding='utf-8')
        package=re.search(r'package ([\w.]+);',text).group(1)
        for method in re.findall(r'native\s+\w+(?:\[\])?\s+(\w+)\s*\(',text):
            symbol='Java_'+package.replace('.','_')+'_'+source.stem+'_'+method
            if symbol not in bridge: problems.append(f'JNI entry point missing: {symbol}')
    cxx=libraries.get('libc++_shared.so',{}).get('exports',set())
    all_exports=set().union(*(lib['exports'] for lib in libraries.values()))
    bionic_cpp={'__cxa_atexit','__cxa_finalize','__cxa_thread_atexit_impl'}
    missing_cpp={s for lib in libraries.values() for s in lib['undefined'] if (s.startswith('_Z') or s.startswith('__cxa_')) and s not in all_exports and s not in bionic_cpp}
    if missing_cpp:problems.append(f'Unresolved C++ runtime symbols: {sorted(missing_cpp)}')
    report={'apk':args.apk.name,'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'office_version':manifest['version'],'source_commit':manifest['source_commit'],
        'verified_runtime_files':len(manifest['files']),'native_libraries':{name:{'needed':v['needed'],'min_load_alignment':min(v['alignment'])} for name,v in libraries.items()},
        'problems':problems,'runtime_execution_verified':False}
    if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({'runtime_files':len(manifest['files']),'native_libraries':len(libraries),'problems':problems}))
    if problems:raise SystemExit(1)

if __name__=='__main__':main()
