"""Persist validated proposals via Java; retry the exact same submission at most once."""

import json
from typing import Annotated, Literal
from urllib.parse import quote

import httpx
from pydantic import Field

from .contracts import Contract, DailyScrumOutput, Identifier, StorySnapshot
from .story_tool import StoryReadTool


RequestId = Annotated[str, Field(pattern=r"^[a-z0-9-]{1,80}$")]
_MISSING = object()


class StoredAnalysis(Contract):
    id: Identifier
    meeting_id: Identifier
    client_request_id: RequestId
    submitted_by: Annotated[int, Field(gt=0)]
    created_at: Identifier
    status: Literal["pending", "no_changes"]
    transcript: Annotated[str, Field(min_length=1, max_length=16000)]
    result: DailyScrumOutput
    story_snapshots: Annotated[list[StorySnapshot], Field(max_length=20)]


class PersistedResult(DailyScrumOutput):
    analysis_id: Identifier
    client_request_id: RequestId
    storage_status: Literal["pending", "no_changes"]

    @classmethod
    def from_record(cls, record: StoredAnalysis):
        return cls(**record.result.model_dump(), analysis_id=record.id,
                   client_request_id=record.client_request_id, storage_status=record.status)


class StorageError(Exception):
    def __init__(self, code: str, status: int, request_id: str):
        self.code, self.status, self.request_id = code, status, request_id
        super().__init__(code)


class JavaAnalysisStore:
    MAX_BYTES = 512 * 1024
    RECORD_TYPE = StoredAnalysis
    RESOURCE = 'status-analyses'

    def __init__(self, origin: str, *, transport: httpx.BaseTransport | None = None):
        StoryReadTool(origin)
        self._origin, self._transport = origin.rstrip("/"), transport

    def _request(self, method, path, token, key, *, payload=None, missing_ok=False):
        try:
            with httpx.Client(timeout=10, follow_redirects=False, trust_env=False, transport=self._transport) as client:
                with client.stream(method, self._origin + path,
                                   headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
                                   content=payload) as response:
                    status = response.status_code
                    if status == 404 and missing_ok:
                        return _MISSING
                    if status != 200:
                        code, outward = {
                            401: ("authentication_required", 401), 403: ("permission_denied", 403),
                            404: ("meeting_not_found", 404), 409: ("storage_conflict", 409),
                            422: ("storage_rejected", 422),
                        }.get(status, ("storage_unavailable", 503) if status >= 500
                              else ("storage_http_error", 502))
                        raise StorageError(code, outward, key)
                    raw = bytearray()
                    for chunk in response.iter_bytes():
                        raw.extend(chunk)
                        if len(raw) > self.MAX_BYTES:
                            raise StorageError("invalid_storage_response", 502, key)
        except httpx.TimeoutException:
            raise StorageError("storage_timeout", 504, key) from None
        except httpx.HTTPError:
            raise StorageError("storage_unavailable", 503, key) from None
        try:
            return json.loads(raw)
        except (ValueError, RecursionError):
            raise StorageError("invalid_storage_response", 502, key) from None

    def _record(self, data, meeting_id, key):
        try:
            record = self.RECORD_TYPE.model_validate(data)
            expected_status = "pending" if record.result.proposed_actions else "no_changes"
            if (record.meeting_id != meeting_id or record.result.meeting_id != meeting_id
                    or record.client_request_id != key or record.status != expected_status):
                raise ValueError("record mismatch")
            return record
        except ValueError:
            raise StorageError("invalid_storage_response", 502, key) from None

    def find(self, *, token: str, meeting_id: str, request_id: str) -> StoredAnalysis | None:
        path = "/api/meetings/" + quote(meeting_id, safe="") + '/' + self.RESOURCE + '/by-request/' + quote(request_id, safe="")
        data = self._request("GET", path, token, request_id, missing_ok=True)
        return None if data is _MISSING else self._record(data, meeting_id, request_id)

    def save(self, *, token: str, request_id: str, result: DailyScrumOutput) -> StoredAnalysis:
        path = "/api/meetings/" + quote(result.meeting_id, safe="") + '/' + self.RESOURCE
        # Serialize once. Never regenerate a model result during storage retries.
        payload = json.dumps({"client_request_id": request_id, "result": result.model_dump()}, ensure_ascii=False).encode()
        for attempt in range(2):
            try:
                data = self._request("POST", path, token, request_id, payload=payload)
                record = self._record(data, result.meeting_id, request_id)
                if record.result != result:
                    raise StorageError("invalid_storage_response", 502, request_id)
                return record
            except StorageError as error:
                if error.code not in ("storage_timeout", "storage_unavailable"):
                    raise
                if attempt == 1:
                    # A failed response does not prove that Java rolled back the write.
                    raise StorageError("storage_outcome_unknown", error.status, request_id) from None
        raise AssertionError("unreachable")
