import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from vxpress import tools
from vxpress.process import Completed, ToolMissing
from tests.vxhelpers import fake_toolchain


class FindToolTests(unittest.TestCase):
    def test_found_on_path(self):
        with mock.patch.object(tools.shutil, "which", return_value="/usr/bin/ffmpeg"), \
             mock.patch.object(tools, "capture", return_value=Completed((), 0, "ffmpeg version 6.1.1 Copyright")):
            tool = tools.find_tool("ffmpeg")
        self.assertTrue(tool.found)
        self.assertEqual(tool.argv, ("/usr/bin/ffmpeg",))
        self.assertEqual(tool.version, "6.1.1")
        self.assertEqual(tool.source, "PATH")

    def test_environment_override_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "ffmpeg-custom"
            binary.write_text("#!/bin/sh\n")
            with mock.patch.dict(os.environ, {"VXPRESS_FFMPEG": str(binary)}), \
                 mock.patch.object(tools, "capture", return_value=Completed((), 0, "ffmpeg version 7.0 x")):
                tool = tools.find_tool("ffmpeg")
        self.assertEqual(tool.argv, (str(binary),))
        self.assertEqual(tool.source, "$VXPRESS_FFMPEG")
        self.assertEqual(tool.version, "7.0")

    def test_override_pointing_nowhere_is_ignored(self):
        with mock.patch.dict(os.environ, {"VXPRESS_FFMPEG": "/nope/ffmpeg"}), \
             mock.patch.object(tools.shutil, "which", return_value=None):
            tool = tools.find_tool("ffmpeg")
        self.assertFalse(tool.found)

    def test_ytdlp_falls_back_to_python_module(self):
        with mock.patch.object(tools.shutil, "which", return_value=None), \
             mock.patch.object(tools, "capture", return_value=Completed((), 0, "2026.10.21")):
            tool = tools.find_tool("yt-dlp")
        self.assertEqual(tool.argv, (sys.executable, "-m", "yt_dlp"))
        self.assertEqual(tool.version, "2026.10.21")
        self.assertEqual(tool.source, "python -m yt_dlp")

    def test_missing_tool_carries_an_install_hint(self):
        with mock.patch.object(tools.shutil, "which", return_value=None), \
             mock.patch.object(tools, "capture", return_value=Completed((), 127, "not found")):
            tool = tools.find_tool("yt-dlp")
        self.assertFalse(tool.found)
        self.assertTrue(tool.hint)
        self.assertIn("hint", tool.to_dict())

    def test_version_probe_that_raises_is_survivable(self):
        with mock.patch.object(tools.shutil, "which", return_value="/usr/bin/ffmpeg"), \
             mock.patch.object(tools, "capture", side_effect=ToolMissing("gone")):
            tool = tools.find_tool("ffmpeg")
        self.assertTrue(tool.found)
        self.assertEqual(tool.version, "")


class ToolchainTests(unittest.TestCase):
    def test_require_passes_when_present(self):
        fake_toolchain().require("yt-dlp", "ffmpeg", "ffprobe")

    def test_require_names_what_is_missing_and_how_to_fix_it(self):
        chain = fake_toolchain(ytdlp=False)
        with self.assertRaises(ToolMissing) as ctx:
            chain.require("yt-dlp")
        message = str(ctx.exception)
        self.assertIn("yt-dlp is not installed", message)
        self.assertIn("install it with", message)
        self.assertIn("VXPRESS_YTDLP", message)

    def test_require_reports_every_missing_tool_at_once(self):
        chain = fake_toolchain(ytdlp=False, ffmpeg=False)
        with self.assertRaises(ToolMissing) as ctx:
            chain.require("yt-dlp", "ffmpeg")
        self.assertIn("yt-dlp", str(ctx.exception))
        self.assertIn("ffmpeg", str(ctx.exception))

    def test_ffmpeg_dir_for_ytdlp(self):
        self.assertEqual(fake_toolchain().ffmpeg_dir, str(Path("/fake/bin")))
        self.assertEqual(fake_toolchain(ffmpeg=False).ffmpeg_dir, "")

    def test_readiness_flags(self):
        payload = fake_toolchain(ytdlp=False).to_dict()
        self.assertFalse(payload["ready_for_download"])
        self.assertTrue(payload["ready_for_mp3"])
        self.assertEqual(payload["yt-dlp"]["found"], False)

    def test_detect_uses_find_tool(self):
        with mock.patch.object(tools, "find_tool", side_effect=lambda name, *_: tools.Tool(name)):
            chain = tools.Toolchain.detect()
        self.assertFalse(chain.ytdlp.found)
        self.assertFalse(chain.ffmpeg.found)


class HintTests(unittest.TestCase):
    def test_hints_exist_for_every_platform(self):
        for name in ("yt-dlp", "ffmpeg", "ffprobe"):
            for system in ("windows", "darwin", "linux"):
                with self.subTest(name=name, system=system):
                    with mock.patch.object(tools.platform, "system", return_value=system):
                        self.assertTrue(tools.Tool(name).hint)

    def test_windows_hint_mentions_a_windows_installer(self):
        with mock.patch.object(tools.platform, "system", return_value="Windows"):
            self.assertIn("winget", tools.Tool("ffmpeg").hint)


if __name__ == "__main__":
    unittest.main()
