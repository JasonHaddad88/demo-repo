"""The local web interface.

Same posture as the rest of VXpress: it is a tool for the machine it runs on.
The socket is loopback-only, every API call needs the token printed at startup,
the Host header must be loopback (blocks DNS rebinding) and cross-origin
requests are refused.
"""

from __future__ import annotations

import hmac
import json
import mimetypes
import re
import secrets
import shutil
import socket
import sys
import tempfile
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

from . import download, jobs as jobs_module, media
from .jobs import JobQueue
from .media import AudioOptions, bitrate_choices
from .process import ToolMissing
from .sources import BadSource, classify, default_output_dir, parse_timestamp, safe_filename

STATIC_DIR = Path(__file__).parent / "static"
STATIC_FILES = {
    "/index.html": "text/html; charset=utf-8",
    "/app.css": "text/css; charset=utf-8",
    "/app.js": "text/javascript; charset=utf-8",
}
ALLOWED_HOSTNAMES = {"127.0.0.1", "localhost", "::1", "[::1]"}
LOOPBACK_BINDS = {"127.0.0.1", "localhost", "::1"}
MAX_JSON_BODY = 256 * 1024
MAX_UPLOAD = 8 * 1024 * 1024 * 1024  # 8 GiB; it is a local disk-to-disk copy
CHUNK = 256 * 1024


