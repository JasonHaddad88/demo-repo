"""The folder you are looking through, the photo you are on, and moving it.

No GUI in here, so all of it can be tested without a screen.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import random
import re
import shutil
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

BASE_EXTENSIONS = {
    ".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".gif", ".bmp", ".dib",
    ".webp", ".tif", ".tiff", ".ico",
}
# Pillow reads these only with the pillow-heif plugin installed.
HEIF_EXTENSIONS = {".heic", ".heif", ".avif"}
MAX_TARGETS = 9


def heif_supported() -> bool:
    return importlib.util.find_spec("pillow_heif") is not None


def image_extensions() -> set[str]:
    return BASE_EXTENSIONS | HEIF_EXTENSIONS if heif_supported() else set(BASE_EXTENSIONS)


def natural_key(name: str) -> list:
    """Sort "IMG_2" before "IMG_10", the way Explorer does."""
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", name)]


def is_hidden(path: Path) -> bool:
    if path.name.startswith("."):
        return True
    if sys.platform == "win32":
        try:
            attributes = os.stat(path).st_file_attributes  # type: ignore[attr-defined]
        except (OSError, AttributeError):
            return False
        return bool(attributes & 0x2)  # FILE_ATTRIBUTE_HIDDEN
    return False


def scan(folder: Path, extensions: set[str] | None = None) -> list[Path]:
    """The photos directly inside [folder], in natural name order."""
    wanted = extensions if extensions is not None else image_extensions()
    try:
        entries = list(folder.iterdir())
    except OSError as exc:
        raise MoveError(f"Can't read {folder}: {exc.strerror or exc}") from exc
    photos = [
        entry for entry in entries
        if entry.suffix.lower() in wanted and entry.is_file() and not is_hidden(entry)
    ]
    return sorted(photos, key=lambda p: natural_key(p.name))


def shuffle_key(seed: int, name: str) -> int:
    """A photo's place in a shuffle: from its name and the seed alone, so
    moving photos out never reshuffles the rest, and the same seed deals the
    same order again next time."""
    digest = hashlib.blake2b(f"{seed}\0{name}".encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big")


def unique_destination(folder: Path, name: str) -> Path:
    """`photo.jpg`, or `photo (2).jpg` if that is taken — never overwrite."""
    candidate = folder / name
    if not candidate.exists():
        return candidate
    stem, suffix = Path(name).stem, Path(name).suffix
    number = 2
    while True:
        candidate = folder / f"{stem} ({number}){suffix}"
        if not candidate.exists():
            return candidate
        number += 1


def same_folder(a: Path, b: Path) -> bool:
    try:
        return a.samefile(b)
    except OSError:
        return os.path.normcase(os.path.abspath(a)) == os.path.normcase(os.path.abspath(b))


class MoveError(Exception):
    """Something a person can fix: the message is shown as is."""


@dataclass(frozen=True)
class Move:
    """One move, remembered so it can be undone."""

    source: Path
    destination: Path
    index: int

    @property
    def renamed(self) -> bool:
        return self.source.name != self.destination.name


class Gallery:
    """A folder of photos and where you are in it."""

    def __init__(self) -> None:
        self.folder: Path | None = None
        self.images: list[Path] = []
        self.index = 0
        self.history: list[Move] = []
        # None for name order; otherwise the seed of the shuffle in force.
        # It carries over to the next folder opened.
        self.shuffle_seed: int | None = None

    def _key(self, path: Path):
        if self.shuffle_seed is None:
            return natural_key(path.name)
        return (shuffle_key(self.shuffle_seed, path.name), natural_key(path.name))

    # -- order --------------------------------------------------------------

    def shuffle(self, seed: int | None = None) -> int:
        """Shows the photos in a new random order, from the top of it. Files
        are never renamed: only the viewing order changes."""
        self.shuffle_seed = seed if seed is not None else random.getrandbits(63)
        self.images.sort(key=self._key)
        self.index = 0
        return self.shuffle_seed

    def sort_by_name(self) -> None:
        """Back to name order, staying on the photo you're looking at."""
        current = self.current
        self.shuffle_seed = None
        self.images.sort(key=self._key)
        if current is not None:
            self.index = self.images.index(current)

    # -- opening ------------------------------------------------------------

    def open(self, path: Path) -> None:
        """Open a folder, or a photo — then its folder, starting at that photo."""
        path = Path(path).expanduser()
        if path.is_file():
            folder, start = path.parent, path
        elif path.is_dir():
            folder, start = path, None
        else:
            raise MoveError(f"{path} doesn't exist")
        images = sorted(scan(folder), key=self._key)
        self.folder = folder
        self.images = images
        self.index = 0
        if start is not None:
            for position, image in enumerate(images):
                if same_folder(image, start):
                    self.index = position
                    break

    def reload(self) -> None:
        """Re-read the folder, staying on the same photo when it is still there."""
        if self.folder is None:
            return
        current = self.current
        self.images = sorted(scan(self.folder), key=self._key)
        if current is not None and current in self.images:
            self.index = self.images.index(current)
        else:
            self.index = min(self.index, max(len(self.images) - 1, 0))

    # -- where we are -------------------------------------------------------

    @property
    def current(self) -> Path | None:
        if 0 <= self.index < len(self.images):
            return self.images[self.index]
        return None

    def neighbour(self, offset: int) -> Path | None:
        position = self.index + offset
        if 0 <= position < len(self.images):
            return self.images[position]
        return None

    def go(self, delta: int) -> bool:
        """Step forward or back; False at either end."""
        if not self.images:
            return False
        target = max(0, min(len(self.images) - 1, self.index + delta))
        changed = target != self.index
        self.index = target
        return changed

    def go_to(self, position: int) -> None:
        if self.images:
            self.index = max(0, min(len(self.images) - 1, position))

    # -- moving -------------------------------------------------------------

    def move_current(self, target: Path) -> Move:
        """Move the photo on screen into [target]; the next photo takes its place."""
        current = self.current
        if current is None:
            raise MoveError("There's no photo to move")
        target = Path(target)
        if not target.is_dir():
            raise MoveError(f"The folder {target} doesn't exist any more — pick it again")
        if same_folder(current.parent, target):
            raise MoveError(f"{current.name} is already in {target.name}")

        destination = unique_destination(target, current.name)
        try:
            # shutil.move renames on the same drive and copies across drives.
            shutil.move(str(current), str(destination))
        except PermissionError as exc:
            raise MoveError(
                f"Couldn't move {current.name}: it's open in another program, or the folder is read-only"
            ) from exc
        except OSError as exc:
            raise MoveError(f"Couldn't move {current.name}: {exc.strerror or exc}") from exc

        record = Move(current, destination, self.index)
        self.history.append(record)
        del self.images[self.index]
        if self.index >= len(self.images):
            self.index = max(len(self.images) - 1, 0)
        return record

    def undo(self) -> Move | None:
        """Put the last moved photo back where it was, and show it again."""
        if not self.history:
            return None
        last = self.history[-1]
        if not last.destination.exists():
            self.history.pop()
            raise MoveError(f"Can't undo: {last.destination.name} is no longer in {last.destination.parent.name}")
        if not last.source.parent.is_dir():
            raise MoveError(f"Can't undo: the folder {last.source.parent} is gone")
        back = last.source if not last.source.exists() else unique_destination(last.source.parent, last.source.name)
        try:
            shutil.move(str(last.destination), str(back))
        except OSError as exc:
            raise MoveError(f"Couldn't undo: {exc.strerror or exc}") from exc
        self.history.pop()

        if self.folder is not None and same_folder(back.parent, self.folder):
            # Where it belongs in the order now, which may not be the order it left.
            key = self._key(back)
            position = next((i for i, image in enumerate(self.images) if self._key(image) > key), len(self.images))
            self.images.insert(position, back)
            self.index = position
        return Move(last.destination, back, last.index)


