import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from portwarden import server
from tests.test_core import build_warden


def _with_data(request, data):
    request.data = data
    return request


class ServerTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.warden, self.run = build_warden(self.tmp.name)
        self.httpd, self.token = server.build_server(
            self.warden, host="127.0.0.1", port=0, quiet=True
        )
        # Never route loopback requests through an ambient HTTP proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        # A short poll interval keeps shutdown() from costing half a second per test.
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True
        )
        self.thread.start()
        self.addCleanup(self._shutdown)

    def _shutdown(self):
        self.httpd.shutdown()
        self.thread.join(timeout=5)
        self.httpd.server_close()

    def request(self, path, method="GET", token=None, body=None, headers=None):
        request = urllib.request.Request(self.base + path, method=method)
        if token is not False:
            request.add_header("X-PortWarden-Token", token or self.token)
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(request if data is None else _with_data(request, data), timeout=10) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            try:
                return exc.code, json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return exc.code, {"raw": raw.decode(errors="replace")}


class BindingTests(unittest.TestCase):
    def test_refuses_routable_bind_addresses(self):
        with tempfile.TemporaryDirectory() as tmp:
            warden, _ = build_warden(tmp)
            for host in ("0.0.0.0", "192.168.1.10", ""):
                with self.subTest(host=host), self.assertRaises(ValueError) as ctx:
                    server.build_server(warden, host=host, port=0)
                self.assertIn("loopback", str(ctx.exception))

    def test_token_is_generated_when_not_supplied(self):
        with tempfile.TemporaryDirectory() as tmp:
            warden, _ = build_warden(tmp)
            httpd, token = server.build_server(warden, port=0, quiet=True)
            self.addCleanup(httpd.server_close)
            self.assertGreaterEqual(len(token), 20)


class AuthTests(ServerTestCase):
    def test_api_requires_a_token(self):
        status, payload = self.request("/api/ports", token=False)
        self.assertEqual(status, 401)
        self.assertIn("token", payload["error"])

    def test_wrong_token_is_rejected(self):
        status, _ = self.request("/api/ports", token="not-the-token")
        self.assertEqual(status, 401)

    def test_token_may_be_passed_as_a_query_parameter(self):
        status, _ = self.request(f"/api/env?token={self.token}", token=False)
        self.assertEqual(status, 200)

    def test_non_loopback_host_header_is_refused(self):
        status, payload = self.request("/api/ports", headers={"Host": "portwarden.example.com"})
        self.assertEqual(status, 403)
        self.assertIn("localhost", payload["error"])

    def test_cross_origin_post_is_refused(self):
        status, payload = self.request(
            "/api/control",
            method="POST",
            body={"service": "nginx", "action": "stop"},
            headers={"Origin": "https://evil.example"},
        )
        self.assertEqual(status, 403)
        self.assertIn("cross-origin", payload["error"])

    def test_index_is_served_without_a_token(self):
        status, payload = self.request("/healthz", token=False)
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])


class ApiTests(ServerTestCase):
    def test_env(self):
        status, payload = self.request("/api/env")
        self.assertEqual(status, 200)
        self.assertEqual(payload["backend"], "systemd")

    def test_ports(self):
        status, payload = self.request("/api/ports")
        self.assertEqual(status, 200)
        ports = {row["port"]: row for row in payload["ports"]}
        self.assertEqual(ports[80]["service"]["name"], "nginx.service")
        self.assertFalse(payload["scanned"])

    def test_ports_with_scan(self):
        status, payload = self.request("/api/ports?scan=1-2&timeout=0.05")
        self.assertEqual(status, 200)
        self.assertTrue(payload["scanned"])

    def test_ports_with_a_bad_scan_spec(self):
        status, payload = self.request("/api/ports?scan=not-a-port")
        self.assertEqual(status, 400)
        self.assertIn("error", payload)

    def test_services_listing_and_filter(self):
        status, payload = self.request("/api/services?all=1&filter=nginx")
        self.assertEqual(status, 200)
        self.assertEqual([s["name"] for s in payload["services"]], ["nginx.service"])

    def test_single_service(self):
        status, payload = self.request("/api/service/nginx")
        self.assertEqual(status, 200)
        self.assertEqual(payload["name"], "nginx.service")

    def test_unknown_service_is_404(self):
        status, _ = self.request("/api/service/doesnotexist")
        self.assertEqual(status, 404)

    def test_unknown_endpoint_is_404(self):
        status, _ = self.request("/api/nope")
        self.assertEqual(status, 404)


