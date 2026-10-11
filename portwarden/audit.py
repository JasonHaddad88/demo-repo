"""Append-only log of every change portwarden makes."""

from __future__ import annotations

import getpass
import json
import os
import time
from pathlib import Path


def default_log_path() -> Path:
    env = os.environ.get("PORTWARDEN_AUDIT_LOG")
    if env:
        return Path(env).expanduser()
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        return base / "portwarden" / "audit.log"
    base = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return base / "portwarden" / "audit.log"


def _actor() -> str:
    try:
        return getpass.getuser()
    except Exception:  # noqa: BLE001 - no controlling terminal / no passwd entry
        return os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"


def record(entry: dict, path: Path | None = None) -> Path:
    """Append one JSON line. Never raises; auditing must not break an action."""
    path = Path(path) if path else default_log_path()
    line = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "actor": _actor(),
        "pid": os.getpid(),
        **entry,
    }
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(line, default=str) + "\n")
    except OSError:
        pass
    return path


def tail(limit: int = 50, path: Path | None = None) -> list[dict]:
    """Most recent entries, newest first."""
    path = Path(path) if path else default_log_path()
    try:
        with path.open(encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()[-max(1, limit) :]
    except OSError:
        return []
    entries = []
    for line in reversed(lines):
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return entries
