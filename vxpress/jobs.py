"""The work queue shared by the CLI and the web UI."""

from __future__ import annotations

import json
import os
import queue
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Deque

from . import download, media
from .media import AudioOptions, ConversionError
from .process import Cancelled, Stream, ToolMissing
from .sources import (
    BadSource,
    Source,
    classify,
    default_output_dir,
    ensure_dir,
    safe_filename,
    unique_path,
)
from .tools import Toolchain

QUEUED = "queued"
RUNNING = "running"
DONE = "done"
FAILED = "failed"
CANCELLED = "cancelled"
ACTIVE_STATUSES = (QUEUED, RUNNING)

VIDEO = "video"
MP3 = "mp3"
KINDS = (VIDEO, MP3)

LOG_LINES = 120


def history_path() -> Path:
    env = os.environ.get("VXPRESS_HISTORY", "").strip()
    if env:
        return Path(env).expanduser()
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        return base / "vxpress" / "history.jsonl"
    base = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return base / "vxpress" / "history.jsonl"


@dataclass
class Job:
    kind: str
    source: Source
    out_dir: Path
    options: AudioOptions = field(default_factory=AudioOptions)
    max_height: int | None = None
    container: str = "mp4"
    playlist: bool = False
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    status: str = QUEUED
    percent: float = 0.0
    stage: str = ""
    message: str = ""
    output_path: str = ""
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    log: Deque[str] = field(default_factory=lambda: deque(maxlen=LOG_LINES))
    cancel_requested: bool = False
    _stream: Stream | None = field(default=None, repr=False)

    @property
    def active(self) -> bool:
        return self.status in ACTIVE_STATUSES

    @property
    def title(self) -> str:
        if self.output_path:
            return Path(self.output_path).name
        return self.source.label

    @property
    def seconds(self) -> float | None:
        if self.started_at is None:
            return None
        return (self.finished_at or time.time()) - self.started_at

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "kind": self.kind,
            "source": self.source.to_dict(),
            "title": self.title,
            "status": self.status,
            "percent": round(self.percent, 1),
            "stage": self.stage,
            "message": self.message,
            "output_path": self.output_path,
            "output_name": Path(self.output_path).name if self.output_path else "",
            "out_dir": str(self.out_dir),
            "options": self.options.to_dict(),
            "playlist": self.playlist,
            "created_at": self.created_at,
            "seconds": round(self.seconds, 1) if self.seconds is not None else None,
            "log": list(self.log)[-12:],
            "downloadable": self.status == DONE and bool(self.output_path),
        }


