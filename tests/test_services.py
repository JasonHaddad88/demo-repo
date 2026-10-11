import unittest
from unittest import mock

from portwarden import safety, services
from portwarden.runner import FakeRunner

SYSTEMCTL = ("systemctl", "--no-pager", "--no-legend")

UNIT_FILES = """\
nginx.service                 enabled  enabled
postgresql.service            disabled disabled
ssh.service                   enabled  enabled
cups.service                  static   -
"""

UNITS = """\
nginx.service      loaded active   running A high performance web server
postgresql.service loaded inactive dead    PostgreSQL RDBMS
ssh.service        loaded active   running OpenBSD Secure Shell server
"""

SHOW_NGINX = """\
Id=nginx.service
Description=A high performance web server
ActiveState=active
SubState=running
UnitFileState=enabled
MainPID=4242
LoadState=loaded
"""

SHOW_MISSING = """\
Id=
Description=nope.service
ActiveState=inactive
SubState=dead
UnitFileState=
MainPID=0
LoadState=not-found
"""

WIN_SERVICES_CSV = (
    '"Name","DisplayName","State","StartMode","ProcessId","Description"\r\n'
    '"MySQL80","MySQL80","Running","Auto","4120","MySQL database server"\r\n'
    '"RpcSs","Remote Procedure Call (RPC)","Running","Auto","928","RPC"\r\n'
    '"Spooler","Print Spooler","Stopped","Disabled","0","Print jobs"\r\n'
)

LAUNCHCTL_LIST = """\
PID\tStatus\tLabel
501\t0\tcom.example.devserver
-\t0\torg.postgresql.postgres
"""

LAUNCHCTL_DISABLED = """\
disabled services = {
\t"org.postgresql.postgres" => true
\t"com.example.devserver" => false
}
"""


def systemd_runner(**extra):
    run = FakeRunner()
    run.add([*SYSTEMCTL, "list-unit-files"], stdout=UNIT_FILES)
    run.add([*SYSTEMCTL, "list-units"], stdout=UNITS)
    run.add([*SYSTEMCTL, "show", "nginx.service"], stdout=SHOW_NGINX)
    run.add([*SYSTEMCTL, "show", "nope.service"], stdout=SHOW_MISSING)
    run.add(["systemctl", "--version"], stdout="systemd 255")
    for argv, kwargs in extra.items():
        run.add(argv.split(), **kwargs)
    return run


class SystemdReadTests(unittest.TestCase):
    def setUp(self):
        self.run = systemd_runner()
        self.backend = services.SystemdBackend(run=self.run, use_sudo=False)

    def test_list_merges_unit_files_and_active_state(self):
        items = {service.name: service for service in self.backend.list_services()}
        self.assertEqual(items["nginx.service"].state, services.RUNNING)
        self.assertTrue(items["nginx.service"].enabled)
        self.assertEqual(items["postgresql.service"].state, services.STOPPED)
        self.assertFalse(items["postgresql.service"].enabled)
        # A unit with no active-state row is still listed, state unknown.
        self.assertEqual(items["cups.service"].state, services.UNKNOWN)
        self.assertIsNone(items["cups.service"].enabled)

    def test_get_parses_show_output(self):
        service = self.backend.get("nginx")
        self.assertEqual(service.name, "nginx.service")
        self.assertTrue(service.running)
        self.assertTrue(service.enabled)
        self.assertEqual(service.pid, 4242)
        self.assertEqual(service.description, "A high performance web server")

    def test_get_missing_unit_raises_lookup_error(self):
        with self.assertRaises(LookupError):
            self.backend.get("nope")

    def test_protected_flag_is_reported(self):
        items = {service.name: service for service in self.backend.list_services()}
        self.assertTrue(items["ssh.service"].protected)
        self.assertFalse(items["nginx.service"].protected)

    def test_available_probes_systemctl(self):
        self.assertTrue(services.SystemdBackend.available(self.run, runtime_dir=None))
        empty = FakeRunner()
        empty.default = empty.default.__class__((), 127, "", "not found")
        self.assertFalse(services.SystemdBackend.available(empty, runtime_dir=None))

    def test_available_requires_systemd_to_be_running(self):
        # systemctl installed, but systemd is not PID 1 (container, WSL).
        self.assertFalse(
            services.SystemdBackend.available(self.run, runtime_dir="/nonexistent/systemd")
        )


