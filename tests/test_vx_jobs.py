import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from vxpress import jobs as jobs_module, media
from vxpress.jobs import CANCELLED, DONE, FAILED, JobQueue
from vxpress.media import AudioOptions
from vxpress.process import Cancelled, Completed, ToolMissing
from vxpress.sources import BadSource
from tests.vxhelpers import (
    FFMPEG_PROGRESS_LINES,
    PROBE_JSON,
    YTDLP_AUDIO_LINES,
    YTDLP_DOWNLOAD_LINES,
    FakeStream,
    fake_toolchain,
)


def fake_stream_class(lines, returncode=0, creates_output=False, record=None):
    """A drop-in replacement for jobs.Stream."""

    def build(argv, on_line=None, **kwargs):
        creates = str(argv[-1]) if creates_output else None
        stream = FakeStream(argv, on_line, lines=lines, returncode=returncode, creates=creates)
        if record is not None:
            record.append(stream)
        return stream

    return build


class QueueTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "out"
        self.history = Path(self.tmp.name) / "history.jsonl"
        self.clip = Path(self.tmp.name) / "clip.mp4"
        self.clip.write_bytes(b"fake video bytes")

    def queue(self, tools=None, workers=1, start=True) -> JobQueue:
        queue = JobQueue(
            tools=tools or fake_toolchain(ffprobe=False),
            workers=workers,
            history=self.history,
            start=start,
        )
        self.addCleanup(queue.shutdown, False)
        return queue


class SubmitValidationTests(QueueTestCase):
    def test_unknown_kind(self):
        with self.assertRaises(BadSource):
            self.queue().submit("gif", "https://example.com/x")

    def test_local_file_cannot_be_downloaded(self):
        with self.assertRaises(BadSource) as ctx:
            self.queue().submit("video", str(self.clip))
        self.assertIn("already on this computer", str(ctx.exception))

    def test_url_needs_ytdlp(self):
        queue = self.queue(tools=fake_toolchain(ytdlp=False))
        with self.assertRaises(ToolMissing):
            queue.submit("mp3", "https://example.com/x")

    def test_local_mp3_needs_ffmpeg(self):
        queue = self.queue(tools=fake_toolchain(ffmpeg=False, ffprobe=False))
        with self.assertRaises(ToolMissing):
            queue.submit("mp3", str(self.clip))

    def test_bad_audio_options_are_refused(self):
        with self.assertRaises(BadSource):
            self.queue().submit(
                "mp3", str(self.clip), out_dir=self.out, options=AudioOptions(start=50, end=10)
            )

    def test_playlist_is_ignored_for_local_files(self):
        queue = self.queue(start=False)
        job = queue.submit("mp3", str(self.clip), out_dir=self.out, playlist=True)
        self.assertFalse(job.playlist)

    def test_output_directory_is_created(self):
        queue = self.queue(start=False)
        target = self.out / "nested"
        queue.submit("mp3", str(self.clip), out_dir=target)
        self.assertTrue(target.is_dir())


class LocalConversionTests(QueueTestCase):
    def test_successful_conversion(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            queue = self.queue()
            job = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, DONE)
        self.assertEqual(job.percent, 100.0)
        self.assertTrue(Path(job.output_path).exists())
        self.assertEqual(Path(job.output_path).name, "clip.mp3")
        self.assertEqual(Path(job.output_path).parent, self.out.resolve())

    def test_title_from_metadata_names_the_file(self):
        info = media.parse_probe(PROBE_JSON, str(self.clip))
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ), mock.patch.object(media, "probe", return_value=info):
            queue = self.queue(tools=fake_toolchain())
            job = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(Path(job.output_path).name, "A Test Clip.mp3")

    def test_failure_is_recorded_with_a_message(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(["Invalid data found"], returncode=1)
        ):
            queue = self.queue()
            job = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, FAILED)
        self.assertIn("not a media file", job.message)
        self.assertEqual(job.output_path, "")

    def test_second_conversion_does_not_overwrite_the_first(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            queue = self.queue()
            first = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
            second = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertNotEqual(first.output_path, second.output_path)
        self.assertEqual(Path(second.output_path).name, "clip (2).mp3")


class UrlJobTests(QueueTestCase):
    def test_mp3_from_a_link(self):
        with mock.patch.object(jobs_module, "Stream", fake_stream_class(YTDLP_AUDIO_LINES)):
            queue = self.queue()
            job = queue.submit("mp3", "https://example.com/watch?v=abc123", out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, DONE)
        self.assertTrue(job.output_path.endswith(".mp3"))
        self.assertEqual(job.title, "Some Video [abc123].mp3")

    def test_video_from_a_link(self):
        with mock.patch.object(jobs_module, "Stream", fake_stream_class(YTDLP_DOWNLOAD_LINES)):
            queue = self.queue()
            job = queue.submit("video", "https://example.com/watch?v=abc123", out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, DONE)
        self.assertTrue(job.output_path.endswith(".mp4"))

    def test_site_error_becomes_a_readable_message(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(["ERROR: Video unavailable"], returncode=1)
        ):
            queue = self.queue()
            job = queue.submit("mp3", "https://example.com/watch?v=abc123", out_dir=self.out)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, FAILED)
        self.assertIn("unavailable", job.message)

    def test_the_command_carries_the_requested_quality(self):
        record = []
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(YTDLP_AUDIO_LINES, record=record)
        ):
            queue = self.queue()
            queue.submit(
                "mp3",
                "https://example.com/x",
                out_dir=self.out,
                options=AudioOptions(bitrate=320),
            )
            self.assertTrue(queue.wait(10))
        self.assertIn("320K", record[0].argv)


