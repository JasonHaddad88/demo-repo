"""yt-dlp: ask a link what it is, fetch the video, or fetch just the audio.

VXpress only ever asks yt-dlp for what a site serves to an ordinary visitor.
It adds nothing for DRM-protected streams, and it does not sign in anywhere.
"""

from __future__ import annotations

import json
import os
import re
import shlex
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

from .media import AudioOptions
from .process import Completed, Stream, capture
from .sources import format_timestamp
from .tools import Toolchain

# Extra flags a user needs for their own setup (cookies, proxy, rate limits).
EXTRA_ARGS_ENV = "VXPRESS_YTDLP_ARGS"

OUTPUT_TEMPLATE = "%(title).150s [%(id)s].%(ext)s"
PLAYLIST_TEMPLATE = "%(playlist_title).80s/%(playlist_index)03d - %(title).120s [%(id)s].%(ext)s"


class DownloadError(RuntimeError):
    """yt-dlp could not deliver the media."""


@dataclass
class VideoInfo:
    title: str = ""
    video_id: str = ""
    url: str = ""
    uploader: str = ""
    duration: float | None = None
    extractor: str = ""
    thumbnail: str = ""
    is_playlist: bool = False
    entries: list = field(default_factory=list)
    formats: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "title": self.title,
            "id": self.video_id,
            "url": self.url,
            "uploader": self.uploader,
            "duration": self.duration,
            "duration_label": format_timestamp(self.duration),
            "extractor": self.extractor,
            "thumbnail": self.thumbnail,
            "is_playlist": self.is_playlist,
            "entries": self.entries,
            "formats": self.formats[:40],
        }


def extra_args() -> list[str]:
    raw = os.environ.get(EXTRA_ARGS_ENV, "").strip()
    return shlex.split(raw) if raw else []


def _base_argv(tools: Toolchain, playlist: bool) -> list[str]:
    argv = [
        *tools.ytdlp.argv,
        "--newline",            # progress on its own line, not redrawn
        "--no-colors",
        "--ignore-config",      # behave the same on every machine
        "--windows-filenames",  # names that are safe on every filesystem
        "--no-mtime",
    ]
    argv.append("--yes-playlist" if playlist else "--no-playlist")
    if tools.ffmpeg_dir:
        argv += ["--ffmpeg-location", tools.ffmpeg_dir]
    return argv


def info(url: str, tools: Toolchain, playlist: bool = False, runner=capture) -> VideoInfo:
    """Metadata for a link, without downloading anything."""
    tools.require("yt-dlp")
    argv = [*_base_argv(tools, playlist), "--no-warnings", "-J"]
    if playlist:
        argv.append("--flat-playlist")
    argv += [*extra_args(), "--", url]

    result = runner(argv, timeout=180)
    if not result.ok:
        raise DownloadError(explain(result.output))
    payload = _first_json_object(result.output)
    if payload is None:
        raise DownloadError(explain(result.output) or "yt-dlp returned no information")
    return parse_info(payload)


