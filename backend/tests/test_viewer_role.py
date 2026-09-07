# -*- coding: utf-8 -*-
"""M03 只读角色(viewer)验收测试:viewer 全部写接口 403、读接口 200,member 对照组可写。
viewer 用户在测试内直建(create_token 模式,参照 test_meeting_review),不动全局种子。"""
import uuid

import pytest
from conftest import TestSession

from app import config, models, security


def auth(token):
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture(autouse=True)
def fake_llm_key(monkeypatch):
    # 假密钥:member 触发 agent run 走到 200 而非 503(BASE_URL/MODEL 已有默认值)
    monkeypatch.setattr(config, "AICAP_LLM_API_KEY", "test-key-not-real")


@pytest.fixture()
def viewer_headers(client):
    """测试内直建 viewer 用户,用后即删,避免污染共享测试库。"""
    with TestSession() as db:
        user = models.User(username="viewer-" + uuid.uuid4().hex, display_name="只读者",
                           role="viewer", color="gray", password_hash="not-used")
        db.add(user)
        db.commit()
        uid = user.id
    yield auth(security.create_token(uid))
    with TestSession() as db:
        db.delete(db.get(models.User, uid))
        db.commit()


def make_story(client, headers, title="viewer 拦截测试卡"):
    r = client.post("/api/stories", json={"title": title, "description": "作为测试者，我希望验证只读拦截",
                                          "acceptance": "viewer 得到 403"}, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()["id"]


def make_task():
    tid = "V" + uuid.uuid4().hex[:5].upper()
    with TestSession() as db:
        db.add(models.Task(id=tid, name="viewer 拦截测试任务", owner_id=1, hours=8,
                           week_start=1, week_end=2, story_ref="TEST", kanban_card_id=None,
                           task_type="feature", estimated_hours=8, status=0))
        db.commit()
    return tid


def make_pool_item(client, headers, title="只读角色测试需求"):
    r = client.post("/api/pool", json={"title": title, "source": "测试", "priority": "Could"}, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()["id"]


def make_meeting(client, headers):
    r = client.post("/api/meetings", json={"title": "只读角色测试会议 " + uuid.uuid4().hex[:6],
                                           "transcript": "讨论权限拦截。"}, headers=headers)
    assert r.status_code == 201, r.text
    return r.json()["id"]


# ------------------------------------------------------------- 只读可用 ----
def test_viewer_can_read_lists(client, viewer_headers):
    """viewer 登录后读接口全部 200(只读可用)。"""
    for path in ("/api/stories", "/api/tasks", "/api/pool", "/api/stories/logs"):
        assert client.get(path, headers=viewer_headers).status_code == 200, path
    me = client.get("/api/auth/me", headers=viewer_headers)
    assert me.status_code == 200 and me.json()["role"] == "viewer"


# ------------------------------------------------------------- 写拦截 ----
def test_viewer_cannot_write_stories(client, viewer_headers, member1_token):
    """viewer 建故事 403、改故事(含拖拽改状态)403,且库内数据未被改动。"""
    hm = auth(member1_token)
    sid = make_story(client, hm)
    payload = {"title": "越权尝试", "description": "作为 viewer，我希望写入", "acceptance": "应被拦截"}
    assert client.post("/api/stories", json=payload, headers=viewer_headers).status_code == 403
    assert client.patch(f"/api/stories/{sid}", json={"status": 2}, headers=viewer_headers).status_code == 403
    story = next(s for s in client.get("/api/stories", headers=hm).json() if s["id"] == sid)
    assert story["status"] == 0


def test_viewer_cannot_write_tasks(client, viewer_headers, member1_token):
    """viewer 改任务 403,member 同一任务 200(拦截到位且未过宽)。"""
    hm = auth(member1_token)
    tid = make_task()
    assert client.patch(f"/api/tasks/{tid}", json={"status": 1}, headers=viewer_headers).status_code == 403
    assert client.patch(f"/api/tasks/{tid}", json={"status": 1}, headers=hm).status_code == 200


def test_viewer_cannot_write_pool(client, viewer_headers, member1_token):
    """viewer 增/删/移入看板需求池 403,条目仍在。"""
    hm = auth(member1_token)
    pid = make_pool_item(client, hm)
    assert client.post("/api/pool", json={"title": "越权需求", "priority": "Could"},
                       headers=viewer_headers).status_code == 403
    assert client.post(f"/api/pool/{pid}/promote", json={"sprint": 2},
                       headers=viewer_headers).status_code == 403
    assert client.delete(f"/api/pool/{pid}", headers=viewer_headers).status_code == 403
    assert any(p["id"] == pid for p in client.get("/api/pool", headers=hm).json())


def test_viewer_cannot_trigger_agent_run(client, viewer_headers, member1_token):
    """viewer 触发会议智能体分析 403(只读角色不能触发 LLM 付费调用),member 可触发。"""
    hm = auth(member1_token)
    meeting_id = make_meeting(client, hm)
    assert client.post(f"/api/meetings/{meeting_id}/runs", headers=viewer_headers).status_code == 403
    assert client.post(f"/api/meetings/{meeting_id}/runs", headers=hm).status_code == 200


def test_viewer_cannot_delete_story(client, viewer_headers, member1_token):
    """viewer 删卡 403(删除本就限 admin/owner,viewer 同样被拒)。"""
    hm = auth(member1_token)
    sid = make_story(client, hm, title="viewer 删除拦截测试卡")
    assert client.delete(f"/api/stories/{sid}?undone=keep", headers=viewer_headers).status_code == 403


# ------------------------------------------------------------- 对照组 ----
def test_member_control_group_can_write(client, member3_token):
    """member(最低可写角色)对全部新拦截端点均放行,确认未拦截过宽。"""
    h = auth(member3_token)
    sid = make_story(client, h, title="member 对照建卡")
    assert client.patch(f"/api/stories/{sid}", json={"status": 1}, headers=h).status_code == 200
    tid = make_task()
    assert client.patch(f"/api/tasks/{tid}", json={"week_start": 2, "week_end": 3}, headers=h).status_code == 200
    pid = make_pool_item(client, h, title="member 对照需求")
    assert client.post(f"/api/pool/{pid}/promote", json={"sprint": 2, "activity": 2},
                       headers=h).status_code == 200
    pid2 = make_pool_item(client, h, title="member 对照删除需求")
    assert client.delete(f"/api/pool/{pid2}", headers=h).status_code == 200
