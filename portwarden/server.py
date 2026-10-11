"""Local-only web dashboard.

Hard rules, because this process can stop services:

* the socket binds a loopback address and nothing else;
* every ``/api/*`` request must carry the access token printed at startup;
* the ``Host`` header must be loopback (blocks DNS-rebinding from a web page);
* a cross-origin ``Origin`` header is rejected outright.
"""

from __future__ import annotations

import hmac
import json
import secrets
import socket
import sys
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from . import audit
from .core import PortWarden
from .services import ACTIONS

STATIC_DIR = Path(__file__).parent / "static"
# The only files the dashboard ever serves, with the type each is sent as.
STATIC_FILES = {
    "/index.html": "text/html; charset=utf-8",
    "/app.css": "text/css; charset=utf-8",
    "/app.js": "text/javascript; charset=utf-8",
}
ALLOWED_HOSTNAMES = {"127.0.0.1", "localhost", "::1", "[::1]"}
LOOPBACK_BINDS = {"127.0.0.1", "localhost", "::1"}
MAX_BODY = 64 * 1024


class _Handler(BaseHTTPRequestHandler):
    server_version = "portwarden"
    sys_version = ""
    warden: PortWarden
    token: str
    quiet: bool = False

    # -- plumbing -------------------------------------------------------
    def log_message(self, fmt: str, *args) -> None:  # noqa: D102
        if self.quiet:
            return
        sys.stderr.write(f"[portwarden] {self.address_string()} {fmt % args}\n")

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; "
            "img-src 'self' data:; form-action 'none'; frame-ancestors 'none'",
        )
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status: int, payload) -> None:
        self._send(status, json.dumps(payload, default=str).encode(), "application/json")

    def _error(self, status: int, message: str) -> None:
        self._json(status, {"error": message})

    def _host_is_local(self) -> bool:
        host = (self.headers.get("Host") or "").strip()
        hostname = host.rsplit(":", 1)[0] if host.count(":") == 1 else host
        if host.startswith("["):
            hostname = host.split("]", 1)[0] + "]"
        return hostname in ALLOWED_HOSTNAMES

    def _origin_ok(self) -> bool:
        origin = self.headers.get("Origin")
        if not origin:
            return True
        parsed = urlparse(origin)
        return parsed.hostname in ALLOWED_HOSTNAMES or f"[{parsed.hostname}]" in ALLOWED_HOSTNAMES

    def _token_ok(self, query: dict[str, list[str]]) -> bool:
        supplied = self.headers.get("X-PortWarden-Token") or ""
        if not supplied:
            supplied = (query.get("token") or [""])[0]
        return bool(supplied) and hmac.compare_digest(supplied, self.token)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        if length > MAX_BODY:
            raise ValueError("request body too large")
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid JSON body: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError("request body must be a JSON object")
        return payload

    # -- routing --------------------------------------------------------
    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        query = parse_qs(parsed.query)

        if not self._host_is_local():
            return self._error(HTTPStatus.FORBIDDEN, "portwarden only answers to localhost")

        if path in ("/", "/index.html"):
            return self._serve_static("index.html")
        if path in STATIC_FILES:
            return self._serve_static(path.lstrip("/"))
        if path == "/healthz":
            return self._json(HTTPStatus.OK, {"ok": True, "service": "portwarden"})

        if not path.startswith("/api/"):
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        if not self._token_ok(query):
            return self._error(HTTPStatus.UNAUTHORIZED, "missing or invalid access token")

        try:
            return self._api_get(path, query)
        except ValueError as exc:
            return self._error(HTTPStatus.BAD_REQUEST, str(exc))
        except LookupError as exc:
            return self._error(HTTPStatus.NOT_FOUND, str(exc))

    def _api_get(self, path: str, query: dict[str, list[str]]) -> None:
        warden = self.warden
        if path == "/api/env":
            return self._json(HTTPStatus.OK, warden.environment())

        if path == "/api/ports":
            scan = (query.get("scan") or [""])[0].strip()
            timeout = float((query.get("timeout") or ["0.25"])[0])
            rows = warden.ports(scan=scan or None, timeout=max(0.01, min(timeout, 5.0)))
            return self._json(
                HTTPStatus.OK,
                {"ports": [row.to_dict() for row in rows], "scanned": bool(scan)},
            )

        if path == "/api/services":
            items = warden.services()
            needle = (query.get("filter") or [""])[0].lower()
            if needle:
                items = [
                    s for s in items if needle in s.name.lower() or needle in s.display.lower()
                ]
            if (query.get("all") or ["0"])[0] not in ("1", "true", "yes"):
                items = [s for s in items if s.state == "running" or s.enabled]
            return self._json(HTTPStatus.OK, {"services": [s.to_dict() for s in items]})

        if path.startswith("/api/service/"):
            name = path[len("/api/service/") :]
            return self._json(HTTPStatus.OK, warden.status(name).to_dict())

        if path == "/api/audit":
            limit = int((query.get("limit") or ["25"])[0])
            return self._json(
                HTTPStatus.OK, {"entries": audit.tail(max(1, min(limit, 500)), warden.audit_path)}
            )

        return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        query = parse_qs(parsed.query)

        if not self._host_is_local():
            return self._error(HTTPStatus.FORBIDDEN, "portwarden only answers to localhost")
        if not self._origin_ok():
            return self._error(HTTPStatus.FORBIDDEN, "cross-origin request refused")
        if not self._token_ok(query):
            return self._error(HTTPStatus.UNAUTHORIZED, "missing or invalid access token")
        if path != "/api/control":
            return self._error(HTTPStatus.NOT_FOUND, "no such endpoint")

        try:
            payload = self._body()
            action = str(payload.get("action", "")).strip()
            if action not in ACTIONS:
                raise ValueError(f"action must be one of {', '.join(ACTIONS)}")
            force = bool(payload.get("force"))
            service = payload.get("service")
            port = payload.get("port")

            if service:
                results = [self.warden.control(str(service), action, force=force)]
            elif port is not None:
                results = self.warden.control_port(int(port), action, force=force)
            else:
                raise ValueError("pass either a service name or a port number")
        except ValueError as exc:
            return self._error(HTTPStatus.BAD_REQUEST, str(exc))

        status = HTTPStatus.OK if all(r.ok for r in results) else HTTPStatus.CONFLICT
        return self._json(status, {"results": [r.to_dict() for r in results]})

    def _serve_static(self, name: str) -> None:
        content_type = STATIC_FILES.get("/" + name)
        candidate = (STATIC_DIR / name).resolve()
        if not content_type or not candidate.is_file():
            return self._error(HTTPStatus.NOT_FOUND, "not found")
        self._send(HTTPStatus.OK, candidate.read_bytes(), content_type)


