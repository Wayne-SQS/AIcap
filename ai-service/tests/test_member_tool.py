"""Read-tool boundary tests with an HTTP transport fixture; no business writes."""
import copy
import json
import unittest

import httpx

from meeting_agent.member_tool import MemberReadTool, MemberToolError


class MemberToolTests(unittest.TestCase):
    def setUp(self):
        self.row = {'user_id': 7, 'display_name': '成员甲', 'role': 'member', 'capacity_hours': 60,
                    'title': '开发', 'tech_stack': [{'name': 'Python', 'level': 3}],
                    'capabilities': [], 'process_domains': [], 'summary': '', 'years_experience': 2,
                    'username': 'private-login', 'password_hash': 'must-not-project', 'color': 'blue',
                    'updated_at': None}
        self.rows, self.status, self.raw, self.requests = [copy.deepcopy(self.row)], 200, None, []

        def respond(request):
            self.requests.append(request)
            return httpx.Response(self.status, headers={'Location': 'https://other.invalid/secret'},
                                  content=self.raw if self.raw is not None else json.dumps(self.rows).encode())

        self.tool = MemberReadTool('http://127.0.0.1:8080', transport=httpx.MockTransport(respond))

    def read(self):
        return self.tool.get_member_profiles(access_token='fixture-token')

    def assert_code(self, code):
        with self.assertRaises(MemberToolError) as caught:
            self.read()
        self.assertEqual(code, caught.exception.code)
        self.assertEqual(code, str(caught.exception))

    def test_projection_fixed_get_and_explicit_six_week_capacity(self):
        result = self.read()[0].model_dump()
        request = self.requests[0]
        self.assertEqual('GET', request.method)
        self.assertEqual('http://127.0.0.1:8080/api/members/profiles', str(request.url))
        self.assertEqual('Bearer fixture-token', request.headers['Authorization'])
        self.assertEqual(b'', request.content)
        self.assertEqual(60, result['six_week_capacity_hours'])
        self.assertEqual(3, result['tech_stack'][0]['level'])
        for key in ('capacity_hours', 'remaining_hours', 'sprint', 'username', 'password_hash', 'updated_at'):
            self.assertNotIn(key, result)

    def test_no_cache_and_no_role_filter_or_capacity_default(self):
        self.read()
        self.rows[0].update(role='viewer', capacity_hours=0, tech_stack=[])
        result = self.tool.get_member_profiles(access_token='second-user')[0]
        self.assertEqual('Bearer second-user', self.requests[-1].headers['Authorization'])
        self.assertEqual('viewer', result.role)
        self.assertEqual(0, result.six_week_capacity_hours)
        self.assertEqual([], result.tech_stack)
        self.rows = []
        self.assertEqual([], self.read())

    def test_invalid_token_never_sends_request(self):
        for token in (None, '', ' ', 'a\r\nSecret: yes', '中文', 'Bearer x'):
            with self.subTest(token=token), self.assertRaises(MemberToolError):
                self.tool.get_member_profiles(access_token=token)
        self.assertEqual([], self.requests)

    def test_http_errors_and_redirect_never_expose_body_or_follow(self):
        self.raw = b'secret-token internal trace'
        for status, code in ((401, 'authentication_required'), (403, 'permission_denied'),
                             (302, 'backend_redirect'), (500, 'backend_http_error'), (204, 'backend_http_error')):
            self.status = status
            self.assert_code(code)
        self.assertEqual(5, len(self.requests))

    def test_network_errors_are_sanitized(self):
        for error in (httpx.ReadTimeout('secret'), httpx.ConnectError('secret')):
            def fail(request):
                raise error
            self.tool = MemberReadTool('http://localhost:8080', transport=httpx.MockTransport(fail))
            self.assert_code('backend_unavailable')

    def test_malformed_and_duplicate_json_is_rejected(self):
        for raw in (b'[] junk', b'{}', b'null', b'[null]', b'[[]]', b'\xff', b'[{"user_id":1,"user_id":2}]'):
            with self.subTest(raw=raw):
                self.raw = raw
                self.assert_code('invalid_backend_response')

    def test_missing_field_or_bad_row_fails_whole_response(self):
        for key in ('user_id', 'capacity_hours', 'tech_stack', 'summary'):
            self.rows = [copy.deepcopy(self.row), copy.deepcopy(self.row)]
            self.rows[1]['user_id'] = 8
            del self.rows[1][key]
            self.assert_code('invalid_backend_response')
        for key, value in (('user_id', True), ('capacity_hours', None), ('capacity_hours', -1),
                           ('capacity_hours', '60'), ('role', 'root'), ('years_experience', 51),
                           ('tech_stack', [{'name': 'Python', 'level': 6}]),
                           ('tech_stack', [{'name': ' ', 'level': 2}])):
            self.rows = [dict(self.row, **{key: value})]
            self.assert_code('invalid_backend_response')

    def test_duplicate_members_and_dimension_names_rejected(self):
        self.rows = [copy.deepcopy(self.row), copy.deepcopy(self.row)]
        self.assert_code('invalid_backend_response')
        self.rows = [dict(self.row, tech_stack=[{'name': 'Python', 'level': 3}, {'name': ' python ', 'level': 2}])]
        self.assert_code('invalid_backend_response')

    def test_response_and_member_limits(self):
        self.raw = b' ' * (self.tool.MAX_RESPONSE_BYTES + 1)
        self.assert_code('response_too_large')
        self.raw = None
        self.rows = [dict(self.row, user_id=i + 1) for i in range(self.tool.MAX_MEMBERS + 1)]
        self.assert_code('invalid_backend_response')

    def test_reject_invalid_origin_and_timeout(self):
        for origin in ('https://u:p@example.com', 'http://localhost/path', 'http://localhost?x=1',
                       'file:///tmp', 'http://localhost:bad', 'http://localhost/#x'):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                MemberReadTool(origin)
        for timeout in (True, 0, -1, float('inf'), float('nan'), '10'):
            with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                MemberReadTool('http://localhost', timeout_seconds=timeout)


if __name__ == '__main__':
    unittest.main()