# -- settings ---------------------------------------------------------------


def settings_path() -> Path:
    override = os.environ.get("VORTEXVIEW_SETTINGS")
    if override:
        return Path(override)
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
        return base / "VortexView" / "settings.json"
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "vortexview" / "settings.json"


@dataclass
class Settings:
    last_folder: str = ""
    last_image: str = ""
    targets: list[str] = field(default_factory=list)
    geometry: str = ""
    theme: str = "vortex"
    shuffle_seed: int | None = None

    @classmethod
    def load(cls, path: Path | None = None) -> "Settings":
        path = path or settings_path()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return cls()
        if not isinstance(data, dict):
            return cls()
        targets = [str(t) for t in data.get("targets", []) if isinstance(t, str) and t][:MAX_TARGETS]
        seed = data.get("shuffle_seed")
        if not isinstance(seed, int) or isinstance(seed, bool):
            seed = None
        return cls(
            last_folder=str(data.get("last_folder", "")),
            last_image=str(data.get("last_image", "")),
            targets=targets,
            geometry=str(data.get("geometry", "")),
            theme=str(data.get("theme", "vortex")) or "vortex",
            shuffle_seed=seed,
        )

    def save(self, path: Path | None = None) -> None:
        path = path or settings_path()
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_suffix(".tmp")
            temporary.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")
            os.replace(temporary, path)
        except OSError:
            pass  # settings are a convenience, never a reason to lose work

    def add_target(self, folder: Path) -> int:
        """Adds a target folder (or finds it) and returns its slot, 0-based."""
        text = str(folder)
        for position, existing in enumerate(self.targets):
            if os.path.normcase(existing) == os.path.normcase(text):
                return position
        if len(self.targets) >= MAX_TARGETS:
            raise MoveError(f"You can have up to {MAX_TARGETS} target folders — remove one first")
        self.targets.append(text)
        return len(self.targets) - 1
