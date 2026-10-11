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
