"""Package a local, reproducible source snapshot without caches or signing secrets.

Usage: python tools/package_local_source.py OUTPUT_DIRECTORY
Existing artifacts are never overwritten. No upload or publication is performed.
"""
from pathlib import Path
import hashlib
import json
import os
import sys
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
BLOCKED_DIRS = {
    ".git", ".gradle", ".kotlin", ".idea", "build", ".cxx", ".asset-cache",
    "__pycache__", "cloud-results", ".cloud-tools", "private", "node_modules",
}
BLOCKED_NAMES = {"local.properties", "keystore.properties", "signing.properties", ".env"}
BLOCKED_SUFFIXES = {".jks", ".keystore", ".apk", ".aab", ".part", ".pyc", ".p12", ".pfx"}


def package(destination: Path):
    destination = destination.resolve()
    if destination == PROJECT or PROJECT in destination.parents:
        raise ValueError("Output must be outside the project")
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / "拾页-V5.2.0-完整源码.zip"
    inventory = []
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for folder, directories, names in os.walk(PROJECT):
            directories[:] = sorted(d for d in directories if d.lower() not in BLOCKED_DIRS)
            for name in sorted(names):
                source = Path(folder) / name
                relative = source.relative_to(PROJECT)
                if source.is_symlink() or name.lower() in BLOCKED_NAMES or source.suffix.lower() in BLOCKED_SUFFIXES:
                    continue
                if len(relative.parts) == 1 and source.suffix.lower() in {".log", ".zip"}:
                    continue
                digest = hashlib.sha256()
                count = 0
                info = zipfile.ZipInfo.from_file(source, relative.as_posix())
                info.compress_type = zipfile.ZIP_DEFLATED
                with source.open("rb") as src, archive.open(info, "w", force_zip64=True) as dst:
                    while block := src.read(1024 * 1024):
                        digest.update(block)
                        count += len(block)
                        dst.write(block)
                inventory.append({"path": relative.as_posix(), "bytes": count, "sha256": digest.hexdigest()})
        archive.writestr("SOURCE_INVENTORY.json", json.dumps(inventory, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(target) as archive:
        failure = archive.testzip()
        if failure:
            raise ValueError("Source ZIP CRC failed: " + failure)
    (destination / "源码文件清单.json").write_text(json.dumps(inventory, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"files": len(inventory), "bytes": target.stat().st_size, "archive": str(target)}, ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    package(Path(sys.argv[1]))
