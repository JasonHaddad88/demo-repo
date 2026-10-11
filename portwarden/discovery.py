"""Finds out which local ports are open and which process owns each one.

Two independent views are combined:

* the kernel's socket table (``ss``, ``netstat``, ``lsof`` or ``/proc/net/*``),
  which gives the owning pid and therefore the owning service;
* an optional TCP connect scan of 127.0.0.1, which proves a port actually
  accepts connections even when the socket table could not be read.
"""

from __future__ import annotations

import os
import platform
import re
import socket
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Iterable, Sequence

from .runner import Runner, RunnerType

LOOPBACK_HOSTS = ("127.0.0.1", "::1", "localhost")

# Ports worth checking when the user does not ask for a specific range:
# the well-known range plus the usual development and database suspects.
DEFAULT_SCAN_PORTS = "1-1024,1433,1521,2375,2376,3000-3010,3306,4000,4200,5000,5432,5601,5672,6379,7000,8000-8100,8443,8787,8888,9000-9010,9090,9200,9300,11211,27017,27018"


@dataclass
class Listener:
    """A socket the machine is listening on."""

    proto: str
    address: str
    port: int
    pids: list[int] = field(default_factory=list)
    processes: list[str] = field(default_factory=list)
    service: str | None = None
    source: str = "socket-table"
    reachable: bool | None = None

    @property
    def process(self) -> str:
        return self.processes[0] if self.processes else "unknown"

    @property
    def pid(self) -> int | None:
        return self.pids[0] if self.pids else None

    @property
    def scope(self) -> str:
        """Where the socket is reachable from."""
        if self.address in ("127.0.0.1", "::1"):
            return "loopback"
        if self.address in ("0.0.0.0", "::", "*"):
            return "all-interfaces"
        return "interface"

    @property
    def key(self) -> str:
        return f"{self.proto}/{self.address}:{self.port}"

    def to_dict(self) -> dict:
        return {
            "proto": self.proto,
            "address": self.address,
            "port": self.port,
            "pids": self.pids,
            "processes": self.processes,
            "process": self.process,
            "pid": self.pid,
            "service": self.service,
            "scope": self.scope,
            "source": self.source,
            "reachable": self.reachable,
            "key": self.key,
        }


def parse_port_spec(spec: str) -> list[int]:
    """Turn ``"22,80,8000-8010"`` into a sorted list of port numbers."""
    ports: set[int] = set()
    for chunk in str(spec).replace(" ", "").split(","):
        if not chunk:
            continue
        if "-" in chunk.lstrip("-"):
            low, _, high = chunk.partition("-")
            start, end = int(low), int(high)
            if start > end:
                start, end = end, start
            if not (0 < start <= 65535 and 0 < end <= 65535):
                raise ValueError(f"port range out of bounds: {chunk}")
            ports.update(range(start, end + 1))
        else:
            port = int(chunk)
            if not 0 < port <= 65535:
                raise ValueError(f"port out of bounds: {port}")
            ports.add(port)
    return sorted(ports)


def _split_host_port(text: str) -> tuple[str, int] | None:
    """Split ``127.0.0.1:631``, ``[::1]:631``, ``*:80`` or ``%lo:53``."""
    text = text.strip()
    if text.startswith("["):
        host, _, rest = text[1:].partition("]")
        port_text = rest.lstrip(":")
    else:
        host, _, port_text = text.rpartition(":")
    if not port_text:
        return None
    host = host or "*"
    if "%" in host:  # ss writes link-local scopes as fe80::1%eth0
        host = host.split("%", 1)[0] or "*"
    if host == "*":
        host = "0.0.0.0"
    try:
        port = int(port_text)
    except ValueError:
        return None
    return host, port


_SS_USERS = re.compile(r'\("(?P<name>[^"]*)",pid=(?P<pid>\d+)')


