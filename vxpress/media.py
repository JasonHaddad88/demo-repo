"""ffmpeg and ffprobe: inspect a media file, and turn one into an .mp3."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

from .process import Completed, Stream, capture
from .sources import BadSource, format_timestamp
from .tools import Toolchain

# What LAME accepts, and what people actually ask for.
BITRATES = (64, 96, 128, 160, 192, 256, 320)
DEFAULT_BITRATE = 192


class ConversionError(RuntimeError):
    """ffmpeg refused to produce the file."""


@dataclass
class AudioOptions:
    """How the .mp3 should sound, and which part of the source to take."""

    bitrate: int = DEFAULT_BITRATE
    vbr: int | None = None  # LAME -q:a, 0 = best .. 9 = smallest
    start: float | None = None
    end: float | None = None
    metadata: bool = True
    thumbnail: bool = False  # cover art; only meaningful for downloads

    def validate(self) -> "AudioOptions":
        if self.vbr is not None:
            if not 0 <= int(self.vbr) <= 9:
                raise BadSource(f"--vbr takes 0 (best) to 9 (smallest), got {self.vbr}")
        else:
            if not 32 <= int(self.bitrate) <= 320:
                raise BadSource(f"--bitrate must be between 32 and 320 kbps, got {self.bitrate}")
        if self.start is not None and self.end is not None and self.end <= self.start:
            raise BadSource(
                f"--end ({format_timestamp(self.end)}) must come after "
                f"--start ({format_timestamp(self.start)})"
            )
        return self

    @property
    def duration(self) -> float | None:
        if self.end is None:
            return None
        return self.end - (self.start or 0.0)

    @property
    def quality_label(self) -> str:
        return f"VBR q{self.vbr}" if self.vbr is not None else f"{self.bitrate} kbps"

    def to_dict(self) -> dict:
        return {
            "bitrate": self.bitrate,
            "vbr": self.vbr,
            "start": self.start,
            "end": self.end,
            "metadata": self.metadata,
            "thumbnail": self.thumbnail,
            "quality": self.quality_label,
        }


@dataclass
class MediaInfo:
    path: str = ""
    title: str = ""
    duration: float | None = None
    size: int | None = None
    format_name: str = ""
    has_audio: bool = False
    has_video: bool = False
    audio_codec: str = ""
    video_codec: str = ""
    tags: dict = field(default_factory=dict)

    @property
    def suggested_title(self) -> str:
        return self.title or Path(self.path).stem

    def to_dict(self) -> dict:
        return {
            "path": self.path,
            "title": self.suggested_title,
            "duration": self.duration,
            "duration_label": format_timestamp(self.duration),
            "size": self.size,
            "format": self.format_name,
            "has_audio": self.has_audio,
            "has_video": self.has_video,
            "audio_codec": self.audio_codec,
            "video_codec": self.video_codec,
        }


def probe(path: str | Path, tools: Toolchain | None = None, runner=capture) -> MediaInfo:
    """Read a local file's duration, streams and tags with ffprobe."""
    tools = tools or Toolchain.detect()
    tools.require("ffprobe")
    target = Path(path)
    result = runner(
        [
            *tools.ffprobe.argv,
            "-v", "error",
            "-show_format",
            "-show_streams",
            "-of", "json",
            str(target),
        ],
        timeout=120,
    )
    if not result.ok:
        raise ConversionError(
            f"ffprobe could not read {target.name}: {result.last_line or 'unknown error'}"
        )
    return parse_probe(result.output, str(target))


def parse_probe(payload: str, path: str = "") -> MediaInfo:
    """Turn ``ffprobe -of json`` output into a MediaInfo."""
    try:
        data = json.loads(payload or "{}")
    except json.JSONDecodeError as exc:
        raise ConversionError(f"ffprobe returned output we cannot read: {exc}") from exc

    container = data.get("format") or {}
    tags = {str(k).lower(): v for k, v in (container.get("tags") or {}).items()}
    info = MediaInfo(
        path=path or container.get("filename", ""),
        title=str(tags.get("title", "")),
        format_name=str(container.get("format_name", "")),
        tags=tags,
    )
    try:
        info.duration = float(container["duration"])
    except (KeyError, TypeError, ValueError):
        info.duration = None
    try:
        info.size = int(container["size"])
    except (KeyError, TypeError, ValueError):
        info.size = None

    for stream in data.get("streams") or []:
        kind = stream.get("codec_type")
        if kind == "audio" and not info.has_audio:
            info.has_audio = True
            info.audio_codec = str(stream.get("codec_name", ""))
            if info.duration is None:
                try:
                    info.duration = float(stream["duration"])
                except (KeyError, TypeError, ValueError):
                    pass
        elif kind == "video" and not info.has_video:
            # Cover art is carried as a video stream; that is not a video.
            if stream.get("disposition", {}).get("attached_pic"):
                continue
            info.has_video = True
            info.video_codec = str(stream.get("codec_name", ""))
    return info


