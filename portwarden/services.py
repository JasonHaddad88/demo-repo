"""Service-manager backends: systemd, Windows SCM and launchd.

Every backend speaks the same five verbs (start, stop, restart, enable,
disable) plus read-only listing and status, and can name the service that owns
a pid so a port can be traced back to something controllable.
"""

from __future__ import annotations

import csv
import io
import os
import platform
import re
from dataclasses import dataclass
from typing import Sequence

from . import safety
from .runner import CommandResult, Runner, RunnerType

ACTIONS = ("start", "stop", "restart", "enable", "disable")

RUNNING = "running"
STOPPED = "stopped"
UNKNOWN = "unknown"


@dataclass
class Service:
    name: str
    display: str = ""
    backend: str = UNKNOWN
    scope: str = "system"
    state: str = UNKNOWN
    start_mode: str = UNKNOWN
    description: str = ""
    pid: int | None = None

    @property
    def running(self) -> bool | None:
        if self.state == RUNNING:
            return True
        if self.state == STOPPED:
            return False
        return None

    @property
    def enabled(self) -> bool | None:
        if self.start_mode in ("enabled", "auto", "auto-delayed"):
            return True
        if self.start_mode in ("disabled", "masked"):
            return False
        return None

    @property
    def protected(self) -> bool:
        return safety.is_protected(self.name, self.backend)

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "display": self.display or self.name,
            "backend": self.backend,
            "scope": self.scope,
            "state": self.state,
            "start_mode": self.start_mode,
            "running": self.running,
            "enabled": self.enabled,
            "description": self.description,
            "pid": self.pid,
            "protected": self.protected,
        }


@dataclass
class ActionResult:
    ok: bool
    action: str
    service: str
    backend: str
    detail: str = ""
    argv: tuple[str, ...] = ()
    dry_run: bool = False
    service_after: Service | None = None

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "action": self.action,
            "service": self.service,
            "backend": self.backend,
            "detail": self.detail,
            "command": " ".join(self.argv),
            "dry_run": self.dry_run,
            "service_after": self.service_after.to_dict() if self.service_after else None,
        }


class Backend:
    """Common behaviour for every service manager."""

    name = UNKNOWN
    privileged_hint = ""

    def __init__(
        self,
        run: RunnerType | None = None,
        scope: str = "system",
        use_sudo: bool | None = None,
        dry_run: bool = False,
    ) -> None:
        self.run = run or Runner()
        self.scope = scope
        self.dry_run = dry_run
        self.use_sudo = self._default_sudo() if use_sudo is None else use_sudo

    def _default_sudo(self) -> bool:
        return False

    @staticmethod
    def available(run: RunnerType | None = None) -> bool:
        return False

    # -- read -----------------------------------------------------------
    def list_services(self) -> list[Service]:
        raise NotImplementedError

    def get(self, name: str) -> Service:
        raise NotImplementedError

    def service_for_pid(self, pid: int) -> str | None:
        return None

    # -- write ----------------------------------------------------------
    def _argv_for(self, action: str, name: str) -> list[str]:
        raise NotImplementedError

    def control(self, name: str, action: str, force: bool = False) -> ActionResult:
        if action not in ACTIONS:
            raise ValueError(f"unknown action: {action!r} (expected one of {', '.join(ACTIONS)})")
        name = safety.validate_name(name)
        safety.check(name, self.name, action, force=force)

        argv = self._argv_for(action, name)
        if self.dry_run:
            return ActionResult(
                ok=True,
                action=action,
                service=name,
                backend=self.name,
                detail="dry run: nothing was changed",
                argv=tuple(argv),
                dry_run=True,
            )

        result = self.run(argv)
        detail = "" if result.ok else result.message
        if not result.ok and self._looks_like_permission_error(result):
            detail = f"{detail} — {self.privileged_hint}".strip(" —")
        after: Service | None = None
        try:
            after = self.get(name)
        except Exception:  # noqa: BLE001 - status read-back is best effort
            after = None
        return ActionResult(
            ok=result.ok,
            action=action,
            service=name,
            backend=self.name,
            detail=detail or self._describe_success(action, name),
            argv=tuple(argv),
            service_after=after,
        )

    def _describe_success(self, action: str, name: str) -> str:
        return f"{action} {name}: ok"

    @staticmethod
    def _looks_like_permission_error(result: CommandResult) -> bool:
        text = (result.stderr + result.stdout).lower()
        needles = (
            "access denied",
            "permission denied",
            "interactive authentication required",
            "must be root",
            "operation not permitted",
            "a password is required",
        )
        return any(needle in text for needle in needles)

    def _privileged(self, argv: Sequence[str]) -> list[str]:
        if self.use_sudo:
            return ["sudo", "-n", *argv]
        return list(argv)