class _Handler(BaseHTTPRequestHandler):
    server_version = "vxpress"
    sys_version = ""
    queue: JobQueue
    token: str
    upload_dir: Path
    quiet: bool = False

    # -- plumbing -------------------------------------------------------
    def log_message(self, fmt: str, *args) -> None:  # noqa: D102
        if self.quiet:
            return
        sys.stderr.write(f"[vxpress] {self.address_string()} {fmt % args}\n")

    def _headers(self, status: int, content_type: str, length: int, extra: dict | None = None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; "
            "img-src 'self' data:; form-action 'none'; frame-ancestors 'none'",
        )
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()

    def _send(self, status: int, body: bytes, content_type: str, extra: dict | None = None) -> None:
        self._headers(status, content_type, len(body), extra)
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status: int, payload) -> None:
        self._send(status, json.dumps(payload, default=str).encode(), "application/json")

    def _error(self, status: int, message: str) -> None:
        self._json(status, {"error": message})

    def _host_is_local(self) -> bool:
        host = (self.headers.get("Host") or "").strip()
        if host.startswith("["):
            return host.split("]", 1)[0] + "]" in ALLOWED_HOSTNAMES
        hostname = host.rsplit(":", 1)[0] if ":" in host else host
        return hostname in ALLOWED_HOSTNAMES

    def _origin_ok(self) -> bool:
        origin = self.headers.get("Origin")
        if not origin:
            return True
        hostname = urlparse(origin).hostname
        return hostname in ALLOWED_HOSTNAMES

    def _token_ok(self, query: dict) -> bool:
        supplied = self.headers.get("X-VXpress-Token") or (query.get("token") or [""])[0]
        return bool(supplied) and hmac.compare_digest(supplied, self.token)

    def _guard(self, query: dict, writing: bool) -> bool:
        if not self._host_is_local():
            self._error(HTTPStatus.FORBIDDEN, "VXpress only answers to localhost")
            return False
        if writing and not self._origin_ok():
            self._error(HTTPStatus.FORBIDDEN, "cross-origin request refused")
            return False
        if not self._token_ok(query):
            self._error(HTTPStatus.UNAUTHORIZED, "missing or invalid access token")
            return False
        return True

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        if length > MAX_JSON_BODY:
            raise BadSource("request body too large")
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise BadSource(f"invalid JSON body: {exc}") from exc
        if not isinstance(payload, dict):
            raise BadSource("request body must be a JSON object")
        return payload

    # -- GET ------------------------------------------------------------
    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path, query = unquote(parsed.path), parse_qs(parsed.query)

        if not self._host_is_local():
            return self._error(HTTPStatus.FORBIDDEN, "VXpress only answers to localhost")
        if path in ("/", "/index.html"):
            return self._serve_static("index.html")
        if path in STATIC_FILES:
            return self._serve_static(path.lstrip("/"))
        if path == "/healthz":
            return self._json(HTTPStatus.OK, {"ok": True, "service": "vxpress"})
        if not path.startswith("/api/"):
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        if not self._guard(query, writing=False):
            return None

        try:
            return self._api_get(path, query)
        except BadSource as exc:
            return self._error(HTTPStatus.BAD_REQUEST, str(exc))
        except ToolMissing as exc:
            return self._error(HTTPStatus.SERVICE_UNAVAILABLE, str(exc))
        except (download.DownloadError, media.ConversionError) as exc:
            return self._error(HTTPStatus.BAD_GATEWAY, str(exc))
        except LookupError as exc:
            return self._error(HTTPStatus.NOT_FOUND, str(exc))

    def _api_get(self, path: str, query: dict):
        if path == "/api/env":
            tools = self.queue.tools
            return self._json(
                HTTPStatus.OK,
                {
                    "tools": tools.to_dict(),
                    "video_dir": str(default_output_dir("video")),
                    "audio_dir": str(default_output_dir("mp3")),
                    "bitrates": list(bitrate_choices()),
                    "default_bitrate": media.DEFAULT_BITRATE,
                    "version": __import__("vxpress").__version__,
                },
            )

        if path == "/api/jobs":
            return self._json(
                HTTPStatus.OK, {"jobs": [job.to_dict() for job in self.queue.list_jobs(200)]}
            )

        if path.startswith("/api/jobs/"):
            job_id = path[len("/api/jobs/") :]
            return self._json(HTTPStatus.OK, self.queue.get(job_id).to_dict())

        if path == "/api/info":
            target = (query.get("target") or [""])[0]
            source = classify(target)
            if source.is_url:
                details = download.info(source.value, self.queue.tools)
                return self._json(HTTPStatus.OK, {"kind": "url", "info": details.to_dict()})
            details = media.probe(source.value, self.queue.tools)
            return self._json(HTTPStatus.OK, {"kind": "file", "info": details.to_dict()})

        if path == "/api/history":
            limit = int((query.get("limit") or ["25"])[0])
            return self._json(
                HTTPStatus.OK,
                {
                    "entries": jobs_module.read_history(
                        max(1, min(limit, 500)), self.queue.history
                    )
                },
            )

        if path.startswith("/api/download/"):
            return self._serve_output(path[len("/api/download/") :])

        return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")

    # -- POST -------------------------------------------------------------
    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path, query = unquote(parsed.path), parse_qs(parsed.query)
        if not self._guard(query, writing=True):
            return None

        try:
            if path == "/api/jobs":
                return self._create_job(self._body())
            if path == "/api/upload":
                return self._receive_upload()
            if path == "/api/jobs/clear":
                return self._json(HTTPStatus.OK, {"removed": self.queue.clear_finished()})
            if path.endswith("/cancel") and path.startswith("/api/jobs/"):
                job_id = path[len("/api/jobs/") : -len("/cancel")]
                return self._json(HTTPStatus.OK, self.queue.cancel(job_id).to_dict())
        except BadSource as exc:
            return self._error(HTTPStatus.BAD_REQUEST, str(exc))
        except ToolMissing as exc:
            return self._error(HTTPStatus.SERVICE_UNAVAILABLE, str(exc))
        except LookupError as exc:
            return self._error(HTTPStatus.NOT_FOUND, str(exc))
        return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")

    def _create_job(self, payload: dict):
        kind = str(payload.get("kind") or "").strip()
        target = str(payload.get("target") or "").strip()
        options = AudioOptions(
            bitrate=int(payload.get("bitrate") or media.DEFAULT_BITRATE),
            vbr=int(payload["vbr"]) if payload.get("vbr") not in (None, "") else None,
            start=parse_timestamp(payload.get("start")),
            end=parse_timestamp(payload.get("end")),
            metadata=bool(payload.get("metadata", True)),
            thumbnail=bool(payload.get("thumbnail", False)),
        )
        max_height = payload.get("max_height")
        job = self.queue.submit(
            kind,
            target,
            out_dir=payload.get("out_dir") or None,
            options=options,
            max_height=int(max_height) if max_height else None,
            container=str(payload.get("container") or "mp4"),
            playlist=bool(payload.get("playlist", False)),
        )
        return self._json(HTTPStatus.ACCEPTED, job.to_dict())

    def _receive_upload(self):
        """Take a file the browser sent and park it where a job can read it."""
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            raise BadSource("no file content was sent")
        if length > MAX_UPLOAD:
            raise BadSource("that file is too large to send through the browser")
        raw_name = self.headers.get("X-Filename") or "upload"
        name = safe_filename(unquote(raw_name), fallback="upload")
        target = self.upload_dir / name
        if target.exists():
            target = self.upload_dir / f"{secrets.token_hex(4)}-{name}"

        written = 0
        try:
            with target.open("wb") as handle:
                while written < length:
                    chunk = self.rfile.read(min(CHUNK, length - written))
                    if not chunk:
                        break
                    handle.write(chunk)
                    written += len(chunk)
        except OSError as exc:
            raise BadSource(f"could not save the upload: {exc}") from exc
        if written != length:
            target.unlink(missing_ok=True)
            raise BadSource("the upload was cut short")
        return self._json(
            HTTPStatus.CREATED, {"path": str(target), "name": name, "size": written}
        )

    # -- files ------------------------------------------------------------
    def _serve_output(self, job_id: str):
        job = self.queue.get(job_id)
        if job.status != "done" or not job.output_path:
            return self._error(HTTPStatus.CONFLICT, "that job has not produced a file (yet)")
        path = Path(job.output_path)
        allowed = Path(job.out_dir).resolve()
        try:
            resolved = path.resolve()
            resolved.relative_to(allowed)
        except (OSError, ValueError):
            return self._error(HTTPStatus.FORBIDDEN, "that file is outside the output folder")
        if not resolved.is_file():
            return self._error(HTTPStatus.NOT_FOUND, "the file is no longer there")

        content_type = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
        size = resolved.stat().st_size
        ascii_name = re.sub(r'[^A-Za-z0-9._ -]', "_", resolved.name) or "download"
        disposition = (
            f'attachment; filename="{ascii_name}"; '
            f"filename*=UTF-8''{quote(resolved.name)}"
        )
        self._headers(HTTPStatus.OK, content_type, size, {"Content-Disposition": disposition})
        if self.command == "HEAD":
            return None
        with resolved.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile, CHUNK)
        return None

    def _serve_static(self, name: str) -> None:
        content_type = STATIC_FILES.get("/" + name)
        candidate = (STATIC_DIR / name).resolve()
        if not content_type or not candidate.is_file():
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        self._send(HTTPStatus.OK, candidate.read_bytes(), content_type)


