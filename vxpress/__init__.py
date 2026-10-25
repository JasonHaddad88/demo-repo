"""Vortex VXpress — download a video from a link, or turn any video into an .mp3.

Pure standard library. The heavy lifting is done by two programs VXpress
drives: yt-dlp for links and ffmpeg for audio.
"""

from .jobs import Job, JobQueue
from .media import AudioOptions, MediaInfo
from .sources import Source, classify
from .tools import Toolchain

__version__ = "1.0.0"
__all__ = [
    "AudioOptions",
    "Job",
    "JobQueue",
    "MediaInfo",
    "Source",
    "Toolchain",
    "classify",
]
