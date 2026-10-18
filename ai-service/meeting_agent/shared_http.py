"""One long-lived httpx client per transport configuration.

Every Java/LLM call site used to build ``httpx.Client`` inside the request, so a
single ``/analyze`` paid at least four TCP+TLS handshakes and every model call
re-negotiated TLS with the provider. Those clients are pooled here instead.

``httpx.Client`` is thread-safe and these are synchronous routes that FastAPI
runs in its worker thread pool, so one client per configuration can serve
concurrent requests. The pool limits are deliberately generous: a shared client
must never become the concurrency bottleneck.

Request semantics are unchanged -- each caller passes exactly the timeout,
redirect and proxy behaviour it passed before, and those values are part of the
cache key, so differently configured clients are never merged.
"""

import threading
from urllib.parse import quote, urlsplit

import httpx

# Far above this service's single-flight concurrency, including LLM calls that
# may overlap across meetings. Never shrink this to "enough for one request".
MAX_CONNECTIONS = 100
MAX_KEEPALIVE_CONNECTIONS = 20

_LOCK = threading.Lock()
_CLIENTS: dict[tuple, httpx.Client] = {}


def _cache_key(timeout, follow_redirects, trust_env, transport):
    # httpx.Timeout defines __eq__ without __hash__, so key on its components.
    if isinstance(timeout, httpx.Timeout):
        timeout = (timeout.connect, timeout.read, timeout.write, timeout.pool)
    return (timeout, follow_redirects, trust_env, transport)


def shared_client(*, timeout, follow_redirects, trust_env, transport=None) -> httpx.Client:
    """Return the pooled client for this configuration, building it on first use.

    The returned client is shared and stays open: do not close it and do not use
    it as a context manager. ``reset_shared_clients`` is the only thing that
    tears the pool down.
    """
    key = _cache_key(timeout, follow_redirects, trust_env, transport)
    with _LOCK:
        client = _CLIENTS.get(key)
        if client is None:
            client = httpx.Client(
                timeout=timeout, follow_redirects=follow_redirects, trust_env=trust_env,
                transport=transport,
                limits=httpx.Limits(max_connections=MAX_CONNECTIONS,
                                    max_keepalive_connections=MAX_KEEPALIVE_CONNECTIONS))
            _CLIENTS[key] = client
        return client


def reset_shared_clients() -> None:
    """Close and forget every pooled client.

    This is the test seam that keeps cases from polluting each other; it is safe
    to call at any time (in-flight streams keep their own response objects).
    """
    with _LOCK:
        clients = tuple(_CLIENTS.values())
        _CLIENTS.clear()
    for client in clients:
        try:
            client.close()
        except Exception:  # An already-broken client must not block the reset.
            pass


def validate_backend_origin(backend_origin: str) -> str:
    """Reject anything that is not a bare HTTP(S) origin; return it without a trailing slash.

    Shared by every Java read adapter so no call site can invent its own, looser check.
    """
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
    return backend_origin.rstrip("/")


def scoped_url(origin: str, path: str, project_id: str) -> str:
    """Bind exactly one Java project into a read URL.

    Multi-project Java requires ``projectId`` on the project-scoped reads
    (``/api/stories``, ``/api/tasks``), and those paths carry no meeting id, so
    Java cannot resolve the project on its behalf. The value must come from
    ``meetings.project_id`` as read back from Java -- never from the caller's
    request body or the model. The shape is re-checked here so nothing with
    whitespace or control characters can be spliced into the query string.
    """
    if (not isinstance(project_id, str) or not project_id or len(project_id) > 64
            or not project_id.isascii()
            or any(c.isspace() or ord(c) < 32 for c in project_id)):
        raise ValueError("project_id must be a short ASCII identifier")
    return origin + path + "?projectId=" + quote(project_id, safe="")
