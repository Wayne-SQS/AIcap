import json
import math
from typing import Annotated, Literal
from urllib.parse import urlsplit

import httpx
from pydantic import Field, model_validator

from .contracts import Contract, Identifier, NonBlank


class TaskSnapshot(Contract):
    """Raw Java task facts; neither hours field represents remaining capacity.

    story_ref/depends_on retain the API's comma-separated text; kanban_card_id
    is a separate association. Unresolved references are not silently repaired.
    Java may default estimated_hours/status/progress/blocked/task_type.
    """
    id: Identifier
    name: Annotated[NonBlank, Field(max_length=200)]
    owner_id: Annotated[int, Field(gt=0)] | None
    hours: Annotated[int, Field(ge=0)]
    estimated_hours: Annotated[int, Field(ge=0)]
    week_start: Annotated[int, Field(ge=1, le=6)]
    week_end: Annotated[int, Field(ge=1, le=6)]
    story_ref: Annotated[str, Field(max_length=100)] | None
    kanban_card_id: Identifier | None
    task_type: Literal['feature', 'management']
    depends_on: Annotated[str, Field(max_length=100)] | None
    status: Annotated[int, Field(ge=0, le=3)]
    progress: Annotated[int, Field(ge=0, le=100)]
    blocked: bool
    sprints: Annotated[list[Annotated[int, Field(ge=1, le=3)]], Field(max_length=3)]

    @model_validator(mode='after')
    def validate_schedule(self):
        if self.week_start > self.week_end:
            raise ValueError('invalid week range')
        expected = sorted({(w - 1) // 2 + 1 for w in range(self.week_start, self.week_end + 1)})
        if self.sprints != expected:
            raise ValueError('sprints disagree with week schedule')
        return self


class TaskToolError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON field')
        result[key] = value
    return result


class TaskReadTool:
    MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    MAX_TASKS = 1000

    def __init__(self, backend_origin: str, *, timeout_seconds: float = 10.0,
                 transport: httpx.BaseTransport | None = None):
        try:
            url = urlsplit(backend_origin)
            valid = (url.scheme in ('http', 'https') and url.hostname
                     and url.username is None and url.password is None
                     and url.path in ('', '/') and not url.query and not url.fragment
                     and not any(c.isspace() or ord(c) < 32 for c in backend_origin)
                     and (url.port is None or url.port > 0))
        except (TypeError, ValueError):
            valid = False
        if not valid:
            raise ValueError('backend_origin must be an HTTP(S) origin without credentials or path')
        if (isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float))
                or not math.isfinite(timeout_seconds) or timeout_seconds <= 0):
            raise ValueError('timeout_seconds must be finite and positive')
        self._url = backend_origin.rstrip('/') + '/api/tasks'
        self._timeout = timeout_seconds
        self._transport = transport

    def get_tasks(self, *, access_token: str) -> list[TaskSnapshot]:
        if (not isinstance(access_token, str) or not access_token
                or any(not 33 <= ord(c) <= 126 for c in access_token)):
            raise TaskToolError('authentication_required')
        try:
            with httpx.Client(timeout=self._timeout, follow_redirects=False, trust_env=False,
                              transport=self._transport) as client:
                with client.stream('GET', self._url, headers={
                    'Authorization': 'Bearer ' + access_token, 'Accept': 'application/json',
                }) as response:
                    status = response.status_code
                    if status != 200:
                        code = {401: 'authentication_required', 403: 'permission_denied'}.get(status)
                        raise TaskToolError(code or ('backend_redirect' if 300 <= status < 400 else 'backend_http_error'))
                    raw = bytearray()
                    for chunk in response.iter_bytes():
                        raw.extend(chunk)
                        if len(raw) > self.MAX_RESPONSE_BYTES:
                            raise TaskToolError('response_too_large')
        except httpx.HTTPError:
            raise TaskToolError('backend_unavailable') from None
        try:
            rows = json.loads(raw, object_pairs_hook=_unique_object)
            if not isinstance(rows, list) or len(rows) > self.MAX_TASKS:
                raise ValueError('invalid task list')
            tasks = []
            for row in rows:
                fields = {key: row[key] for key in TaskSnapshot.model_fields}
                tasks.append(TaskSnapshot.model_validate(fields))
            if len({t.id for t in tasks}) != len(tasks):
                raise ValueError('duplicate task')
        except (ValueError, TypeError, KeyError, RecursionError):
            raise TaskToolError('invalid_backend_response') from None
        return tasks

