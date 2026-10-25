import json
import os
import unittest
from unittest import mock

from vxpress import download
from vxpress.download import DownloadError, YtdlpProgress
from vxpress.media import AudioOptions
from tests.vxhelpers import (
    YTDLP_AUDIO_LINES,
    YTDLP_DOWNLOAD_LINES,
    YTDLP_INFO_JSON,
    YTDLP_PLAYLIST_JSON,
    fake_capture,
    fake_toolchain,
    stream_factory,
)


class InfoTests(unittest.TestCase):
    def setUp(self):
        self.tools = fake_toolchain()

    def test_single_video(self):
        runner = fake_capture({"yt-dlp": (0, YTDLP_INFO_JSON)})
        info = download.info("https://example.com/watch?v=abc123", self.tools, runner=runner)
        self.assertEqual(info.title, "Some Video")
        self.assertEqual(info.video_id, "abc123")
        self.assertEqual(info.uploader, "A Channel")
        self.assertEqual(info.duration, 245.0)
        self.assertFalse(info.is_playlist)
        self.assertEqual(len(info.formats), 2)
        self.assertEqual(info.to_dict()["duration_label"], "4:05")

    def test_playlist(self):
        runner = fake_capture({"yt-dlp": (0, YTDLP_PLAYLIST_JSON)})
        info = download.info("https://example.com/playlist?list=PL1", self.tools, playlist=True, runner=runner)
        self.assertTrue(info.is_playlist)
        self.assertEqual([entry["title"] for entry in info.entries], ["First", "Second"])
        self.assertEqual(info.entries[1]["duration_label"], "3:20")
        self.assertIn("--flat-playlist", " ".join(runner.calls[0]))

    def test_json_mixed_with_warnings_is_still_read(self):
        noisy = "WARNING: something\n" + YTDLP_INFO_JSON + "\n"
        runner = fake_capture({"yt-dlp": (0, noisy)})
        self.assertEqual(download.info("https://example.com/x", self.tools, runner=runner).title, "Some Video")

    def test_failure_is_explained(self):
        runner = fake_capture({"yt-dlp": (1, "ERROR: Unsupported URL: https://example.com/x")})
        with self.assertRaises(DownloadError) as ctx:
            download.info("https://example.com/x", self.tools, runner=runner)
        self.assertIn("does not support this site", str(ctx.exception))

    def test_no_json_at_all(self):
        runner = fake_capture({"yt-dlp": (0, "nothing useful here")})
        with self.assertRaises(DownloadError):
            download.info("https://example.com/x", self.tools, runner=runner)

    def test_missing_ytdlp_is_reported_before_running(self):
        from vxpress.process import ToolMissing

        with self.assertRaises(ToolMissing):
            download.info("https://example.com/x", fake_toolchain(ytdlp=False))

    def test_parse_info_tolerates_sparse_payloads(self):
        info = download.parse_info(json.loads('{"id": "z"}'))
        self.assertEqual(info.title, "z")
        self.assertIsNone(info.duration)
        self.assertEqual(info.formats, [])


class ArgvTests(unittest.TestCase):
    def setUp(self):
        self.tools = fake_toolchain()
        self.url = "https://example.com/watch?v=abc123"

    def test_video_command(self):
        argv = download.video_argv(self.url, "/out", self.tools)
        self.assertEqual(argv[0], "/fake/yt-dlp")
        self.assertIn("--no-playlist", argv)
        self.assertIn("--newline", argv)
        self.assertEqual(argv[argv.index("-P") + 1], "/out")
        self.assertEqual(argv[argv.index("--merge-output-format") + 1], "mp4")
        self.assertEqual(argv[-1], self.url)
        self.assertEqual(argv[-2], "--")  # the URL can never look like an option
        self.assertIn("--ffmpeg-location", argv)

    def test_resolution_cap_goes_into_the_format_selector(self):
        argv = download.video_argv(self.url, "/out", self.tools, max_height=720)
        selector = argv[argv.index("-f") + 1]
        self.assertIn("height<=720", selector)

    def test_container_any_skips_merging(self):
        argv = download.video_argv(self.url, "/out", self.tools, container="any")
        self.assertNotIn("--merge-output-format", argv)

    def test_playlist_mode(self):
        argv = download.video_argv(self.url, "/out", self.tools, playlist=True)
        self.assertIn("--yes-playlist", argv)
        self.assertIn("--ignore-errors", argv)
        self.assertIn("playlist_index", argv[argv.index("-o") + 1])

    def test_audio_command(self):
        argv = download.audio_argv(self.url, "/out", self.tools, AudioOptions())
        self.assertIn("-x", argv)
        self.assertEqual(argv[argv.index("--audio-format") + 1], "mp3")
        self.assertEqual(argv[argv.index("--audio-quality") + 1], "192K")
        self.assertIn("--embed-metadata", argv)
        self.assertNotIn("--embed-thumbnail", argv)

    def test_audio_vbr_and_thumbnail(self):
        argv = download.audio_argv(
            self.url, "/out", self.tools, AudioOptions(vbr=0, thumbnail=True, metadata=False)
        )
        self.assertEqual(argv[argv.index("--audio-quality") + 1], "0")
        self.assertIn("--embed-thumbnail", argv)
        self.assertNotIn("--embed-metadata", argv)

    def test_audio_trim_uses_download_sections(self):
        argv = download.audio_argv(self.url, "/out", self.tools, AudioOptions(start=30, end=90))
        self.assertEqual(argv[argv.index("--download-sections") + 1], "*30.000-90.000")
        argv = download.audio_argv(self.url, "/out", self.tools, AudioOptions(start=30))
        self.assertEqual(argv[argv.index("--download-sections") + 1], "*30.000-inf")

    def test_extra_args_from_the_environment(self):
        with mock.patch.dict(os.environ, {download.EXTRA_ARGS_ENV: "--cookies /tmp/c.txt --limit-rate 1M"}):
            argv = download.video_argv(self.url, "/out", self.tools)
        self.assertIn("--cookies", argv)
        self.assertEqual(argv[argv.index("--cookies") + 1], "/tmp/c.txt")
        self.assertLess(argv.index("--cookies"), argv.index("--"))


