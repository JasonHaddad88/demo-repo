import subprocess
import tempfile
import unittest
from pathlib import Path

from vxpress import media
from vxpress.media import AudioOptions, ConversionError, FfmpegProgress
from vxpress.sources import BadSource
from vxpress.tools import Toolchain
from tests.vxhelpers import (
    FFMPEG_AVAILABLE,
    FFMPEG_PROGRESS_LINES,
    PROBE_AUDIO_ONLY,
    PROBE_COVER_ART,
    PROBE_JSON,
    PROBE_NO_AUDIO,
    fake_capture,
    fake_toolchain,
    stream_factory,
)


class AudioOptionTests(unittest.TestCase):
    def test_defaults_are_valid(self):
        AudioOptions().validate()

    def test_bitrate_bounds(self):
        AudioOptions(bitrate=320).validate()
        for bad in (0, 31, 321, 1000):
            with self.subTest(bitrate=bad), self.assertRaises(BadSource):
                AudioOptions(bitrate=bad).validate()

    def test_vbr_bounds(self):
        AudioOptions(vbr=0).validate()
        AudioOptions(vbr=9).validate()
        for bad in (-1, 10):
            with self.subTest(vbr=bad), self.assertRaises(BadSource):
                AudioOptions(vbr=bad).validate()

    def test_end_must_follow_start(self):
        AudioOptions(start=10, end=20).validate()
        with self.assertRaises(BadSource) as ctx:
            AudioOptions(start=30, end=20).validate()
        self.assertIn("must come after", str(ctx.exception))

    def test_duration_and_labels(self):
        self.assertIsNone(AudioOptions(start=10).duration)
        self.assertEqual(AudioOptions(start=10, end=25).duration, 15)
        self.assertEqual(AudioOptions(bitrate=256).quality_label, "256 kbps")
        self.assertEqual(AudioOptions(vbr=2).quality_label, "VBR q2")


class ProbeParsingTests(unittest.TestCase):
    def test_video_with_audio(self):
        info = media.parse_probe(PROBE_JSON, "/videos/clip.mp4")
        self.assertTrue(info.has_audio)
        self.assertTrue(info.has_video)
        self.assertEqual(info.audio_codec, "aac")
        self.assertEqual(info.video_codec, "h264")
        self.assertAlmostEqual(info.duration, 212.512, places=3)
        self.assertEqual(info.size, 18234567)
        self.assertEqual(info.title, "A Test Clip")
        self.assertEqual(info.to_dict()["duration_label"], "3:33")

    def test_audio_only_file(self):
        info = media.parse_probe(PROBE_AUDIO_ONLY)
        self.assertTrue(info.has_audio)
        self.assertFalse(info.has_video)

    def test_video_without_audio(self):
        info = media.parse_probe(PROBE_NO_AUDIO)
        self.assertFalse(info.has_audio)
        self.assertTrue(info.has_video)

    def test_cover_art_is_not_a_video_stream(self):
        info = media.parse_probe(PROBE_COVER_ART)
        self.assertTrue(info.has_audio)
        self.assertFalse(info.has_video)

    def test_missing_duration_is_not_fatal(self):
        info = media.parse_probe('{"streams": [], "format": {}}')
        self.assertIsNone(info.duration)
        self.assertEqual(info.to_dict()["duration_label"], "?")

    def test_duration_falls_back_to_the_audio_stream(self):
        payload = '{"streams": [{"codec_type": "audio", "duration": "61.0"}], "format": {}}'
        self.assertEqual(media.parse_probe(payload).duration, 61.0)

    def test_garbage_output_is_reported(self):
        with self.assertRaises(ConversionError):
            media.parse_probe("not json at all {{{")

    def test_suggested_title_falls_back_to_the_filename(self):
        info = media.parse_probe('{"format": {}}', "/videos/Holiday Clip.mkv")
        self.assertEqual(info.suggested_title, "Holiday Clip")

    def test_probe_surfaces_ffprobe_failure(self):
        runner = fake_capture({"ffprobe": (1, "clip.mp4: Invalid data found")})
        with self.assertRaises(ConversionError) as ctx:
            media.probe("/videos/clip.mp4", fake_toolchain(), runner=runner)
        self.assertIn("could not read", str(ctx.exception))


