import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

from vxpress import jobs as jobs_module, server
from vxpress.jobs import DONE, JobQueue
from vxpress.sources import BadSource
from tests.test_vx_jobs import fake_stream_class
from tests.vxhelpers import FFMPEG_PROGRESS_LINES, fake_toolchain


def _with_data(request, data):
    request.data = data
    return request


class ServerTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "out"
        self.uploads = Path(self.tmp.name) / "uploads"
        self.history = Path(self.tmp.name) / "history.jsonl"
        self.clip = Path(self.tmp.name) / "clip.mp4"
        self.clip.write_bytes(b"fake video")

        self.queue = JobQueue(
            tools=fake_toolchain(ffprobe=False), workers=1, history=self.history
        )
        self.addCleanup(self.queue.shutdown, False)

        self.httpd, self.token = server.build_server(
            self.queue, host="127.0.0.1", port=0, quiet=True, upload_dir=self.uploads
        )
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True
        )
        self.thread.start()
        self.addCleanup(self._shutdown)

    def _shutdown(self):
        self.httpd.shutdown()
        self.thread.join(timeout=5)
        self.httpd.server_close()

    def request(self, path, method="GET", token=None, body=None, headers=None, raw=None):
        request = urllib.request.Request(self.base + path, method=method)
        if token is not False:
            request.add_header("X-VXpress-Token", token or self.token)
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(
                request if data is None else _with_data(request, data), timeout=10
            ) as response:
                payload = response.read()
                try:
                    return response.status, json.loads(payload or b"{}")
                except json.JSONDecodeError:
                    return response.status, {"raw": payload}
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            try:
                return exc.code, json.loads(payload or b"{}")
            except json.JSONDecodeError:
                return exc.code, {"raw": payload.decode(errors="replace")}


class BindingTests(unittest.TestCase):
    def test_only_loopback_may_be_bound(self):
        queue = JobQueue(tools=fake_toolchain(), workers=1, start=False)
        self.addCleanup(queue.shutdown, False)
        for host in ("0.0.0.0", "192.168.1.5", ""):
            with self.subTest(host=host), self.assertRaises(BadSource) as ctx:
                server.build_server(queue, host=host, port=0)
            self.assertIn("loopback", str(ctx.exception))

    def test_a_token_is_generated(self):
        queue = JobQueue(tools=fake_toolchain(), workers=1, start=False)
        self.addCleanup(queue.shutdown, False)
        httpd, token = server.build_server(queue, port=0, quiet=True)
        self.addCleanup(httpd.server_close)
        self.assertGreaterEqual(len(token), 20)


class AuthTests(ServerTestCase):
    def test_token_required(self):
        status, payload = self.request("/api/jobs", token=False)
        self.assertEqual(status, 401)
        self.assertIn("token", payload["error"])

    def test_wrong_token(self):
        self.assertEqual(self.request("/api/jobs", token="wrong")[0], 401)

    def test_query_token_is_accepted(self):
        status, _ = self.request(f"/api/env?token={self.token}", token=False)
        self.assertEqual(status, 200)

    def test_foreign_host_header_refused(self):
        status, payload = self.request("/api/jobs", headers={"Host": "vxpress.example.com"})
        self.assertEqual(status, 403)
        self.assertIn("localhost", payload["error"])

    def test_cross_origin_post_refused(self):
        status, payload = self.request(
            "/api/jobs",
            method="POST",
            body={"kind": "mp3", "target": "https://example.com/x"},
            headers={"Origin": "https://evil.example"},
        )
        self.assertEqual(status, 403)
        self.assertIn("cross-origin", payload["error"])

    def test_healthz_needs_no_token(self):
        status, payload = self.request("/healthz", token=False)
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])


