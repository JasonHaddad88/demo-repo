"""Command line interface for portwarden."""

from __future__ import annotations

import argparse
import json
import sys
from typing import Sequence

from . import discovery, safety
from .core import PortWarden, PortRow
from .services import ACTIONS, Service

PROGRAM = "portwarden"


# -- rendering ----------------------------------------------------------
def _table(rows: list[list[str]], headers: list[str]) -> str:
    widths = [len(h) for h in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(cell))
    line = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers)).rstrip()
    rule = "  ".join("-" * widths[i] for i in range(len(headers)))
    body = [
        "  ".join(cell.ljust(widths[i]) for i, cell in enumerate(row)).rstrip() for row in rows
    ]
    return "\n".join([line, rule, *body])


def _yes_no(value: bool | None) -> str:
    return {True: "yes", False: "no", None: "?"}[value]


def _port_rows(rows: list[PortRow]) -> str:
    if not rows:
        return "No listening sockets found."
    # The probe column only appears when a connect scan actually ran.
    probed = any(row.listener.reachable is not None for row in rows)
    table = []
    for row in rows:
        listener, service = row.listener, row.service
        cells = [
            str(listener.port),
            listener.proto,
            listener.address,
            listener.scope,
        ]
        if probed:
            cells.append(
                {True: "open", False: "closed", None: "-"}[listener.reachable]
            )
        cells += [
            listener.process + (f" ({listener.pid})" if listener.pid else ""),
            service.name if service else "-",
            _yes_no(service.running) if service else "-",
            _yes_no(service.enabled) if service else "-",
            "protected" if service and service.protected else "",
        ]
        table.append(cells)
    headers = ["PORT", "PROTO", "ADDRESS", "EXPOSURE"]
    if probed:
        headers.append("PROBE")
    headers += ["PROCESS", "SERVICE", "RUN", "BOOT", "FLAGS"]
    return _table(table, headers)


def _service_rows(items: list[Service]) -> str:
    if not items:
        return "No services reported by the service manager."
    table = [
        [
            service.name,
            service.state,
            service.start_mode,
            "protected" if service.protected else "",
            (service.description or service.display)[:60],
        ]
        for service in items
    ]
    return _table(table, ["SERVICE", "STATE", "AT BOOT", "FLAGS", "DESCRIPTION"])


def _dump(data, as_json: bool, text: str) -> None:
    if as_json:
        print(json.dumps(data, indent=2, default=str))
    else:
        print(text)


