"""FastAPI + real workflow/Java HTTP adapters, with explicit local fixtures."""

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from fastapi.testclient import TestClient

from meeting_agent.api import create_app
from meeting_agent.model_client import ModelError


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.calls, self.model_calls = [], []
        self.role = "member"
        self.transcript = "US13 基本完成但未验收。"
        self.records = {}
        self.save_calls = []
        self.save_status = 200
        self.overrides = {}
        self.model_error = None
        self.result = {"meeting_id": "m1", "summary": "会议无明确状态变更", "proposed_actions": [], "open_questions": []}
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.calls.append((self.path, self.headers.get("Authorization")))
                data = {
                    "/api/auth/me": {"id": 1, "role": fixture.role, "username": "fixture-user"},
                    "/api/meetings/m1": {"id": "m1", "transcript": fixture.transcript, "created_by": 2},
                    "/api/stories": [{"id": "US13", "title": "登录", "status": 1, "sprint": 2, "owner_id": None}],
                }
                if any('/' + resource + '/by-request/' in self.path for resource in
                       ('status-analyses', 'planning-analyses', 'review-analyses', 'retro-analyses', 'refinement-analyses', 'assignment-suggestions')):
                    key = self.path.rsplit("/", 1)[1]
                    default = (200, fixture.records[key]) if key in fixture.records else (404, {})
                else:
                    default = (200, data.get(self.path))
                status, body = fixture.overrides.get(self.path, default)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if status == 302:
                    self.send_header("Location", "/must-not-follow")
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                fixture.calls.append((self.path, self.headers.get("Authorization")))
                fixture.save_calls.append(payload)
                if self.path.endswith('/assignment-suggestions'):
                    record = {'id': 'saved-1', 'meeting_id': 'm1', 'client_request_id': payload['client_request_id'],
                              'submitted_by': 1, 'created_at': '2026-10-01 12:00:00', 'input': payload['input'],
                              'result': payload['result'], 'review': None}
                    if fixture.save_status == 200:
                        fixture.records[payload['client_request_id']] = record
                    self.send_response(fixture.save_status)
                    self.end_headers()
                    self.wfile.write(json.dumps(record).encode())
                    return
                record = {"id": "saved-1", "meeting_id": "m1", "client_request_id": payload["client_request_id"],
                          "submitted_by": 1, "created_at": "2026-09-13 12:00:00", "status": "pending" if payload["result"]["proposed_actions"] else "no_changes",
                          "transcript": fixture.transcript, "result": payload["result"], "story_snapshots": ([{"id": "US13", "title": "登录", "status": 1, "sprint": 2, "owner_id": None}] if payload["result"]["proposed_actions"] else [])}
                if self.path.endswith('/retro-analyses'):
                    record.pop('story_snapshots')
                    record['member_snapshots'] = []
                if fixture.save_status == 200:
                    fixture.records[payload["client_request_id"]] = record
                self.send_response(fixture.save_status)
                self.end_headers()
                self.wfile.write(json.dumps(record).encode())

            def log_message(self, *args):
                pass

        class ModelFixture:
            def complete(self, messages):
                fixture.model_calls.append(messages)
                if fixture.model_error:
                    raise fixture.model_error
                return fixture.result

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01})
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.origin = f"http://127.0.0.1:{self.server.server_port}"
        self.app = create_app(backend_origin=self.origin, model_factory=ModelFixture)
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def post(self, **kwargs):
        return self.client.post("/api/meetings/m1/analyze", **{
            "json": {"meeting_type": "daily_scrum", "client_request_id": "request-1"}, "headers": {"Authorization": "Bearer fixture-token"}, **kwargs})

    def test_authorized_roles_analyze_java_transcript_in_order(self):
        for role in ("admin", "owner", "member"):
            self.role = role
            self.calls.clear()
            self.records.clear()
            response = self.post()
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual([], response.json()["proposed_actions"])
            self.assertEqual(["/api/auth/me", "/api/meetings/m1", "/api/meetings/m1/status-analyses/by-request/request-1", "/api/stories", "/api/meetings/m1/status-analyses"], [x[0] for x in self.calls])
            self.assertTrue(all(x[1] == "Bearer fixture-token" for x in self.calls))
            context = json.loads(self.model_calls[-1][1]["content"])
            self.assertEqual("US13 基本完成但未验收。", context["transcript_segments"][0]["text"])
            self.assertNotIn("fixture-token", json.dumps(self.model_calls[-1]))

    def test_missing_and_wrong_auth_scheme_stop_before_java(self):
        for headers in ({}, {"Authorization": "Basic token"}, {"Authorization": "Bearer"},
                        {"Authorization": "Bearer two tokens"}):
            response = self.post(headers=headers)
            self.assertEqual(401, response.status_code)
            self.assertEqual("Bearer", response.headers["www-authenticate"])
        self.assertEqual([], self.calls)
        self.assertEqual([], self.model_calls)

    def test_expired_token_and_forbidden_meeting_never_analyze(self):
        for path, status in (("/api/auth/me", 401), ("/api/meetings/m1", 403), ("/api/meetings/m1", 404)):
            self.overrides = {path: (status, {"detail": "secret backend content"})}
            response = self.post()
            self.assertEqual(status, response.status_code)
            self.assertNotIn("secret", response.text)
        self.assertEqual([], self.model_calls)

    def test_viewer_and_unknown_roles_are_denied_before_meeting_read(self):
        for role in ("viewer", "superadmin"):
            self.calls.clear()
            self.role = role
            response = self.post()
            self.assertEqual(403, response.status_code)
            self.assertEqual(["/api/auth/me"], [x[0] for x in self.calls])
        self.assertEqual([], self.model_calls)

    def test_role_is_rechecked_on_every_request(self):
        self.assertEqual(200, self.post().status_code)
        self.role = "viewer"
        self.assertEqual(403, self.post().status_code)
        self.assertEqual(1, len(self.model_calls))

    def test_client_cannot_supply_transcript_role_snapshot_or_model(self):
        for field in ("transcript", "role", "stories", "base_url", "api_key", "approved"):
            response = self.post(json={"meeting_type": "daily_scrum", field: "secret-client-value"})
            self.assertEqual(422, response.status_code)
            self.assertNotIn("secret-client-value", response.text)
        self.assertEqual([], self.model_calls)

    def test_invalid_meeting_type_sprint_and_json(self):
        for body in ({}, {"meeting_type": "sprint_planning"}, {"meeting_type": "daily_scrum", "current_sprint": True},
                     {"meeting_type": "daily_scrum", "current_sprint": 5}):
            self.assertEqual(422, self.post(json=body).status_code)
        response = self.client.post("/api/meetings/m1/analyze", content="{secret",
                                    headers={"Authorization": "Bearer token", "Content-Type": "application/json"})
        self.assertEqual(422, response.status_code)
        self.assertNotIn("secret", response.text)
        self.assertEqual([], self.model_calls)

    def test_java_response_contract_and_identity_mismatch_fail_closed(self):
        for path, body in (("/api/auth/me", {"id": True, "role": "member"}),
                           ("/api/auth/me", {"id": 1}),
                           ("/api/meetings/m1", {"id": "m2", "transcript": "原文"}),
                           ("/api/meetings/m1", {"id": "m1"}),
                           ("/api/meetings/m1", [])):
            self.overrides = {path: (200, body)}
            self.assertEqual(502, self.post().status_code)
        self.assertEqual([], self.model_calls)

    def test_backend_error_redirect_and_story_auth_failure(self):
        for path, upstream, expected in (("/api/auth/me", 500, 502), ("/api/auth/me", 404, 502),
                                          ("/api/meetings/m1", 302, 502), ("/api/stories", 401, 401)):
            self.calls.clear()
            self.overrides = {path: (upstream, {})}
            self.assertEqual(expected, self.post().status_code)
            self.assertNotIn("/must-not-follow", [x[0] for x in self.calls])
        self.assertEqual([], self.model_calls)

    def test_unconfigured_model_is_503_after_authorization(self):
        app = create_app(backend_origin=self.origin)
        with patch.dict("os.environ", {"AICAP_LLM_API_KEY": ""}), TestClient(app) as client:
            response = client.post("/api/meetings/m1/analyze", json={"meeting_type": "daily_scrum", "client_request_id": "request-1"},
                                   headers={"Authorization": "Bearer fixture-token"})
        self.assertEqual(503, response.status_code)
        self.assertEqual("model_not_configured", response.json()["detail"])
        self.assertEqual(["/api/auth/me", "/api/meetings/m1", "/api/meetings/m1/status-analyses/by-request/request-1"], [x[0] for x in self.calls])

    def test_model_and_proposal_errors_have_safe_http_statuses(self):
        for code, status in (("provider_timeout", 504), ("provider_unavailable", 503), ("provider_http_error", 502)):
            self.model_error = ModelError(code)
            self.assertEqual(status, self.post().status_code)
        self.model_error = None
        self.result = {"secret": "invalid model output"}
        response = self.post()
        self.assertEqual(502, response.status_code)
        self.assertEqual({"detail": "invalid_model_proposal"}, response.json())

    def test_success_returns_record_id_and_exact_validated_payload(self):
        response = self.post()
        self.assertEqual(200, response.status_code)
        self.assertEqual("saved-1", response.json()["analysis_id"])
        self.assertEqual("no_changes", response.json()["storage_status"])
        self.assertEqual("request-1", response.json()["client_request_id"])
        self.assertEqual("1.0", self.save_calls[0]["result"]["schema_version"])
        self.assertEqual("daily_scrum", self.save_calls[0]["result"]["meeting_type"])

    def test_nonempty_status_proposal_is_persisted_as_pending(self):
        self.transcript = "US13 已验收完成。"
        self.result = {"meeting_id": "m1", "summary": "登录已验收", "open_questions": [],
                       "proposed_actions": [{"proposal_id": "p1", "action": "update_story_status",
                          "story_id": "US13", "expected": {"status": 1}, "changes": {"status": 2},
                          "reason": "会议确认", "evidence": [{"segment_id": "S1", "quote": self.transcript}]}]}
        response = self.post()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("pending", response.json()["storage_status"])
        self.assertEqual(2, response.json()["proposed_actions"][0]["changes"]["status"])
        self.assertEqual(self.result["proposed_actions"], self.save_calls[0]["result"]["proposed_actions"])

    def test_same_key_retry_returns_saved_record_without_model_or_save(self):
        first = self.post()
        self.model_error = ModelError("provider_http_error")
        self.calls.clear()
        second = self.post()
        self.assertEqual(first.json(), second.json())
        self.assertEqual(1, len(self.model_calls))
        self.assertEqual(1, len(self.save_calls))
        self.assertEqual(3, len(self.calls))

    def test_storage_failure_is_not_reported_as_success(self):
        for upstream, expected in ((401, 401), (403, 403), (409, 409), (422, 422), (500, 503)):
            self.save_calls.clear()
            self.save_status = upstream
            response = self.post()
            self.assertEqual(expected, response.status_code)
            self.assertEqual("request-1", response.json()["client_request_id"])
            self.assertNotIn("analysis_id", response.json())
            self.assertEqual(2 if upstream == 500 else 1, len(self.save_calls))

    def test_missing_or_invalid_request_key_is_rejected(self):
        for body in ({"meeting_type": "daily_scrum"}, {"meeting_type": "daily_scrum", "client_request_id": "Bad/Key"}):
            self.assertEqual(422, self.post(json=body).status_code)
        self.assertEqual([], self.model_calls)

    def test_health_and_openapi_require_no_backend_or_model(self):
        self.assertEqual({"status": "ok"}, self.client.get("/health").json())
        schema = self.client.get("/openapi.json").json()
        operation = schema["paths"]["/api/meetings/{meeting_id}/analyze"]["post"]
        self.assertIn("security", operation)
        self.assertEqual([], self.calls)
        self.assertEqual([], self.model_calls)


if __name__ == "__main__":
    unittest.main()
