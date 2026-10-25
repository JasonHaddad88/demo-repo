import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from vxpress import sources
from vxpress.sources import BadSource


class ClassifyTests(unittest.TestCase):
    def test_http_and_https_links(self):
        for url in (
            "https://example.com/watch?v=abc",
            "http://example.com/video.mp4",
            "  https://example.com/x  ",
            '"https://example.com/y"',
        ):
            with self.subTest(url=url):
                source = sources.classify(url)
                self.assertEqual(source.kind, "url")
                self.assertTrue(source.value.startswith("http"))

    def test_other_schemes_are_refused(self):
        for url in ("ftp://example.com/x", "magnet:?xt=urn:btih:abc", "javascript:alert(1)"):
            with self.subTest(url=url), self.assertRaises(BadSource) as ctx:
                sources.classify(url)
            self.assertIn("not supported", str(ctx.exception))

    def test_link_without_a_host(self):
        with self.assertRaises(BadSource):
            sources.classify("https:///nothing")

    def test_local_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "clip.mp4"
            path.write_bytes(b"x")
            source = sources.classify(str(path))
            self.assertEqual(source.kind, "file")
            self.assertEqual(source.value, str(path.resolve()))
            self.assertEqual(source.label, "clip.mp4")

    def test_missing_file_mentions_links(self):
        with self.assertRaises(BadSource) as ctx:
            sources.classify("/definitely/not/here.mp4")
        self.assertIn("no such file", str(ctx.exception))
        self.assertIn("https://", str(ctx.exception))

    def test_directory_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(BadSource) as ctx:
                sources.classify(tmp)
            self.assertIn("folder", str(ctx.exception))

    def test_empty_input(self):
        for value in ("", "   ", None):
            with self.subTest(value=value), self.assertRaises(BadSource):
                sources.classify(value)

    def test_windows_style_path_is_not_mistaken_for_a_scheme(self):
        # "C:" must read as a drive letter, not a URL scheme.
        with self.assertRaises(BadSource) as ctx:
            sources.classify(r"C:\Users\User\Videos\clip.mp4")
        self.assertIn("no such file", str(ctx.exception))


class FilenameTests(unittest.TestCase):
    def test_strips_characters_filesystems_reject(self):
        self.assertEqual(sources.safe_filename('a/b\\c:d*e?f"g<h>i|j'), "a_b_c_d_e_f_g_h_i_j")

    def test_collapses_whitespace_and_trims_dots(self):
        self.assertEqual(sources.safe_filename("  My   Song ... "), "My Song")

    def test_windows_reserved_names(self):
        self.assertEqual(sources.safe_filename("CON"), "_CON")
        self.assertEqual(sources.safe_filename("nul.mp3"), "_nul.mp3")

    def test_length_cap(self):
        self.assertEqual(len(sources.safe_filename("x" * 400)), 150)

    def test_empty_falls_back(self):
        self.assertEqual(sources.safe_filename("///"), "___")
        self.assertEqual(sources.safe_filename(""), "vxpress-output")

    def test_unicode_is_kept(self):
        self.assertEqual(sources.safe_filename("Café — Niño"), "Café — Niño")

    def test_unique_path_avoids_overwriting(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "song.mp3"
            self.assertEqual(sources.unique_path(first), first)
            first.write_bytes(b"1")
            second = sources.unique_path(first)
            self.assertEqual(second.name, "song (2).mp3")
            second.write_bytes(b"2")
            self.assertEqual(sources.unique_path(first).name, "song (3).mp3")


class TimestampTests(unittest.TestCase):
    def test_accepted_forms(self):
        cases = {
            None: None,
            "": None,
            "90": 90.0,
            90: 90.0,
            "1:30": 90.0,
            "0:01:30": 90.0,
            "1:00:00": 3600.0,
            "0:00:01.5": 1.5,
        }
        for value, expected in cases.items():
            with self.subTest(value=value):
                self.assertEqual(sources.parse_timestamp(value), expected)

    def test_rejected_forms(self):
        for value in ("abc", "1:2:3:4", "-5", "1::2", ":30"):
            with self.subTest(value=value), self.assertRaises(BadSource):
                sources.parse_timestamp(value)

    def test_formatting(self):
        self.assertEqual(sources.format_timestamp(None), "?")
        self.assertEqual(sources.format_timestamp(0), "0:00")
        self.assertEqual(sources.format_timestamp(95), "1:35")
        self.assertEqual(sources.format_timestamp(3725), "1:02:05")


class OutputDirTests(unittest.TestCase):
    def test_defaults_split_audio_and_video(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("VXPRESS_OUTPUT_DIR", None)
            self.assertEqual(sources.default_output_dir("mp3").name, "audio")
            self.assertEqual(sources.default_output_dir("video").name, "video")

    def test_environment_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"VXPRESS_OUTPUT_DIR": tmp}):
                self.assertEqual(sources.default_output_dir("mp3"), Path(tmp) / "audio")

    def test_ensure_dir_creates_and_resolves(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "deep" / "nested"
            created = sources.ensure_dir(target)
            self.assertTrue(created.is_dir())
            self.assertEqual(created, target.resolve())

    def test_ensure_dir_reports_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            blocker = Path(tmp) / "file"
            blocker.write_bytes(b"x")
            with self.assertRaises(BadSource):
                sources.ensure_dir(blocker / "child")


if __name__ == "__main__":
    unittest.main()
