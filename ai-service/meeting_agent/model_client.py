"""Bounded, non-streaming chat-completions adapter for the configured provider."""

import json
import math
import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit

import httpx


class ModelError(Exception):
    def __init__(self, code: str, *, diagnostic: str | None = None):
        self.code = code
        # Diagnostics are fixed local labels, never provider text or field names.
        allowed = {f'{stage}_{reason}' for stage in ('envelope', 'content')
                   for reason in ('json_syntax', 'duplicate_key', 'invalid_encoding', 'too_deep', 'structure')}
        allowed.update({'choices_structure', 'finish_reason_missing', 'message_structure',
                        'unexpected_message', 'content_not_text'})
        self.diagnostic = diagnostic if diagnostic in allowed else None
        super().__init__(code)


@dataclass(frozen=True)
class ModelSettings:
    api_key: str = field(repr=False)
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-v4-flash"
    timeout_seconds: float = 45.0

    @classmethod
    def from_env(cls):
        return cls(api_key=os.environ.get("AICAP_LLM_API_KEY", ""),
                   base_url=os.environ.get("AICAP_LLM_BASE_URL", "https://api.deepseek.com"),
                   model=os.environ.get("AICAP_LLM_MODEL", "deepseek-v4-flash"))


class _DuplicateKey(ValueError):
    pass


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKey("duplicate JSON key")
        result[key] = value
    return result


class ChatModelClient:
    MAX_BYTES = 1024 * 1024

    def __init__(self, settings: ModelSettings, *, transport: httpx.BaseTransport | None = None):
        self._settings = settings
        self._transport = transport
        if (not isinstance(settings.api_key, str) or not settings.api_key
                or any(not 33 <= ord(c) <= 126 for c in settings.api_key)
                or not isinstance(settings.model, str) or not settings.model.strip()):
            raise ModelError("model_not_configured")
        try:
            url = urlsplit(settings.base_url)
            local = url.hostname in ("localhost", "127.0.0.1", "::1")
            valid = (url.hostname and (url.scheme == "https" or (url.scheme == "http" and local))
                     and url.username is None and url.password is None and not url.query and not url.fragment
                     and not any(c.isspace() or ord(c) < 32 for c in settings.base_url))
            valid = valid and (url.port is None or url.port > 0)
            timeout = settings.timeout_seconds
            valid = valid and not isinstance(timeout, bool) and math.isfinite(timeout) and timeout > 0
        except (ValueError, TypeError):
            valid = False
        if not valid:
            raise ModelError("invalid_model_config")

    def complete(self, messages: list[dict[str, str]]) -> dict:
        settings = self._settings
        body = {"model": settings.model, "messages": messages, "stream": False,
                "max_tokens": 6000, "response_format": {"type": "json_object"}}
        if urlsplit(settings.base_url).hostname == "api.deepseek.com":
            body["thinking"] = {"type": "disabled"}
        try:
            with httpx.Client(timeout=settings.timeout_seconds, follow_redirects=False,
                              trust_env=False, transport=self._transport) as client:
                with client.stream("POST", settings.base_url.rstrip("/") + "/chat/completions",
                                   headers={"Authorization": "Bearer " + settings.api_key}, json=body) as response:
                    if response.status_code != 200:
                        raise ModelError("provider_http_error")
                    raw = bytearray()
                    for chunk in response.iter_bytes():
                        raw.extend(chunk)
                        if len(raw) > self.MAX_BYTES:
                            raise ModelError("provider_response_too_large")
        except httpx.TimeoutException:
            raise ModelError("provider_timeout") from None
        except httpx.HTTPError:
            raise ModelError("provider_unavailable") from None
        stage = 'envelope'
        try:
            envelope = json.loads(raw, object_pairs_hook=_unique_object)
            stage = 'choices'
            choices = envelope["choices"]
            if not isinstance(choices, list) or len(choices) != 1:
                raise ValueError("expected one choice")
            choice = choices[0]
            stage = 'finish_reason'
            if choice["finish_reason"] != "stop":
                raise ModelError("incomplete_model_output")
            stage = 'message'
            message = choice["message"]
            if not isinstance(message, dict):
                raise ValueError("expected assistant message object")
            if message.get("role") != "assistant" or message.get("tool_calls") or message.get("refusal"):
                raise ModelError('invalid_model_response', diagnostic='unexpected_message')
            if not isinstance(message.get("content"), str):
                raise ModelError('invalid_model_response', diagnostic='content_not_text')
            stage = 'content'
            result = json.loads(message["content"], object_pairs_hook=_unique_object)
            if not isinstance(result, dict):
                raise ValueError("expected object")
        except (ValueError, TypeError, KeyError, RecursionError) as error:
            if isinstance(error, _DuplicateKey):
                reason = 'duplicate_key'
            elif isinstance(error, json.JSONDecodeError):
                reason = 'json_syntax'
            elif isinstance(error, UnicodeError):
                reason = 'invalid_encoding'
            elif isinstance(error, RecursionError):
                reason = 'too_deep'
            else:
                reason = 'structure'
            diagnostic = ('finish_reason_missing' if stage == 'finish_reason'
                          else f'{stage}_{reason}')
            raise ModelError("invalid_model_response", diagnostic=diagnostic) from None
        return result

