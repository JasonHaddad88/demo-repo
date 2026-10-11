import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

from portwarden import cli
from tests.test_core import build_warden


class CliTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.warden, self.run = build_warden(self.tmp.name)
        patcher = mock.patch.object(cli, "PortWarden", return_value=self.warden)
        self.factory = patcher.start()
        self.addCleanup(patcher.stop)

    def invoke(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cli.main(list(argv))
        return code, out.getvalue(), err.getvalue()


class ReadCommandTests(CliTestCase):
    def test_ports_table(self):
        code, out, _ = self.invoke("ports")
        self.assertEqual(code, 0)
        self.assertIn("nginx.service", out)
        self.assertIn("PORT", out)
        self.assertIn("all-interfaces", out)

    def test_ports_json(self):
        code, out, _ = self.invoke("--json", "ports")
        self.assertEqual(code, 0)
        rows = json.loads(out)
        self.assertEqual(rows[0]["service"]["name"], "ssh.service")

    def test_single_port_found_and_missing(self):
        code, out, _ = self.invoke("port", "80")
        self.assertEqual(code, 0)
        self.assertIn("nginx.service", out)

        code, out, _ = self.invoke("port", "64999")
        self.assertEqual(code, 1)
        self.assertIn("Nothing is listening", out)

    def test_scan_accepts_a_port_spec(self):
        code, out, _ = self.invoke("scan", "--ports", "1-2", "--timeout", "0.05")
        self.assertEqual(code, 0)
        self.assertIn("PORT", out)

    def test_scan_rejects_a_bad_spec(self):
        code, _, err = self.invoke("scan", "--ports", "nope")
        self.assertEqual(code, 2)
        self.assertIn("portwarden:", err)

    def test_services_list(self):
        code, out, _ = self.invoke("services", "--all")
        self.assertEqual(code, 0)
        self.assertIn("nginx.service", out)
        self.assertIn("postgresql.service", out)

    def test_services_filter(self):
        code, out, _ = self.invoke("services", "--all", "--filter", "nginx")
        self.assertIn("nginx.service", out)
        self.assertNotIn("postgresql.service", out)

    def test_status(self):
        code, out, _ = self.invoke("status", "nginx")
        self.assertEqual(code, 0)
        self.assertIn("running     yes", out)
        self.assertIn("protected   no", out)

    def test_status_unknown_service(self):
        code, _, err = self.invoke("status", "doesnotexist")
        self.assertEqual(code, 1)
        self.assertIn("no such systemd unit", err)

    def test_env(self):
        code, out, _ = self.invoke("env")
        self.assertEqual(code, 0)
        self.assertIn("systemd", out)


class ActionCommandTests(CliTestCase):
    def test_start_needs_no_confirmation(self):
        code, out, _ = self.invoke("start", "nginx")
        self.assertEqual(code, 0)
        self.assertIn("[ok] start nginx", out)

    def test_stop_without_a_tty_and_without_yes_is_refused(self):
        with mock.patch("sys.stdin", io.StringIO()):
            code, out, err = self.invoke("stop", "nginx")
        self.assertEqual(code, 130)
        self.assertIn("Aborted", out)
        self.assertIn("without confirmation", err)

    def test_stop_with_yes(self):
        code, out, _ = self.invoke("stop", "nginx", "--yes")
        self.assertEqual(code, 0)
        self.assertIn("[ok] stop nginx", out)

    def test_interactive_confirmation_declined(self):
        with mock.patch.object(cli, "_confirm", return_value=False):
            code, out, _ = self.invoke("disable", "nginx")
        self.assertEqual(code, 130)
        self.assertIn("Aborted", out)

    def test_protected_service_is_refused(self):
        code, _, err = self.invoke("stop", "ssh", "--yes")
        self.assertEqual(code, 1)
        self.assertIn("protected", err)

    def test_protected_service_with_force(self):
        code, out, _ = self.invoke("stop", "ssh", "--yes", "--force")
        self.assertEqual(code, 0)
        self.assertIn("[ok] stop ssh", out)

    def test_by_port(self):
        code, out, _ = self.invoke("restart", "80", "--by-port", "--yes")
        self.assertEqual(code, 0)
        self.assertIn("nginx.service", out)

    def test_by_port_with_no_service(self):
        code, _, err = self.invoke("stop", "9999", "--by-port", "--yes")
        self.assertEqual(code, 1)
        self.assertIn("not managed", err)

    def test_dry_run_is_announced(self):
        warden, run = build_warden(self.tmp.name, dry_run=True)
        self.factory.return_value = warden
        code, out, _ = self.invoke("--dry-run", "stop", "nginx")
        self.assertEqual(code, 0)
        self.assertIn("dry run", out)

    def test_json_output_for_actions(self):
        code, out, _ = self.invoke("--json", "start", "nginx")
        self.assertEqual(code, 0)
        results = json.loads(out)
        self.assertTrue(results[0]["ok"])

    def test_audit_lists_recent_changes(self):
        self.invoke("start", "nginx")
        code, out, _ = self.invoke("audit")
        self.assertEqual(code, 0)
        self.assertIn("start", out)
        self.assertIn("nginx", out)


class ParserTests(unittest.TestCase):
    def test_every_action_has_a_subcommand(self):
        parser = cli.build_parser()
        for action in ("start", "stop", "restart", "enable", "disable"):
            with self.subTest(action=action):
                args = parser.parse_args([action, "nginx"])
                self.assertEqual(args.action, action)
                self.assertEqual(args.target, "nginx")

    def test_scope_and_sudo_flags(self):
        parser = cli.build_parser()
        self.assertIsNone(parser.parse_args(["ports"]).sudo)
        self.assertTrue(parser.parse_args(["--sudo", "ports"]).sudo)
        self.assertFalse(parser.parse_args(["--no-sudo", "ports"]).sudo)
        self.assertEqual(parser.parse_args(["--scope", "user", "ports"]).scope, "user")

    def test_serve_defaults(self):
        args = cli.build_parser().parse_args(["serve"])
        self.assertEqual((args.bind, args.port), ("127.0.0.1", 8787))

    def test_command_is_required(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            cli.build_parser().parse_args([])


if __name__ == "__main__":
    unittest.main()
