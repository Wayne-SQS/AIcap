# -*- coding: utf-8 -*-
"""
爱管理 · 后端扩展测试(测试用例执行层)
====================================
对应测试设计文档「后端 API 用例」批次:
  - AUTH-xx  认证与鉴权(正向/负向/越权)
  - STORY-xx 用户故事 CRUD / 筛选 / 变更日志 / 边界
  - POOL-xx  需求池 CRUD / 移入看板 / 边界
  - TASK-xx  任务读取
  - DASH-xx  仪表盘统计口径与数据一致性
  - HEALTH-xx 健康检查
每条用例自建数据,互不依赖(共享 AIcap_test 会话库,遵循既有 conftest 约定)。
"""

import re

from fastapi.testclient import TestClient

from app import models


def auth(token):
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------- AUTH ----
def test_login_unknown_user_401(client):
    """AUTH-05 未注册账号登录 -> 401 且不泄露用户是否存在的差异信息。"""
    r = client.post("/api/auth/login", json={"username": "不存在的用户", "password": "123456"})
    assert r.status_code == 401
    assert r.json()["detail"] == "用户名或密码错误"


def test_login_empty_fields_422(client):
    """AUTH-06 登录请求体缺字段 -> 422 参数校验失败。"""
    assert client.post("/api/auth/login", json={}).status_code == 422
    assert client.post("/api/auth/login", json={"username": "成员1"}).status_code == 422


def test_login_member_role_and_me(client):
    """AUTH-03 成员(非管理员)登录成功且 /me 返回自身角色。"""
    r = client.post("/api/auth/login", json={"username": "成员3", "password": "123456"})
    assert r.status_code == 200
    token = r.json()["access_token"]
    me = client.get("/api/auth/me", headers=auth(token))
    assert me.status_code == 200
    assert me.json()["username"] == "成员3"
    assert me.json()["role"] == "member"


def test_me_rejects_garbage_token_401(client):
    """AUTH-07 伪造/损坏 token -> 401。"""
    assert client.get("/api/auth/me", headers=auth("not-a-jwt")).status_code == 401
    assert client.get("/api/auth/me", headers={"Authorization": "Bearer"}).status_code == 401


def test_users_list_requires_token_and_returns_roles(client):
    """AUTH-08 GET /api/auth/users 未登录 401;登录后返回 4 名成员及角色。"""
    assert client.get("/api/auth/users").status_code == 401
    r = client.get("/api/auth/users", headers=auth(client.post(
        "/api/auth/login", json={"username": "成员1", "password": "123456"}
    ).json()["access_token"]))
    assert r.status_code == 200
    data = r.json()
    assert len(data) == 5
    roles = {u["role"] for u in data}
    assert {"admin", "owner", "member", "viewer"} <= roles
    assert all({"id", "username", "display_name", "role", "color"} <= set(u) for u in data)


# --------------------------------------------------------------- STORY ----
def test_story_create_full_and_defaults(client, member1_token):
    """STORY-01 全字段创建成功返回一致;STORY-02 仅标题创建应用默认值。"""
    h = auth(member1_token)
    full = client.post("/api/stories", json={
        "title": "扩展测试故事", "description": "作为测试，我希望验证", "acceptance": "可验证",
        "priority": "Must", "sprint": 2, "activity": 3, "status": 0, "owner_id": 2,
    }, headers=h)
    assert full.status_code == 200
    b = full.json()
    assert re.match(r"^US\d{2,}$", b["id"])
    assert b["priority"] == "Must" and b["sprint"] == 2 and b["activity"] == 3 and b["owner_id"] == 2

    mini = client.post("/api/stories", json={"title": "仅标题故事"}, headers=h)
    assert mini.status_code == 200
    m = mini.json()
    assert m["description"] == "" and m["acceptance"] == ""
    assert m["priority"] == "Must" and m["sprint"] == 1 and m["activity"] == 2 and m["status"] == 0


def test_story_create_requires_token_401(client):
    """STORY-03 未登录创建故事 -> 401。"""
    assert client.post("/api/stories", json={"title": "x"}).status_code == 401


def test_story_title_too_long_422(client, member1_token):
    """STORY-04 标题超长(>200) -> 422 校验失败(边界)。"""
    r = client.post("/api/stories", json={"title": "长" * 201}, headers=auth(member1_token))
    assert r.status_code == 422


def test_story_status_wrong_type_422(client, member1_token):
    """STORY-05 status 传非整数 -> 422(边界)。"""
    r = client.post("/api/stories", json={"title": "坏状态", "status": "完成"},
                    headers=auth(member1_token))
    assert r.status_code == 422


