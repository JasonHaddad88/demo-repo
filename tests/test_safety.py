import unittest

from portwarden import safety


class ProtectionTests(unittest.TestCase):
    def test_systemd_critical_units_are_protected(self):
        for name in (
            "ssh",
            "sshd.service",
            "systemd-logind.service",
            "NetworkManager",
            "dbus.service",
            "getty@tty1.service",
            "firewalld",
        ):
            with self.subTest(name=name):
                self.assertTrue(safety.is_protected(name, "systemd"))

    def test_ordinary_units_are_not_protected(self):
        for name in ("nginx.service", "postgresql", "redis-server.service", "docker", "cups"):
            with self.subTest(name=name):
                self.assertFalse(safety.is_protected(name, "systemd"))

    def test_windows_and_launchd_lists(self):
        self.assertTrue(safety.is_protected("RpcSs", "windows"))
        self.assertTrue(safety.is_protected("rpcss", "windows"))
        self.assertFalse(safety.is_protected("MySQL80", "windows"))
        self.assertTrue(safety.is_protected("com.apple.sshd", "launchd"))
        self.assertTrue(safety.is_protected("system/com.apple.cupsd", "launchd"))
        self.assertFalse(safety.is_protected("org.postgresql.postgres", "launchd"))

    def test_unknown_backend_protects_nothing(self):
        self.assertFalse(safety.is_protected("ssh", "none"))

    def test_check_blocks_destructive_actions_only(self):
        safety.check("sshd", "systemd", "start")
        safety.check("sshd", "systemd", "enable")
        for action in ("stop", "disable", "restart"):
            with self.subTest(action=action), self.assertRaises(safety.ProtectedService):
                safety.check("sshd", "systemd", action)

    def test_force_overrides(self):
        safety.check("sshd", "systemd", "stop", force=True)

    def test_error_message_names_the_pattern(self):
        with self.assertRaises(safety.ProtectedService) as ctx:
            safety.check("systemd-resolved.service", "systemd", "stop")
        self.assertIn("systemd-*", str(ctx.exception))
        self.assertIn("--force", str(ctx.exception))


class NameValidationTests(unittest.TestCase):
    def test_accepts_real_service_names(self):
        for name in (
            "nginx",
            "nginx.service",
            "getty@tty1.service",
            "com.apple.cupsd",
            "system/com.apple.cupsd",
            "MySQL80",
            "redis-server",
        ):
            with self.subTest(name=name):
                self.assertEqual(safety.validate_name(name), name)

    def test_rejects_option_injection_and_shell_metacharacters(self):
        for name in (
            "--now",
            "-f",
            "nginx; rm -rf /",
            "nginx && reboot",
            "$(whoami)",
            "`id`",
            "nginx | cat",
            "../../etc/passwd",
            "",
            "   ",
            "a" * 200,
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                safety.validate_name(name)


if __name__ == "__main__":
    unittest.main()
