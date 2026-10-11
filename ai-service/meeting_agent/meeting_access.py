"""Use Java as the authority for identity and meeting visibility."""

from typing import Annotated
from urllib.parse import quote

import httpx
from pydantic import Field

from .contracts import Contract, Identifier, NonBlank
from .story_tool import StoryReadTool


class AccessError(Exception):
    def __init__(self, code: str, status: int):
        self.code, self.status = code, status
        super().__init__(code)


class SavedMeeting(Contract):
    id: Identifier
    transcript: Annotated[NonBlank, Field(max_length=16000)]


class JavaMeetingAccess:
    def __init__(self, backend_origin: str):
        StoryReadTool(backend_origin)  # Share existing trusted-origin validation.
        self._origin = backend_origin.rstrip("/")

    def _get(self, path: str, token: str) -> dict:
        try:
            with httpx.Client(timeout=10, follow_redirects=False, trust_env=False) as client:
                with client.stream("GET", self._origin + path,
                                   headers={"Authorization": "Bearer " + token, "Accept": "application/json"}) as response:
                    if response.status_code in (401, 403, 404):
                        codes = {401: "authentication_required", 403: "permission_denied", 404: "meeting_not_found"}
                        # /auth/me being missing is a deployment error, not a missing meeting.
                        if response.status_code == 404 and path == "/api/auth/me":
                            raise AccessError("backend_http_error", 502)
                        raise AccessError(codes[response.status_code], response.status_code)
                    if response.status_code != 200:
                        raise AccessError("backend_http_error", 502)
                    raw = bytearray()
                    for chunk in response.iter_bytes():
                        raw.extend(chunk)
                        if len(raw) > 256 * 1024:
                            raise AccessError("invalid_backend_response", 502)
        except httpx.TimeoutException:
            raise AccessError("backend_timeout", 504) from None
        except httpx.HTTPError:
            raise AccessError("backend_unavailable", 503) from None
        import json
        try:
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError("expected object")
        except (ValueError, RecursionError):
            raise AccessError("invalid_backend_response", 502) from None
        return data

    def authorize_and_load(self, *, token: str, meeting_id: str) -> SavedMeeting:
        user = self._get("/api/auth/me", token)
        if type(user.get("id")) is not int or user["id"] <= 0 or not isinstance(user.get("role"), str):
            raise AccessError("invalid_backend_response", 502)
        if user["role"] not in ("admin", "owner", "member"):
            raise AccessError("analysis_forbidden", 403)
        data = self._get("/api/meetings/" + quote(meeting_id, safe=""), token)
        try:
            meeting = SavedMeeting.model_validate({"id": data["id"], "transcript": data["transcript"]})
            if meeting.id != meeting_id:
                raise ValueError("meeting mismatch")
        except (ValueError, KeyError):
            raise AccessError("invalid_backend_response", 502) from None
        return meeting