def build_server(
    queue: JobQueue,
    host: str = "127.0.0.1",
    port: int = 8799,
    token: str | None = None,
    quiet: bool = False,
    upload_dir: Path | None = None,
) -> tuple[ThreadingHTTPServer, str]:
    if host not in LOOPBACK_BINDS:
        raise BadSource(
            f"refusing to bind {host!r}: VXpress serves the machine it runs on, "
            "so it listens on loopback only"
        )
    token = token or secrets.token_urlsafe(24)
    uploads = Path(upload_dir) if upload_dir else Path(tempfile.mkdtemp(prefix="vxpress-upload-"))
    uploads.mkdir(parents=True, exist_ok=True)

    handler = type(
        "VXpressHandler",
        (_Handler,),
        {"queue": queue, "token": token, "upload_dir": uploads, "quiet": quiet},
    )
    server_class = ThreadingHTTPServer
    if ":" in host:
        server_class = type(
            "ThreadingHTTPServerV6", (ThreadingHTTPServer,), {"address_family": socket.AF_INET6}
        )
    httpd = server_class((host, port), handler)
    httpd.daemon_threads = True
    return httpd, token


def serve(
    queue: JobQueue,
    host: str = "127.0.0.1",
    port: int = 8799,
    open_browser: bool = False,
    token: str | None = None,
) -> int:
    try:
        httpd, token = build_server(queue, host=host, port=port, token=token)
    except OSError as exc:
        print(f"vxpress: cannot listen on {host}:{port}: {exc}", file=sys.stderr)
        return 1
    except BadSource as exc:
        print(f"vxpress: {exc}", file=sys.stderr)
        return 2

    bound_host, bound_port = httpd.server_address[0], httpd.server_address[1]
    display = f"[{bound_host}]" if ":" in str(bound_host) else bound_host
    url = f"http://{display}:{bound_port}/?token={token}"
    tools = queue.tools

    print(f"Vortex VXpress  {url}")
    print(
        f"  yt-dlp: {tools.ytdlp.version or 'missing'}   "
        f"ffmpeg: {tools.ffmpeg.version or 'missing'}"
    )
    print(f"  files land in {default_output_dir('video')} and {default_output_dir('mp3')}")
    print("  loopback only; the token above is required for every request. Ctrl-C to stop.")
    if not tools.ytdlp.found:
        print(f"  note: links cannot be fetched until yt-dlp is installed ({tools.ytdlp.hint})")
    if not tools.ffmpeg.found:
        print(f"  note: mp3 output needs ffmpeg ({tools.ffmpeg.hint})")

    if open_browser:
        threading.Timer(0.4, lambda: webbrowser.open(url)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nvxpress: stopped")
    finally:
        httpd.server_close()
        queue.shutdown(wait=False)
    return 0