def mp3_argv(
    source: str | Path,
    destination: str | Path,
    options: AudioOptions,
    tools: Toolchain,
) -> list[str]:
    """The exact ffmpeg command used to produce an .mp3."""
    options.validate()
    argv = [*tools.ffmpeg.argv, "-hide_banner", "-nostdin", "-loglevel", "error", "-y"]
    if options.start:
        argv += ["-ss", f"{options.start:.3f}"]
    argv += ["-i", str(source)]
    if options.duration:
        argv += ["-t", f"{options.duration:.3f}"]
    argv += ["-vn", "-map", "0:a:0", "-c:a", "libmp3lame"]
    if options.vbr is not None:
        argv += ["-q:a", str(int(options.vbr))]
    else:
        argv += ["-b:a", f"{int(options.bitrate)}k"]
    if options.metadata:
        argv += ["-map_metadata", "0", "-id3v2_version", "3"]
    else:
        argv += ["-map_metadata", "-1"]
    argv += ["-progress", "pipe:1", "-nostats", str(destination)]
    return argv


class FfmpegProgress:
    """Reads ``-progress pipe:1`` output and reports percent complete."""

    _TIME = re.compile(r"^out_time=(?P<h>\d+):(?P<m>\d\d):(?P<s>\d\d(?:\.\d+)?)")
    _TIME_US = re.compile(r"^out_time_(?:us|ms)=(?P<value>-?\d+)")

    def __init__(self, duration: float | None) -> None:
        self.duration = duration if duration and duration > 0 else None
        self.seconds = 0.0
        self.finished = False

    def feed(self, line: str) -> float | None:
        """Returns percent (0-100) when it changed, else None."""
        line = line.strip()
        if line.startswith("progress=") and line.endswith("end"):
            self.finished = True
            return 100.0

        match = self._TIME.match(line)
        if match:
            self.seconds = (
                int(match.group("h")) * 3600
                + int(match.group("m")) * 60
                + float(match.group("s"))
            )
        else:
            match = self._TIME_US.match(line)
            if not match:
                return None
            raw = int(match.group("value"))
            if raw < 0:
                return None
            # ffmpeg reports microseconds in out_time_us and, confusingly,
            # in out_time_ms too on most builds.
            self.seconds = raw / 1_000_000

        if not self.duration:
            return None
        return max(0.0, min(99.9, self.seconds / self.duration * 100))


def convert_to_mp3(
    source: str | Path,
    destination: str | Path,
    options: AudioOptions,
    tools: Toolchain,
    on_progress: Callable[[float], None] | None = None,
    info: MediaInfo | None = None,
    make_stream: Callable[..., Stream] = Stream,
) -> Path:
    """Run ffmpeg and return the finished .mp3 path."""
    tools.require("ffmpeg")
    source_path, destination_path = Path(source), Path(destination)
    if info is None and tools.ffprobe.found:
        info = probe(source_path, tools)
    if info is not None and not info.has_audio:
        raise ConversionError(
            f"{source_path.name} has no audio track, so there is nothing to convert"
        )

    total = info.duration if info else None
    if options.duration:
        total = options.duration
    elif total and options.start:
        total = max(0.0, total - options.start)
    progress = FfmpegProgress(total)

    def on_line(line: str) -> None:
        percent = progress.feed(line)
        if percent is not None and on_progress:
            on_progress(percent)

    argv = mp3_argv(source_path, destination_path, options, tools)
    result: Completed = make_stream(argv, on_line).run()
    if not result.ok:
        raise ConversionError(_ffmpeg_message(result, source_path))
    if not destination_path.exists() or destination_path.stat().st_size == 0:
        raise ConversionError(
            f"ffmpeg reported success but produced no audio for {source_path.name}"
        )
    if on_progress:
        on_progress(100.0)
    return destination_path


def _ffmpeg_message(result: Completed, source: Path) -> str:
    text = result.output.strip()
    lowered = text.lower()
    if "no such file" in lowered:
        return f"ffmpeg cannot open {source}"
    if "invalid data found" in lowered:
        return f"{source.name} is not a media file ffmpeg can read"
    if "unknown encoder" in lowered and "libmp3lame" in lowered:
        return (
            "this ffmpeg build has no libmp3lame encoder, so it cannot write mp3 — "
            "install a full ffmpeg build"
        )
    if "does not contain any stream" in lowered or "matches no streams" in lowered:
        return f"{source.name} has no audio track, so there is nothing to convert"
    return text.splitlines()[-1] if text else f"ffmpeg exited with status {result.returncode}"


def bitrate_choices() -> Sequence[int]:
    return BITRATES