def test_story_filters_owner_status_q(client, member1_token):
    """STORY-06 组合筛选 owner/status/q 与结果一致。"""
    h = auth(member1_token)
    sid = client.post("/api/stories", json={
        "title": "筛选目标·唯一关键词", "sprint": 3, "status": 1, "owner_id": 3, "activity": 4
    }, headers=h).json()["id"]

    by_owner = client.get("/api/stories?owner=3", headers=h).json()
    assert any(x["id"] == sid for x in by_owner) and all(x["owner_id"] == 3 for x in by_owner)

    by_q = client.get("/api/stories?q=唯一关键词", headers=h).json()
    assert [x["id"] for x in by_q] == [sid]

    by_sprint_status = client.get("/api/stories?sprint=3&status=1", headers=h).json()
    assert any(x["id"] == sid for x in by_sprint_status)
    assert all(x["sprint"] == 3 and x["status"] == 1 for x in by_sprint_status)

    by_activity = client.get("/api/stories?activity=4", headers=h).json()
    assert all(x["activity"] == 4 for x in by_activity)


def test_story_patch_404_and_noop(client, member1_token):
    """STORY-07 补丁不存在的故事 -> 404;空补丁返回原数据。"""
    h = auth(member1_token)
    assert client.patch("/api/stories/M9999", json={"status": 2}, headers=h).status_code == 404

    sid = client.post("/api/stories", json={"title": "空补丁目标"}, headers=h).json()["id"]
    r = client.patch(f"/api/stories/{sid}", json={}, headers=h)
    assert r.status_code == 200
    assert r.json()["title"] == "空补丁目标"


def test_story_patch_logs_move_vs_edit(client, member1_token):
    """STORY-08 改状态记 move、改标题记 edit 且日志可查。"""
    h = auth(member1_token)
    sid = client.post("/api/stories", json={"title": "日志故事"}, headers=h).json()["id"]

    client.patch(f"/api/stories/{sid}", json={"status": 2}, headers=h)
    client.patch(f"/api/stories/{sid}", json={"title": "日志故事-改名"}, headers=h)

    logs = client.get("/api/stories/logs", headers=h).json()
    mine = [l for l in logs if l["story_id"] == sid]
    types = [l["log_type"] for l in mine]
    assert "move" in types and "edit" in types and "create" in types


def test_story_delete_404_and_roles(client, member1_token, member3_token):
    """STORY-09 member 删除被拒(403);owner 删除成功;删除不存在 -> 404。"""
    h1, h3 = auth(member1_token), auth(member3_token)
    sid = client.post("/api/stories", json={"title": "删除越权目标"}, headers=h1).json()["id"]

    assert client.delete(f"/api/stories/{sid}", headers=h3).status_code == 403  # 成员无权限
    assert client.get(f"/api/stories", headers=h3).status_code == 200  # 成员可读
    assert client.delete(f"/api/stories/{sid}", headers=h1).status_code == 200  # admin 可删
    assert client.delete(f"/api/stories/{sid}", headers=h1).status_code == 404  # 再删 404
    assert client.delete("/api/stories/US40404", headers=h1).status_code == 404


def test_story_id_auto_increment(client, member1_token):
    """STORY-10 新故事 ID 按现有最大值递增(USxx 格式)。"""
    h = auth(member1_token)
    all_ids = [s["id"] for s in client.get("/api/stories", headers=h).json()]
    nums = [int(m.group(1)) for i in all_ids if (m := re.match(r"^US(\d+)$", i or ""))]
    expect = f"US{((max(nums) if nums else 0) + 1):02d}"
    got = client.post("/api/stories", json={"title": "自增ID验证"}, headers=h).json()["id"]
    assert got == expect


def test_logs_requires_token(client):
    """STORY-11 变更日志接口未登录 -> 401。"""
    assert client.get("/api/stories/logs").status_code == 401


# ----------------------------------------------------------------- POOL ----
def test_pool_crud_and_404(client, member1_token):
    """POOL-01 需求池增删查;POOL-03 删除不存在 -> 404。"""
    h = auth(member1_token)
    created = client.post("/api/pool", json={
        "title": "池子目标", "description": "描述", "source": "会议X", "priority": "Should"
    }, headers=h)
    assert created.status_code == 200
    pid = created.json()["id"]
    assert pid.startswith("R")

    items = client.get("/api/pool", headers=h).json()
    row = next(x for x in items if x["id"] == pid)
    assert row["title"] == "池子目标" and row["source"] == "会议X" and row["priority"] == "Should"

    assert client.delete(f"/api/pool/{pid}", headers=h).status_code == 200
    assert client.delete(f"/api/pool/{pid}", headers=h).status_code == 404
    assert client.delete("/api/pool/R404", headers=h).status_code == 404


def test_pool_minimal_and_requires_token(client, member1_token):
    """POOL-02 仅标题创建默认 Could;未登录 401。"""
    assert client.post("/api/pool", json={"title": "匿名词条"}).status_code == 401
    r = client.post("/api/pool", json={"title": "默认优先词条"}, headers=auth(member1_token))
    assert r.status_code == 200
    assert r.json()["priority"] == "Could"


