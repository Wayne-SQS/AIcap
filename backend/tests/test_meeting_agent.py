"""Hermetic Agent tests. Scripted model responses are TEST FIXTURES, not live AI."""
import copy
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from uuid import uuid4

import httpx
import pytest

from app import config, models, security
from app.meeting_agent import jobs, runner
from app.meeting_agent.model_client import ModelClient
from app.meeting_agent.schemas import AgentError
from app.meeting_agent.tools import execute_tool
from conftest import TestSession

TRANSCRIPT = '我们希望新增导出周报功能。\n权限测试由成员3下周五完成。\n登录基本完成，仍待验收。'
EVIDENCE = {'segment_id': 'seg-1', 'quote': '我们希望新增导出周报功能。'}


def headers(token):
    return {'Authorization': 'Bearer ' + token}


def analysis(title=None):
    return {'summary': '讨论了周报新需求和权限测试，登录仍需验收。',
            'decisions': [{'text': '提出周报需求', 'evidence': EVIDENCE}],
            'action_items': [{'description': '权限测试', 'owner_mention': '成员3', 'deadline_text': '下周五',
                              'evidence': {'segment_id': 'seg-2', 'quote': '权限测试由成员3下周五完成。'}}],
            'status_constraints': [{'text':'登录仍待验收', 'evidence':{'segment_id':'seg-3', 'quote':'登录基本完成，仍待验收。'}}],
            'risks': [], 'unresolved_questions': ['周报范围和 Sprint 待确认'],
            'proposals': [{'action': 'pool.create', 'title': title or '测试周报-' + uuid4().hex[:8],
                           'description': '范围待细化', 'note': '会议未承诺Sprint', 'evidence': EVIDENCE}]}


class ScriptedModel:
    def __init__(self, result=None, bad_tool=None):
        self.result = result or analysis()
        self.bad_tool = bad_tool
        self.calls = []

    def complete(self, messages, tools, *, final=False):
        self.calls.append({'messages': copy.deepcopy(messages), 'final': final})
        if final:
            return {'role': 'assistant', 'content': json.dumps(self.result, ensure_ascii=False)}, {'total_tokens': 40}
        if len(self.calls) == 1:
            names = [self.bad_tool] if self.bad_tool else ['search_stories', 'search_pool']
            return {'role': 'assistant', 'content': None, 'tool_calls': [
                {'id': 'call-' + str(i), 'type': 'function',
                 'function': {'name': name, 'arguments': '{"keyword":"周报"}'}}
                for i, name in enumerate(names)]}, {'total_tokens': 20}
        return {'role': 'assistant', 'content': '查询完成，准备生成JSON。'}, {'total_tokens': 20}


@pytest.fixture(autouse=True)
def isolated_agent_config(monkeypatch):
    # Explicit fake credentials never leave the process in this suite.
    monkeypatch.setattr(config, 'AICAP_LLM_API_KEY', 'test-key-not-real')
    with TestSession() as db:
        db.query(models.MeetingAgentEvent).delete()
        db.query(models.MeetingAgentRun).delete()
        db.commit()


def create(client, token):
    h = headers(token)
    r = client.post('/api/meetings', headers=h, json={'title': 'Agent测试', 'transcript': TRANSCRIPT})
    assert r.status_code == 201, r.text
    meeting_id = r.json()['id']
    r = client.post(f'/api/meetings/{meeting_id}/runs', headers=h)
    assert r.status_code == 200, r.text
    return meeting_id, r.json()['id']


def result(client, token, run_id):
    r = client.get('/api/agent-runs/' + run_id, headers=headers(token))
    assert r.status_code == 200, r.text
    return r.json()


