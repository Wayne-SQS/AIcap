# -*- coding: utf-8 -*-
"""智能体 API 契约 Stub 验收:两智能体运行类端点 501、权限边界、/api/agents 注册表与 OpenAPI 契约锁定。
viewer 用户在测试内直建(create_token 模式,参照 test_viewer_role),不动全局种子。"""
import uuid

import pytest
from conftest import TestSession

from app import models, security


def auth(token):
    return {'Authorization': f'Bearer {token}'}


@pytest.fixture()
def viewer_headers(client):
    """测试内直建 viewer 用户,用后即删,避免污染共享测试库。"""
    with TestSession() as db:
        user = models.User(username='viewer-' + uuid.uuid4().hex, display_name='只读者',
                           role='viewer', color='gray', password_hash='not-used')
        db.add(user)
        db.commit()
        uid = user.id
    yield auth(security.create_token(uid))
    with TestSession() as db:
        db.delete(db.get(models.User, uid))
        db.commit()


CANVAS_RUN = {'mode': 'generate', 'view': 'story_map', 'instruction': '生成项目规划图'}
PROFILE_RUN = {'target': 'team', 'date_from': '2026-01-01', 'date_to': '2026-01-31'}

# 两智能体 × (POST runs 携合法体 / GET runs / GET runs/{id} / POST retry);不存在的 run_id 一律 501,不伪装 404
RUN_501_CASES = [
    ('POST', '/api/canvas-agent/runs', CANVAS_RUN, '画布智能体'),
    ('GET', '/api/canvas-agent/runs', None, '画布智能体'),
    ('GET', '/api/canvas-agent/runs/not-exist', None, '画布智能体'),
    ('POST', '/api/canvas-agent/runs/not-exist/retry', None, '画布智能体'),
    ('POST', '/api/profile-agent/runs', PROFILE_RUN, '成员画像智能体'),
    ('GET', '/api/profile-agent/runs', None, '成员画像智能体'),
    ('GET', '/api/profile-agent/runs/not-exist', None, '成员画像智能体'),
    ('POST', '/api/profile-agent/runs/not-exist/retry', None, '成员画像智能体'),
]

# 11 个新端点全部要求登录
ALL_NEW_ENDPOINTS = [
    ('GET', '/api/canvas-agent/config', None),
    ('POST', '/api/canvas-agent/runs', CANVAS_RUN),
    ('GET', '/api/canvas-agent/runs', None),
    ('GET', '/api/canvas-agent/runs/not-exist', None),
    ('POST', '/api/canvas-agent/runs/not-exist/retry', None),
    ('GET', '/api/profile-agent/config', None),
    ('POST', '/api/profile-agent/runs', PROFILE_RUN),
    ('GET', '/api/profile-agent/runs', None),
    ('GET', '/api/profile-agent/runs/not-exist', None),
    ('POST', '/api/profile-agent/runs/not-exist/retry', None),
    ('GET', '/api/agents', None),
]

# viewer 触发付费 LLM 调用的写端点:两个 POST runs + 两个 retry
VIEWER_WRITE_CASES = [
    ('POST', '/api/canvas-agent/runs', CANVAS_RUN),
    ('POST', '/api/profile-agent/runs', PROFILE_RUN),
    ('POST', '/api/canvas-agent/runs/not-exist/retry', None),
    ('POST', '/api/profile-agent/runs/not-exist/retry', None),
]


# ------------------------------------------------------------ 501 契约 ----
@pytest.mark.parametrize('method,path,body,keyword', RUN_501_CASES)
def test_run_endpoints_stub_501(client, member1_token, method, path, body, keyword):
    """member(合法可写角色)调用 run 类端点:校验通过后 501,detail 指明智能体尚未开发。"""
    r = client.request(method, path, json=body, headers=auth(member1_token))
    assert r.status_code == 501, (path, r.status_code, r.text)
    assert keyword in r.json()['detail']


# ------------------------------------------------------------ 权限 ----
@pytest.mark.parametrize('method,path,body', ALL_NEW_ENDPOINTS)
def test_new_endpoints_require_login(client, method, path, body):
    """11 个新端点无 token 一律 401。"""
    r = client.request(method, path, json=body)
    assert r.status_code == 401, (path, r.status_code)


@pytest.mark.parametrize('method,path,body', VIEWER_WRITE_CASES)
def test_viewer_cannot_write_agent_runs(client, viewer_headers, member3_token, method, path, body):
    """viewer 触发两个智能体运行/重试 403;member 同一请求 501(拦截未过宽,对照组)。"""
    r = client.request(method, path, json=body, headers=viewer_headers)
    assert r.status_code == 403, (path, r.status_code)
    r = client.request(method, path, json=body, headers=auth(member3_token))
    assert r.status_code == 501, (path, r.status_code)


def test_viewer_can_read_configs_and_registry(client, viewer_headers):
    """viewer 读两个 config 与 /api/agents 注册表全部 200(只读可用)。"""
    for path in ('/api/canvas-agent/config', '/api/profile-agent/config', '/api/agents'):
        assert client.get(path, headers=viewer_headers).status_code == 200, path


def test_viewer_run_lists_are_501_not_403(client, viewer_headers):
    """GET runs 仅要求登录:viewer 命中 501 预留契约而非 403。"""
    assert client.get('/api/canvas-agent/runs', headers=viewer_headers).status_code == 501
    assert client.get('/api/profile-agent/runs', headers=viewer_headers).status_code == 501