def test_pool_promote_creates_story_and_removes_item(client, member1_token):
    """POOL-04 移入看板:新故事字段派生自词条、保留来源、池条目删除、ID 递增。"""
    h = auth(member1_token)
    pid = client.post("/api/pool", json={
        "title": "移入目标需求", "description": "移入描述", "source": "会议 09-07", "priority": "Must"
    }, headers=h).json()["id"]

    r = client.post(f"/api/pool/{pid}/promote", headers=h, json={"sprint":2})
    assert r.status_code == 200
    story = r.json()
    assert story["sprint"] == 2 and story["status"] == 0
    assert story["description"] == "移入描述"
    assert "会议 09-07" in story["acceptance"]
    assert story["priority"] == "Must"

    assert all(x["id"] != pid for x in client.get("/api/pool", headers=h).json())
    assert client.post(f"/api/pool/{pid}/promote", headers=h, json={"sprint":2}).status_code == 404


def test_pool_promote_requires_token(client):
    """POOL-05 移入看板未登录 -> 401。"""
    assert client.post("/api/pool/R01/promote").status_code == 401


# ---------------------------------------------------------------- TASK ----
def test_tasks_list_and_requires_token(client, member1_token):
    """TASK-01 任务列表登录后返回结构合法;未登录 401。"""
    assert client.get("/api/tasks").status_code == 401
    data = client.get("/api/tasks", headers=auth(member1_token)).json()
    assert isinstance(data, list)
    for t in data:
        assert {"id", "name", "owner_id", "hours", "week_start", "week_end", "story_ref", "depends_on", "sprints"} <= set(t)
        assert re.match(r"^T\d+$", t["id"])


def test_task_patch_validates_relations_and_updates_schedule(client, member1_token):
    """TASK-02 任务更新复用 Story/Task 真实关联，并校验前置依赖。"""
    h = auth(member1_token)
    db = client.app.dependency_overrides[get_db].__closure__[0].cell_contents() if False else None
    # Use API-created stories and direct test DB task setup through the imported model/session fixture behavior.
    from app.database import get_db
    session_factory = client.app.dependency_overrides[get_db]
    session_generator = session_factory()
    session = next(session_generator)
    try:
        session.add(models.Story(id="US88", title="关联任务测试", description="", acceptance="", owner_id=1))
        session.add(models.Task(id="T88", name="前置任务", owner_id=1, hours=4, week_start=1, week_end=1, story_ref="US88", depends_on=""))
        session.add(models.Task(id="T89", name="待更新任务", owner_id=2, hours=4, week_start=2, week_end=2, story_ref="US88", depends_on="T88"))
        session.commit()
    finally:
        session_generator.close()

    updated = client.patch("/api/tasks/T89", json={
        "owner_id": 3, "week_start": 3, "week_end": 4, "story_ref": "US88", "depends_on": "T88",
    }, headers=h)
    assert updated.status_code == 200
    assert updated.json()["owner_id"] == 3
    assert updated.json()["sprints"] == [2]

    assert client.patch("/api/tasks/T89", json={"week_start": 5, "week_end": 4}, headers=h).status_code == 400
    assert client.patch("/api/tasks/T89", json={"story_ref": "US404"}, headers=h).status_code == 400
    assert client.patch("/api/tasks/T88", json={"depends_on": "T89"}, headers=h).status_code == 400


# ------------------------------------------------------------- DASHBOARD ----
def test_dashboard_consistency(client, member1_token):
    """DASH-01 统计口径:总数=各状态和,percent 与完成数一致,by_sprint 结构合法。"""
    h = auth(member1_token)
    client.post("/api/stories", json={"title": "统计故事A", "status": 2, "sprint": 1}, headers=h)
    client.post("/api/stories", json={"title": "统计故事B", "status": 1, "sprint": 2}, headers=h)

    d = client.get("/api/dashboard", headers=h).json()
    stories = client.get("/api/stories", headers=h).json()
    total = len(stories)
    done = sum(1 for s in stories if s["status"] == 2)
    doing = sum(1 for s in stories if s["status"] == 1)
    todo = sum(1 for s in stories if s["status"] == 0)

    assert d["total"] == total == d["done"] + d["doing"] + d["todo"]
    assert d["done"] == done and d["doing"] == doing and d["todo"] == todo
    assert d["percent"] == (round(done / total * 100) if total else 0)
    assert len(d["by_sprint"]) == 4
    for sp in d["by_sprint"]:
        assert set(sp) == {"sprint", "total", "done", "percent"}
        assert sp["sprint"] in (1, 2, 3, 4)
    sprint_total = sum(sp["total"] for sp in d["by_sprint"])
    assert sprint_total == total  # 阶段1数据约定 sprint ∈ 1..3


def test_dashboard_requires_token(client):
    """DASH-02 未登录 -> 401。"""
    assert client.get("/api/dashboard").status_code == 401


# --------------------------------------------------------------- HEALTH ----
def test_health_ok(client):
    """HEALTH-01 健康检查返回 ok 且 db 连通。"""
    r = client.get("/api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok" and body["db"] is True
