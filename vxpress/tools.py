"""Locating the external programs VXpress drives: yt-dlp, ffmpeg, ffprobe."""

from __future__ import annotations

import os
import platform
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from .process import ToolMissing, capture

# Environment overrides, for binaries that are not on PATH.
ENV_OVERRIDES = {
    "yt-dlp": "VXPRESS_YTDLP",
    "ffmpeg": "VXPRESS_FFMPEG",
    "ffprobe": "VXPRESS_FFPROBE",
}

INSTALL_HINTS = {
    "yt-dlp": {
        "windows": "winget install yt-dlp.yt-dlp   (or: py -m pip install -U yt-dlp)",
        "darwin": "brew install yt-dlp   (or: python3 -m pip install -U yt-dlp)",
        "linux": "python3 -m pip install -U yt-dlp   (distro packages are often far behind)",
    },
    "ffmpeg": {
        "windows": "winget install Gyan.FFmpeg",
        "darwin": "brew install ffmpeg",
        "linux": "sudo apt install ffmpeg   (or your distro's equivalent)",
    },
}
INSTALL_HINTS["ffprobe"] = INSTALL_HINTS["ffmpeg"]


@dataclass
class Tool:
    name: str
    argv: tuple[str, ...] = ()
    version: str = ""
    source: str = "missing"

    @property
    def found(self) -> bool:
        return bool(self.argv)

    @property
    def hint(self) -> str:
        system = platform.system().lower()
        hints = INSTALL_HINTS.get(self.name, {})
        return hints.get(system) or hints.get("linux", "")

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "found": self.found,
            "command": " ".join(self.argv),
            "version": self.version,
            "source": self.source,
            "hint": "" if self.found else self.hint,
        }


def _version_of(argv: Sequence[str], name: str) -> str:
    """First line of ``--version`` output, trimmed to something printable."""
    try:
        result = capture([*argv, "--version"], timeout=30)
    except ToolMissing:
        return ""
    if not result.ok and not result.output.strip():
        return ""
    first = next((line.strip() for line in result.output.splitlines() if line.strip()), "")
    if name in ("ffmpeg", "ffprobe") and first.startswith(name):
        # "ffmpeg version 6.1.1-3ubuntu5 Copyright (c) ..." -> "6.1.1-3ubuntu5"
        parts = first.split()
        return parts[2] if len(parts) > 2 else first
    return first


def find_tool(name: str, extra_dirs: Sequence[str] = ()) -> Tool:
    """Find one program: env override, then PATH, then yt-dlp as a module."""
    override = os.environ.get(ENV_OVERRIDES.get(name, ""), "").strip()
    if override:
        path = Path(override).expanduser()
        if path.is_file():
            argv = (str(path),)
            return Tool(name, argv, _version_of(argv, name), f"${ENV_OVERRIDES[name]}")

    for directory in extra_dirs:
        candidate = shutil.which(name, path=str(directory))
        if candidate:
            argv = (candidate,)
            return Tool(name, argv, _version_of(argv, name), "bundled")

    located = shutil.which(name)
    if located:
        argv = (located,)
        return Tool(name, argv, _version_of(argv, name), "PATH")

    if name == "yt-dlp":
        # Installed with pip but no console script on PATH (common on Windows).
        argv = (sys.executable, "-m", "yt_dlp")
        probe = capture([*argv, "--version"], timeout=30)
        if probe.ok:
            return Tool(name, argv, probe.last_line.strip(), "python -m yt_dlp")

    return Tool(name)


@dataclass
class Toolchain:
    ytdlp: Tool
    ffmpeg: Tool
    ffprobe: Tool

    @classmethod
    def detect(cls, extra_dirs: Sequence[str] = ()) -> "Toolchain":
        return cls(
            ytdlp=find_tool("yt-dlp", extra_dirs),
            ffmpeg=find_tool("ffmpeg", extra_dirs),
            ffprobe=find_tool("ffprobe", extra_dirs),
        )

    @property
    def ffmpeg_dir(self) -> str:
        """Directory holding ffmpeg, to hand to yt-dlp's --ffmpeg-location."""
        if self.ffmpeg.found:
            return str(Path(self.ffmpeg.argv[0]).parent)
        return ""

    def require(self, *names: str) -> None:
        """Raise with an install hint when a needed program is missing."""
        missing = [getattr(self, _attr(name)) for name in names]
        absent = [tool for tool in missing if not tool.found]
        if not absent:
            return
        lines = []
        for tool in absent:
            lines.append(f"{tool.name} is not installed or not on PATH")
            if tool.hint:
                lines.append(f"    install it with: {tool.hint}")
        lines.append(
            "    or point VXpress at it directly: "
            + ", ".join(f"{ENV_OVERRIDES[t.name]}=<path>" for t in absent)
        )
        raise ToolMissing("\n".join(lines))

    def to_dict(self) -> dict:
        return {
            "yt-dlp": self.ytdlp.to_dict(),
            "ffmpeg": self.ffmpeg.to_dict(),
            "ffprobe": self.ffprobe.to_dict(),
            "ready_for_download": self.ytdlp.found,
            "ready_for_mp3": self.ffmpeg.found,
        }


def _attr(name: str) -> str:
    return {"yt-dlp": "ytdlp", "ffmpeg": "ffmpeg", "ffprobe": "ffprobe"}[name]
