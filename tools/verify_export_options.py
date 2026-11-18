"""Read actual R02 exports independently of Android's decoders (Pillow + NumPy).

TIFF samples are read as unsigned 16-bit from uncompressed strips, never through
Pillow's RGB8 conversion. References come from the test's independent 2D area
integration over the original rendered raster, not the production row resizer.
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image


def read_pixels(path: Path) -> np.ndarray:
    if path.suffix.lower() not in {".tif", ".tiff"}:
        with Image.open(path) as image:
            return np.array(image.convert("RGB"))
    data = path.read_bytes()
    order = {b"II": "<", b"MM": ">"}[data[:2]]
    if struct.unpack_from(order + "H", data, 2)[0] != 42:
        raise ValueError("Only classic TIFF fixtures are expected")
    offset = struct.unpack_from(order + "I", data, 4)[0]
    count = struct.unpack_from(order + "H", data, offset)[0]
    tags: dict[int, tuple[int, ...]] = {}
    for entry in range(count):
        position = offset + 2 + entry * 12
        tag, kind, length, value = struct.unpack_from(order + "HHII", data, position)
        if kind not in {3, 4}:
            continue
        unit, code = (2, "H") if kind == 3 else (4, "I")
        start = position + 8 if length * unit <= 4 else value
        tags[tag] = struct.unpack_from(order + str(length) + code, data, start)
    if tags.get(259, (1,)) != (1,) or tags[258] != (16, 16, 16) or tags[277] != (3,):
        raise ValueError("Expected uncompressed RGB16 TIFF")
    if tags.get(284, (1,)) != (1,):
        raise ValueError("Expected chunky TIFF")
    offsets, lengths = tags[273], tags[279]
    if len(offsets) != len(lengths):
        raise ValueError("Strip directory length differs")
    raw = b"".join(data[start:start + size] for start, size in zip(offsets, lengths))
    width, height = tags[256][0], tags[257][0]
    if len(raw) != width * height * 6:
        raise ValueError("Truncated TIFF samples")
    return np.frombuffer(raw, dtype=order + "u2").reshape(height, width, 3)


def verify(directory: Path) -> dict:
    rows = json.loads((directory / "results.json").read_text(encoding="utf-8"))
    if len(rows) != 57 or len({row["file"] for row in rows}) != len(rows):
        raise ValueError("Expected all 57 distinct input/size/container cases")
    results = []
    for row in rows:
        actual = read_pixels(directory / row["file"])
        reference = read_pixels(directory / row["reference"])
        expected_shape = (row["height"], row["width"], 3)
        if actual.shape != expected_shape or reference.shape != expected_shape:
            raise ValueError(f"{row['file']}: dimensions differ: {actual.shape}, {reference.shape}, {expected_shape}")
        difference = np.abs(actual.astype(np.int32) - reference.astype(np.int32))
        maximum = int(difference.max())
        results.append({**row, "max_channel_difference": maximum,
                        "mean_channel_difference": float(difference.mean())})
    passed = all(row["max_channel_difference"] <= 1 for row in results)
    summary = {"passed": passed, "files": len(results), "tolerance": "1 code value; independent floating-point integration may round half-way samples differently",
               "results": results}
    (directory / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if not passed:
        failures = [(row["file"], row["max_channel_difference"]) for row in results if row["max_channel_difference"] > 1]
        raise ValueError(f"Full decoded pixel comparison failed: {failures}")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    summary = verify(args.directory)
    print(f"PASS: {summary['files']} independently decoded full images; maximum channel difference "
          f"{max(row['max_channel_difference'] for row in summary['results'])}")


if __name__ == "__main__":
    main()