class SystemdWriteTests(unittest.TestCase):
    def setUp(self):
        self.run = systemd_runner()
        self.backend = services.SystemdBackend(run=self.run, use_sudo=False)

    def test_commands_are_built_correctly(self):
        for action in services.ACTIONS:
            with self.subTest(action=action):
                result = self.backend.control("nginx", action)
                self.assertEqual(result.argv[-2:], (action, "nginx.service"))
                self.assertTrue(result.ok)

    def test_sudo_prefix_when_requested(self):
        backend = services.SystemdBackend(run=self.run, use_sudo=True)
        result = backend.control("nginx", "stop")
        self.assertEqual(result.argv[:2], ("sudo", "-n"))

    def test_user_scope_passes_user_flag(self):
        backend = services.SystemdBackend(run=self.run, scope="user", use_sudo=False)
        result = backend.control("nginx", "restart")
        self.assertIn("--user", result.argv)

    def test_protected_unit_is_refused(self):
        with self.assertRaises(safety.ProtectedService):
            self.backend.control("ssh", "stop")
        self.assertNotIn((*SYSTEMCTL, "stop", "ssh.service"), self.run.calls)

    def test_protected_unit_can_be_forced(self):
        result = self.backend.control("ssh", "stop", force=True)
        self.assertTrue(result.ok)
        self.assertEqual(result.argv[-2:], ("stop", "ssh.service"))

    def test_unknown_action_raises(self):
        with self.assertRaises(ValueError):
            self.backend.control("nginx", "obliterate")

    def test_dry_run_changes_nothing(self):
        backend = services.SystemdBackend(run=self.run, use_sudo=False, dry_run=True)
        result = backend.control("nginx", "stop")
        self.assertTrue(result.dry_run)
        self.assertEqual(result.argv[-2:], ("stop", "nginx.service"))
        self.assertEqual(self.run.calls, [])

    def test_failure_detail_includes_privilege_hint(self):
        run = systemd_runner()
        run.add(
            [*SYSTEMCTL, "stop", "nginx.service"],
            returncode=1,
            stderr="Interactive authentication required.",
        )
        backend = services.SystemdBackend(run=run, use_sudo=False)
        result = backend.control("nginx", "stop")
        self.assertFalse(result.ok)
        self.assertIn("sudo", result.detail)

    def test_status_is_read_back_after_an_action(self):
        result = self.backend.control("nginx", "start")
        self.assertIsNotNone(result.service_after)
        self.assertTrue(result.service_after.running)


class SystemdPidTests(unittest.TestCase):
    def test_falls_back_to_systemctl_status(self):
        run = systemd_runner()
        run.add(
            [*SYSTEMCTL, "status", "4242"],
            stdout="● nginx.service - A high performance web server\n   CGroup: /system.slice/nginx.service\n",
        )
        backend = services.SystemdBackend(run=run, use_sudo=False)
        self.assertEqual(backend.service_for_pid(4242), "nginx.service")

    def test_cgroup_regex_picks_the_service_unit(self):
        pattern = services.SystemdBackend._CGROUP_UNIT
        text = "0::/system.slice/docker.service\n"
        self.assertEqual(pattern.search(text).group(1), "docker.service")