class SystemdBackend(Backend):
    name = "systemd"
    privileged_hint = "run portwarden with sudo, or pass --sudo to use a non-interactive sudo"

    def _default_sudo(self) -> bool:
        return self.scope == "system" and hasattr(os, "geteuid") and os.geteuid() != 0

    @staticmethod
    def available(
        run: RunnerType | None = None, runtime_dir: str | None = "/run/systemd/system"
    ) -> bool:
        """True when systemd is installed *and* running as init.

        ``systemctl`` is often present in containers and WSL where systemd is
        not PID 1; there it only ever answers "System has not been booted with
        systemd", so treat that as no backend at all.
        """
        if runtime_dir and not os.path.isdir(runtime_dir):
            return False
        run = run or Runner()
        return run(["systemctl", "--version"]).ok

    def _base(self, privileged: bool = False) -> list[str]:
        argv = ["systemctl", "--no-pager", "--no-legend"]
        if self.scope == "user":
            argv.append("--user")
        return self._privileged(argv) if privileged else argv

    def list_services(self) -> list[Service]:
        services: dict[str, Service] = {}

        units = self.run([*self._base(), "list-unit-files", "--type=service"])
        for line in units.stdout.splitlines():
            parts = line.split()
            if len(parts) < 2:
                continue
            unit = parts[0]
            services[unit] = Service(
                name=unit,
                display=unit.removesuffix(".service"),
                backend=self.name,
                scope=self.scope,
                state=UNKNOWN,
                start_mode=parts[1],
            )

        active = self.run([*self._base(), "list-units", "--type=service", "--all", "--plain"])
        for line in active.stdout.splitlines():
            parts = line.split(maxsplit=4)
            if len(parts) < 4:
                continue
            unit, active_state = parts[0], parts[2]
            service = services.get(unit) or Service(
                name=unit,
                display=unit.removesuffix(".service"),
                backend=self.name,
                scope=self.scope,
            )
            service.state = RUNNING if active_state == "active" else STOPPED
            service.description = parts[4] if len(parts) > 4 else ""
            services[unit] = service

        return sorted(services.values(), key=lambda s: s.name)

    def get(self, name: str) -> Service:
        name = safety.validate_name(name)
        unit = name if "." in name.rsplit("/", 1)[-1] else f"{name}.service"
        properties = "Id,Description,ActiveState,SubState,UnitFileState,MainPID,LoadState"
        result = self.run([*self._base(), "show", unit, f"--property={properties}"])
        values: dict[str, str] = {}
        for line in result.stdout.splitlines():
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()

        if values.get("LoadState") in ("not-found", None) and not values.get("Id"):
            raise LookupError(f"no such systemd unit: {unit}")

        pid_text = values.get("MainPID", "0")
        pid = int(pid_text) if pid_text.isdigit() and pid_text != "0" else None
        active_state = values.get("ActiveState", UNKNOWN)
        return Service(
            name=values.get("Id") or unit,
            display=(values.get("Id") or unit).removesuffix(".service"),
            backend=self.name,
            scope=self.scope,
            state=RUNNING if active_state == "active" else STOPPED if active_state else UNKNOWN,
            start_mode=values.get("UnitFileState", UNKNOWN) or UNKNOWN,
            description=values.get("Description", ""),
            pid=pid,
        )

    _CGROUP_UNIT = re.compile(r"([A-Za-z0-9@._\\-]+\.(?:service|socket|scope|mount))")

    def service_for_pid(self, pid: int) -> str | None:
        try:
            with open(f"/proc/{int(pid)}/cgroup", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            text = ""
        for match in self._CGROUP_UNIT.finditer(text):
            unit = match.group(1)
            if unit.endswith(".service"):
                return unit
        result = self.run([*self._base(), "status", str(int(pid))])
        for line in result.stdout.splitlines():
            found = self._CGROUP_UNIT.search(line)
            if found and found.group(1).endswith(".service"):
                return found.group(1)
        return None

    def _argv_for(self, action: str, name: str) -> list[str]:
        unit = name if "." in name.rsplit("/", 1)[-1] else f"{name}.service"
        return [*self._base(privileged=True), action, unit]


class WindowsBackend(Backend):
    name = "windows"
    privileged_hint = "re-run portwarden from an elevated (Administrator) console"

    _PS = ("powershell", "-NoProfile", "-NonInteractive", "-Command")
    _START_MODES = {
        "auto": "enabled",
        "automatic": "enabled",
        "auto (delayed)": "auto-delayed",
        "manual": "manual",
        "disabled": "disabled",
        "boot": "enabled",
        "system": "enabled",
    }

    @staticmethod
    def available(run: RunnerType | None = None) -> bool:
        return platform.system().lower() == "windows"

    def _query(self, where: str = "") -> list[dict[str, str]]:
        filter_clause = f" | Where-Object {{ {where} }}" if where else ""
        script = (
            "Get-CimInstance Win32_Service"
            f"{filter_clause}"
            " | Select-Object Name,DisplayName,State,StartMode,ProcessId,Description"
            " | ConvertTo-Csv -NoTypeInformation"
        )
        result = self.run([*self._PS, script], timeout=60)
        if not result.ok or not result.stdout.strip():
            return []
        return list(csv.DictReader(io.StringIO(result.stdout)))

    def _to_service(self, row: dict[str, str]) -> Service:
        pid_text = (row.get("ProcessId") or "").strip()
        pid = int(pid_text) if pid_text.isdigit() and pid_text != "0" else None
        mode = (row.get("StartMode") or "").strip().lower()
        return Service(
            name=(row.get("Name") or "").strip(),
            display=(row.get("DisplayName") or "").strip(),
            backend=self.name,
            scope=self.scope,
            state=RUNNING if (row.get("State") or "").strip().lower() == "running" else STOPPED,
            start_mode=self._START_MODES.get(mode, mode or UNKNOWN),
            description=(row.get("Description") or "").strip(),
            pid=pid,
        )

    def list_services(self) -> list[Service]:
        return sorted((self._to_service(row) for row in self._query()), key=lambda s: s.name.lower())

    def get(self, name: str) -> Service:
        name = safety.validate_name(name)
        rows = self._query(where=f"$_.Name -eq '{name}'")
        if not rows:
            raise LookupError(f"no such Windows service: {name}")
        return self._to_service(rows[0])

    def service_for_pid(self, pid: int) -> str | None:
        result = self.run(["tasklist", "/svc", "/fo", "csv", "/nh", "/fi", f"PID eq {int(pid)}"])
        if not result.ok:
            return None
        for row in csv.reader(io.StringIO(result.stdout)):
            if len(row) >= 3 and row[2] not in ("N/A", ""):
                return row[2].split(",")[0].strip()
        return None

    def _argv_for(self, action: str, name: str) -> list[str]:
        if action == "start":
            return ["sc.exe", "start", name]
        if action == "stop":
            return ["sc.exe", "stop", name]
        if action == "restart":
            # sc.exe has no restart; PowerShell does it in one call.
            return [*self._PS, f"Restart-Service -Name '{name}' -Force"]
        # `sc config` needs the value in its own argument after "start=".
        return ["sc.exe", "config", name, "start=", "auto" if action == "enable" else "disabled"]


class LaunchdBackend(Backend):
    name = "launchd"
    privileged_hint = "re-run portwarden with sudo for system domain services"

    def _default_sudo(self) -> bool:
        return self.scope == "system" and hasattr(os, "geteuid") and os.geteuid() != 0

    @staticmethod
    def available(run: RunnerType | None = None) -> bool:
        return platform.system().lower() == "darwin"

    @property
    def domain(self) -> str:
        if self.scope == "user":
            uid = os.getuid() if hasattr(os, "getuid") else 0
            return f"gui/{uid}"
        return "system"

    def _target(self, label: str) -> str:
        return label if "/" in label else f"{self.domain}/{label}"

    def _disabled_labels(self) -> set[str]:
        result = self.run(self._privileged(["launchctl", "print-disabled", self.domain]))
        disabled: set[str] = set()
        for line in result.stdout.splitlines():
            match = re.search(r'"(?P<label>[^"]+)"\s*=>\s*(?P<value>\w+)', line)
            if match and match.group("value").lower() in ("true", "disabled"):
                disabled.add(match.group("label"))
        return disabled

    def _loaded(self) -> dict[str, int | None]:
        result = self.run(["launchctl", "list"])
        loaded: dict[str, int | None] = {}
        for line in result.stdout.splitlines()[1:]:
            parts = line.split(maxsplit=2)
            if len(parts) < 3:
                continue
            pid_text, label = parts[0], parts[2].strip()
            loaded[label] = int(pid_text) if pid_text.isdigit() else None
        return loaded

    def list_services(self) -> list[Service]:
        disabled = self._disabled_labels()
        loaded = self._loaded()
        services = []
        for label in sorted(set(loaded) | disabled):
            pid = loaded.get(label)
            services.append(
                Service(
                    name=label,
                    display=label,
                    backend=self.name,
                    scope=self.scope,
                    state=RUNNING if pid else STOPPED if label in loaded else UNKNOWN,
                    start_mode="disabled" if label in disabled else "enabled",
                    pid=pid,
                )
            )
        return services

    def get(self, name: str) -> Service:
        label = safety.validate_name(name).rsplit("/", 1)[-1]
        for service in self.list_services():
            if service.name == label:
                return service
        raise LookupError(f"no such launchd service: {label}")

    def service_for_pid(self, pid: int) -> str | None:
        for label, service_pid in self._loaded().items():
            if service_pid == int(pid):
                return label
        return None

    def _argv_for(self, action: str, name: str) -> list[str]:
        target = self._target(name)
        verbs = {
            "start": ["launchctl", "kickstart", target],
            "stop": ["launchctl", "bootout", target],
            "restart": ["launchctl", "kickstart", "-k", target],
            "enable": ["launchctl", "enable", target],
            "disable": ["launchctl", "disable", target],
        }
        return self._privileged(verbs[action])


class UnsupportedBackend(Backend):
    """No service manager found — discovery still works, control does not."""

    name = "none"

    def list_services(self) -> list[Service]:
        return []

    def get(self, name: str) -> Service:
        raise LookupError("no service manager is available on this system")

    def control(self, name: str, action: str, force: bool = False) -> ActionResult:
        return ActionResult(
            ok=False,
            action=action,
            service=name,
            backend=self.name,
            detail=(
                "no running service manager found (looked for systemd as init, the "
                "Windows service manager and launchd). Port discovery still works; "
                "in a container or WSL without systemd there are no services to "
                "switch, so stop the process where you started it"
            ),
        )


def detect_backend(
    run: RunnerType | None = None,
    scope: str = "system",
    use_sudo: bool | None = None,
    dry_run: bool = False,
    system: str | None = None,
) -> Backend:
    """Pick the service manager for this machine."""
    run = run or Runner()
    system = (system or platform.system()).lower()
    kwargs = {"run": run, "scope": scope, "use_sudo": use_sudo, "dry_run": dry_run}

    if system == "windows":
        return WindowsBackend(**kwargs)
    if system == "darwin":
        return LaunchdBackend(**kwargs)
    if SystemdBackend.available(run):
        return SystemdBackend(**kwargs)
    return UnsupportedBackend(**kwargs)