class ReadEndpointTests(ServerTestCase):
    def test_env(self):
        status, payload = self.request("/api/env")
        self.assertEqual(status, 200)
        self.assertTrue(payload["tools"]["yt-dlp"]["found"])
        self.assertIn("audio_dir", payload)
        self.assertIn(192, payload["bitrates"])

    def test_jobs_listing_starts_empty(self):
        status, payload = self.request("/api/jobs")
        self.assertEqual(status, 200)
        self.assertEqual(payload["jobs"], [])

    def test_unknown_job(self):
        self.assertEqual(self.request("/api/jobs/nope")[0], 404)

    def test_info_for_a_link(self):
        from vxpress import download

        details = download.parse_info(json.loads('{"id": "a", "title": "Clip", "duration": 10}'))
        with mock.patch.object(server.download, "info", return_value=details):
            status, payload = self.request("/api/info?target=https://example.com/x")
        self.assertEqual(status, 200)
        self.assertEqual(payload["kind"], "url")
        self.assertEqual(payload["info"]["title"], "Clip")

    def test_info_rejects_a_bad_target(self):
        status, payload = self.request("/api/info?target=ftp://example.com/x")
        self.assertEqual(status, 400)
        self.assertIn("not supported", payload["error"])

    def test_history(self):
        self.history.parent.mkdir(parents=True, exist_ok=True)
        self.history.write_text(json.dumps({"status": "done", "kind": "mp3"}) + "\n")
        status, payload = self.request("/api/history?limit=5")
        self.assertEqual(status, 200)
        self.assertEqual(payload["entries"][0]["kind"], "mp3")

    def test_unknown_endpoint(self):
        self.assertEqual(self.request("/api/nope")[0], 404)


