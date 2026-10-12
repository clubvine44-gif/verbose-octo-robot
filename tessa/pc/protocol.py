"""Validated control protocol for the Windows TESSA mobile bridge."""
from __future__ import annotations

SUPPORTED_KEYS = {
    "ENTER": ("enter",),
    "SPACE": ("space",),
    "CTRL+A": ("ctrl", "a"),
    "CTRL+C": ("ctrl", "c"),
    "CTRL+V": ("ctrl", "v"),
    "ESC": ("esc",),
}

def key_sequence(key: str) -> tuple[str, ...]:
    if not isinstance(key, str) or key not in SUPPORTED_KEYS:
        raise ValueError("unsupported_hotkey")
    return SUPPORTED_KEYS[key]

def normalized_point(x: object, y: object, left: int, top: int, width: int, height: int) -> tuple[int, int]:
    if isinstance(x, bool) or isinstance(y, bool):
        raise ValueError("invalid_coordinates")
    try:
        nx, ny = float(x), float(y)
    except (ValueError, TypeError) as exc:
        raise ValueError("invalid_coordinates") from exc
    if not (0 <= nx <= 1 and 0 <= ny <= 1):
        raise ValueError("out_of_bounds")
    if width <= 0 or height <= 0:
        raise ValueError("invalid_monitor")
    return left + min(width - 1, round(nx * (width - 1))), top + min(height - 1, round(ny * (height - 1)))
