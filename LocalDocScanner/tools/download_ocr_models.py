"""Download the exact official PP-OCRv6 ONNX assets used by the Android build."""

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1] / "ppocr-sdk" / "src" / "main" / "assets" / "models"
BASE = "https://huggingface.co/PaddlePaddle/{repo}/resolve/main/{name}?download=true"
FILES = {
    "tiny/det/inference.onnx": ("PP-OCRv6_tiny_det_onnx", "inference.onnx"),
    "tiny/rec/inference.onnx": ("PP-OCRv6_tiny_rec_onnx", "inference.onnx"),
    "tiny/rec/inference.yml": ("PP-OCRv6_tiny_rec_onnx", "inference.yml"),
    "medium/det/inference.onnx": ("PP-OCRv6_medium_det_onnx", "inference.onnx"),
    "medium/rec/inference.onnx": ("PP-OCRv6_medium_rec_onnx", "inference.onnx"),
    "medium/rec/inference.yml": ("PP-OCRv6_medium_rec_onnx", "inference.yml"),
}


def download(relative: str, source: tuple[str, str]) -> tuple[str, int]:
    target = ROOT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 1024:
        return relative, target.stat().st_size
    repo, name = source
    temporary = target.with_suffix(target.suffix + ".part")
    with urlopen(BASE.format(repo=repo, name=name), timeout=120) as response, temporary.open("wb") as out:
        while block := response.read(1024 * 1024):
            out.write(block)
    temporary.replace(target)
    return relative, target.stat().st_size


def main() -> None:
    with ThreadPoolExecutor(max_workers=4) as pool:
        for relative, size in pool.map(lambda item: download(*item), FILES.items()):
            print(f"{relative}\t{size}")


if __name__ == "__main__":
    main()