def parse_ss(text: str) -> list[Listener]:
    """Parse ``ss -H -tulpn`` output (Linux)."""
    listeners: list[Listener] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 5:
            continue
        proto, state = parts[0].lower(), parts[1].upper()
        if proto not in ("tcp", "udp"):
            continue
        if proto == "tcp" and state != "LISTEN":
            continue
        if proto == "udp" and state not in ("UNCONN", "LISTEN"):
            continue
        hostport = _split_host_port(parts[4])
        if not hostport:
            continue
        users = _SS_USERS.findall(line)
        listeners.append(
            Listener(
                proto=proto,
                address=hostport[0],
                port=hostport[1],
                pids=[int(pid) for _, pid in users],
                processes=[name for name, _ in users],
            )
        )
    return listeners


def parse_netstat(text: str) -> list[Listener]:
    """Parse ``netstat -ano`` output (Windows)."""
    listeners: list[Listener] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 4 or parts[0].upper() not in ("TCP", "UDP"):
            continue
        proto = parts[0].lower()
        if proto == "tcp":
            if len(parts) < 5 or parts[3].upper() != "LISTENING":
                continue
            pid_text = parts[4]
        else:
            pid_text = parts[-1]
        hostport = _split_host_port(parts[1])
        if not hostport:
            continue
        try:
            pid = int(pid_text)
        except ValueError:
            continue
        listeners.append(
            Listener(proto=proto, address=hostport[0], port=hostport[1], pids=[pid])
        )
    return listeners


def parse_lsof(text: str) -> list[Listener]:
    """Parse ``lsof -nP -iTCP -sTCP:LISTEN`` / ``-iUDP`` output (macOS, BSD)."""
    listeners: list[Listener] = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 9 or parts[0] == "COMMAND":
            continue
        proto = parts[7].lower()
        if proto not in ("tcp", "udp"):
            continue
        name = parts[8]
        if proto == "tcp" and "(LISTEN)" not in line:
            continue
        if "->" in name:  # an established connection, not a listener
            continue
        hostport = _split_host_port(name)
        if not hostport:
            continue
        try:
            pid = int(parts[1])
        except ValueError:
            continue
        listeners.append(
            Listener(
                proto=proto,
                address=hostport[0],
                port=hostport[1],
                pids=[pid],
                processes=[parts[0]],
            )
        )
    return listeners


def _hex_to_address(hexaddr: str) -> str:
    """Decode the address column of /proc/net/{tcp,udp}{,6}."""
    raw = bytes.fromhex(hexaddr)
    if len(raw) == 4:
        return socket.inet_ntop(socket.AF_INET, raw[::-1])
    if len(raw) == 16:
        words = [raw[i : i + 4][::-1] for i in range(0, 16, 4)]
        return socket.inet_ntop(socket.AF_INET6, b"".join(words))
    return hexaddr


def _socket_inode_owners() -> dict[str, tuple[int, str]]:
    """Map socket inode -> (pid, process name) by walking /proc/<pid>/fd."""
    owners: dict[str, tuple[int, str]] = {}
    try:
        pids = [entry for entry in os.listdir("/proc") if entry.isdigit()]
    except OSError:
        return owners
    for pid in pids:
        fd_dir = f"/proc/{pid}/fd"
        try:
            fds = os.listdir(fd_dir)
        except OSError:
            continue  # not ours to read
        name = ""
        for fd in fds:
            try:
                target = os.readlink(f"{fd_dir}/{fd}")
            except OSError:
                continue
            if not target.startswith("socket:["):
                continue
            inode = target[8:-1]
            if not name:
                try:
                    with open(f"/proc/{pid}/comm", encoding="utf-8", errors="replace") as fh:
                        name = fh.read().strip()
                except OSError:
                    name = "unknown"
            owners.setdefault(inode, (int(pid), name))
    return owners


def parse_proc_net(text: str, proto: str, owners: dict[str, tuple[int, str]]) -> list[Listener]:
    """Parse one /proc/net/{tcp,udp}{,6} table."""
    listening_state = "0A" if proto == "tcp" else "07"
    listeners: list[Listener] = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 10:
            continue
        if parts[3].upper() != listening_state:
            continue
        hexaddr, _, hexport = parts[1].partition(":")
        try:
            port = int(hexport, 16)
        except ValueError:
            continue
        owner = owners.get(parts[9])
        listeners.append(
            Listener(
                proto=proto,
                address=_hex_to_address(hexaddr),
                port=port,
                pids=[owner[0]] if owner else [],
                processes=[owner[1]] if owner else [],
                source="proc",
            )
        )
    return listeners


