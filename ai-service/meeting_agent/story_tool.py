"""Read Java stories through authenticated REST, without model-controlled URLs."""

import json
import math
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from pydantic import ValidationError
from pydantic import Field
from typing import Annotated, Literal, TypeVar

from .contracts import DailyScrumInput, StorySnapshot, TranscriptSegment


class PlanningStorySnapshot(StorySnapshot):
    """Planning-only content, preserved verbatim including empty/null values.

    Missing keys fail validation; API content is evidence to inspect, not model
    instructions or proof that acceptance criteria have actually been met.
    """
    description: str | None
    acceptance: str | None
    priority: Literal['Must', 'Should', 'Could']
    activity: Annotated[int, Field(ge=1, le=5)]


StoryProjection = TypeVar('StoryProjection', bound=StorySnapshot)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON field')
        result[key] = value
    return result


class StoryToolError(Exception):
    """Stable error code; never expose response bodies, tokens or transport details."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class StoryReadTool:
    """One fixed GET endpoint. Origin is trusted service config, token is per call.

    Java remains the authority for authentication and read permissions. The Tool
    does not grant permission to start analysis or approve any project changes.
    """

    MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    MAX_STORIES = 1000

    def __init__(self, backend_origin: str, *, timeout_seconds: float = 10.0):
        try:
            url = urlsplit(backend_origin)
            valid = (
                url.scheme in ("http", "https") and url.hostname
                and url.username is None and url.password is None
                and url.path in ("", "/") and not url.query and not url.fragment
                and not any(c.isspace() or ord(c) < 32 for c in backend_origin)
            )
            # Accessing port also rejects malformed port strings.
            port = url.port
            valid = valid and (port is None or port > 0)
        except (TypeError, ValueError):
            valid = False
        if not valid:
            raise ValueError("backend_origin must be an HTTP(S) origin without credentials or path")
        if (isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float))
                or not math.isfinite(timeout_seconds) or timeout_seconds <= 0):
            raise ValueError("timeout_seconds must be finite and positive")
        self._url = backend_origin.rstrip("/") + "/api/stories"
        self._timeout = timeout_seconds
        # Never forward a caller's Bearer token via redirects or environment proxies.
        self._opener = build_opener(ProxyHandler({}), _NoRedirect())

    def get_stories(self, *, access_token: str) -> list[StorySnapshot]:
        return self._read_stories(access_token=access_token, projection=StorySnapshot)

    def get_planning_stories(self, *, access_token: str) -> list[PlanningStorySnapshot]:
        return self._read_stories(access_token=access_token, projection=PlanningStorySnapshot)

    def _read_stories(self, *, access_token: str,
                      projection: type[StoryProjection]) -> list[StoryProjection]:
        if (not isinstance(access_token, str) or not access_token
                or any(not 33 <= ord(c) <= 126 for c in access_token)):
            raise StoryToolError("authentication_required")
        request = Request(self._url, method="GET", headers={
            "Authorization": "Bearer " + access_token,
            "Accept": "application/json",
        })
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                if response.status != 200:
                    raise StoryToolError("backend_http_error")
                raw = response.read(self.MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            status = error.code
            error.close()
            code = {401: "authentication_required", 403: "permission_denied"}.get(status)
            if code is None:
                code = "backend_redirect" if 300 <= status < 400 else "backend_http_error"
            raise StoryToolError(code) from None
        except (TimeoutError, URLError, OSError, HTTPException):
            raise StoryToolError("backend_unavailable") from None
        if len(raw) > self.MAX_RESPONSE_BYTES:
            raise StoryToolError("response_too_large")
        try:
            rows = json.loads(raw, object_pairs_hook=_unique_object)
            if not isinstance(rows, list) or len(rows) > self.MAX_STORIES:
                raise ValueError("invalid story list")
            # Java returns additional story fields. Deliberately project only the
            # existing contract, but never default missing fields or skip bad rows.
            stories = [projection.model_validate({
                key: row[key] for key in projection.model_fields
            }) for row in rows]
            if len({story.id for story in stories}) != len(stories):
                raise ValueError("duplicate story id")
        except (ValueError, TypeError, KeyError, RecursionError):
            raise StoryToolError("invalid_backend_response") from None
        return stories

    def load_daily_context(
        self, *, access_token: str, meeting_id: str,
        transcript_segments: list[TranscriptSegment], current_sprint: int | None = None,
    ) -> DailyScrumInput:
        """Compose trusted meeting data and freshly read stories, without caching.

        The calling service must load/authorize the meeting itself. No meeting
        lookup or model generation is performed by this read-only adapter.
        """
        try:
            context = DailyScrumInput.model_validate({
                "meeting_id": meeting_id, "current_sprint": current_sprint,
                "transcript_segments": transcript_segments, "stories": [],
            })
        except ValidationError:
            raise StoryToolError("invalid_meeting_context") from None
        data = context.model_dump()
        data["stories"] = [story.model_dump() for story in self.get_stories(access_token=access_token)]
        return DailyScrumInput.model_validate(data)
