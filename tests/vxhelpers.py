"""Shared fakes for the VXpress tests: no real yt-dlp, ffmpeg or network."""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Sequence

from vxpress.process import Cancelled, Completed
from vxpress.tools import Tool, Toolchain

FFMPEG_AVAILABLE = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
YTDLP_AVAILABLE = bool(shutil.which("yt-dlp"))


def fake_toolchain(ytdlp: bool = True, ffmpeg: bool = True, ffprobe: bool = True) -> Toolchain:
    def tool(name: str, present: bool, command: str) -> Tool:
        if not present:
            return Tool(name)
        return Tool(name, (command,), "test-version", "test")

    return Toolchain(
        ytdlp=tool("yt-dlp", ytdlp, "/fake/yt-dlp"),
        ffmpeg=tool("ffmpeg", ffmpeg, "/fake/bin/ffmpeg"),
        ffprobe=tool("ffprobe", ffprobe, "/fake/bin/ffprobe"),
    )


class FakeStream:
    """Stands in for process.Stream: replays canned output lines."""

    def __init__(
        self,
        argv: Sequence[str],
        on_line=None,
        lines: Sequence[str] = (),
        returncode: int = 0,
        creates: str | None = None,
        **_kwargs,
    ) -> None:
        self.argv = tuple(str(a) for a in argv)
        self.on_line = on_line
        self.lines = list(lines)
        self.returncode = returncode
        self.creates = creates
        self._cancelled = False
        self.cancelled_before_run = False

    def cancel(self, grace: float = 0.0) -> None:
        self._cancelled = True

    def run(self) -> Completed:
        if self._cancelled:
            self.cancelled_before_run = True
            raise Cancelled("cancelled")
        for line in self.lines:
            if self.on_line:
                self.on_line(line)
            if self._cancelled:
                raise Cancelled("cancelled")
        if self.creates and self.returncode == 0:
            path = Path(self.creates)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"ID3fake-audio-bytes")
        return Completed(self.argv, self.returncode, "\n".join(self.lines))


def stream_factory(
    lines: Sequence[str] = (),
    returncode: int = 0,
    creates_output: bool = False,
    record: list | None = None,
):
    """Build a make_stream replacement; records every stream it creates."""

    def factory(argv, on_line=None, **kwargs):
        creates = str(argv[-1]) if creates_output else None
        stream = FakeStream(
            argv, on_line, lines=lines, returncode=returncode, creates=creates, **kwargs
        )
        if record is not None:
            record.append(stream)
        return stream

    return factory


def fake_capture(responses: dict[str, tuple[int, str]]):
    """Replacement for process.capture keyed by a substring of the command."""
    calls: list[tuple[str, ...]] = []

    def runner(argv, timeout=None):
        key = " ".join(str(a) for a in argv)
        calls.append(tuple(str(a) for a in argv))
        for needle, (code, output) in responses.items():
            if needle in key:
                return Completed(tuple(str(a) for a in argv), code, output)
        return Completed(tuple(str(a) for a in argv), 0, "")

    runner.calls = calls  # type: ignore[attr-defined]
    return runner


PROBE_JSON = """
{
  "streams": [
    {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080},
    {"codec_type": "audio", "codec_name": "aac", "duration": "212.5"}
  ],
  "format": {
    "filename": "/videos/clip.mp4",
    "format_name": "mov,mp4,m4a",
    "duration": "212.512000",
    "size": "18234567",
    "tags": {"title": "A Test Clip", "artist": "Nobody"}
  }
}
"""

PROBE_AUDIO_ONLY = """
{
  "streams": [{"codec_type": "audio", "codec_name": "mp3"}],
  "format": {"format_name": "mp3", "duration": "95.0", "size": "1520000", "tags": {}}
}
"""

PROBE_NO_AUDIO = """
{
  "streams": [{"codec_type": "video", "codec_name": "h264", "height": 720}],
  "format": {"format_name": "mov,mp4,m4a", "duration": "30.0", "size": "900000"}
}
"""

PROBE_COVER_ART = """
{
  "streams": [
    {"codec_type": "video", "codec_name": "mjpeg", "disposition": {"attached_pic": 1}},
    {"codec_type": "audio", "codec_name": "mp3"}
  ],
  "format": {"format_name": "mp3", "duration": "180.0"}
}
"""

YTDLP_INFO_JSON = (
    '{"id": "abc123", "title": "Some Video", "webpage_url": "https://example.com/watch?v=abc123",'
    ' "uploader": "A Channel", "duration": 245.0, "extractor_key": "Example",'
    ' "thumbnail": "https://example.com/t.jpg",'
    ' "formats": [{"format_id": "18", "ext": "mp4", "height": 360, "fps": 30,'
    ' "vcodec": "avc1", "acodec": "mp4a", "filesize": 10485760, "format_note": "360p"},'
    ' {"format_id": "140", "ext": "m4a", "acodec": "mp4a", "vcodec": "none", "abr": 128}]}'
)

YTDLP_PLAYLIST_JSON = (
    '{"id": "PL1", "title": "My Playlist", "webpage_url": "https://example.com/playlist?list=PL1",'
    ' "uploader": "A Channel", "extractor_key": "Example",'
    ' "entries": [{"id": "a", "title": "First", "duration": 100},'
    ' {"id": "b", "title": "Second", "duration": 200}]}'
)

YTDLP_DOWNLOAD_LINES = [
    "[youtube] Extracting URL: https://example.com/watch?v=abc123",
    "[info] abc123: Downloading 1 format(s): 137+140",
    "[download] Destination: /out/Some Video [abc123].f137.mp4",
    "[download]   0.0% of   10.00MiB at  1.00MiB/s ETA 00:10",
    "[download]  52.3% of   10.00MiB at  2.00MiB/s ETA 00:02",
    "[download] 100% of   10.00MiB in 00:05",
    "[download] Destination: /out/Some Video [abc123].f140.m4a",
    "[download] 100% of    1.00MiB in 00:01",
    '[Merger] Merging formats into "/out/Some Video [abc123].mp4"',
]

YTDLP_AUDIO_LINES = [
    "[info] abc123: Downloading 1 format(s): 140",
    "[download] Destination: /out/Some Video [abc123].m4a",
    "[download]  33.0% of    1.00MiB at  500.00KiB/s ETA 00:01",
    "[download] 100% of    1.00MiB in 00:02",
    "[ExtractAudio] Destination: /out/Some Video [abc123].mp3",
    "Deleting original file /out/Some Video [abc123].m4a (pass -k to keep)",
]

FFMPEG_PROGRESS_LINES = [
    "out_time=00:00:00.500000",
    "progress=continue",
    "out_time=00:01:00.000000",
    "progress=continue",
    "out_time=00:03:32.500000",
    "progress=end",
]