class WindowsBackendTests(unittest.TestCase):
    def setUp(self):
        self.run = FakeRunner()
        self.run.add(["powershell"], stdout=WIN_SERVICES_CSV)
        self.backend = services.WindowsBackend(run=self.run)

    def test_list_parses_csv(self):
        items = {service.name: service for service in self.backend.list_services()}
        self.assertTrue(items["MySQL80"].running)
        self.assertTrue(items["MySQL80"].enabled)
        self.assertEqual(items["MySQL80"].pid, 4120)
        self.assertFalse(items["Spooler"].running)
        self.assertFalse(items["Spooler"].enabled)
        self.assertIsNone(items["Spooler"].pid)
        self.assertTrue(items["RpcSs"].protected)

    def test_start_stop_use_sc(self):
        self.assertEqual(self.backend._argv_for("start", "MySQL80"), ["sc.exe", "start", "MySQL80"])
        self.assertEqual(self.backend._argv_for("stop", "MySQL80"), ["sc.exe", "stop", "MySQL80"])

    def test_enable_disable_set_start_type(self):
        self.assertEqual(
            self.backend._argv_for("enable", "MySQL80"),
            ["sc.exe", "config", "MySQL80", "start=", "auto"],
        )
        self.assertEqual(
            self.backend._argv_for("disable", "MySQL80"),
            ["sc.exe", "config", "MySQL80", "start=", "disabled"],
        )

    def test_restart_uses_powershell(self):
        argv = self.backend._argv_for("restart", "MySQL80")
        self.assertEqual(argv[0], "powershell")
        self.assertIn("Restart-Service", argv[-1])

    def test_service_for_pid_reads_tasklist(self):
        self.run.add(
            ["tasklist"],
            stdout='"mysqld.exe","4120","MySQL80"\r\n',
        )
        self.assertEqual(self.backend.service_for_pid(4120), "MySQL80")

    def test_get_unknown_service_raises(self):
        run = FakeRunner()
        run.add(["powershell"], stdout="")
        with self.assertRaises(LookupError):
            services.WindowsBackend(run=run).get("NoSuchSvc")


class LaunchdBackendTests(unittest.TestCase):
    def setUp(self):
        self.run = FakeRunner()
        self.run.add(["launchctl", "list"], stdout=LAUNCHCTL_LIST)
        self.run.add(["launchctl", "print-disabled"], stdout=LAUNCHCTL_DISABLED)
        self.backend = services.LaunchdBackend(run=self.run, use_sudo=False)

    def test_list_merges_loaded_and_disabled(self):
        items = {service.name: service for service in self.backend.list_services()}
        self.assertTrue(items["com.example.devserver"].running)
        self.assertEqual(items["com.example.devserver"].pid, 501)
        self.assertTrue(items["com.example.devserver"].enabled)
        self.assertFalse(items["org.postgresql.postgres"].running)
        self.assertFalse(items["org.postgresql.postgres"].enabled)

    def test_targets_include_the_domain(self):
        argv = self.backend._argv_for("disable", "com.example.devserver")
        self.assertEqual(argv, ["launchctl", "disable", "system/com.example.devserver"])

    def test_stop_boots_the_service_out(self):
        self.assertEqual(
            self.backend._argv_for("stop", "com.example.devserver"),
            ["launchctl", "bootout", "system/com.example.devserver"],
        )

    def test_apple_services_are_protected(self):
        with self.assertRaises(safety.ProtectedService):
            self.backend.control("com.apple.cupsd", "stop")

    def test_service_for_pid(self):
        self.assertEqual(self.backend.service_for_pid(501), "com.example.devserver")
        self.assertIsNone(self.backend.service_for_pid(99999))


class UnsupportedBackendTests(unittest.TestCase):
    def test_control_explains_itself(self):
        backend = services.UnsupportedBackend()
        result = backend.control("nginx", "stop")
        self.assertFalse(result.ok)
        self.assertIn("no running service manager", result.detail)
        self.assertEqual(backend.list_services(), [])


class DetectionTests(unittest.TestCase):
    def test_platform_selection(self):
        run = systemd_runner()
        self.assertIsInstance(
            services.detect_backend(run=run, system="Windows"), services.WindowsBackend
        )
        self.assertIsInstance(
            services.detect_backend(run=run, system="Darwin"), services.LaunchdBackend
        )
        with mock.patch.object(services.SystemdBackend, "available", return_value=True):
            self.assertIsInstance(
                services.detect_backend(run=run, system="Linux"), services.SystemdBackend
            )

    def test_linux_without_systemd_falls_back(self):
        run = FakeRunner()
        run.default = run.default.__class__((), 127, "", "systemctl: not found")
        self.assertIsInstance(
            services.detect_backend(run=run, system="Linux"), services.UnsupportedBackend
        )


if __name__ == "__main__":
    unittest.main()
