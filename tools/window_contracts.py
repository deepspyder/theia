"""Read the exact binding-origin contract owned by the Android dispatcher."""
import re
from pathlib import Path


CONTRACT = Path(__file__).resolve().parents[1] / "app/src/main/cpp/windows/window_plan.hpp"
ROW = re.compile(
    r'\{\s*"(spk_\w+)"\s*,\s*Kind::(\w+)\s*,\s*\{([\d,\s]*)\}'
    r'\s*(?:,\s*(0x[0-9a-fA-F]+|\d+))?\s*\}'
)


def relocation_contracts(path: Path = CONTRACT) -> dict[str, int]:
    source = path.read_text(encoding="utf-8")
    rows = list(ROW.finditer(source))
    if len(rows) != len(re.findall(r'\{\s*"spk_', source)) or not rows:
        raise ValueError("Unreviewed Android window rule syntax")
    result = {}
    for row in rows:
        name, kind, strides, extra = row.groups()
        if name in result:
            raise ValueError(f"Duplicate Android window rule: {name}")
        values = [int(value.strip()) for value in strides.split(",") if value.strip()]
        mask = int(extra, 0) if extra else 0
        if len(values) > 16 or mask >= 1 << 16:
            raise ValueError(f"Invalid Android binding mask: {name}")
        if kind not in {"Point", "Opaque"} and extra is None:
            raise ValueError(f"Missing reviewed non-pointwise windows: {name}")
        for index, stride in enumerate(values):
            if stride:
                mask |= 1 << index
        result[name] = mask
    return result
