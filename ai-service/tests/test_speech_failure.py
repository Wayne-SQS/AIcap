"""Only a decoder rejection may be reported as the caller's bad audio."""
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from meeting_agent.diarization_worker import diarize
from meeting_agent.speech_runtime import (DIAGNOSTIC_LIMIT, INTERNAL_ERROR, INVALID_AUDIO,
                                          is_decode_error, log_worker_diagnostic, worker_failure)
from meeting_agent.transcription_worker import transcribe

LOGGER = "meeting_agent.speech_runtime"


class FakeFFmpegError(Exception):
    """Stands in for av.error.FFmpegError, which is an optional extra here."""


def fake_modules(*, open_error):
    """Fake the optional av / numpy / engine imports the workers probe for."""
    av_module = types.ModuleType("av")
    error_module = types.ModuleType("av.error")
    error_module.FFmpegError = FakeFFmpegError
    av_module.error = error_module
    av_module.FFmpegError = FakeFFmpegError
    av_module.AudioResampler = lambda **kwargs: None

    def open_container(*args, **kwargs):
        raise open_error

    av_module.open = open_container
    whisper_module = types.ModuleType("faster_whisper")
    whisper_module.WhisperModel = object
    return {"av": av_module, "numpy": types.ModuleType("numpy"),
            "faster_whisper": whisper_module,
            "sherpa_onnx": types.ModuleType("sherpa_onnx")}


class SpeechFailureTests(unittest.TestCase):
    def test_only_decoder_rejections_are_invalid_audio(self):
        with patch.dict(sys.modules, fake_modules(open_error=FakeFFmpegError("unused"))):
            self.assertTrue(is_decode_error(FakeFFmpegError("Invalid data found")))
            self.assertFalse(is_decode_error(RuntimeError("internal bug")))
            with self.assertLogs(LOGGER, level="ERROR"):
                self.assertEqual({"error": INVALID_AUDIO},
                                 worker_failure(FakeFFmpegError("bad container"), context="test"))

    def test_unrelated_crash_is_internal_error_with_a_bounded_diagnostic(self):
        with self.assertLogs(LOGGER, level="ERROR") as logs:
            failure = worker_failure(ValueError("x" * 5000), context="test")
        self.assertEqual(INTERNAL_ERROR, failure["error"])
        self.assertEqual(DIAGNOSTIC_LIMIT, len(failure["diagnostic"]))
        self.assertIn("ValueError", failure["diagnostic"])
        self.assertEqual(1, len(logs.output))

    def test_unrelated_exception_is_not_a_decode_error(self):
        self.assertFalse(is_decode_error(RuntimeError("boom")))
        self.assertFalse(is_decode_error(FakeFFmpegError("same name, different class")))

    def test_diagnostics_are_logged_but_never_returned_to_a_caller(self):
        with self.assertLogs(LOGGER, level="ERROR") as logs:
            log_worker_diagnostic("stt worker internal error", "boom at C:/private/path")
            log_worker_diagnostic("stt worker internal error", None)
            log_worker_diagnostic("stt worker internal error", 7)
        self.assertEqual(1, len(logs.output))
        self.assertIn("C:/private/path", logs.output[0])

    def test_transcribe_blames_the_file_only_for_decoder_rejections(self):
        with patch.dict(sys.modules, fake_modules(open_error=FakeFFmpegError("no stream"))):
            with self.assertLogs(LOGGER, level="ERROR"):
                self.assertEqual({"error": INVALID_AUDIO},
                                 transcribe(Path("audio.mp3"), Path("model"), "auto"))
        with patch.dict(sys.modules, fake_modules(open_error=RuntimeError("wrapper bug"))):
            with self.assertLogs(LOGGER, level="ERROR") as logs:
                result = transcribe(Path("audio.mp3"), Path("model"), "auto")
        self.assertEqual(INTERNAL_ERROR, result["error"])
        self.assertIn("wrapper bug", result["diagnostic"])
        self.assertIn("wrapper bug", logs.output[0])

    def test_diarize_blames_the_file_only_for_decoder_rejections(self):
        with patch.dict(sys.modules, fake_modules(open_error=FakeFFmpegError("no stream"))):
            with self.assertLogs(LOGGER, level="ERROR"):
                self.assertEqual({"error": INVALID_AUDIO},
                                 diarize(Path("audio.mp3"), Path("model"), 2))
        with patch.dict(sys.modules, fake_modules(open_error=RuntimeError("clustering bug"))):
            with self.assertLogs(LOGGER, level="ERROR"):
                result = diarize(Path("audio.mp3"), Path("model"), 2)
        self.assertEqual(INTERNAL_ERROR, result["error"])
        self.assertIn("clustering bug", result["diagnostic"])


if __name__ == "__main__":
    unittest.main()