def _read_proc_net() -> list[Listener]:
    owners = _socket_inode_owners()
    listeners: list[Listener] = []
    for path, proto in (
        ("/proc/net/tcp", "tcp"),
        ("/proc/net/tcp6", "tcp"),
        ("/proc/net/udp", "udp"),
        ("/proc/net/udp6", "udp"),
    ):
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                listeners.extend(parse_proc_net(fh.read(), proto, owners))
        except OSError:
            continue
    return listeners


def _dedupe(listeners: Iterable[Listener]) -> list[Listener]:
    merged: dict[tuple[str, str, int], Listener] = {}
    for item in listeners:
        key = (item.proto, item.address, item.port)
        existing = merged.get(key)
        if existing is None:
            merged[key] = item
            continue
        names = item.processes + ["unknown"] * (len(item.pids) - len(item.processes))
        for pid, name in zip(item.pids, names):
            if pid not in existing.pids:
                existing.pids.append(pid)
                existing.processes.append(name)
    return sorted(merged.values(), key=lambda l: (l.port, l.proto, l.address))


def listening_sockets(run: RunnerType | None = None, system: str | None = None) -> list[Listener]:
    """Every TCP/UDP socket this machine is listening on."""
    run = run or Runner()
    system = (system or platform.system()).lower()

    if system == "windows":
        result = run(["netstat", "-ano"])
        return _dedupe(parse_netstat(result.stdout) if result.ok else [])

    if system == "linux":
        result = run(["ss", "-H", "-tulpn"])
        if result.ok and result.stdout.strip():
            return _dedupe(parse_ss(result.stdout))
        found = _read_proc_net()
        if found:
            return _dedupe(found)

    # macOS, BSD, or Linux without `ss`.
    collected: list[Listener] = []
    for argv in (
        ["lsof", "-nP", "-iTCP", "-sTCP:LISTEN"],
        ["lsof", "-nP", "-iUDP"],
    ):
        result = run(argv)
        if result.ok:
            collected.extend(parse_lsof(result.stdout))
    if collected:
        return _dedupe(collected)
    if system == "linux":
        return _dedupe(_read_proc_net())
    return []


def probe_port(port: int, host: str = "127.0.0.1", timeout: float = 0.25) -> bool:
    """True when a TCP connection to ``host:port`` is accepted."""
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        try:
            return sock.connect_ex((host, port)) == 0
        except OSError:
            return False


def scan_ports(
    ports: Sequence[int],
    host: str = "127.0.0.1",
    timeout: float = 0.25,
    workers: int = 256,
) -> list[int]:
    """TCP connect scan of the given ports on ``host``. Loopback only."""
    if host not in LOOPBACK_HOSTS:
        raise ValueError(
            f"portwarden only scans the local machine; {host!r} is not a loopback address"
        )
    ports = list(dict.fromkeys(int(p) for p in ports))
    if not ports:
        return []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(ports)))) as pool:
        hits = pool.map(lambda p: (p, probe_port(p, host, timeout)), ports)
        return sorted(port for port, is_open in hits if is_open)


def inventory(
    ports: Sequence[int] | None = None,
    host: str = "127.0.0.1",
    timeout: float = 0.25,
    run: RunnerType | None = None,
    system: str | None = None,
) -> list[Listener]:
    """Socket table plus, optionally, a connect scan merged into one list."""
    listeners = listening_sockets(run=run, system=system)
    if ports is None:
        return listeners

    requested = set(ports)
    open_ports = set(scan_ports(ports, host=host, timeout=timeout))
    by_port: dict[int, list[Listener]] = {}
    for item in listeners:
        by_port.setdefault(item.port, []).append(item)

    for port, items in by_port.items():
        for item in items:
            if item.proto == "tcp" and port in requested:
                item.reachable = port in open_ports

    # A port that answers but is missing from the socket table (common when
    # another user owns the process and we are not root) still gets a row.
    for port in sorted(open_ports - set(by_port)):  # answered, but unattributed
        listeners.append(
            Listener(
                proto="tcp",
                address=host,
                port=port,
                source="scan",
                reachable=True,
            )
        )
    return _dedupe(listeners)
