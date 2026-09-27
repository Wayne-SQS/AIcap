import json
import unittest

import httpx

from meeting_agent.task_tool import TaskReadTool, TaskToolError


def task_row(**changes):
    return dict(dict(id='T01', name='登录实现', owner_id=7, hours=8, estimated_hours=13,
                     week_start=2, week_end=5, story_ref='US13,US14', kanban_card_id='US13',
                     task_type='feature', depends_on='T99', status=3, progress=40,
                     blocked=True, sprints=[1, 2, 3]), **changes)


class TaskToolTests(unittest.TestCase):
    def setUp(self):
        self.rows, self.raw, self.status, self.requests = [task_row()], None, 200, []
        def respond(request):
            self.requests.append(request)
            return httpx.Response(self.status, headers={'Location': 'https://other.invalid'},
                                  content=self.raw if self.raw is not None else json.dumps(self.rows).encode())
        self.tool = TaskReadTool('http://localhost:8080', transport=httpx.MockTransport(respond))

    def read(self):
        return self.tool.get_tasks(access_token='secret')

    def assert_code(self, code):
        with self.assertRaises(TaskToolError) as caught:
            self.read()
        self.assertEqual(code, str(caught.exception))

    def test_exact_get_projection_and_preserved_facts(self):
        self.rows[0]['private_data'] = 'excluded'
        self.assertEqual(task_row(), self.read()[0].model_dump())
        req = self.requests[0]
        self.assertEqual(('GET', 'http://localhost:8080/api/tasks', b''), (req.method, str(req.url), req.content))
        self.assertEqual('Bearer secret', req.headers['Authorization'])
        self.rows = [task_row(owner_id=None, story_ref='', depends_on=None, kanban_card_id=None,
                              hours=0, estimated_hours=0, task_type='management')]
        self.assertEqual(self.rows[0], self.tool.get_tasks(access_token='second')[0].model_dump())
        self.assertEqual('Bearer second', self.requests[-1].headers['Authorization'])
        self.rows = []
        self.assertEqual([], self.read())

    def test_bad_rows_fail_entire_response(self):
        for key, value in (('hours', -1), ('hours', True), ('estimated_hours', '3'),
                           ('owner_id', 0), ('status', 4), ('progress', 101), ('blocked', 1),
                           ('week_start', 0), ('week_end', 1), ('sprints', [2]),
                           ('sprints', [1, 2, True]), ('task_type', 'other'), ('name', ' ')):
            with self.subTest(key=key, value=value):
                self.rows = [task_row(), task_row(id='T02', **{key: value})]
                self.assert_code('invalid_backend_response')
        for key in task_row():
            self.rows = [task_row()]
            del self.rows[0][key]
            self.assert_code('invalid_backend_response')
        self.rows = [task_row(), task_row()]
        self.assert_code('invalid_backend_response')

    def test_json_and_size_limits(self):
        for raw in (b'{}', b'null', b'[null]', b'[] junk', b'\xff', b'[{"id":"a","id":"b"}]'):
            self.raw = raw
            self.assert_code('invalid_backend_response')
        self.raw = b' ' * (self.tool.MAX_RESPONSE_BYTES + 1)
        self.assert_code('response_too_large')
        self.raw = None
        self.rows = [task_row(id=f'T{i}') for i in range(self.tool.MAX_TASKS + 1)]
        self.assert_code('invalid_backend_response')

    def test_auth_http_redirect_and_network_errors(self):
        for token in (None, '', ' ', 'x\r\ny', '中文'):
            with self.assertRaises(TaskToolError):
                self.tool.get_tasks(access_token=token)
        self.assertEqual([], self.requests)
        self.raw = b'sensitive diagnostic'
        for status, code in ((401, 'authentication_required'), (403, 'permission_denied'),
                             (302, 'backend_redirect'), (500, 'backend_http_error')):
            self.status = status
            self.assert_code(code)
        self.assertEqual(4, len(self.requests))
        def fail(request):
            raise httpx.ReadTimeout('sensitive')
        self.tool = TaskReadTool('http://localhost', transport=httpx.MockTransport(fail))
        self.assert_code('backend_unavailable')

    def test_invalid_origin_and_timeout(self):
        for origin in ('http://u:p@localhost', 'http://localhost/path', 'file:///tmp', 'http://localhost?x=1'):
            with self.assertRaises(ValueError):
                TaskReadTool(origin)
        for timeout in (True, 0, -1, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                TaskReadTool('http://localhost', timeout_seconds=timeout)