class CommandBuildingTests(unittest.TestCase):
    def setUp(self):
        self.tools = fake_toolchain()

    def test_basic_command(self):
        argv = media.mp3_argv("/in/clip.mp4", "/out/clip.mp3", AudioOptions(), self.tools)
        self.assertEqual(argv[0], "/fake/bin/ffmpeg")
        self.assertIn("-vn", argv)
        self.assertEqual(argv[argv.index("-i") + 1], "/in/clip.mp4")
        self.assertEqual(argv[-1], "/out/clip.mp3")
        self.assertIn("libmp3lame", argv)
        self.assertIn("192k", argv)
        self.assertIn("-progress", argv)

    def test_vbr_replaces_constant_bitrate(self):
        argv = media.mp3_argv("in.mp4", "out.mp3", AudioOptions(vbr=2), self.tools)
        self.assertIn("-q:a", argv)
        self.assertEqual(argv[argv.index("-q:a") + 1], "2")
        self.assertNotIn("-b:a", argv)

    def test_trim_puts_seek_before_the_input(self):
        argv = media.mp3_argv("in.mp4", "out.mp3", AudioOptions(start=30, end=45), self.tools)
        self.assertLess(argv.index("-ss"), argv.index("-i"))
        self.assertEqual(argv[argv.index("-ss") + 1], "30.000")
        self.assertEqual(argv[argv.index("-t") + 1], "15.000")

    def test_metadata_can_be_dropped(self):
        keep = media.mp3_argv("in.mp4", "out.mp3", AudioOptions(metadata=True), self.tools)
        drop = media.mp3_argv("in.mp4", "out.mp3", AudioOptions(metadata=False), self.tools)
        self.assertEqual(keep[keep.index("-map_metadata") + 1], "0")
        self.assertEqual(drop[drop.index("-map_metadata") + 1], "-1")

    def test_invalid_options_are_caught_before_running(self):
        with self.assertRaises(BadSource):
            media.mp3_argv("in.mp4", "out.mp3", AudioOptions(bitrate=9999), self.tools)


class ProgressTests(unittest.TestCase):
    def test_percent_from_out_time(self):
        progress = FfmpegProgress(duration=200.0)
        self.assertAlmostEqual(progress.feed("out_time=00:00:50.000000"), 25.0, places=1)
        self.assertAlmostEqual(progress.feed("out_time=00:01:40.000000"), 50.0, places=1)

    def test_microsecond_form(self):
        progress = FfmpegProgress(duration=100.0)
        self.assertAlmostEqual(progress.feed("out_time_us=50000000"), 50.0, places=1)

    def test_negative_and_unparsable_values(self):
        progress = FfmpegProgress(duration=100.0)
        self.assertIsNone(progress.feed("out_time_us=-1"))
        self.assertIsNone(progress.feed("frame=12"))
        self.assertIsNone(progress.feed(""))

    def test_end_marker_reports_completion(self):
        progress = FfmpegProgress(duration=None)
        self.assertEqual(progress.feed("progress=end"), 100.0)
        self.assertTrue(progress.finished)

    def test_unknown_duration_yields_no_percent(self):
        progress = FfmpegProgress(duration=None)
        self.assertIsNone(progress.feed("out_time=00:00:10.000000"))
        self.assertEqual(progress.seconds, 10.0)

    def test_percent_never_reaches_100_before_the_end(self):
        progress = FfmpegProgress(duration=10.0)
        self.assertLess(progress.feed("out_time=00:00:20.000000"), 100.0)


class ConvertTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tools = fake_toolchain()
        self.source = Path(self.tmp.name) / "clip.mp4"
        self.source.write_bytes(b"fake")
        self.destination = Path(self.tmp.name) / "clip.mp3"
        self.info = media.parse_probe(PROBE_JSON, str(self.source))

    def test_successful_conversion_reports_progress(self):
        seen = []
        result = media.convert_to_mp3(
            self.source,
            self.destination,
            AudioOptions(),
            self.tools,
            on_progress=seen.append,
            info=self.info,
            make_stream=stream_factory(FFMPEG_PROGRESS_LINES, creates_output=True),
        )
        self.assertEqual(result, self.destination)
        self.assertTrue(self.destination.exists())
        self.assertTrue(seen)
        self.assertEqual(seen[-1], 100.0)
        self.assertTrue(all(0 <= value <= 100 for value in seen))

    def test_refuses_a_file_with_no_audio(self):
        with self.assertRaises(ConversionError) as ctx:
            media.convert_to_mp3(
                self.source,
                self.destination,
                AudioOptions(),
                self.tools,
                info=media.parse_probe(PROBE_NO_AUDIO, str(self.source)),
                make_stream=stream_factory([], creates_output=True),
            )
        self.assertIn("no audio track", str(ctx.exception))

    def test_failure_is_translated(self):
        cases = {
            "Unknown encoder 'libmp3lame'": "libmp3lame",
            "clip.mp4: Invalid data found when processing input": "not a media file",
            "Output file #0 does not contain any stream": "no audio track",
        }
        for output, needle in cases.items():
            with self.subTest(output=output):
                with self.assertRaises(ConversionError) as ctx:
                    media.convert_to_mp3(
                        self.source,
                        self.destination,
                        AudioOptions(),
                        self.tools,
                        info=self.info,
                        make_stream=stream_factory([output], returncode=1),
                    )
                self.assertIn(needle, str(ctx.exception))

    def test_success_without_an_output_file_is_an_error(self):
        with self.assertRaises(ConversionError) as ctx:
            media.convert_to_mp3(
                self.source,
                self.destination,
                AudioOptions(),
                self.tools,
                info=self.info,
                make_stream=stream_factory(["progress=end"], creates_output=False),
            )
        self.assertIn("produced no audio", str(ctx.exception))

    def test_trim_shortens_the_progress_total(self):
        seen = []
        media.convert_to_mp3(
            self.source,
            self.destination,
            AudioOptions(start=10, end=20),
            self.tools,
            on_progress=seen.append,
            info=self.info,
            make_stream=stream_factory(["out_time=00:00:05.000000"], creates_output=True),
        )
        # Five seconds into a ten second excerpt is halfway, not 2% of the film.
        self.assertAlmostEqual(seen[0], 50.0, places=1)


@unittest.skipUnless(FFMPEG_AVAILABLE, "ffmpeg and ffprobe are not installed")
class RealFfmpegTests(unittest.TestCase):
    """End-to-end against the real ffmpeg, on a clip we generate ourselves."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.clip = Path(cls.tmp.name) / "source.mp4"
        subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest", "-metadata", "title=Generated Clip", str(cls.clip),
            ],
            check=True,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.tools = Toolchain.detect()
        self.out = Path(self.tmp.name) / "out"
        self.out.mkdir(exist_ok=True)

    def test_probe_reads_the_generated_clip(self):
        info = media.probe(self.clip, self.tools)
        self.assertTrue(info.has_audio)
        self.assertTrue(info.has_video)
        self.assertAlmostEqual(info.duration, 4.0, delta=0.3)
        self.assertEqual(info.title, "Generated Clip")

    def test_conversion_produces_a_real_mp3(self):
        destination = self.out / "real.mp3"
        seen = []
        media.convert_to_mp3(
            self.clip, destination, AudioOptions(bitrate=128), self.tools, on_progress=seen.append
        )
        self.assertTrue(destination.exists())
        self.assertGreater(destination.stat().st_size, 1000)
        produced = media.probe(destination, self.tools)
        self.assertTrue(produced.has_audio)
        self.assertFalse(produced.has_video)
        self.assertAlmostEqual(produced.duration, 4.0, delta=0.4)
        self.assertEqual(seen[-1], 100.0)

    def test_trimming_a_real_file(self):
        destination = self.out / "trimmed.mp3"
        media.convert_to_mp3(
            self.clip, destination, AudioOptions(start=1, end=3), self.tools
        )
        produced = media.probe(destination, self.tools)
        self.assertAlmostEqual(produced.duration, 2.0, delta=0.4)

    def test_video_without_audio_is_refused_by_the_real_probe(self):
        silent = Path(self.tmp.name) / "silent.mp4"
        subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=5", "-t", "1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", str(silent),
            ],
            check=True,
            capture_output=True,
        )
        with self.assertRaises(ConversionError) as ctx:
            media.convert_to_mp3(silent, self.out / "nope.mp3", AudioOptions(), self.tools)
        self.assertIn("no audio track", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