def _first_json_object(text: str) -> dict | None:
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("{") and line.endswith("}"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
    return None


def parse_info(payload: dict) -> VideoInfo:
    """Turn ``yt-dlp -J`` output into a VideoInfo."""
    entries = payload.get("entries")
    if entries is not None:
        listed = [
            {
                "title": str(entry.get("title") or entry.get("id") or "?"),
                "id": str(entry.get("id") or ""),
                "duration": entry.get("duration"),
                "duration_label": format_timestamp(entry.get("duration")),
            }
            for entry in entries
            if isinstance(entry, dict)
        ]
        return VideoInfo(
            title=str(payload.get("title") or "playlist"),
            video_id=str(payload.get("id") or ""),
            url=str(payload.get("webpage_url") or ""),
            uploader=str(payload.get("uploader") or payload.get("channel") or ""),
            extractor=str(payload.get("extractor_key") or payload.get("extractor") or ""),
            is_playlist=True,
            entries=listed,
        )

    formats = []
    for fmt in payload.get("formats") or []:
        if not isinstance(fmt, dict):
            continue
        formats.append(
            {
                "format_id": str(fmt.get("format_id") or ""),
                "ext": str(fmt.get("ext") or ""),
                "resolution": str(
                    fmt.get("resolution")
                    or (f"{fmt.get('width')}x{fmt.get('height')}" if fmt.get("height") else "")
                ),
                "height": fmt.get("height"),
                "fps": fmt.get("fps"),
                "vcodec": str(fmt.get("vcodec") or ""),
                "acodec": str(fmt.get("acodec") or ""),
                "abr": fmt.get("abr"),
                "filesize": fmt.get("filesize") or fmt.get("filesize_approx"),
                "note": str(fmt.get("format_note") or ""),
            }
        )

    duration = payload.get("duration")
    return VideoInfo(
        title=str(payload.get("title") or payload.get("id") or "video"),
        video_id=str(payload.get("id") or ""),
        url=str(payload.get("webpage_url") or payload.get("original_url") or ""),
        uploader=str(payload.get("uploader") or payload.get("channel") or ""),
        duration=float(duration) if isinstance(duration, (int, float)) else None,
        extractor=str(payload.get("extractor_key") or payload.get("extractor") or ""),
        thumbnail=str(payload.get("thumbnail") or ""),
        formats=formats,
    )


def video_argv(
    url: str,
    out_dir: str | Path,
    tools: Toolchain,
    max_height: int | None = None,
    container: str = "mp4",
    playlist: bool = False,
) -> list[str]:
    """Command for "give me the video file"."""
    height = f"[height<={int(max_height)}]" if max_height else ""
    selector = f"bv*{height}+ba/b{height}/bv*+ba/b"
    argv = _base_argv(tools, playlist)
    argv += [
        "-f", selector,
        "-P", str(out_dir),
        "-o", PLAYLIST_TEMPLATE if playlist else OUTPUT_TEMPLATE,
        "--no-overwrites",
        "--continue",
    ]
    if container and container != "any":
        argv += ["--merge-output-format", container]
    if playlist:
        argv.append("--ignore-errors")
    argv += [*extra_args(), "--", url]
    return argv


def audio_argv(
    url: str,
    out_dir: str | Path,
    tools: Toolchain,
    options: AudioOptions,
    playlist: bool = False,
) -> list[str]:
    """Command for "give me just the sound, as mp3"."""
    options.validate()
    argv = _base_argv(tools, playlist)
    argv += [
        "-f", "ba/b",
        "-x",
        "--audio-format", "mp3",
        "--audio-quality", str(int(options.vbr)) if options.vbr is not None else f"{int(options.bitrate)}K",
        "-P", str(out_dir),
        "-o", PLAYLIST_TEMPLATE if playlist else OUTPUT_TEMPLATE,
    ]
    if options.metadata:
        argv.append("--embed-metadata")
    if options.thumbnail:
        argv.append("--embed-thumbnail")
    if options.start is not None or options.end is not None:
        start = f"{options.start:.3f}" if options.start is not None else "0"
        end = f"{options.end:.3f}" if options.end is not None else "inf"
        argv += ["--download-sections", f"*{start}-{end}", "--force-keyframes-at-cuts"]
    if playlist:
        argv.append("--ignore-errors")
    argv += [*extra_args(), "--", url]
    return argv


class YtdlpProgress:
    """Follows yt-dlp's --newline output: percent, stage and final filename."""

    _PERCENT = re.compile(r"\[download\]\s+(?P<percent>\d{1,3}(?:\.\d+)?)%")
    _DESTINATION = re.compile(r"^\[(?P<stage>[A-Za-z]+)\] Destination: (?P<path>.+)$")
    _MERGER = re.compile(r'^\[Merger\] Merging formats into "(?P<path>.+)"$')
    _MOVE = re.compile(r'^\[MoveFiles\] Moving file "(?:.+)" to "(?P<path>.+)"$')
    _ALREADY = re.compile(r"^\[download\] (?P<path>.+) has already been downloaded$")
    _ITEM = re.compile(r"^\[download\] Downloading item (?P<index>\d+) of (?P<total>\d+)")

    STAGE_LABELS = {
        "ExtractAudio": "converting to mp3",
        "Merger": "merging audio and video",
        "Fixup": "fixing up the file",
        "EmbedThumbnail": "embedding cover art",
        "Metadata": "writing tags",
        "MoveFiles": "moving into place",
    }

    def __init__(self) -> None:
        self.percent = 0.0
        self.stage = "starting"
        self.files: list[str] = []
        self.item: tuple[int, int] | None = None

    @property
    def final_file(self) -> str:
        return self.files[-1] if self.files else ""

    def feed(self, line: str) -> bool:
        """Consume one output line; True when something worth showing changed."""
        line = line.strip()
        changed = False

        match = self._ITEM.match(line)
        if match:
            self.item = (int(match.group("index")), int(match.group("total")))
            self.stage = f"item {self.item[0]} of {self.item[1]}"
            return True

        match = self._PERCENT.search(line)
        if match:
            percent = min(100.0, float(match.group("percent")))
            if percent != self.percent:
                self.percent = percent
                changed = True
            if self.stage in ("starting", "preparing"):
                self.stage = "downloading"
                changed = True

        for pattern, stage in (
            (self._MERGER, "merging audio and video"),
            (self._MOVE, "moving into place"),
            (self._ALREADY, "already downloaded"),
        ):
            found = pattern.match(line)
            if found:
                self._remember(found.group("path"))
                self.stage = stage
                return True

        found = self._DESTINATION.match(line)
        if found:
            stage = found.group("stage")
            self._remember(found.group("path"))
            self.stage = self.STAGE_LABELS.get(stage, "downloading")
            return True

        for needle, label in (
            ("[ExtractAudio]", "converting to mp3"),
            ("[Merger]", "merging audio and video"),
            ("[EmbedThumbnail]", "embedding cover art"),
            ("[Metadata]", "writing tags"),
        ):
            if line.startswith(needle) and self.stage != label:
                self.stage = label
                return True

        return changed

    def _remember(self, path: str) -> None:
        path = path.strip().strip('"')
        if path and (not self.files or self.files[-1] != path):
            self.files.append(path)


def _pick_output(progress: YtdlpProgress, want_extension: str = "") -> str:
    """The file the user actually asked for, out of everything yt-dlp wrote."""
    if want_extension:
        for candidate in reversed(progress.files):
            if candidate.lower().endswith(want_extension.lower()):
                return candidate
    return progress.final_file


def run_download(
    argv: Sequence[str],
    on_progress: Callable[[float, str], None] | None = None,
    want_extension: str = "",
    make_stream: Callable[..., Stream] = Stream,
) -> tuple[str, Completed]:
    """Execute a prepared yt-dlp command and report the file it produced."""
    progress = YtdlpProgress()

    def on_line(line: str) -> None:
        if progress.feed(line) and on_progress:
            on_progress(progress.percent, progress.stage)

    result: Completed = make_stream(argv, on_line).run()
    if not result.ok:
        raise DownloadError(explain(result.output))
    produced = _pick_output(progress, want_extension)
    if not produced:
        raise DownloadError(
            "yt-dlp finished but did not say which file it wrote — "
            "check the output folder"
        )
    return produced, result


MESSAGES = (
    ("unsupported url", "yt-dlp does not support this site"),
    ("is not a valid url", "that does not look like a link yt-dlp can open"),
    ("video unavailable", "the site says this video is unavailable"),
    ("private video", "this video is private, so it cannot be downloaded"),
    ("members-only", "this video is for members only"),
    ("sign in to confirm", "the site wants a signed-in session; VXpress does not sign in for you"),
    ("this video is drm protected", "this video is DRM-protected and cannot be downloaded"),
    ("drm", "this stream is DRM-protected and cannot be downloaded"),
    ("http error 403", "the site refused the request (HTTP 403)"),
    ("http error 404", "the link points at something that no longer exists (HTTP 404)"),
    ("http error 429", "the site is rate-limiting you (HTTP 429) — wait and try again"),
    ("unable to download webpage", "could not reach the site — check your connection"),
    ("ffmpeg not found", "ffmpeg is required for this and was not found"),
    ("requested format is not available", "no matching format — try without --max-height"),
    ("no space left", "the disk is full"),
)


def explain(output: str) -> str:
    """Turn yt-dlp's noise into one sentence a person can act on."""
    text = (output or "").strip()
    if not text:
        return "yt-dlp failed without saying why"
    errors = [
        line.strip() for line in text.splitlines() if line.strip().upper().startswith("ERROR:")
    ]
    detail = errors[-1] if errors else text.splitlines()[-1].strip()
    lowered = detail.lower()
    for needle, message in MESSAGES:
        if needle in lowered:
            return f"{message}  ({detail.lstrip('ERROR:').strip()[:160]})"
    return detail[:300]