# ------------------------------------------------------------ 注册表与向后兼容 ----
def test_agents_registry_and_agent_config_compat(client, member1_token):
    """GET /api/agents 返回三个智能体;meeting 条目与既有 /agent/config 逐项一致。"""
    h = auth(member1_token)
    r = client.get('/api/agents', headers=h)
    assert r.status_code == 200
    agents = r.json()['agents']
    assert len(agents) == 3
    assert {a['agent_id'] for a in agents} == {'meeting', 'canvas', 'profile'}
    for a in agents:
        for key in ('name', 'status', 'available', 'config_path'):
            assert key in a, (a['agent_id'], key)
    canvas = next(a for a in agents if a['agent_id'] == 'canvas')
    profile = next(a for a in agents if a['agent_id'] == 'profile')
    meeting = next(a for a in agents if a['agent_id'] == 'meeting')
    assert canvas['supported_views'] == ['story_map', 'gantt', 'member_load', 'uml']
    assert canvas['available'] is False
    assert canvas['config_path'] == '/api/canvas-agent/config'
    assert profile['supported_targets'] == ['member', 'team']
    assert profile['available'] is False
    assert profile['config_path'] == '/api/profile-agent/config'
    cfg = client.get('/api/agent/config', headers=h).json()
    assert meeting['configured'] == cfg['configured']
    assert meeting['model'] == cfg['model']
    assert meeting['worker_enabled'] == cfg['worker_enabled']
    assert meeting['supported_actions'] == cfg['supported_actions']
    assert meeting['prompt_version'] == cfg['prompt_version']
    assert meeting['config_path'] == '/api/agent/config'
    # tripwire:/agent/config 响应键集合不可漂移(前端正在消费)
    assert set(cfg.keys()) == {'configured', 'model', 'worker_enabled', 'supported_actions', 'prompt_version'}


def test_config_endpoints_return_capability(client, member1_token):
    """GET config 是真实实现:返回 CAPABILITY 能力声明(单一事实来源)。"""
    h = auth(member1_token)
    canvas = client.get('/api/canvas-agent/config', headers=h).json()
    assert canvas['agent_id'] == 'canvas'
    assert canvas['name'] == 'AI 项目规划画布智能体'
    assert canvas['status'] == 'planned'
    assert canvas['available'] is False
    assert canvas['configured'] is False
    assert canvas['model'] is None
    assert canvas['worker_enabled'] is False
    assert canvas['supported_actions'] == []
    assert canvas['supported_views'] == ['story_map', 'gantt', 'member_load', 'uml']
    assert canvas['supported_uml_kinds'] == ['use_case', 'sequence', 'class']
    assert canvas['prompt_version'] is None
    profile = client.get('/api/profile-agent/config', headers=h).json()
    assert profile['agent_id'] == 'profile'
    assert profile['name'] == 'AI 任务提交与成员能力画像智能体'
    assert profile['status'] == 'planned'
    assert profile['available'] is False
    assert profile['configured'] is False
    assert profile['model'] is None
    assert profile['worker_enabled'] is False
    assert profile['supported_targets'] == ['member', 'team']
    assert profile['prompt_version'] is None


# ------------------------------------------------------------ 422 契约锁定 ----
def test_canvas_run_input_contract(client, member1_token):
    """canvas 请求体校验:view 枚举、instruction 长度、uml_kind 交叉校验、未知字段。"""
    h = auth(member1_token)
    url = '/api/canvas-agent/runs'
    base = {'mode': 'generate', 'view': 'story_map', 'instruction': '生成项目规划图'}
    assert client.post(url, json={**base, 'view': 'timeline'}, headers=h).status_code == 422
    assert client.post(url, json={**base, 'instruction': ''}, headers=h).status_code == 422
    assert client.post(url, json={**base, 'instruction': '长' * 2001}, headers=h).status_code == 422
    r = client.post(url, json={**base, 'uml_kind': 'class'}, headers=h)
    assert r.status_code == 422
    assert 'uml_kind 仅在 view=uml 时允许' in r.text
    assert client.post(url, json={**base, 'unknown_field': 1}, headers=h).status_code == 422


def test_profile_run_input_contract(client, member1_token):
    """profile 请求体校验:日期先后、member_id 交叉校验、日期格式。"""
    h = auth(member1_token)
    url = '/api/profile-agent/runs'
    base = {'target': 'member', 'member_id': 1, 'date_from': '2026-01-01', 'date_to': '2026-01-31'}
    r = client.post(url, json={**base, 'date_to': '2025-12-31'}, headers=h)
    assert r.status_code == 422
    assert 'date_to 不能早于 date_from' in r.text
    r = client.post(url, json={k: v for k, v in base.items() if k != 'member_id'}, headers=h)
    assert r.status_code == 422
    r = client.post(url, json={**base, 'target': 'team'}, headers=h)
    assert r.status_code == 422
    assert '团队分析不能指定成员' in r.text
    assert client.post(url, json={**base, 'date_from': '2026/01/01'}, headers=h).status_code == 422


# ------------------------------------------------------------ OpenAPI ----
def test_openapi_documents_stub_paths(client):
    """OpenAPI 文档包含两智能体 run 端点与注册表端点。"""
    paths = client.get('/openapi.json').json()['paths']
    assert '/api/canvas-agent/runs' in paths
    assert '/api/profile-agent/runs' in paths
    assert '/api/agents' in paths
