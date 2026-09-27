import json
import unittest

import httpx

from meeting_agent.analysis_store import JavaAnalysisStore, StorageError
from meeting_agent.contracts import DailyScrumOutput


class AnalysisStoreTests(unittest.TestCase):
    def setUp(self):
        self.result = DailyScrumOutput(meeting_id="m1", summary="无变更", proposed_actions=[], open_questions=[])
        self.record = {"id": "saved-1", "meeting_id": "m1", "client_request_id": "request-1",
                       "submitted_by": 1, "created_at": "2026-09-13 12:00:00", "status": "no_changes",
                       "transcript": "无状态变更。", "result": self.result.model_dump(), "story_snapshots": []}

    def save(self, handler):
        store = JavaAnalysisStore("http://localhost:8080", transport=httpx.MockTransport(handler))
        return store.save(token="user-token", request_id="request-1", result=self.result)

    def test_lost_response_retries_identical_bytes_and_returns_same_record(self):
        calls = []
        def handle(request):
            calls.append(request.content)
            self.assertEqual("Bearer user-token", request.headers["authorization"])
            if len(calls) == 1:
                raise httpx.ReadTimeout("fixture: write committed but response lost")
            return httpx.Response(200, json=self.record)
        self.assertEqual("saved-1", self.save(handle).id)
        self.assertEqual(2, len(calls))
        self.assertEqual(calls[0], calls[1])
        self.assertEqual(self.result.model_dump(), json.loads(calls[0])["result"])

    def test_two_timeouts_stop_with_unknown_outcome_and_request_key(self):
        calls = []
        def handle(request):
            calls.append(request)
            raise httpx.ReadTimeout("secret")
        with self.assertRaises(StorageError) as caught:
            self.save(handle)
        self.assertEqual("storage_outcome_unknown", str(caught.exception))
        self.assertEqual("request-1", caught.exception.request_id)
        self.assertEqual(2, len(calls))

    def test_invalid_or_mismatched_success_response_is_not_success(self):
        for body in (None, {}, {**self.record, "meeting_id": "other"},
                     {**self.record, "client_request_id": "other"}, {**self.record, "status": "pending"}):
            calls = []
            def handle(request):
                calls.append(request)
                return httpx.Response(200, content=json.dumps(body))
            with self.subTest(body=body), self.assertRaises(StorageError) as caught:
                self.save(handle)
            self.assertEqual("invalid_storage_response", str(caught.exception))
            self.assertEqual(1, len(calls))

    def test_lookup_404_is_absent_but_200_null_is_invalid(self):
        for status, expected in ((404, None), (200, "error")):
            store = JavaAnalysisStore("http://localhost:8080", transport=httpx.MockTransport(
                lambda request: httpx.Response(status, content="null")))
            if expected is None:
                self.assertIsNone(store.find(token="token", meeting_id="m1", request_id="request-1"))
            else:
                with self.assertRaises(StorageError):
                    store.find(token="token", meeting_id="m1", request_id="request-1")

    def test_redirect_is_not_followed_or_retried(self):
        calls = []
        def handle(request):
            calls.append(request)
            return httpx.Response(302, headers={"Location": "https://other.invalid"})
        with self.assertRaises(StorageError) as caught:
            self.save(handle)
        self.assertEqual("storage_http_error", str(caught.exception))
        self.assertEqual(1, len(calls))


if __name__ == "__main__":
    unittest.main()