class JobEndpointTests(ServerTestCase):
    def test_creating_a_conversion_job(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            status, payload = self.request(
                "/api/jobs",
                method="POST",
                body={"kind": "mp3", "target": str(self.clip), "out_dir": str(self.out),
                      "bitrate": 256},
            )
            self.assertEqual(status, 202)
            self.assertEqual(payload["kind"], "mp3")
            self.assertEqual(payload["options"]["quality"], "256 kbps")
            self.assertTrue(self.queue.wait(10))
        job = self.queue.get(payload["id"])
        self.assertEqual(job.status, DONE)

    def test_unknown_kind_is_refused(self):
        status, payload = self.request(
            "/api/jobs", method="POST", body={"kind": "gif", "target": str(self.clip)}
        )
        self.assertEqual(status, 400)
        self.assertIn("kind must be one of", payload["error"])

    def test_bad_target_is_refused(self):
        status, payload = self.request(
            "/api/jobs", method="POST", body={"kind": "mp3", "target": "/nope/x.mp4"}
        )
        self.assertEqual(status, 400)
        self.assertIn("no such file", payload["error"])

    def test_bad_timestamp_is_refused(self):
        status, payload = self.request(
            "/api/jobs",
            method="POST",
            body={"kind": "mp3", "target": str(self.clip), "start": "nope"},
        )
        self.assertEqual(status, 400)
        self.assertIn("not a timestamp", payload["error"])

    def test_missing_tool_reports_service_unavailable(self):
        self.queue.tools = fake_toolchain(ytdlp=False)
        status, payload = self.request(
            "/api/jobs", method="POST", body={"kind": "mp3", "target": "https://example.com/x"}
        )
        self.assertEqual(status, 503)
        self.assertIn("yt-dlp is not installed", payload["error"])

    def test_malformed_body(self):
        status, _ = self.request("/api/jobs", method="POST", raw=b"{not json")
        self.assertEqual(status, 400)

    def test_cancel(self):
        queue = self.queue
        queue.shutdown(wait=True)  # nothing will pick the job up
        status, payload = self.request(
            "/api/jobs",
            method="POST",
            body={"kind": "mp3", "target": str(self.clip), "out_dir": str(self.out)},
        )
        self.assertEqual(status, 202)
        status, cancelled = self.request(f"/api/jobs/{payload['id']}/cancel", method="POST")
        self.assertEqual(status, 200)
        self.assertEqual(cancelled["status"], "cancelled")

    def test_clear_finished(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            self.request(
                "/api/jobs",
                method="POST",
                body={"kind": "mp3", "target": str(self.clip), "out_dir": str(self.out)},
            )
            self.assertTrue(self.queue.wait(10))
        status, payload = self.request("/api/jobs/clear", method="POST")
        self.assertEqual(status, 200)
        self.assertEqual(payload["removed"], 1)


class DownloadEndpointTests(ServerTestCase):
    def _finished_job(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            job = self.queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(self.queue.wait(10))
        self.assertEqual(job.status, DONE)
        return job

    def test_finished_file_is_served_as_an_attachment(self):
        job = self._finished_job()
        request = urllib.request.Request(self.base + f"/api/download/{job.id}")
        request.add_header("X-VXpress-Token", self.token)
        with self.opener.open(request, timeout=10) as response:
            body = response.read()
            self.assertEqual(response.status, 200)
            self.assertIn("attachment", response.headers["Content-Disposition"])
            self.assertIn("clip.mp3", response.headers["Content-Disposition"])
        self.assertEqual(body, Path(job.output_path).read_bytes())

    def test_unfinished_job_has_nothing_to_download(self):
        self.queue.shutdown(wait=True)
        job = self.queue.submit("mp3", str(self.clip), out_dir=self.out)
        status, payload = self.request(f"/api/download/{job.id}")
        self.assertEqual(status, 409)
        self.assertIn("not produced a file", payload["error"])

    def test_unknown_job(self):
        self.assertEqual(self.request("/api/download/nope")[0], 404)

    def test_deleted_file_is_reported(self):
        job = self._finished_job()
        Path(job.output_path).unlink()
        status, payload = self.request(f"/api/download/{job.id}")
        self.assertEqual(status, 404)
        self.assertIn("no longer there", payload["error"])

    def test_a_file_outside_the_output_folder_is_refused(self):
        job = self._finished_job()
        job.output_path = str(self.clip)  # outside job.out_dir
        status, payload = self.request(f"/api/download/{job.id}")
        self.assertEqual(status, 403)
        self.assertIn("outside the output folder", payload["error"])


class UploadEndpointTests(ServerTestCase):
    def test_a_dropped_file_is_saved_where_a_job_can_read_it(self):
        payload_bytes = b"pretend this is an mkv" * 50
        status, payload = self.request(
            "/api/upload",
            method="POST",
            raw=payload_bytes,
            headers={"X-Filename": "My%20Clip.mkv", "Content-Type": "application/octet-stream"},
        )
        self.assertEqual(status, 201)
        saved = Path(payload["path"])
        self.assertTrue(saved.is_file())
        self.assertEqual(saved.read_bytes(), payload_bytes)
        self.assertEqual(saved.parent, self.uploads)
        self.assertEqual(payload["name"], "My Clip.mkv")
        self.assertEqual(payload["size"], len(payload_bytes))

    def test_dangerous_names_are_neutralised(self):
        status, payload = self.request(
            "/api/upload",
            method="POST",
            raw=b"x",
            headers={"X-Filename": "..%2F..%2Fetc%2Fpasswd"},
        )
        self.assertEqual(status, 201)
        saved = Path(payload["path"])
        # What matters is that nothing escapes the upload folder: the path
        # separators are gone, so the name is a single harmless component.
        self.assertEqual(saved.parent, self.uploads)
        self.assertNotIn("/", saved.name)
        self.assertNotIn("\\", saved.name)
        self.assertNotEqual(saved.name.strip("._"), "")
        self.assertTrue(saved.is_file())

    def test_empty_upload(self):
        status, payload = self.request("/api/upload", method="POST", raw=b"")
        self.assertEqual(status, 400)
        self.assertIn("no file content", payload["error"])

    def test_uploaded_file_can_then_be_converted(self):
        status, payload = self.request(
            "/api/upload", method="POST", raw=b"video bytes", headers={"X-Filename": "drop.mp4"}
        )
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            status, job = self.request(
                "/api/jobs",
                method="POST",
                body={"kind": "mp3", "target": payload["path"], "out_dir": str(self.out)},
            )
            self.assertEqual(status, 202)
            self.assertTrue(self.queue.wait(10))
        self.assertEqual(self.queue.get(job["id"]).status, DONE)


class StaticTests(ServerTestCase):
    def test_index(self):
        with self.opener.open(self.base + "/", timeout=10) as response:
            body = response.read().decode()
        self.assertIn("VXpress", body)
        self.assertIn("app.js", body)

    def test_assets(self):
        for path, content_type in (("/app.js", "text/javascript"), ("/app.css", "text/css")):
            with self.subTest(path=path):
                with self.opener.open(self.base + path, timeout=10) as response:
                    self.assertEqual(response.status, 200)
                    self.assertIn(content_type, response.headers["Content-Type"])

    def test_csp_allows_no_inline_or_remote_code(self):
        with self.opener.open(self.base + "/", timeout=10) as response:
            policy = response.headers["Content-Security-Policy"]
        self.assertIn("default-src 'none'", policy)
        self.assertIn("script-src 'self'", policy)
        self.assertNotIn("unsafe-inline", policy)

    def test_nothing_else_is_served(self):
        for path in ("/server.py", "/static/app.js", "/../etc/passwd"):
            with self.subTest(path=path):
                self.assertEqual(self.request(path, token=False)[0], 404)


if __name__ == "__main__":
    unittest.main()