def test_unconfigured_returns_503_and_no_run(client, member3_token, monkeypatch):
    monkeypatch.setattr(config, 'AICAP_LLM_API_KEY', '')
    h = headers(member3_token)
    cfg = client.get('/api/agent/config', headers=h).json()
    assert cfg['configured'] is False and 'key' not in json.dumps(cfg)
    meeting = client.post('/api/meetings', headers=h, json={'title': '未配置', 'transcript': TRANSCRIPT}).json()
    assert client.post(f"/api/meetings/{meeting['id']}/runs", headers=h).status_code == 503
    assert client.get(f"/api/meetings/{meeting['id']}/runs", headers=h).json() == []


def test_full_agent_to_human_review(client, member3_token, member1_token):
    meeting_id, run_id = create(client, member3_token)
    h = headers(member3_token)
    before = client.get('/api/pool', headers=h).json()
    model = ScriptedModel()
    assert jobs.run_next(TestSession, model)
    data = result(client, member3_token, run_id)
    assert data['status'] == 'awaiting_review' and data['attempt'] == 1
    assert len(model.calls) == 3
    assert {e['detail']['name'] for e in data['events'] if e['kind'] == 'tool'} == {'search_stories', 'search_pool'}
    assert client.get('/api/pool', headers=h).json() == before
    ids = data['result']['suggestion_ids']
    assert len(ids) == 1
    suggestion = client.get('/api/suggestions/' + ids[0], headers=h).json()
    assert suggestion['agent_run_id'] == run_id
    assert suggestion['status'] == 'pending' and suggestion['origin'] == 'agent'
    assert '系统默认' in suggestion['note'] and suggestion['changes']['priority'] == 'Could'
    assert client.post(f'/api/meetings/{meeting_id}/runs', headers=h).json()['id'] == run_id
    assert not jobs.run_next(TestSession, ScriptedModel())
    assert client.post(f'/api/agent-runs/{run_id}/retry', headers=h).status_code == 409
    approved = client.post('/api/suggestions/' + ids[0] + '/review', headers=headers(member1_token),
                           json={'decision': 'approve'}).json()
    assert approved['execution_status'] == 'succeeded'
    assert len(client.get('/api/pool', headers=h).json()) == len(before) + 1


@pytest.mark.parametrize('mutation,code', [
    ('quote', 'invalid_evidence'), ('segment', 'invalid_evidence'),
    ('owner', 'invented_assignment'), ('deadline', 'invented_assignment'),
    ('action', 'invalid_output'), ('extra', 'invalid_output'),
])
def test_invalid_ai_result_cannot_create_suggestions(client, member3_token, mutation, code):
    meeting_id, run_id = create(client, member3_token)
    body = copy.deepcopy(analysis())
    if mutation == 'quote': body['proposals'][0]['evidence']['quote'] = '不存在的会议内容'
    if mutation == 'segment': body['proposals'][0]['evidence']['segment_id'] = 'seg-999'
    if mutation == 'owner': body['action_items'][0]['owner_mention'] = '成员4'
    if mutation == 'deadline': body['action_items'][0]['deadline_text'] = '2026-09-30'
    if mutation == 'action': body['proposals'][0]['action'] = 'task.update'
    if mutation == 'extra': body['proposals'][0]['approved'] = True
    jobs.run_next(TestSession, ScriptedModel(body))
    data = result(client, member3_token, run_id)
    assert data['status'] == 'failed' and data['error_code'] == code
    assert data['result'] is None
    assert client.get('/api/suggestions', params={'meeting_id': meeting_id}, headers=headers(member3_token)).json() == []


def test_forbidden_write_tool_and_retry(client, member3_token):
    meeting_id, run_id = create(client, member3_token)
    jobs.run_next(TestSession, ScriptedModel(bad_tool='approve_suggestion'))
    data = result(client, member3_token, run_id)
    assert data['error_code'] == 'tool_not_allowed'
    retried = client.post('/api/agent-runs/' + run_id + '/retry', headers=headers(member3_token)).json()
    assert retried['id'] == run_id and retried['attempt'] == 2
    jobs.run_next(TestSession, ScriptedModel())
    data = result(client, member3_token, run_id)
    assert data['status'] == 'awaiting_review'
    assert {e['attempt'] for e in data['events']} == {1, 2}
    assert len(client.get('/api/suggestions', params={'meeting_id': meeting_id}, headers=headers(member3_token)).json()) == 1