class JobQueue:
    """A small thread pool that runs download and conversion jobs."""

    def __init__(
        self,
        tools: Toolchain | None = None,
        workers: int = 2,
        history: Path | None = None,
        start: bool = True,
    ) -> None:
        self.tools = tools or Toolchain.detect()
        self.history = Path(history) if history is not None else history_path()
        self._jobs: dict[str, Job] = {}
        self._order: list[str] = []
        self._queue: "queue.Queue[str | None]" = queue.Queue()
        self._lock = threading.RLock()
        self._threads: list[threading.Thread] = []
        self._workers = max(1, workers)
        self._stopping = False
        if start:
            self.start()

    # -- lifecycle ------------------------------------------------------
    def start(self) -> None:
        if self._threads:
            return
        for index in range(self._workers):
            thread = threading.Thread(
                target=self._worker, name=f"vxpress-worker-{index}", daemon=True
            )
            thread.start()
            self._threads.append(thread)

    def shutdown(self, wait: bool = True, timeout: float = 10.0) -> None:
        self._stopping = True
        for job in self.list_jobs():
            if job.active:
                self.cancel(job.id)
        for _ in self._threads:
            self._queue.put(None)
        if wait:
            for thread in self._threads:
                thread.join(timeout=timeout)
        self._threads.clear()

    # -- queue ----------------------------------------------------------
    def submit(
        self,
        kind: str,
        target: str,
        out_dir: str | Path | None = None,
        options: AudioOptions | None = None,
        max_height: int | None = None,
        container: str = "mp4",
        playlist: bool = False,
    ) -> Job:
        """Validate a request and put it in the queue."""
        if kind not in KINDS:
            raise BadSource(f"kind must be one of {', '.join(KINDS)}, got {kind!r}")
        source = classify(target)
        options = (options or AudioOptions()).validate()

        if kind == VIDEO and not source.is_url:
            raise BadSource(
                f"{Path(source.value).name} is already on this computer — "
                "choose 'convert to mp3' instead of 'download'"
            )
        if source.is_url:
            self.tools.require("yt-dlp")
            if kind == MP3:
                self.tools.require("ffmpeg")
        else:
            self.tools.require("ffmpeg")

        directory = ensure_dir(Path(out_dir) if out_dir else default_output_dir(kind))
        job = Job(
            kind=kind,
            source=source,
            out_dir=directory,
            options=options,
            max_height=max_height,
            container=container,
            playlist=bool(playlist) and source.is_url,
        )
        with self._lock:
            self._jobs[job.id] = job
            self._order.append(job.id)
        self._queue.put(job.id)
        return job

    def get(self, job_id: str) -> Job:
        with self._lock:
            job = self._jobs.get(job_id)
        if job is None:
            raise LookupError(f"no such job: {job_id}")
        return job

    def list_jobs(self, limit: int | None = None) -> list[Job]:
        with self._lock:
            jobs = [self._jobs[job_id] for job_id in reversed(self._order)]
        return jobs[:limit] if limit else jobs

    def cancel(self, job_id: str) -> Job:
        job = self.get(job_id)
        if not job.active:
            return job
        job.cancel_requested = True
        stream = job._stream
        if stream is not None:
            stream.cancel()
        elif job.status == QUEUED:
            self._finish(job, CANCELLED, "cancelled before it started")
        return job

    def clear_finished(self) -> int:
        with self._lock:
            removed = [job_id for job_id, job in self._jobs.items() if not job.active]
            for job_id in removed:
                self._jobs.pop(job_id, None)
                if job_id in self._order:
                    self._order.remove(job_id)
        return len(removed)

    # -- execution ------------------------------------------------------
    def _worker(self) -> None:
        while True:
            job_id = self._queue.get()
            try:
                if job_id is None:
                    return
                job = self._jobs.get(job_id)
                if job is None or job.status != QUEUED:
                    continue
                if job.cancel_requested:
                    self._finish(job, CANCELLED, "cancelled before it started")
                    continue
                self._run(job)
            finally:
                self._queue.task_done()

    def _stream_factory(self, job: Job) -> Callable[..., Stream]:
        def factory(argv, on_line=None, **kwargs) -> Stream:
            stream = Stream(argv, on_line, **kwargs)
            job._stream = stream
            if job.cancel_requested:
                stream.cancel()
            return stream

        return factory

    def _run(self, job: Job) -> None:
        job.status = RUNNING
        job.started_at = time.time()
        job.stage = "starting"
        try:
            if job.source.is_url:
                output = self._run_url(job)
            else:
                output = self._run_local(job)
            job.output_path = str(output)
            job.percent = 100.0
            self._finish(job, DONE, "finished")
        except Cancelled:
            self._finish(job, CANCELLED, "cancelled")
        except (download.DownloadError, ConversionError, BadSource, ToolMissing) as exc:
            self._finish(job, FAILED, str(exc))
        except Exception as exc:  # noqa: BLE001 - a worker must never die silently
            self._finish(job, FAILED, f"unexpected error: {exc}")

    def _run_url(self, job: Job) -> str:
        def on_progress(percent: float, stage: str) -> None:
            job.percent = percent
            job.stage = stage

        if job.kind == MP3:
            argv = download.audio_argv(
                job.source.value, job.out_dir, self.tools, job.options, playlist=job.playlist
            )
            want = ".mp3"
        else:
            argv = download.video_argv(
                job.source.value,
                job.out_dir,
                self.tools,
                max_height=job.max_height,
                container=job.container,
                playlist=job.playlist,
            )
            want = f".{job.container}" if job.container != "any" else ""

        produced, result = download.run_download(
            argv,
            on_progress=on_progress,
            want_extension=want,
            make_stream=self._stream_factory(job),
        )
        job.log.extend(result.output.splitlines()[-LOG_LINES:])
        return produced

    def _run_local(self, job: Job) -> str:
        source_path = Path(job.source.value)
        job.stage = "reading the file"
        info = media.probe(source_path, self.tools) if self.tools.ffprobe.found else None
        base = safe_filename((info.suggested_title if info else source_path.stem))
        destination = unique_path(Path(job.out_dir) / f"{base}.mp3")

        def on_progress(percent: float) -> None:
            job.percent = percent
            job.stage = "converting to mp3"

        produced = media.convert_to_mp3(
            source_path,
            destination,
            job.options,
            self.tools,
            on_progress=on_progress,
            info=info,
            make_stream=self._stream_factory(job),
        )
        if job._stream is not None:
            job.log.extend(list(job._stream.lines)[-LOG_LINES:])
        return str(produced)

    def _finish(self, job: Job, status: str, message: str) -> None:
        job.status = status
        job.message = message
        job.finished_at = time.time()
        job.stage = "" if status == DONE else job.stage
        job._stream = None
        self._record(job)

    def _record(self, job: Job) -> None:
        entry = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "kind": job.kind,
            "status": job.status,
            "source": job.source.value,
            "output": job.output_path,
            "seconds": round(job.seconds, 1) if job.seconds is not None else None,
            "quality": job.options.quality_label if job.kind == MP3 else job.container,
            "message": job.message,
        }
        try:
            self.history.parent.mkdir(parents=True, exist_ok=True)
            with self.history.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(entry, default=str) + "\n")
        except OSError:
            pass  # history is a convenience, never a reason to fail a job

    def wait(self, timeout: float = 120.0) -> bool:
        """Block until nothing is queued or running (tests and the CLI)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not any(job.active for job in self.list_jobs()):
                return True
            time.sleep(0.05)
        return False


def read_history(limit: int = 25, path: Path | None = None) -> list[dict]:
    """Most recent finished jobs, newest first."""
    target = Path(path) if path is not None else history_path()
    try:
        with target.open(encoding="utf-8", errors="replace") as handle:
            lines = handle.readlines()[-max(1, limit) :]
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
