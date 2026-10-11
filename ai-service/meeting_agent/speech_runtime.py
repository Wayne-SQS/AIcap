"""Shared process boundary for local speech workers."""
import logging
import os


# Ten-minute inputs need headroom on supported CPU-only machines. The HTTP request
# remains bounded, and the disposable worker is killed by subprocess on expiry.
SPEECH_JOB_TIMEOUT_SECONDS = 600

# Failure codes shared by the workers and the parent that reads their payload.
# Only a decoder rejection is evidence about the caller's file; every other crash
# is this service failing and must not be reported as a bad upload.
INVALID_AUDIO = 'invalid_audio'
INTERNAL_ERROR = 'internal_error'
DIAGNOSTIC_LIMIT = 400

_LOGGER = logging.getLogger(__name__)


def _decode_error_types():
    """PyAV's decode exception classes; empty when PyAV is not installed."""
    try:
        import av
    except ImportError:
        return ()
    error_module = getattr(av, 'error', None)
    candidates = (getattr(error_module, 'FFmpegError', None),
                  getattr(av, 'FFmpegError', None),
                  getattr(error_module, 'InvalidDataError', None),
                  getattr(av, 'InvalidDataError', None))
    types = []
    for candidate in candidates:
        if isinstance(candidate, type) and candidate not in types:
            types.append(candidate)
    return tuple(types)


def is_decode_error(error) -> bool:
    """True only for av/FFmpeg decode failures, i.e. the caller's file is at fault."""
    return isinstance(error, _decode_error_types())


def worker_failure(error, *, context: str) -> dict:
    """Turn a worker crash into the payload its parent reads.

    Only decoder rejections are ``invalid_audio``: that is the one case where the
    caller's own file is demonstrably wrong. Anything else -- internal bugs,
    missing pieces of the runtime, broken model output -- is ``internal_error``
    with a bounded diagnostic that stays in the process/log channel and is never
    echoed to the caller. Reporting those as ``invalid_audio`` made users retry an
    upload that was never the problem while the real fault stayed invisible.
    """
    diagnostic = f'{type(error).__name__}: {error}'[:DIAGNOSTIC_LIMIT]
    _LOGGER.error('%s failed: %s', context, diagnostic)
    if is_decode_error(error):
        return {'error': INVALID_AUDIO}
    return {'error': INTERNAL_ERROR, 'diagnostic': diagnostic}


def log_worker_diagnostic(context: str, diagnostic) -> None:
    """Log a worker-supplied diagnostic server-side; never return it to a caller."""
    if isinstance(diagnostic, str) and diagnostic:
        _LOGGER.error('%s: %s', context, diagnostic[:DIAGNOSTIC_LIMIT])


def worker_environment() -> dict[str, str]:
    """Return only OS/runtime variables needed by an offline CPU child process."""
    allowed = {
        'ALLUSERSPROFILE', 'APPDATA', 'COMSPEC', 'HOMEDRIVE', 'HOMEPATH',
        'LOCALAPPDATA', 'NUMBER_OF_PROCESSORS', 'OS', 'PATH', 'PATHEXT',
        'PROCESSOR_ARCHITECTURE', 'PROCESSOR_IDENTIFIER', 'PROGRAMDATA',
        'PROGRAMFILES', 'PROGRAMFILES(X86)', 'SYSTEMDRIVE', 'SYSTEMROOT',
        'TEMP', 'TMP', 'USERPROFILE', 'WINDIR',
    }
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', PYTHONNOUSERSITE='1')
    return env
