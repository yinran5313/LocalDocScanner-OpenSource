from pathlib import Path
import argparse

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def otsu(gray: np.ndarray) -> np.ndarray:
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = gray.size
    cumulative = np.cumsum(hist)
    cumulative_sum = np.cumsum(hist * np.arange(256))
    global_sum = cumulative_sum[-1]
    denominator = cumulative * (total - cumulative)
    denominator[denominator == 0] = 1
    variance = (global_sum * cumulative - cumulative_sum * total) ** 2 / denominator
    threshold = int(np.argmax(variance[:-1]))
    return np.where(gray >= threshold, 255, 0).astype(np.uint8)


def adaptive(gray: np.ndarray, radius: int = 28, sensitivity: float = 0.22) -> np.ndarray:
    values = gray.astype(np.float64)
    squared = values * values
    integral = np.pad(values, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    squared_integral = np.pad(squared, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    height, width = gray.shape
    ys, xs = np.indices((height, width))
    left = np.maximum(0, xs - radius)
    right = np.minimum(width - 1, xs + radius)
    top = np.maximum(0, ys - radius)
    bottom = np.minimum(height - 1, ys + radius)

    def sums(table: np.ndarray) -> np.ndarray:
        return (
            table[bottom + 1, right + 1]
            - table[top, right + 1]
            - table[bottom + 1, left]
            + table[top, left]
        )

    count = (right - left + 1) * (bottom - top + 1)
    mean = sums(integral) / count
    variance = np.maximum(0.0, sums(squared_integral) / count - mean * mean)
    threshold = mean * (1.0 + sensitivity * (np.sqrt(variance) / 128.0 - 1.0))
    return np.where(values <= threshold, 0, 255).astype(np.uint8)


def label(image: Image.Image, text: str) -> Image.Image:
    strip = 64
    canvas = Image.new("RGB", (image.width, image.height + strip), "white")
    canvas.paste(image.convert("RGB"), (0, strip))
    draw = ImageDraw.Draw(canvas)
    draw.text((18, 18), text, fill="black", font=ImageFont.load_default(size=28))
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare global and local document binarization")
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--crop", type=int, nargs=4, metavar=("LEFT","TOP","RIGHT","BOTTOM"))
    args = parser.parse_args()
    screenshot = Image.open(args.source).convert("RGB")
    document = screenshot.crop(args.crop) if args.crop else screenshot
    gray = np.asarray(document.convert("L"))
    panels = [
        label(document, "Original preview"),
        label(Image.fromarray(otsu(gray), mode="L"), "Old global Otsu"),
        label(Image.fromarray(adaptive(gray), mode="L"), "New local adaptive"),
    ]
    target_height = 980
    resized = []
    for panel in panels:
        width = round(panel.width * target_height / panel.height)
        resized.append(panel.resize((width, target_height), Image.Resampling.LANCZOS))
    output = Image.new("RGB", (sum(p.width for p in resized), target_height), "#dddddd")
    x = 0
    for panel in resized:
        output.paste(panel, (x, 0))
        x += panel.width
    args.output.parent.mkdir(parents=True, exist_ok=True)
    output.save(args.output)
    print(args.output)


if __name__ == "__main__":
    main()