def test_missing_fields_remain_null_and_no_proposals(client, member3_token):
    _, run_id = create(client, member3_token)
    body = analysis()
    body['action_items'][0]['owner_mention'] = None
    body['action_items'][0]['deadline_text'] = None
    body['proposals'] = []
    jobs.run_next(TestSession, ScriptedModel(body))
    data = result(client, member3_token, run_id)
    assert data['status'] == 'completed'
    assert data['result']['action_items'][0]['owner_mention'] is None
    assert data['result']['suggestion_ids'] == []


def test_exact_duplicate_proposals_skipped(client, member3_token):
    _, run_id = create(client, member3_token)
    body = analysis()
    body['proposals'].append(copy.deepcopy(body['proposals'][0]))
    jobs.run_next(TestSession, ScriptedModel(body))
    data = result(client, member3_token, run_id)
    assert len(data['result']['suggestion_ids']) == 1
    assert len(data['result']['skipped_proposals']) == 1


def test_existing_story_not_recreated(client, member3_token):
    with TestSession() as db:
        title = db.query(models.Story).first().title
    _, run_id = create(client, member3_token)
    jobs.run_next(TestSession, ScriptedModel(analysis(title)))
    data = result(client, member3_token, run_id)
    assert data['status'] == 'completed' and data['result']['suggestion_ids'] == []


def test_all_proposals_rollback_on_persistence_failure(client, member3_token, monkeypatch):
    meeting_id, run_id = create(client, member3_token)
    body = analysis()
    body['proposals'].append(analysis()['proposals'][0])
    original = jobs.stage_suggestion
    calls = []
    def fail_second(db, suggestion, user):
        calls.append(1)
        if len(calls) == 2: raise RuntimeError('sensitive upstream detail')
        return original(db, suggestion, user)
    monkeypatch.setattr(jobs, 'stage_suggestion', fail_second)
    jobs.run_next(TestSession, ScriptedModel(body))
    data = result(client, member3_token, run_id)
    assert data['status'] == 'failed' and data['error_code'] == 'internal_error'
    assert 'sensitive' not in json.dumps(data)
    assert client.get('/api/suggestions', params={'meeting_id': meeting_id}, headers=headers(member3_token)).json() == []


def test_expired_lease_recovers_and_preserves_attempt(client, member3_token):
    _, run_id = create(client, member3_token)
    with TestSession() as db:
        run = db.get(models.MeetingAgentRun, run_id)
        run.status = 'running'; run.worker_token = 'dead-process'
        run.lease_until = datetime.utcnow() - timedelta(seconds=1)
        db.commit()
    data = result(client, member3_token, run_id)
    assert data['status'] == 'failed' and data['error_code'] == 'interrupted'
    assert client.post('/api/agent-runs/' + run_id + '/retry', headers=headers(member3_token)).status_code == 200
    jobs.run_next(TestSession, ScriptedModel())
    assert result(client, member3_token, run_id)['status'] == 'awaiting_review'


def test_concurrent_create_and_workers(client, member3_token):
    meeting_id, run_id = create(client, member3_token)
    h = headers(member3_token)
    with ThreadPoolExecutor(max_workers=3) as executor:
        responses = list(executor.map(lambda _: client.post(f'/api/meetings/{meeting_id}/runs', headers=h), range(3)))
    assert all(r.status_code == 200 and r.json()['id'] == run_id for r in responses)
    model = ScriptedModel()
    with ThreadPoolExecutor(max_workers=3) as executor:
        list(executor.map(lambda _: jobs.run_next(TestSession, model), range(3)))
    assert len(model.calls) == 3
    assert len(result(client, member3_token, run_id)['result']['suggestion_ids']) == 1


