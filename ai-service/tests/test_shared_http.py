"""Pooled HTTP clients: one per configuration, reused across calls, resettable."""
import unittest
from unittest.mock import patch

import httpx

from meeting_agent import shared_http
from meeting_agent.analysis_store import JavaAnalysisStore
from meeting_agent.shared_http import reset_shared_clients, shared_client


class SharedHttpClientTests(unittest.TestCase):
    def setUp(self):
        reset_shared_clients()
        self.addCleanup(reset_shared_clients)

    def test_same_configuration_reuses_one_client(self):
        self.assertIs(shared_client(timeout=10, follow_redirects=False, trust_env=False),
                      shared_client(timeout=10, follow_redirects=False, trust_env=False))

    def test_different_configurations_are_never_merged(self):
        base = shared_client(timeout=10, follow_redirects=False, trust_env=False)
        self.assertIsNot(base, shared_client(timeout=20, follow_redirects=False, trust_env=False))
        self.assertIsNot(base, shared_client(timeout=10, follow_redirects=True, trust_env=False))
        self.assertIsNot(base, shared_client(timeout=10, follow_redirects=False, trust_env=True))
        transport = httpx.MockTransport(lambda request: httpx.Response(200))
        self.assertIsNot(base, shared_client(timeout=10, follow_redirects=False, trust_env=False,
                                             transport=transport))

    def test_httpx_timeout_object_is_keyed_by_its_components(self):
        # httpx.Timeout is unhashable, so it must be normalised rather than pooled by identity.
        first = shared_client(timeout=httpx.Timeout(10.0), follow_redirects=False, trust_env=False)
        self.assertIs(first, shared_client(timeout=httpx.Timeout(10.0), follow_redirects=False,
                                           trust_env=False))

    def test_pool_is_wider_than_any_single_request(self):
        self.assertGreaterEqual(shared_http.MAX_CONNECTIONS, 100)
        self.assertGreaterEqual(shared_http.MAX_KEEPALIVE_CONNECTIONS, 20)

    def test_reset_closes_and_drops_every_client(self):
        client = shared_client(timeout=10, follow_redirects=False, trust_env=False)
        reset_shared_clients()
        self.assertTrue(client.is_closed)
        self.assertIsNot(client, shared_client(timeout=10, follow_redirects=False, trust_env=False))

    def test_reset_is_safe_when_nothing_was_pooled(self):
        reset_shared_clients()
        reset_shared_clients()

    def test_store_builds_the_client_once_for_repeated_requests(self):
        """Regression: each stored request used to open its own TCP+TLS connection."""
        store = JavaAnalysisStore("http://localhost:8080",
                                  transport=httpx.MockTransport(lambda request: httpx.Response(404)))
        builds = []
        real_client = httpx.Client

        def counting_client(*args, **kwargs):
            builds.append(1)
            return real_client(*args, **kwargs)

        with patch.object(shared_http.httpx, "Client", side_effect=counting_client):
            reset_shared_clients()
            for _ in range(2):
                self.assertIsNone(store.find(token="user-token", meeting_id="m1",
                                             request_id="request-1"))
        self.assertEqual(1, len(builds))


if __name__ == "__main__":
    unittest.main()