def _confirm(prompt: str, assume_yes: bool) -> bool:
    if assume_yes:
        return True
    if not sys.stdin or not sys.stdin.isatty():
        print(
            "Refusing to change a service without confirmation; pass --yes when running "
            "non-interactively.",
            file=sys.stderr,
        )
        return False
    try:
        answer = input(f"{prompt} [y/N] ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        return False
    return answer in ("y", "yes")


# -- commands -----------------------------------------------------------
def cmd_scan(args: argparse.Namespace, warden: PortWarden) -> int:
    rows = warden.ports(scan=args.ports, host=args.host, timeout=args.timeout)
    if args.listening_only:
        rows = [row for row in rows if row.listener.source != "scan"]
    _dump([row.to_dict() for row in rows], args.json, _port_rows(rows))
    return 0


def cmd_ports(args: argparse.Namespace, warden: PortWarden) -> int:
    rows = warden.ports(scan=None)
    _dump([row.to_dict() for row in rows], args.json, _port_rows(rows))
    return 0


def cmd_port(args: argparse.Namespace, warden: PortWarden) -> int:
    rows = warden.port(args.port)
    text = _port_rows(rows) if rows else f"Nothing is listening on port {args.port}."
    _dump([row.to_dict() for row in rows], args.json, text)
    return 0 if rows else 1


def cmd_services(args: argparse.Namespace, warden: PortWarden) -> int:
    items = warden.services()
    if args.filter:
        needle = args.filter.lower()
        items = [s for s in items if needle in s.name.lower() or needle in s.display.lower()]
    if not args.all:
        items = [s for s in items if s.state == "running" or s.enabled]
    _dump([s.to_dict() for s in items], args.json, _service_rows(items))
    return 0


def cmd_status(args: argparse.Namespace, warden: PortWarden) -> int:
    try:
        service = warden.status(args.service)
    except (LookupError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    text = "\n".join(
        [
            f"service     {service.name}",
            f"backend     {service.backend} ({service.scope})",
            f"running     {_yes_no(service.running)} ({service.state})",
            f"at boot     {_yes_no(service.enabled)} ({service.start_mode})",
            f"main pid    {service.pid or '-'}",
            f"protected   {'yes' if service.protected else 'no'}",
            f"description {service.description or '-'}",
        ]
    )
    _dump(service.to_dict(), args.json, text)
    return 0


def cmd_action(args: argparse.Namespace, warden: PortWarden) -> int:
    action = args.action
    by_port = args.target.isdigit() and args.by_port
    label = f"port {args.target}" if by_port else args.target

    if action in safety.DESTRUCTIVE_ACTIONS and not warden.dry_run:
        if not _confirm(f"{action} {label}?", args.yes):
            print("Aborted.")
            return 130

    if by_port:
        results = warden.control_port(int(args.target), action, force=args.force)
    else:
        results = [warden.control(args.target, action, force=args.force)]

    if args.json:
        print(json.dumps([r.to_dict() for r in results], indent=2, default=str))
    else:
        for result in results:
            stream = sys.stdout if result.ok else sys.stderr
            mark = "ok" if result.ok else "FAILED"
            print(f"[{mark}] {action} {result.service}: {result.detail}", file=stream)
            after = result.service_after
            if after:
                print(
                    f"        now: running={_yes_no(after.running)} "
                    f"at-boot={_yes_no(after.enabled)}",
                    file=stream,
                )
    return 0 if all(r.ok for r in results) else 1


def cmd_audit(args: argparse.Namespace, warden: PortWarden) -> int:
    from . import audit

    entries = audit.tail(args.limit, path=warden.audit_path)
    if args.json:
        print(json.dumps(entries, indent=2, default=str))
        return 0
    if not entries:
        print(f"No audit entries yet ({audit.default_log_path()}).")
        return 0
    for entry in entries:
        mark = "ok " if entry.get("ok") else "err"
        dry = " [dry-run]" if entry.get("dry_run") else ""
        print(
            f"{entry.get('ts', '?')} {mark} {entry.get('action', '?'):8} "
            f"{entry.get('service', '?')}{dry} {entry.get('detail', '')}".rstrip()
        )
    return 0


def cmd_serve(args: argparse.Namespace, warden: PortWarden) -> int:
    from .server import serve

    return serve(
        warden,
        host=args.bind,
        port=args.port,
        open_browser=args.open,
        token=args.token,
    )


def cmd_env(args: argparse.Namespace, warden: PortWarden) -> int:
    env = warden.environment()
    text = "\n".join(f"{key:20} {value}" for key, value in env.items())
    _dump(env, args.json, text)
    return 0


# -- parser -------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=PROGRAM,
        description=(
            "Inspect the ports your own machine is listening on and switch the "
            "services behind them on or off."
        ),
        epilog="Run `%(prog)s serve` for the web dashboard.",
    )
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument(
        "--scope",
        choices=("system", "user"),
        default="system",
        help="service manager scope (systemd/launchd user units; default: system)",
    )
    parser.add_argument(
        "--sudo",
        dest="sudo",
        action="store_true",
        default=None,
        help="prefix privileged commands with `sudo -n` (default: only when not root)",
    )
    parser.add_argument(
        "--no-sudo", dest="sudo", action="store_false", help="never use sudo"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the command that would run, change nothing",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    scan = subparsers.add_parser("scan", help="connect-scan local ports and map them to services")
    scan.add_argument(
        "--ports",
        default=discovery.DEFAULT_SCAN_PORTS,
        help="ports to probe, e.g. 1-1024,3000,8000-8100 (default: well-known plus common dev ports)",
    )
    scan.add_argument("--host", default="127.0.0.1", help="loopback address to probe")
    scan.add_argument("--timeout", type=float, default=0.25, help="per-port timeout in seconds")
    scan.add_argument(
        "--listening-only",
        action="store_true",
        help="hide ports that answered but could not be attributed to a process",
    )
    scan.set_defaults(func=cmd_scan)

    ports = subparsers.add_parser("ports", help="list listening sockets from the socket table")
    ports.set_defaults(func=cmd_ports)

    port = subparsers.add_parser("port", help="show what holds a single port")
    port.add_argument("port", type=int)
    port.set_defaults(func=cmd_port)

    service_list = subparsers.add_parser("services", help="list services the manager knows about")
    service_list.add_argument("--all", action="store_true", help="include stopped and disabled")
    service_list.add_argument("--filter", help="substring match on name or description")
    service_list.set_defaults(func=cmd_services)

    status = subparsers.add_parser("status", help="show one service in detail")
    status.add_argument("service")
    status.set_defaults(func=cmd_status)

    for action in ACTIONS:
        sub = subparsers.add_parser(action, help=f"{action} a service (or the service on a port)")
        sub.add_argument("target", help="service name, or a port number with --by-port")
        sub.add_argument(
            "--by-port",
            action="store_true",
            help="treat the target as a port number and act on the service holding it",
        )
        sub.add_argument("--force", action="store_true", help="allow protected services")
        sub.add_argument("-y", "--yes", action="store_true", help="skip the confirmation prompt")
        sub.set_defaults(func=cmd_action, action=action)

    audit_cmd = subparsers.add_parser("audit", help="show recent changes portwarden made")
    audit_cmd.add_argument("--limit", type=int, default=25)
    audit_cmd.set_defaults(func=cmd_audit)

    serve = subparsers.add_parser("serve", help="run the local web dashboard")
    serve.add_argument("--port", type=int, default=8787, help="dashboard port (default: 8787)")
    serve.add_argument(
        "--bind",
        default="127.0.0.1",
        help="loopback address to bind (refuses anything routable)",
    )
    serve.add_argument("--open", action="store_true", help="open the dashboard in a browser")
    serve.add_argument("--token", help="use a fixed access token instead of a random one")
    serve.set_defaults(func=cmd_serve)

    env = subparsers.add_parser("env", help="show detected platform, backend and log path")
    env.set_defaults(func=cmd_env)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    warden = PortWarden(scope=args.scope, use_sudo=args.sudo, dry_run=args.dry_run)
    try:
        return args.func(args, warden)
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130
    except ValueError as exc:
        print(f"{PROGRAM}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