def test_role_change_stops_worker(client, member3_token):
    _, run_id = create(client, member3_token)
    with TestSession() as db:
        db.get(models.User, 3).role = 'viewer'; db.commit()
    try:
        model = ScriptedModel()
        jobs.run_next(TestSession, model)
        assert not model.calls
        assert result(client, member3_token, run_id)['error_code'] == 'permission_changed'
        assert client.post('/api/agent-runs/' + run_id + '/retry', headers=headers(member3_token)).status_code == 403
    finally:
        with TestSession() as db:
            db.get(models.User, 3).role = 'member'; db.commit()


def test_auth_and_missing_resources(client, member3_token):
    h = headers(member3_token)
    for path in ['/api/agent/config', '/api/agent-runs/missing', '/api/meetings/missing/runs']:
        assert client.get(path).status_code == 401
    assert client.post('/api/meetings/missing/runs').status_code == 401
    assert client.post('/api/agent-runs/missing/retry').status_code == 401
    assert client.get('/api/agent-runs/missing', headers=h).status_code == 404
    assert client.post('/api/meetings/missing/runs', headers=h).status_code == 404


def test_tool_limits_and_real_data(client):
    members = execute_tool(TestSession, 3, 'list_members', {})
    assert any(m['id'] == 3 for m in members['items'])
    summary = execute_tool(TestSession, 3, 'project_summary', {})
    assert summary['member_capacity'] is None and summary['current_sprint'] is None
    assert execute_tool(TestSession, 3, 'list_tasks', {'owner_id': 3})['limitations']
    assert execute_tool(TestSession, 3, 'search_stories', {'keyword': '%never%'})['items'] == []
    with pytest.raises(AgentError): execute_tool(TestSession, 3, 'search_pool', {'keyword': '', 'sql': 'DROP TABLE'})


def test_loop_stops_when_model_refuses_tools(client, monkeypatch):
    class NoTools:
        def complete(self, *args, **kwargs): return {'role': 'assistant', 'content': '{}'}, {}
    monkeypatch.setattr(config, 'AICAP_AGENT_MAX_STEPS', 2)
    with pytest.raises(AgentError, match='轮数上限'):
        runner.analyze(TRANSCRIPT, 3, TestSession, NoTools(), lambda *args: None, lambda: None)


def test_model_transport_uses_tools_and_json_without_exposing_key(client):
    bodies = []
    def handle(request):
        body = json.loads(request.content); bodies.append(body)
        assert request.headers['Authorization'] == 'Bearer test-key-not-real'
        return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {'content': '{}'}}],
                                        'usage': {'total_tokens': 5, 'private': 'must-not-return'}})
    model = ModelClient(transport=httpx.MockTransport(handle))
    _, usage = model.complete([{'role': 'user', 'content': 'JSON'}], [])
    model.complete([{'role': 'user', 'content': 'JSON'}], [], final=True)
    assert 'tools' in bodies[0] and bodies[0]['thinking']['type'] == 'disabled'
    assert 'tools' not in bodies[1] and bodies[1]['response_format'] == {'type': 'json_object'}
    assert usage == {'total_tokens': 5} and 'test-key-not-real' not in json.dumps(bodies)


@pytest.mark.parametrize('status', [401, 429, 500])
def test_provider_errors_are_safe(client, status):
    model = ModelClient(transport=httpx.MockTransport(lambda _: httpx.Response(status, text='secret-key-response')))
    with pytest.raises(AgentError) as exc:
        model.complete([], [])
    assert str(status) in str(exc.value) and 'secret-key-response' not in str(exc.value)


def test_provider_timeout_and_truncation(client):
    def timeout(request): raise httpx.ReadTimeout('private URL', request=request)
    with pytest.raises(AgentError) as exc:
        ModelClient(transport=httpx.MockTransport(timeout)).complete([], [])
    assert exc.value.code == 'provider_timeout' and 'private' not in str(exc.value)
    model = ModelClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={
        'choices': [{'finish_reason': 'length', 'message': {'content': '{'}}]})))
    with pytest.raises(AgentError) as exc:
        model.complete([], [])
    assert exc.value.code == 'incomplete_response'
