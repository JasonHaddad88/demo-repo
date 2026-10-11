"""Ties port discovery to service control."""

from __future__ import annotations

import platform
import sys
from dataclasses import dataclass
from typing import Sequence

from . import audit, discovery, safety, services
from .runner import Runner, RunnerType
from .services import ActionResult, Backend, Service

__all__ = ["PortWarden", "PortRow"]


@dataclass
class PortRow:
    """One listening socket, with the service that can switch it off."""

    listener: discovery.Listener
    service: Service | None = None

    def to_dict(self) -> dict:
        data = self.listener.to_dict()
        data["service"] = self.service.to_dict() if self.service else None
        data["controllable"] = self.service is not None
        return data


class PortWarden:
    def __init__(
        self,
        backend: Backend | None = None,
        run: RunnerType | None = None,
        scope: str = "system",
        use_sudo: bool | None = None,
        dry_run: bool = False,
        system: str | None = None,
        audit_path=None,
    ) -> None:
        self.run = run or Runner()
        self.system = (system or platform.system()).lower()
        self.backend = backend or services.detect_backend(
            run=self.run, scope=scope, use_sudo=use_sudo, dry_run=dry_run, system=self.system
        )
        self.dry_run = dry_run
        self.audit_path = audit_path
        self._service_cache: dict[str, Service | None] = {}

    # -- read -----------------------------------------------------------
    def ports(
        self,
        scan: str | Sequence[int] | None = None,
        host: str = "127.0.0.1",
        timeout: float = 0.25,
        resolve_services: bool = True,
    ) -> list[PortRow]:
        ports = discovery.parse_port_spec(scan) if isinstance(scan, str) else scan
        listeners = discovery.inventory(
            ports=list(ports) if ports else None,
            host=host,
            timeout=timeout,
            run=self.run,
            system=self.system,
        )
        rows = []
        for listener in listeners:
            service = None
            if resolve_services and listener.pid is not None:
                service = self._service_for_pid(listener.pid)
                listener.service = service.name if service else None
            rows.append(PortRow(listener=listener, service=service))
        return rows

    def port(self, port: int, **kwargs) -> list[PortRow]:
        return [row for row in self.ports(**kwargs) if row.listener.port == int(port)]

    def services(self) -> list[Service]:
        return self.backend.list_services()

    def status(self, name: str) -> Service:
        return self.backend.get(safety.validate_name(name))

    def _service_for_pid(self, pid: int) -> Service | None:
        key = str(pid)
        if key in self._service_cache:
            return self._service_cache[key]
        service: Service | None = None
        try:
            name = self.backend.service_for_pid(pid)
            if name:
                try:
                    service = self.backend.get(name)
                except LookupError:
                    service = Service(name=name, backend=self.backend.name)
        except Exception:  # noqa: BLE001 - a missing pid must not break a listing
            service = None
        self._service_cache[key] = service
        return service

    # -- write ----------------------------------------------------------
    def control(self, name: str, action: str, force: bool = False) -> ActionResult:
        """Run one of start/stop/restart/enable/disable and log the outcome."""
        try:
            result = self.backend.control(name, action, force=force)
        except safety.ProtectedService as exc:
            result = ActionResult(
                ok=False,
                action=action,
                service=name,
                backend=self.backend.name,
                detail=str(exc),
            )
        except ValueError as exc:
            result = ActionResult(
                ok=False, action=action, service=name, backend=self.backend.name, detail=str(exc)
            )

        audit.record(
            {
                "action": action,
                "service": name,
                "backend": result.backend,
                "scope": self.backend.scope,
                "ok": result.ok,
                "dry_run": result.dry_run,
                "forced": force,
                "command": " ".join(result.argv),
                "detail": result.detail,
            },
            path=self.audit_path,
        )
        return result

    def control_port(self, port: int, action: str, force: bool = False) -> list[ActionResult]:
        """Apply an action to whatever services own ``port``."""
        rows = self.port(port)
        targets = {row.service.name for row in rows if row.service}
        if not targets:
            detail = (
                f"nothing is listening on port {port}"
                if not rows
                else f"port {port} is held by "
                + ", ".join(sorted({row.listener.process for row in rows}))
                + ", which is not managed by a known service"
            )
            return [
                ActionResult(
                    ok=False,
                    action=action,
                    service=f"port:{port}",
                    backend=self.backend.name,
                    detail=detail,
                )
            ]
        return [self.control(name, action, force=force) for name in sorted(targets)]

    # -- meta -----------------------------------------------------------
    def environment(self) -> dict:
        return {
            "platform": platform.platform(),
            "system": self.system,
            "python": sys.version.split()[0],
            "backend": self.backend.name,
            "scope": self.backend.scope,
            "sudo": getattr(self.backend, "use_sudo", False),
            "dry_run": self.dry_run,
            "audit_log": str(audit.default_log_path() if self.audit_path is None else self.audit_path),
            "default_scan_ports": discovery.DEFAULT_SCAN_PORTS,
        }
