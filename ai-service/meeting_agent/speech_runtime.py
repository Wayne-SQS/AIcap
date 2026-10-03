"""Shared process boundary for local speech workers."""
import os


# Ten-minute inputs need headroom on supported CPU-only machines. The HTTP request
# remains bounded, and the disposable worker is killed by subprocess on expiry.
SPEECH_JOB_TIMEOUT_SECONDS = 600


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
