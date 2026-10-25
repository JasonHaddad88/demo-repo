"""Command line interface for Vortex VXpress."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Sequence

from . import download, jobs as jobs_module, media
from .jobs import DONE, FAILED, Job, JobQueue
from .media import AudioOptions, bitrate_choices
from .process import ToolMissing
from .sources import BadSource, classify, default_output_dir, format_timestamp, parse_timestamp
from .tools import Toolchain

PROGRAM = "vxpress"
SPINNER = "|/-\\"


# -- output helpers -----------------------------------------------------
def _human_size(value: int | None) -> str:
    if not value:
        return "?"
    size = float(value)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return "?"


def _table(rows: list[list[str]], headers: list[str]) -> str:
    widths = [len(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(cell))
    head = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers)).rstrip()
    rule = "  ".join("-" * widths[i] for i in range(len(headers)))
    body = ["  ".join(c.ljust(widths[i]) for i, c in enumerate(row)).rstrip() for row in rows]
    return "\n".join([head, rule, *body])


def _bar(percent: float, width: int = 24) -> str:
    filled = int(max(0.0, min(100.0, percent)) / 100 * width)
    return "#" * filled + "." * (width - filled)


def _is_tty() -> bool:
    try:
        return bool(sys.stdout.isatty())
    except (AttributeError, ValueError):
        return False


# -- job running --------------------------------------------------------
def _targets(args: argparse.Namespace) -> list[str]:
    targets = list(args.target or [])
    if getattr(args, "batch_file", None):
        path = Path(args.batch_file).expanduser()
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise BadSource(f"cannot read --batch-file {path}: {exc}") from exc
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                targets.append(line)
    if not targets:
        raise BadSource("give me at least one link or file (or use --batch-file)")
    return targets


def _audio_options(args: argparse.Namespace) -> AudioOptions:
    return AudioOptions(
        bitrate=getattr(args, "bitrate", media.DEFAULT_BITRATE),
        vbr=getattr(args, "vbr", None),
        start=parse_timestamp(getattr(args, "start", None)),
        end=parse_timestamp(getattr(args, "end", None)),
        metadata=not getattr(args, "no_metadata", False),
        thumbnail=getattr(args, "thumbnail", False),
    )


def _render_progress(queue: JobQueue, tick: int) -> str:
    active = [job for job in reversed(queue.list_jobs()) if job.active]
    if not active:
        return ""
    job = next((j for j in active if j.status == "running"), active[0])
    remaining = len(active) - 1
    extra = f" (+{remaining} waiting)" if remaining > 0 else ""
    spin = SPINNER[tick % len(SPINNER)]
    stage = job.stage or job.status
    return f" {spin} [{_bar(job.percent)}] {job.percent:5.1f}%  {stage}  {job.title[:46]}{extra}"


def _run_jobs(queue: JobQueue, requests: list[tuple[str, str]], args: argparse.Namespace) -> int:
    """Submit every request, follow progress, then report. Returns an exit code."""
    submitted: list[Job] = []
    failures = 0
    for kind, target in requests:
        try:
            submitted.append(queue.submit(
                kind,
                target,
                out_dir=args.out,
                options=_audio_options(args),
                max_height=getattr(args, "max_height", None),
                container=getattr(args, "container", "mp4"),
                playlist=getattr(args, "playlist", False),
            ))
        except BadSource as exc:
            print(f"{PROGRAM}: {target}: {exc}", file=sys.stderr)
            failures += 1

    if not submitted:
        return 2

    live = _is_tty() and not args.json
    reported: set[str] = set()
    tick = 0
    try:
        while any(job.active for job in submitted):
            if live:
                line = _render_progress(queue, tick)
                if line:
                    sys.stdout.write("\r" + line.ljust(100))
                    sys.stdout.flush()
            for job in submitted:
                if not job.active and job.id not in reported:
                    reported.add(job.id)
                    if live:
                        sys.stdout.write("\r" + " " * 100 + "\r")
                    _report(job, args.json)
            tick += 1
            time.sleep(0.2)
    except KeyboardInterrupt:
        if live:
            sys.stdout.write("\r" + " " * 100 + "\r")
        print("Stopping…", file=sys.stderr)
        for job in submitted:
            queue.cancel(job.id)
        queue.wait(timeout=15)

    if live:
        sys.stdout.write("\r" + " " * 100 + "\r")
    for job in submitted:
        if job.id not in reported:
            _report(job, args.json)

    failures += sum(1 for job in submitted if job.status != DONE)
    if args.json:
        print(json.dumps([job.to_dict() for job in submitted], indent=2, default=str))
    return 1 if failures else 0


def _report(job: Job, as_json: bool) -> None:
    if as_json:
        return
    if job.status == DONE:
        print(f"[ok]     {job.output_path}")
    elif job.status == FAILED:
        print(f"[failed] {job.source.label}: {job.message}", file=sys.stderr)
    else:
        print(f"[{job.status}] {job.source.label}: {job.message}", file=sys.stderr)


# -- commands -----------------------------------------------------------
def cmd_get(args: argparse.Namespace, queue: JobQueue) -> int:
    return _run_jobs(queue, [("video", t) for t in _targets(args)], args)


def cmd_mp3(args: argparse.Namespace, queue: JobQueue) -> int:
    return _run_jobs(queue, [("mp3", t) for t in _targets(args)], args)


def cmd_info(args: argparse.Namespace, queue: JobQueue) -> int:
    source = classify(args.target[0])
    if source.is_url:
        queue.tools.require("yt-dlp")
        details = download.info(source.value, queue.tools, playlist=args.playlist)
        if args.json:
            print(json.dumps(details.to_dict(), indent=2, default=str))
            return 0
        lines = [
            f"title     {details.title}",
            f"uploader  {details.uploader or '-'}",
            f"duration  {format_timestamp(details.duration)}",
            f"site      {details.extractor or '-'}",
            f"id        {details.video_id or '-'}",
        ]
        if details.is_playlist:
            lines.append(f"playlist  {len(details.entries)} items")
            for index, entry in enumerate(details.entries[:20], start=1):
                lines.append(f"   {index:3d}. {entry['title']} ({entry['duration_label']})")
            if len(details.entries) > 20:
                lines.append(f"   … and {len(details.entries) - 20} more")
        print("\n".join(lines))
        if details.formats:
            rows = [
                [
                    fmt["format_id"],
                    fmt["ext"],
                    fmt["resolution"] or "audio only",
                    str(fmt["fps"] or ""),
                    _human_size(fmt["filesize"]),
                    fmt["note"],
                ]
                for fmt in details.formats[-15:]
            ]
            print()
            print(_table(rows, ["ID", "EXT", "RESOLUTION", "FPS", "SIZE", "NOTE"]))
        return 0

    queue.tools.require("ffprobe")
    details = media.probe(source.value, queue.tools)
    if args.json:
        print(json.dumps(details.to_dict(), indent=2, default=str))
        return 0
    print(
        "\n".join(
            [
                f"file      {details.path}",
                f"title     {details.suggested_title}",
                f"duration  {format_timestamp(details.duration)}",
                f"size      {_human_size(details.size)}",
                f"container {details.format_name or '-'}",
                f"audio     {details.audio_codec or 'none'}",
                f"video     {details.video_codec or 'none'}",
            ]
        )
    )
    return 0


def cmd_doctor(args: argparse.Namespace, queue: JobQueue) -> int:
    tools = queue.tools
    payload = tools.to_dict()
    if args.json:
        payload["output_video"] = str(default_output_dir("video"))
        payload["output_audio"] = str(default_output_dir("mp3"))
        print(json.dumps(payload, indent=2, default=str))
        return 0 if payload["ready_for_download"] and payload["ready_for_mp3"] else 1

    rows = []
    for tool in (tools.ytdlp, tools.ffmpeg, tools.ffprobe):
        rows.append(
            [
                tool.name,
                "found" if tool.found else "MISSING",
                tool.version or "-",
                tool.source,
            ]
        )
    print(_table(rows, ["TOOL", "STATE", "VERSION", "FOUND VIA"]))
    print()
    print(f"download videos   {'yes' if tools.ytdlp.found else 'no — yt-dlp missing'}")
    print(f"convert to mp3    {'yes' if tools.ffmpeg.found else 'no — ffmpeg missing'}")
    print(f"video folder      {default_output_dir('video')}")
    print(f"audio folder      {default_output_dir('mp3')}")
    print(f"history           {queue.history}")
    missing = [tool for tool in (tools.ytdlp, tools.ffmpeg) if not tool.found]
    if missing:
        print()
        for tool in missing:
            print(f"install {tool.name}: {tool.hint}")
    return 1 if missing else 0


def cmd_history(args: argparse.Namespace, queue: JobQueue) -> int:
    entries = jobs_module.read_history(args.limit, queue.history)
    if args.json:
        print(json.dumps(entries, indent=2, default=str))
        return 0
    if not entries:
        print(f"Nothing yet ({queue.history}).")
        return 0
    rows = [
        [
            entry.get("ts", "?"),
            entry.get("kind", "?"),
            entry.get("status", "?"),
            str(entry.get("quality", "")),
            (entry.get("output") or entry.get("source") or "")[-70:],
        ]
        for entry in entries
    ]
    print(_table(rows, ["WHEN", "KIND", "STATUS", "QUALITY", "RESULT"]))
    return 0


def cmd_serve(args: argparse.Namespace, queue: JobQueue) -> int:
    from .server import serve

    return serve(queue, host=args.bind, port=args.port, open_browser=args.open, token=args.token)


# -- parser -------------------------------------------------------------
def _add_audio_flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--bitrate",
        type=int,
        default=media.DEFAULT_BITRATE,
        metavar="KBPS",
        help=f"constant bitrate in kbps (common: {', '.join(str(b) for b in bitrate_choices())})",
    )
    parser.add_argument(
        "--vbr",
        type=int,
        metavar="0-9",
        help="use variable bitrate instead: 0 is best quality, 9 is smallest",
    )
    parser.add_argument("--start", metavar="TS", help="skip to this point, e.g. 0:30")
    parser.add_argument("--end", metavar="TS", help="stop here, e.g. 2:45")
    parser.add_argument(
        "--thumbnail", action="store_true", help="embed cover art (links only)"
    )
    parser.add_argument(
        "--no-metadata", action="store_true", help="do not copy title/artist tags"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=PROGRAM,
        description=(
            "Vortex VXpress — download a video from a link, or turn any video "
            "(local file or link) into an .mp3."
        ),
        epilog=f"Run `{PROGRAM} serve` for the drag-and-drop web interface.",
    )
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument("--out", metavar="DIR", help="where finished files go")
    parser.add_argument(
        "--workers", type=int, default=2, metavar="N", help="jobs to run at once (default 2)"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    get = subparsers.add_parser("get", help="download the video behind a link")
    get.add_argument("target", nargs="*", help="one or more links")
    get.add_argument("--batch-file", metavar="FILE", help="read links from a file, one per line")
    get.add_argument("--playlist", action="store_true", help="fetch every item in a playlist")
    get.add_argument(
        "--max-height", type=int, metavar="PX", help="cap the resolution, e.g. 1080"
    )
    get.add_argument(
        "--container",
        default="mp4",
        choices=("mp4", "mkv", "webm", "any"),
        help="container to merge into (default mp4)",
    )
    _add_audio_flags(get)
    get.set_defaults(func=cmd_get)

    mp3 = subparsers.add_parser("mp3", help="convert a link or a local file to .mp3")
    mp3.add_argument("target", nargs="*", help="links and/or paths to local video files")
    mp3.add_argument("--batch-file", metavar="FILE", help="read targets from a file")
    mp3.add_argument("--playlist", action="store_true", help="convert every item in a playlist")
    _add_audio_flags(mp3)
    mp3.set_defaults(func=cmd_mp3)

    info = subparsers.add_parser("info", help="show what a link or file contains")
    info.add_argument("target", nargs=1)
    info.add_argument("--playlist", action="store_true", help="list playlist items")
    info.set_defaults(func=cmd_info)

    doctor = subparsers.add_parser("doctor", help="check yt-dlp and ffmpeg are installed")
    doctor.set_defaults(func=cmd_doctor)

    history = subparsers.add_parser("history", help="what VXpress has produced before")
    history.add_argument("--limit", type=int, default=20)
    history.set_defaults(func=cmd_history)

    serve = subparsers.add_parser("serve", help="run the local web interface")
    serve.add_argument("--port", type=int, default=8799, help="default 8799")
    serve.add_argument("--bind", default="127.0.0.1", help="loopback address to bind")
    serve.add_argument("--open", action="store_true", help="open it in your browser")
    serve.add_argument("--token", help="fixed access token instead of a random one")
    serve.set_defaults(func=cmd_serve)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    queue = JobQueue(tools=Toolchain.detect(), workers=max(1, args.workers))
    try:
        return args.func(args, queue)
    except ToolMissing as exc:
        print(f"{PROGRAM}: {exc}", file=sys.stderr)
        return 127
    except BadSource as exc:
        print(f"{PROGRAM}: {exc}", file=sys.stderr)
        return 2
    except (download.DownloadError, media.ConversionError) as exc:
        print(f"{PROGRAM}: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130
    finally:
        queue.shutdown(wait=False)


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
