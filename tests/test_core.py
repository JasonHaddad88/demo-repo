import json
import tempfile
import unittest
from pathlib import Path

from portwarden import audit
from portwarden.core import PortWarden
from portwarden.runner import FakeRunner
from portwarden.services import SystemdBackend
from tests.test_services import SHOW_NGINX, SYSTEMCTL, UNIT_FILES, UNITS

SS_OUTPUT = """\
tcp   LISTEN 0 511   0.0.0.0:80   0.0.0.0:*  users:(("nginx",pid=4242,fd=6))
tcp   LISTEN 0 4096  127.0.0.1:22 0.0.0.0:*  users:(("sshd",pid=900,fd=3))
tcp   LISTEN 0 128   127.0.0.1:9999 0.0.0.0:* users:(("python3",pid=7777,fd=5))
"""


class StubBackend(SystemdBackend):
    """systemd backend with a canned pid -> unit mapping."""

    def __init__(self, *args, pid_map=None, **kwargs):
        super().__init__(*args, **kwargs)
        self.pid_map = pid_map or {}

    def service_for_pid(self, pid):
        return self.pid_map.get(int(pid))


def build_warden(tmp_path, dry_run=False, pid_map=None):
    run = FakeRunner()
    run.add(["ss"], stdout=SS_OUTPUT)
    run.add([*SYSTEMCTL, "list-unit-files"], stdout=UNIT_FILES)
    run.add([*SYSTEMCTL, "list-units"], stdout=UNITS)
    run.add([*SYSTEMCTL, "show", "nginx.service"], stdout=SHOW_NGINX)
    backend = StubBackend(
        run=run,
        use_sudo=False,
        dry_run=dry_run,
        pid_map=pid_map if pid_map is not None else {4242: "nginx.service", 900: "ssh.service"},
    )
    warden = PortWarden(
        backend=backend,
        run=run,
        system="Linux",
        dry_run=dry_run,
        audit_path=Path(tmp_path) / "audit.log",
    )
    return warden, run


class PortListingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.warden, self.run = build_warden(self.tmp.name)

    def test_ports_are_linked_to_services(self):
        rows = {row.listener.port: row for row in self.warden.ports()}
        self.assertEqual(rows[80].service.name, "nginx.service")
        self.assertTrue(rows[80].service.running)
        self.assertEqual(rows[80].listener.scope, "all-interfaces")
        self.assertEqual(rows[22].service.name, "ssh.service")
        self.assertTrue(rows[22].service.protected)

    def test_unmanaged_port_has_no_service(self):
        rows = {row.listener.port: row for row in self.warden.ports()}
        self.assertIsNone(rows[9999].service)
        self.assertFalse(rows[9999].to_dict()["controllable"])

    def test_port_lookup_filters(self):
        self.assertEqual(len(self.warden.port(80)), 1)
        self.assertEqual(self.warden.port(65000), [])

    def test_pid_lookups_are_cached(self):
        self.warden.ports()
        first = len(self.run.calls)
        self.warden._service_for_pid(4242)
        self.assertEqual(len(self.run.calls), first)

    def test_serialization_is_json_safe(self):
        payload = [row.to_dict() for row in self.warden.ports()]
        json.loads(json.dumps(payload))


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.warden, self.run = build_warden(self.tmp.name)
        self.log = Path(self.tmp.name) / "audit.log"

    def test_successful_action_is_logged(self):
        result = self.warden.control("nginx", "stop")
        self.assertTrue(result.ok)
        entries = audit.tail(10, self.log)
        self.assertEqual(entries[0]["action"], "stop")
        self.assertEqual(entries[0]["service"], "nginx")
        self.assertTrue(entries[0]["ok"])
        self.assertIn("systemctl", entries[0]["command"])

    def test_protected_refusal_is_reported_and_logged(self):
        result = self.warden.control("ssh", "stop")
        self.assertFalse(result.ok)
        self.assertIn("protected", result.detail)
        self.assertFalse(audit.tail(10, self.log)[0]["ok"])

    def test_invalid_name_does_not_raise(self):
        result = self.warden.control("nginx; reboot", "stop")
        self.assertFalse(result.ok)
        self.assertIn("not a valid service name", result.detail)

    def test_control_port_targets_the_owning_service(self):
        results = self.warden.control_port(80, "restart")
        self.assertEqual([r.service for r in results], ["nginx.service"])
        self.assertTrue(results[0].ok)

    def test_control_port_without_a_service_explains_why(self):
        results = self.warden.control_port(9999, "stop")
        self.assertFalse(results[0].ok)
        self.assertIn("python3", results[0].detail)
        self.assertIn("not managed", results[0].detail)

    def test_control_port_with_nothing_listening(self):
        results = self.warden.control_port(64999, "stop")
        self.assertFalse(results[0].ok)
        self.assertIn("nothing is listening", results[0].detail)

    def test_dry_run_records_but_does_not_execute(self):
        warden, run = build_warden(self.tmp.name, dry_run=True)
        result = warden.control("nginx", "stop")
        self.assertTrue(result.ok)
        self.assertTrue(result.dry_run)
        self.assertNotIn((*SYSTEMCTL, "stop", "nginx.service"), run.calls)
        self.assertTrue(audit.tail(1, self.log)[0]["dry_run"])

    def test_environment_reports_backend(self):
        env = self.warden.environment()
        self.assertEqual(env["backend"], "systemd")
        self.assertEqual(env["system"], "linux")
        self.assertIn("default_scan_ports", env)


class AuditTests(unittest.TestCase):
    def test_tail_returns_newest_first_and_skips_junk(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "audit.log"
            audit.record({"action": "stop", "service": "a", "ok": True}, path)
            with path.open("a") as fh:
                fh.write("not json\n")
            audit.record({"action": "start", "service": "b", "ok": True}, path)
            entries = audit.tail(10, path)
            self.assertEqual([e["service"] for e in entries], ["b", "a"])

    def test_missing_log_is_not_an_error(self):
        self.assertEqual(audit.tail(10, Path("/nonexistent/portwarden/audit.log")), [])

    def test_unwritable_path_does_not_raise(self):
        audit.record({"action": "stop"}, Path("/proc/portwarden-should-not-exist/audit.log"))


if __name__ == "__main__":
    unittest.main()
