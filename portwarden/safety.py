"""Guard rails: which services portwarden refuses to touch without --force."""

from __future__ import annotations

import fnmatch
import re

# Stopping or disabling any of these can cut you off from the machine, break
# login, or take the network down. They are refused unless the caller passes
# an explicit override.
PROTECTED_PATTERNS: dict[str, tuple[str, ...]] = {
    "systemd": (
        "systemd-*",
        "ssh",
        "sshd",
        "ssh.socket",
        "dbus",
        "dbus-broker",
        "polkit",
        "NetworkManager*",
        "networking",
        "systemd-networkd*",
        "systemd-resolved",
        "wpa_supplicant",
        "firewalld",
        "ufw",
        "nftables",
        "iptables",
        "getty@*",
        "serial-getty@*",
        "user@*",
        "init.scope",
        "rc-local",
        "dracut*",
        "auditd",
        "udisks2",
        "accounts-daemon",
        "gdm",
        "sddm",
        "lightdm",
        "display-manager",
    ),
    "windows": (
        "RpcSs",
        "RpcEptMapper",
        "DcomLaunch",
        "LSM",
        "EventLog",
        "Dhcp",
        "Dnscache",
        "nsi",
        "BFE",
        "MpsSvc",
        "WinDefend",
        "SecurityHealthService",
        "CryptSvc",
        "gpsvc",
        "Winmgmt",
        "TermService",
        "LanmanServer",
        "LanmanWorkstation",
        "Power",
        "PlugPlay",
        "Schedule",
        "SamSs",
        "UserManager",
        "ProfSvc",
        "NlaSvc",
        "netprofm",
        "WlanSvc",
        "Netman",
        "wuauserv",
        "TrustedInstaller",
    ),
    "launchd": (
        "com.apple.*",
        "system/com.apple.*",
    ),
}

# Matched against the unit/service name with the backend suffix stripped.
_SUFFIXES = (".service", ".socket", ".timer", ".target", ".mount", ".path")


class ProtectedService(Exception):
    """Raised when an action would hit a protected service."""

    def __init__(self, name: str, pattern: str) -> None:
        super().__init__(
            f"{name!r} is protected (matches {pattern!r}); "
            "pass --force (CLI) or tick 'allow protected' (web UI) to override"
        )
        self.name = name
        self.pattern = pattern


DESTRUCTIVE_ACTIONS = frozenset({"stop", "disable", "restart"})


def normalize(name: str) -> str:
    base = name.strip()
    # launchd names arrive as "system/com.apple.foo"; keep both forms matchable.
    for suffix in _SUFFIXES:
        if base.endswith(suffix):
            base = base[: -len(suffix)]
            break
    return base


def match_protected(name: str, backend: str) -> str | None:
    """Return the pattern a service matches, or None when it is unprotected."""
    patterns = PROTECTED_PATTERNS.get(backend, ())
    candidates = {normalize(name), name.strip()}
    tail = name.rsplit("/", 1)[-1]
    candidates.add(normalize(tail))
    for pattern in patterns:
        for candidate in candidates:
            if fnmatch.fnmatch(candidate, pattern) or fnmatch.fnmatch(
                candidate.lower(), pattern.lower()
            ):
                return pattern
    return None


def is_protected(name: str, backend: str) -> bool:
    return match_protected(name, backend) is not None


def check(name: str, backend: str, action: str, force: bool = False) -> None:
    """Raise ProtectedService when ``action`` on ``name`` needs an override."""
    if action not in DESTRUCTIVE_ACTIONS or force:
        return
    pattern = match_protected(name, backend)
    if pattern:
        raise ProtectedService(name, pattern)


_VALID_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@:+/\\-]{0,127}$")


def validate_name(name: str) -> str:
    """Reject anything that is not a plausible service identifier.

    Names reach the process layer as argv entries (never a shell), but keeping
    them to this shape stops option injection such as ``--now`` or ``-f``.
    """
    name = (name or "").strip()
    if not _VALID_NAME.match(name):
        raise ValueError(f"not a valid service name: {name!r}")
    if ".." in name:
        raise ValueError(f"not a valid service name: {name!r}")
    return name