def build_server(
    warden: PortWarden,
    host: str = "127.0.0.1",
    port: int = 8787,
    token: str | None = None,
    quiet: bool = False,
) -> tuple[ThreadingHTTPServer, str]:
    if host not in LOOPBACK_BINDS:
        raise ValueError(
            f"refusing to bind {host!r}: portwarden can control services, so it listens on "
            "loopback only"
        )
    token = token or secrets.token_urlsafe(24)

    handler = type(
        "PortWardenHandler", (_Handler,), {"warden": warden, "token": token, "quiet": quiet}
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
    warden: PortWarden,
    host: str = "127.0.0.1",
    port: int = 8787,
    open_browser: bool = False,
    token: str | None = None,
) -> int:
    try:
        httpd, token = build_server(warden, host=host, port=port, token=token)
    except OSError as exc:
        print(f"portwarden: cannot listen on {host}:{port}: {exc}", file=sys.stderr)
        return 1

    bound_host, bound_port = httpd.server_address[0], httpd.server_address[1]
    display_host = f"[{bound_host}]" if ":" in str(bound_host) else bound_host
    url = f"http://{display_host}:{bound_port}/?token={token}"

    env = warden.environment()
    print(f"portwarden dashboard  {url}")
    print(f"  backend: {env['backend']} ({env['scope']})   dry-run: {env['dry_run']}")
    print(f"  audit log: {env['audit_log']}")
    print("  loopback only; the token above is required for every API call. Ctrl-C to stop.")

    if open_browser:
        threading.Timer(0.4, lambda: webbrowser.open(url)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nportwarden: stopped")
    finally:
        httpd.server_close()
    return 0