class CancelTests(QueueTestCase):
    def test_queued_job_can_be_cancelled_before_it_starts(self):
        queue = self.queue(start=False)
        job = queue.submit("mp3", str(self.clip), out_dir=self.out)
        queue.cancel(job.id)
        self.assertEqual(job.status, CANCELLED)

    def test_running_job_is_stopped(self):
        started = __import__("threading").Event()

        class SlowStream(FakeStream):
            def run(self):
                started.set()
                for _ in range(200):
                    if self._cancelled:
                        raise Cancelled("cancelled")
                    time.sleep(0.01)
                return Completed(self.argv, 0, "")

        def build(argv, on_line=None, **kwargs):
            return SlowStream(argv, on_line)

        with mock.patch.object(jobs_module, "Stream", build):
            queue = self.queue()
            job = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(started.wait(5))
            queue.cancel(job.id)
            self.assertTrue(queue.wait(10))
        self.assertEqual(job.status, CANCELLED)

    def test_cancelling_a_finished_job_is_harmless(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            queue = self.queue()
            job = queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
            queue.cancel(job.id)
        self.assertEqual(job.status, DONE)

    def test_cancelling_an_unknown_job(self):
        with self.assertRaises(LookupError):
            self.queue().cancel("nope")


class BookkeepingTests(QueueTestCase):
    def test_jobs_are_listed_newest_first(self):
        queue = self.queue(start=False)
        first = queue.submit("mp3", str(self.clip), out_dir=self.out)
        second = queue.submit("mp3", str(self.clip), out_dir=self.out)
        self.assertEqual([job.id for job in queue.list_jobs()], [second.id, first.id])

    def test_clear_finished_keeps_active_jobs(self):
        queue = self.queue(start=False)
        queued = queue.submit("mp3", str(self.clip), out_dir=self.out)
        done = queue.submit("mp3", str(self.clip), out_dir=self.out)
        done.status = DONE
        self.assertEqual(queue.clear_finished(), 1)
        self.assertEqual([job.id for job in queue.list_jobs()], [queued.id])

    def test_history_records_every_outcome(self):
        with mock.patch.object(
            jobs_module, "Stream", fake_stream_class(FFMPEG_PROGRESS_LINES, creates_output=True)
        ):
            queue = self.queue()
            queue.submit("mp3", str(self.clip), out_dir=self.out)
            self.assertTrue(queue.wait(10))
        entries = jobs_module.read_history(10, self.history)
        self.assertEqual(entries[0]["status"], DONE)
        self.assertEqual(entries[0]["kind"], "mp3")
        self.assertEqual(entries[0]["quality"], "192 kbps")

    def test_history_survives_a_corrupt_line(self):
        self.history.parent.mkdir(parents=True, exist_ok=True)
        self.history.write_text('{"status": "done"}\nnot json\n{"status": "failed"}\n')
        entries = jobs_module.read_history(10, self.history)
        self.assertEqual([entry["status"] for entry in entries], ["failed", "done"])

    def test_missing_history_file(self):
        self.assertEqual(jobs_module.read_history(5, Path("/nope/vxpress/history.jsonl")), [])

    def test_job_serialization_is_complete(self):
        queue = self.queue(start=False)
        payload = queue.submit("mp3", str(self.clip), out_dir=self.out).to_dict()
        for key in ("id", "kind", "status", "percent", "source", "options", "downloadable"):
            self.assertIn(key, payload)
        self.assertFalse(payload["downloadable"])

    def test_wait_times_out_when_a_job_never_finishes(self):
        queue = self.queue(start=False)
        queue.submit("mp3", str(self.clip), out_dir=self.out)
        self.assertFalse(queue.wait(timeout=0.2))


if __name__ == "__main__":
    unittest.main()
