"""Read authenticated Java audio with bounded bytes and verified content identity."""
import hashlib
import json
from typing import Annotated, Literal
from urllib.parse import quote
import httpx
from pydantic import Field
from .contracts import Contract, Identifier
from .story_tool import StoryReadTool

MAX_AUDIO_BYTES = 25 * 1024 * 1024


class AudioInputRequest(Contract):
    audio_id: Identifier


class PreparedAudio(Contract):
    meeting_id: Identifier
    audio_id: Identifier
    sha256: Annotated[str, Field(pattern=r'^[0-9a-f]{64}$')]
    byte_size: Annotated[int, Field(gt=0, le=MAX_AUDIO_BYTES)]
    reported_duration_ms: Annotated[int, Field(ge=0, le=21600000)] | None
    duration_source: Literal['upload_metadata_unverified'] = 'upload_metadata_unverified'
    input_status: Literal['verified_bytes'] = 'verified_bytes'
    transcription_status: Literal['not_started'] = 'not_started'
    diarization_status: Literal['not_started'] = 'not_started'


class AudioReadError(Exception):
    def __init__(self, code, status):
        self.code, self.status = code, status
        super().__init__(code)


class AudioReadTool:
    def __init__(self, origin, *, transport=None):
        StoryReadTool(origin)
        self.origin, self.transport = origin.rstrip('/'), transport

    def _get(self, path, token, limit, *, binary=False):
        try:
            with httpx.Client(timeout=20, trust_env=False, follow_redirects=False, transport=self.transport) as client:
                with client.stream('GET', self.origin + path, headers={'Authorization': 'Bearer ' + token}) as response:
                    status = response.status_code
                    if status in (401, 403, 404):
                        raise AudioReadError({401: 'authentication_required', 403: 'permission_denied', 404: 'audio_or_meeting_not_found'}[status], status)
                    if status != 200:
                        raise AudioReadError('audio_backend_unavailable' if status >= 500 else 'audio_backend_http_error', 503 if status >= 500 else 502)
                    if binary and response.headers.get('content-type', '').split(';')[0].strip().lower() != 'audio/mpeg':
                        raise AudioReadError('invalid_audio_content_type', 502)
                    data = bytearray()
                    for chunk in response.iter_bytes():
                        data.extend(chunk)
                        if len(data) > limit:
                            raise AudioReadError('audio_response_too_large', 502)
                    return bytes(data), response.headers.get('x-audio-sha256')
        except httpx.TimeoutException:
            raise AudioReadError('audio_backend_timeout', 504) from None
        except httpx.HTTPError:
            raise AudioReadError('audio_backend_unavailable', 503) from None

    def _json(self, path, token):
        try:
            return json.loads(self._get(path, token, 256 * 1024)[0])
        except (ValueError, RecursionError):
            raise AudioReadError('invalid_audio_metadata', 502) from None

    def read(self, *, meeting_id, audio_id, token):
        user = self._json('/api/auth/me', token)
        if not isinstance(user, dict) or type(user.get('id')) is not int or user['id'] <= 0:
            raise AudioReadError('invalid_audio_identity', 502)
        if user.get('role') not in ('admin', 'owner', 'member'):
            raise AudioReadError('transcription_forbidden', 403)
        # Listing authorizes the meeting and establishes association before downloading.
        rows = self._json('/api/meetings/' + quote(meeting_id, safe='') + '/audio', token)
        if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise AudioReadError('invalid_audio_metadata', 502)
        matches = [row for row in rows if row.get('id') == audio_id]
        if not matches:
            raise AudioReadError('audio_not_in_meeting', 404)
        if len(matches) != 1 or matches[0].get('meeting_id') != meeting_id:
            raise AudioReadError('invalid_audio_metadata', 502)
        row = matches[0]
        try:
            prepared = PreparedAudio(meeting_id=meeting_id, audio_id=audio_id,
                sha256=row['sha256'], byte_size=row['byte_size'], reported_duration_ms=row['duration_ms'])
        except (KeyError, ValueError):
            raise AudioReadError('invalid_audio_metadata', 502) from None
        # Never follow the supplied metadata URL or pass the user's token to a provider.
        content, digest = self._get('/api/audio/' + quote(audio_id, safe=''), token, prepared.byte_size, binary=True)
        if len(content) != prepared.byte_size or hashlib.sha256(content).hexdigest() != prepared.sha256 or digest != prepared.sha256:
            raise AudioReadError('audio_integrity_mismatch', 502)
        return prepared, content
