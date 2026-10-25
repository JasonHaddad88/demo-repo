import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from vxpress import cli, download, jobs as jobs_module, media
from tests.test_vx_jobs import fake_stream_class
from tests.vxhelpers import (
    FFMPEG_PROGRESS_LINES,
    PROBE_JSON,
    YTDLP_AUDIO_LINES,
    YTDLP_DOWNLOAD_LINES,
    fake_toolchain,
)


class CliTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "out"
        self.clip = Path(self.tmp.name) / "clip.mp4"
        self.clip.write_bytes(b"fake video")
        self.history = Path(self.tmp.name) / "history.jsonl"

        patcher = mock.patch.object(
            cli.Toolchain, "detect", staticmethod(lambda *a, **k: fake_toolchain(ffprobe=False))
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        history_patcher = mock.patch.object(
            jobs_module, "history_path", lambda: self.history
        )
        history_patcher.start()
        self.addCleanup(history_patcher.stop)

    def invoke(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cli.main(list(argv))
        return code, out.getvalue(), err.getvalue()


class DoctorTests(CliTestCase):
    def test_everything_present(self):
        # ffprobe is absent in this fixture, which only costs us titles and
        # durations, so doctor still reports a usable install.
        code, out, _ = self.invoke("doctor")
        self.assertEqual(code, 0)
        self.assertIn("yt-dlp", out)
        self.assertIn("convert to mp3    yes", out)
        self.assertIn("MISSING", out)

    def test_missing_tools_are_called_out(self):
        with mock.patch.object(
            cli.Toolchain, "detect", staticmethod(lambda *a, **k: fake_toolchain(ytdlp=False))
        ):
            code, out, _ = self.invoke("doctor")
        self.assertEqual(code, 1)
        self.assertIn("MISSING", out)
        self.assertIn("download videos   no", out)
        self.assertIn("install yt-dlp", out)

    def test_json_form(self):
        code, out, _ = self.invoke("--json", "doctor")
        payload = json.loads(out)
        self.assertIn("yt-dlp", payload)
        self.assertIn("output_audio", payload)
        self.assertFalse(payload["ffprobe"]["found"])
        self.assertEqual(code, 0)

    def test_all_three_tools_present_exits_zero(self):
        with mock.patch.object(
            cli.Toolchain, "detect", staticmethod(lambda *a, **k: fake_toolchain())
        ):
            code, _, _ = self.invoke("doctor")
        self.assertEqual(code, 0)


class InfoTests(CliTestCase):
    def test_local_file(self):
        info = media.parse_probe(PROBE_JSON, str(self.clip))
        with mock.patch.object(cli.Toolchain, "detect", staticmethod(lambda *a, **k: fake_toolchain())), \
             mock.patch.object(media, "probe", return_value=info):
            code, out, _ = self.invoke("info", str(self.clip))
        self.assertEqual(code, 0)
        self.assertIn("A Test Clip", out)
        self.assertIn("3:33", out)
        self.assertIn("audio     aac", out)

    def test_link(self):
        details = download.parse_info(json.loads(
            '{"id": "abc", "title": "Some Video", "duration": 60, "uploader": "Chan",'
            ' "formats": [{"format_id": "18", "ext": "mp4", "height": 360}]}'
        ))
        with mock.patch.object(download, "info", return_value=details):
            code, out, _ = self.invoke("info", "https://example.com/watch?v=abc")
        self.assertEqual(code, 0)
        self.assertIn("Some Video", out)
        self.assertIn("1:00", out)
        self.assertIn("RESOLUTION", out)

    def test_link_json(self):
        details = download.parse_info(json.loads('{"id": "abc", "title": "T"}'))
        with mock.patch.object(download, "info", return_value=details):
            code, out, _ = self.invoke("--json", "info", "https://example.com/x")
        self.assertEqual(json.loads(out)["title"], "T")
        self.assertEqual(code, 0)

    def test_bad_target(self):
        code, _, err = self.invoke("info", "ftp://example.com/x")
        self.assertEqual(code, 2)
        self.assertIn("not supported", err)


class ConvertTests(CliTestCase):
    def test_local_file_to_mp3(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            code, out, _ = self.invoke("--out", str(self.out), "mp3", str(self.clip))
        self.assertEqual(code, 0)
        self.assertIn("[ok]", out)
        self.assertIn("clip.mp3", out)

    def test_link_to_mp3(self):
        with mock.patch.object(jobs_module, "Stream", fake_stream_class(YTDLP_AUDIO_LINES)):
            code, out, _ = self.invoke("--out", str(self.out), "mp3", "https://example.com/x")
        self.assertEqual(code, 0)
        self.assertIn(".mp3", out)

    def test_link_to_video(self):
        with mock.patch.object(jobs_module, "Stream", fake_stream_class(YTDLP_DOWNLOAD_LINES)):
            code, out, _ = self.invoke("--out", str(self.out), "get", "https://example.com/x")
        self.assertEqual(code, 0)
        self.assertIn(".mp4", out)

    def test_failed_job_exits_one(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(["ERROR: Video unavailable"], returncode=1)
        ):
            code, _, err = self.invoke("--out", str(self.out), "mp3", "https://example.com/x")
        self.assertEqual(code, 1)
        self.assertIn("[failed]", err)
        self.assertIn("unavailable", err)

    def test_several_targets_in_one_run(self):
        second = Path(self.tmp.name) / "other.mp4"
        second.write_bytes(b"fake")
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            code, out, _ = self.invoke(
                "--out", str(self.out), "mp3", str(self.clip), str(second)
            )
        self.assertEqual(code, 0)
        self.assertEqual(out.count("[ok]"), 2)

    def test_batch_file(self):
        listing = Path(self.tmp.name) / "list.txt"
        listing.write_text(f"# links\n{self.clip}\n\n")
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            code, out, _ = self.invoke(
                "--out", str(self.out), "mp3", "--batch-file", str(listing)
            )
        self.assertEqual(code, 0)
        self.assertIn("[ok]", out)

    def test_missing_batch_file(self):
        code, _, err = self.invoke("mp3", "--batch-file", "/nope/list.txt")
        self.assertEqual(code, 2)
        self.assertIn("cannot read", err)

    def test_no_targets_at_all(self):
        code, _, err = self.invoke("mp3")
        self.assertEqual(code, 2)
        self.assertIn("at least one link or file", err)

    def test_one_bad_target_does_not_stop_the_others(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            code, out, err = self.invoke(
                "--out", str(self.out), "mp3", "/nope/missing.mp4", str(self.clip)
            )
        self.assertEqual(code, 1)
        self.assertIn("no such file", err)
        self.assertIn("[ok]", out)

    def test_missing_tool_exits_127(self):
        # A missing program is a condition of the whole run, not of one target.
        with mock.patch.object(
            cli.Toolchain, "detect", staticmethod(lambda *a, **k: fake_toolchain(ffmpeg=False, ffprobe=False))
        ):
            code, _, err = self.invoke("mp3", str(self.clip))
        self.assertEqual(code, 127)
        self.assertIn("ffmpeg is not installed", err)
        self.assertIn("install it with", err)

    def test_json_output(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            code, out, _ = self.invoke("--json", "--out", str(self.out), "mp3", str(self.clip))
        payload = json.loads(out)
        self.assertEqual(payload[0]["status"], "done")
        self.assertEqual(code, 0)

    def test_quality_flags_reach_the_command(self):
        record = []
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(YTDLP_AUDIO_LINES, record=record)
        ):
            self.invoke(
                "--out", str(self.out), "mp3", "https://example.com/x",
                "--bitrate", "320", "--thumbnail",
            )
        self.assertIn("320K", record[0].argv)
        self.assertIn("--embed-thumbnail", record[0].argv)

    def test_bad_timestamp_is_rejected(self):
        code, _, err = self.invoke("mp3", str(self.clip), "--start", "banana")
        self.assertEqual(code, 2)
        self.assertIn("not a timestamp", err)


class HistoryTests(CliTestCase):
    def test_empty(self):
        code, out, _ = self.invoke("history")
        self.assertEqual(code, 0)
        self.assertIn("Nothing yet", out)

    def test_entries_are_listed(self):
        self.history.parent.mkdir(parents=True, exist_ok=True)
        self.history.write_text(
            json.dumps({"ts": "2026-10-25T10:00:00+0000", "kind": "mp3", "status": "done",
                        "quality": "192 kbps", "output": "/music/song.mp3"}) + "\n"
        )
        code, out, _ = self.invoke("history")
        self.assertIn("song.mp3", out)
        self.assertIn("192 kbps", out)
        self.assertEqual(code, 0)


class ParserTests(unittest.TestCase):
    def test_subcommands(self):
        parser = cli.build_parser()
        for command in ("get", "mp3", "info", "doctor", "history", "serve"):
            with self.subTest(command=command):
                args = parser.parse_args(
                    [command, "x"] if command in ("get", "mp3", "info") else [command]
                )
                self.assertEqual(args.command, command)

    def test_audio_defaults(self):
        args = cli.build_parser().parse_args(["mp3", "x"])
        self.assertEqual(args.bitrate, media.DEFAULT_BITRATE)
        self.assertIsNone(args.vbr)
        self.assertFalse(args.thumbnail)

    def test_video_defaults(self):
        args = cli.build_parser().parse_args(["get", "x"])
        self.assertEqual(args.container, "mp4")
        self.assertIsNone(args.max_height)

    def test_serve_defaults(self):
        args = cli.build_parser().parse_args(["serve"])
        self.assertEqual((args.bind, args.port), ("127.0.0.1", 8799))

    def test_container_choices_are_enforced(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            cli.build_parser().parse_args(["get", "x", "--container", "avi"])

    def test_command_is_required(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            cli.build_parser().parse_args([])


if __name__ == "__main__":
    unittest.main()
