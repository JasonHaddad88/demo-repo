"""What the user handed us: a web link, or a file already on this machine."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

ALLOWED_SCHEMES = ("http", "https")

# Characters no mainstream filesystem accepts, plus the ones Windows reserves.
_UNSAFE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_WINDOWS_RESERVED = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}


class BadSource(ValueError):
    """The input is neither a usable link nor a readable local file."""


@dataclass(frozen=True)
class Source:
    kind: str  # "url" or "file"
    value: str
    label: str

    @property
    def is_url(self) -> bool:
        return self.kind == "url"

    def to_dict(self) -> dict:
        return {"kind": self.kind, "value": self.value, "label": self.label}


def classify(target: str) -> Source:
    """Decide whether ``target`` is a link or a local path, and validate it."""
    text = (target or "").strip().strip('"').strip("'")
    if not text:
        raise BadSource("give me a video link or the path to a local file")

    parsed = urlparse(text)
    if parsed.scheme and parsed.scheme.lower() not in ("file",) and len(parsed.scheme) > 1:
        scheme = parsed.scheme.lower()
        if scheme not in ALLOWED_SCHEMES:
            raise BadSource(
                f"{scheme}:// links are not supported — give an http(s) link "
                "or a path to a file on this computer"
            )
        if not parsed.netloc:
            raise BadSource(f"that link has no host in it: {text!r}")
        return Source("url", text, parsed.netloc + parsed.path[:60])

    path = Path(os.path.expandvars(text)).expanduser()
    if path.is_dir():
        raise BadSource(f"{path} is a folder, not a video file")
    if not path.exists():
        raise BadSource(
            f"no such file: {path}  (if you meant a link, include https://)"
        )
    if not path.is_file():
        raise BadSource(f"{path} is not a regular file")
    return Source("file", str(path.resolve()), path.name)


def safe_filename(name: str, fallback: str = "vxpress-output", max_length: int = 150) -> str:
    """Turn any title into something every filesystem will accept."""
    cleaned = _UNSAFE.sub("_", (name or "").strip())
    cleaned = cleaned.strip(". ")  # Windows rejects trailing dots and spaces
    cleaned = re.sub(r"\s+", " ", cleaned)
    if cleaned.split(".")[0].upper() in _WINDOWS_RESERVED:
        cleaned = f"_{cleaned}"
    if len(cleaned) > max_length:
        cleaned = cleaned[:max_length].rstrip(". ")
    return cleaned or fallback


def unique_path(path: Path) -> Path:
    """``song.mp3`` -> ``song (2).mp3`` when the first one already exists."""
    if not path.exists():
        return path
    stem, suffix, parent = path.stem, path.suffix, path.parent
    for index in range(2, 1000):
        candidate = parent / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
    raise BadSource(f"too many files named like {path.name}")


def default_output_dir(kind: str) -> Path:
    """Where finished files land unless --out says otherwise."""
    env = os.environ.get("VXPRESS_OUTPUT_DIR", "").strip()
    base = Path(env).expanduser() if env else Path.home() / "VXpress"
    return base / ("audio" if kind == "mp3" else "video")


def ensure_dir(path: Path) -> Path:
    path = Path(path).expanduser()
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise BadSource(f"cannot create output folder {path}: {exc}") from exc
    if not os.access(path, os.W_OK):
        raise BadSource(f"output folder is not writable: {path}")
    return path.resolve()


def parse_timestamp(text: str | float | int | None) -> float | None:
    """Accept ``90``, ``1:30`` or ``00:01:30.5`` and return seconds."""
    if text is None or text == "":
        return None
    if isinstance(text, (int, float)):
        seconds = float(text)
    else:
        parts = str(text).strip().split(":")
        if len(parts) > 3 or not all(parts):
            raise BadSource(f"not a timestamp: {text!r} (use 90, 1:30 or 0:01:30)")
        try:
            seconds = 0.0
            for part in parts:
                seconds = seconds * 60 + float(part)
        except ValueError as exc:
            raise BadSource(f"not a timestamp: {text!r}") from exc
    if seconds < 0:
        raise BadSource(f"timestamp cannot be negative: {text!r}")
    return seconds


def format_timestamp(seconds: float | None) -> str:
    """Seconds as ``H:MM:SS`` (or ``M:SS`` under an hour)."""
    if seconds is None:
        return "?"
    seconds = max(0, int(round(seconds)))
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"