class ProgressTests(unittest.TestCase):
    def test_video_download_sequence(self):
        progress = YtdlpProgress()
        for line in YTDLP_DOWNLOAD_LINES:
            progress.feed(line)
        self.assertEqual(progress.percent, 100.0)
        self.assertEqual(progress.final_file, "/out/Some Video [abc123].mp4")
        self.assertEqual(progress.stage, "merging audio and video")

    def test_audio_download_sequence(self):
        progress = YtdlpProgress()
        for line in YTDLP_AUDIO_LINES:
            progress.feed(line)
        self.assertEqual(progress.final_file, "/out/Some Video [abc123].mp3")
        self.assertEqual(progress.stage, "converting to mp3")

    def test_percent_parsing(self):
        progress = YtdlpProgress()
        self.assertTrue(progress.feed("[download]  52.3% of 10.00MiB at 2.00MiB/s ETA 00:02"))
        self.assertEqual(progress.percent, 52.3)
        self.assertEqual(progress.stage, "downloading")

    def test_already_downloaded_counts_as_the_output(self):
        progress = YtdlpProgress()
        progress.feed("[download] /out/Some Video [abc123].mp4 has already been downloaded")
        self.assertEqual(progress.final_file, "/out/Some Video [abc123].mp4")

    def test_moved_file_wins(self):
        progress = YtdlpProgress()
        progress.feed("[ExtractAudio] Destination: /tmp/song.mp3")
        progress.feed('[MoveFiles] Moving file "/tmp/song.mp3" to "/out/song.mp3"')
        self.assertEqual(progress.final_file, "/out/song.mp3")

    def test_playlist_items_are_tracked(self):
        progress = YtdlpProgress()
        progress.feed("[download] Downloading item 2 of 5")
        self.assertEqual(progress.item, (2, 5))
        self.assertIn("2 of 5", progress.stage)

    def test_unrelated_lines_change_nothing(self):
        progress = YtdlpProgress()
        self.assertFalse(progress.feed("[youtube] Extracting URL: https://example.com"))


class RunDownloadTests(unittest.TestCase):
    def test_returns_the_merged_video(self):
        seen = []
        produced, result = download.run_download(
            ["yt-dlp", "url"],
            on_progress=lambda percent, stage: seen.append((percent, stage)),
            want_extension=".mp4",
            make_stream=stream_factory(YTDLP_DOWNLOAD_LINES),
        )
        self.assertEqual(produced, "/out/Some Video [abc123].mp4")
        self.assertTrue(result.ok)
        self.assertTrue(seen)

    def test_prefers_the_requested_extension(self):
        produced, _ = download.run_download(
            ["yt-dlp", "url"],
            want_extension=".mp3",
            make_stream=stream_factory(YTDLP_AUDIO_LINES),
        )
        self.assertTrue(produced.endswith(".mp3"))

    def test_failure_raises_with_an_explanation(self):
        with self.assertRaises(DownloadError) as ctx:
            download.run_download(
                ["yt-dlp", "url"],
                make_stream=stream_factory(["ERROR: Video unavailable"], returncode=1),
            )
        self.assertIn("unavailable", str(ctx.exception))

    def test_success_without_a_filename_is_an_error(self):
        with self.assertRaises(DownloadError) as ctx:
            download.run_download(["yt-dlp", "url"], make_stream=stream_factory(["[info] nothing"]))
        self.assertIn("did not say which file", str(ctx.exception))


class ExplainTests(unittest.TestCase):
    def test_known_failures_get_plain_language(self):
        cases = {
            "ERROR: Unsupported URL: https://x": "does not support this site",
            "ERROR: Video unavailable": "unavailable",
            "ERROR: Private video. Sign in if you've been granted access": "private",
            "ERROR: unable to download webpage": "could not reach the site",
            "ERROR: HTTP Error 429: Too Many Requests": "rate-limiting",
            "ERROR: This video is DRM protected": "DRM-protected",
            "ERROR: Requested format is not available": "no matching format",
            "ERROR: No space left on device": "disk is full",
        }
        for output, needle in cases.items():
            with self.subTest(output=output):
                self.assertIn(needle, download.explain(output))

    def test_last_error_line_wins(self):
        text = "WARNING: x\nERROR: first problem\nERROR: Video unavailable\n"
        self.assertIn("unavailable", download.explain(text))

    def test_unknown_errors_are_passed_through(self):
        self.assertIn("something odd", download.explain("ERROR: something odd happened"))

    def test_empty_output(self):
        self.assertIn("without saying why", download.explain(""))


if __name__ == "__main__":
    unittest.main()