class ControlEndpointTests(ServerTestCase):
    def test_stop_a_service(self):
        status, payload = self.request(
            "/api/control", method="POST", body={"service": "nginx", "action": "stop"}
        )
        self.assertEqual(status, 200)
        self.assertTrue(payload["results"][0]["ok"])

    def test_protected_service_conflicts(self):
        status, payload = self.request(
            "/api/control", method="POST", body={"service": "ssh", "action": "stop"}
        )
        self.assertEqual(status, 409)
        self.assertIn("protected", payload["results"][0]["detail"])

    def test_protected_service_with_force(self):
        status, payload = self.request(
            "/api/control",
            method="POST",
            body={"service": "ssh", "action": "stop", "force": True},
        )
        self.assertEqual(status, 200)
        self.assertTrue(payload["results"][0]["ok"])

    def test_by_port(self):
        status, payload = self.request(
            "/api/control", method="POST", body={"port": 80, "action": "restart"}
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["results"][0]["service"], "nginx.service")

    def test_unknown_action_is_rejected(self):
        status, payload = self.request(
            "/api/control", method="POST", body={"service": "nginx", "action": "melt"}
        )
        self.assertEqual(status, 400)
        self.assertIn("action must be one of", payload["error"])

    def test_missing_target_is_rejected(self):
        status, payload = self.request("/api/control", method="POST", body={"action": "stop"})
        self.assertEqual(status, 400)
        self.assertIn("service name or a port", payload["error"])

    def test_malformed_body(self):
        request = urllib.request.Request(
            self.base + "/api/control", method="POST", data=b"{not json"
        )
        request.add_header("X-PortWarden-Token", self.token)
        try:
            self.opener.open(request, timeout=10)
            self.fail("expected an HTTP error")
        except urllib.error.HTTPError as exc:
            self.assertEqual(exc.code, 400)

    def test_actions_show_up_in_the_audit_endpoint(self):
        self.request("/api/control", method="POST", body={"service": "nginx", "action": "stop"})
        status, payload = self.request("/api/audit?limit=5")
        self.assertEqual(status, 200)
        self.assertEqual(payload["entries"][0]["service"], "nginx")


class StaticTests(ServerTestCase):
    def test_index_html_is_served(self):
        with self.opener.open(self.base + "/", timeout=10) as response:
            body = response.read().decode()
            self.assertEqual(response.status, 200)
            self.assertIn("portwarden", body)
            self.assertIn("app.js", body)

    def test_assets_are_served_with_their_types(self):
        for path, content_type, needle in (
            ("/app.js", "text/javascript", "X-PortWarden-Token"),
            ("/app.css", "text/css", "--accent"),
        ):
            with self.subTest(path=path):
                with self.opener.open(self.base + path, timeout=10) as response:
                    self.assertEqual(response.status, 200)
                    self.assertIn(content_type, response.headers["Content-Type"])
                    self.assertIn(needle, response.read().decode())

    def test_csp_forbids_inline_and_remote_code(self):
        with self.opener.open(self.base + "/", timeout=10) as response:
            policy = response.headers["Content-Security-Policy"]
        self.assertIn("default-src 'none'", policy)
        self.assertIn("script-src 'self'", policy)
        self.assertNotIn("unsafe-inline", policy)

    def test_unknown_path_is_404(self):
        status, _ = self.request("/../etc/passwd", token=False)
        self.assertEqual(status, 404)

    def test_other_files_in_the_package_are_not_served(self):
        for path in ("/server.py", "/static/app.js", "/app.js/../server.py"):
            with self.subTest(path=path):
                status, _ = self.request(path, token=False)
                self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
