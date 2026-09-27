"""Authenticated read-only projection of Java member profiles for future Planning."""

import json
import math
from typing import Annotated, Literal
from urllib.parse import urlsplit

import httpx
from pydantic import Field, model_validator

from .contracts import Contract, NonBlank


class ProfileSkill(Contract):
    name: Annotated[NonBlank, Field(max_length=50)]
    level: Annotated[int, Field(ge=1, le=5)]


class MemberProfileSnapshot(Contract):
    user_id: Annotated[int, Field(gt=0)]
    display_name: Annotated[NonBlank, Field(max_length=50)]
    role: Literal['admin', 'owner', 'member', 'viewer']
    six_week_capacity_hours: Annotated[int, Field(ge=0)]
    title: Annotated[str, Field(max_length=50)]
    tech_stack: Annotated[list[ProfileSkill], Field(max_length=20)]
    capabilities: Annotated[list[ProfileSkill], Field(max_length=20)]
    process_domains: Annotated[list[ProfileSkill], Field(max_length=20)]
    summary: Annotated[str, Field(max_length=500)]
    years_experience: Annotated[int, Field(ge=0, le=50)]

    @model_validator(mode='after')
    def unique_dimensions(self):
        for items in (self.tech_stack, self.capabilities, self.process_domains):
            names = [item.name.strip().casefold() for item in items]
            if len(names) != len(set(names)):
                raise ValueError('duplicate profile skill')
        return self


class MemberToolError(Exception):
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


class MemberReadTool:
    MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    MAX_MEMBERS = 1000

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
        self._url = backend_origin.rstrip('/') + '/api/members/profiles'
        self._timeout = timeout_seconds
        self._transport = transport

    def get_member_profiles(self, *, access_token: str) -> list[MemberProfileSnapshot]:
        if (not isinstance(access_token, str) or not access_token
                or any(not 33 <= ord(c) <= 126 for c in access_token)):
            raise MemberToolError('authentication_required')
        try:
            with httpx.Client(timeout=self._timeout, follow_redirects=False, trust_env=False,
                              transport=self._transport) as client:
                with client.stream('GET', self._url, headers={
                    'Authorization': 'Bearer ' + access_token, 'Accept': 'application/json',
                }) as response:
                    status = response.status_code
                    if status != 200:
                        code = {401: 'authentication_required', 403: 'permission_denied'}.get(status)
                        raise MemberToolError(code or ('backend_redirect' if 300 <= status < 400 else 'backend_http_error'))
                    raw = bytearray()
                    for chunk in response.iter_bytes():
                        raw.extend(chunk)
                        if len(raw) > self.MAX_RESPONSE_BYTES:
                            raise MemberToolError('response_too_large')
        except httpx.HTTPError:
            raise MemberToolError('backend_unavailable') from None
        try:
            rows = json.loads(raw, object_pairs_hook=_unique_object)
            if not isinstance(rows, list) or len(rows) > self.MAX_MEMBERS:
                raise ValueError('invalid profile list')
            profiles = []
            for row in rows:
                fields = {key: row[key] for key in MemberProfileSnapshot.model_fields
                          if key != 'six_week_capacity_hours'}
                # Java User/schema explicitly defines capacity_hours as six weeks.
                # Do not interpret it as remaining capacity or a Sprint allocation.
                fields['six_week_capacity_hours'] = row['capacity_hours']
                profiles.append(MemberProfileSnapshot.model_validate(fields))
            if len({p.user_id for p in profiles}) != len(profiles):
                raise ValueError('duplicate member')
        except (ValueError, TypeError, KeyError, RecursionError):
            raise MemberToolError('invalid_backend_response') from None
        return profiles
