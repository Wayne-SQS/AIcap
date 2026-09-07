# -*- coding: utf-8 -*-
"""
爱管理 · 看板↔甘特血缘重构 后端测试
====================================
对应测试设计文档 v2「2.2 血缘删除(KGN) / 2.3 任务接口(TASK) / 2.4 数据完整性(DB)」批次。
每条用例自建数据,互不依赖(共享 AIcap_test 会话库,遵循既有 conftest 约定)。
"""

import re

from conftest import TestSession

from app import models, seed


def auth(token):
    return {"Authorization": f"Bearer {token}"}


# ------------------------------------------------------------- helpers ----
def make_card(client, headers, title="血缘测试卡", **extra):
    payload = {"title": title, "sprint": 2, "activity": 2, "status": 0}
    payload.update(extra)
    r = client.post("/api/stories", json=payload, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()["id"]


def make_tasks(rows):
    """rows: [(task_id, status, week_start, week_end)] -> 直插 DB 并返回 id 列表。"""
    with TestSession() as db:
        ids = []
        for tid, st, w0, w1 in rows:
            db.add(models.Task(id=tid, name="血缘任务" + tid, owner_id=1, hours=8,
                               week_start=w0, week_end=w1, story_ref="TEST",
                               kanban_card_id=None, task_type="feature",
                               estimated_hours=8, status=st))
            ids.append(tid)
        db.commit()
    return ids


def get_task(client, headers, task_id):
    r = client.get("/api/tasks", headers=headers)
    assert r.status_code == 200
    return next(t for t in r.json() if t["id"] == task_id)


def card_exists(client, headers, card_id):
    """单条 GET 路由不存在(仅列表/POST/PATCH/DELETE),用列表判断存在性。"""
    return any(s["id"] == card_id for s in client.get("/api/stories", headers=headers).json())


def attach(client, headers, task_id, card_id):
    r = client.patch(f"/api/tasks/{task_id}", json={"kanban_card_id": card_id}, headers=headers)
    assert r.status_code == 200, r.text


# -------------------------------------------------------------- TASK ----
def test_tasks_list_returns_lineage_fields(client, member1_token):
    """TASK-02 任务列表回显血缘字段:feature 挂卡 / management 不挂卡。"""
    h = auth(member1_token)
    card = make_card(client, h)
    make_tasks([("KX01", 0, 1, 2), ("KX02", 0, 3, 4)])
    attach(client, h, "KX01", card)
    with TestSession() as db:  # KX02 标记管理任务
        db.get(models.Task, "KX02").task_type = "management"
        db.commit()

    data = {t["id"]: t for t in client.get("/api/tasks", headers=h).json()}
    for t in data.values():  # 新字段必须全部透出
        assert {"kanban_card_id", "estimated_hours", "task_type", "status"} <= set(t)
    assert data["KX01"]["kanban_card_id"] == card
    assert data["KX01"]["task_type"] == "feature"
    assert data["KX01"]["estimated_hours"] == 8
    assert data["KX02"]["kanban_card_id"] is None and data["KX02"]["task_type"] == "management"


def test_task_patch_moves_weeks(client, member1_token):
    """TASK-03 PATCH 挪周正常路径:200 回显且库内生效。"""
    h = auth(member1_token)
    make_tasks([("KX03", 0, 1, 2)])
    r = client.patch("/api/tasks/KX03", json={"week_start": 3, "week_end": 4}, headers=h)
    assert r.status_code == 200
    assert (r.json()["week_start"], r.json()["week_end"]) == (3, 4)
    assert (get_task(client, h, "KX03")["week_start"], get_task(client, h, "KX03")["week_end"]) == (3, 4)


def test_task_patch_updates_status_and_rebinds(client, member1_token):
    """TASK-04/05 PATCH 改状态;解绑(null)与换挂有效卡。"""
    h = auth(member1_token)
    c1, c2 = make_card(client, h), make_card(client, h)
    make_tasks([("KX04", 0, 1, 2)])
    attach(client, h, "KX04", c1)
    r = client.patch("/api/tasks/KX04", json={"status": 2}, headers=h)
    assert r.status_code == 200 and r.json()["status"] == 2
    r = client.patch("/api/tasks/KX04", json={"kanban_card_id": None}, headers=h)
    assert r.status_code == 200 and r.json()["kanban_card_id"] is None
    r = client.patch("/api/tasks/KX04", json={"kanban_card_id": c2}, headers=h)
    assert r.status_code == 200 and r.json()["kanban_card_id"] == c2
    assert get_task(client, h, "KX04")["kanban_card_id"] == c2


def test_task_patch_not_found_404(client, member1_token):
    """TASK-06 PATCH 不存在任务 -> 404。"""
    r = client.patch("/api/tasks/KX404", json={"status": 1}, headers=auth(member1_token))
    assert r.status_code == 404
    assert r.json()["detail"] == "任务不存在"


def test_task_patch_rejects_inverted_weeks_and_bad_card(client, member1_token):
    """TASK-07/08 周序倒挂 400;挂不存在的卡 400。"""
    h = auth(member1_token)
    make_tasks([("KX05", 0, 1, 2)])
    r = client.patch("/api/tasks/KX05", json={"week_start": 5, "week_end": 3}, headers=h)
    assert r.status_code == 400 and "结束周" in r.json()["detail"]
    r = client.patch("/api/tasks/KX05", json={"kanban_card_id": "M999"}, headers=h)
    assert r.status_code == 400 and "看板卡不存在" in r.json()["detail"]


def test_task_patch_field_bounds_422(client, member1_token):
    """TASK-09 字段越界:week 0/7 与 status 9 均 422。"""
    h = auth(member1_token)
    make_tasks([("KX06", 0, 1, 2)])
    for body in ({"week_start": 0}, {"week_start": 7}, {"week_end": 0}, {"week_end": 7}, {"status": 9}):
        assert client.patch("/api/tasks/KX06", json=body, headers=h).status_code == 422


def test_task_patch_empty_body_is_noop(client, member1_token):
    """TASK-10 空补丁幂等:200 返回原数据。"""
    h = auth(member1_token)
    make_tasks([("KX07", 1, 2, 3)])
    r = client.patch("/api/tasks/KX07", json={}, headers=h)
    assert r.status_code == 200
    assert (r.json()["status"], r.json()["week_start"], r.json()["week_end"]) == (1, 2, 3)


def test_task_patch_requires_token_401(client):
    """TASK-11 未登录 PATCH -> 401。"""
    assert client.patch("/api/tasks/KX07", json={"status": 1}).status_code == 401


def test_task_patch_mixed_fields_atomic(client, member1_token):
    """TASK-12 混合合法字段同请求原子生效。"""
    h = auth(member1_token)
    make_tasks([("KX08", 0, 2, 4)])
    r = client.patch("/api/tasks/KX08", json={"status": 2, "week_start": 3, "week_end": 5}, headers=h)
    assert r.status_code == 200
    t = get_task(client, h, "KX08")
    assert (t["status"], t["week_start"], t["week_end"]) == (2, 3, 5)


def test_task_patch_merges_with_stored_weeks(client, member1_token):
    """TASK-13 仅改 week_end 与库内 week_start 合并校验:倒挂拒绝且不写入。"""
    h = auth(member1_token)
    make_tasks([("KX09", 0, 3, 4)])
    r = client.patch("/api/tasks/KX09", json={"week_end": 2}, headers=h)
    assert r.status_code == 400
    assert get_task(client, h, "KX09")["week_end"] == 4  # 未写入


# --------------------------------------------------------------- KGN ----
def _setup_card_with_subs(client, headers, subs, tag):
    """造卡 + 挂 subs[(status,w0,w1)] 子任务,返回 (card_id, task_ids)。"""
    card = make_card(client, headers, title="级联" + tag)
    rows = [(f"KC{tag}{i}", st, w0, w1) for i, (st, w0, w1) in enumerate(subs)]
    ids = make_tasks(rows)
    for tid in ids:
        attach(client, headers, tid, card)
    return card, ids


def test_delete_card_cancel_cascades(client, member1_token):
    """KGN-01 cancel:未完成子任务置已取消(status=3)并解绑;返回审计字段。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 1, 2), (1, 3, 4)], "A")
    r = client.delete(f"/api/stories/{card}?undone=cancel", headers=h)
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True and body["undone"] == "cancel" and body["affected"] == 2  # KGN-09
    for tid in ids:
        t = get_task(client, h, tid)
        assert t["kanban_card_id"] is None and t["status"] == 3
    assert not card_exists(client, h, card)


def test_delete_card_keep_unbinds_only(client, member1_token):
    """KGN-02 keep:子任务状态不变仅解绑,任务仍可查。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 1, 2), (1, 3, 4)], "B")
    r = client.delete(f"/api/stories/{card}?undone=keep", headers=h)
    assert r.status_code == 200 and r.json()["affected"] == 2
    statuses = {get_task(client, h, tid)["status"] for tid in ids}
    assert statuses == {0, 1}  # 状态原样保留
    assert all(get_task(client, h, tid)["kanban_card_id"] is None for tid in ids)


def test_delete_card_detach_moves_undone(client, member1_token):
    """KGN-03 detach:未完成子任务整体 +2 周并入下个 Sprint 并解绑。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 3, 4)], "C")
    r = client.delete(f"/api/stories/{card}?undone=detach", headers=h)
    assert r.status_code == 200 and r.json()["undone"] == "detach"
    t = get_task(client, h, ids[0])
    assert (t["week_start"], t["week_end"]) == (5, 6) and t["kanban_card_id"] is None


def test_delete_card_done_subs_untouched(client, member1_token):
    """KGN-04 已完成子任务(status=2)不受级联影响:保留原周与状态。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(2, 2, 2), (0, 3, 4)], "D")
    r = client.delete(f"/api/stories/{card}?undone=detach", headers=h)
    assert r.status_code == 200 and r.json()["affected"] == 1  # 仅 1 条未完成
    done, undone = get_task(client, h, ids[0]), get_task(client, h, ids[1])
    assert (done["week_start"], done["week_end"], done["status"]) == (2, 2, 2)  # 完成的原地不动
    assert (undone["week_start"], undone["week_end"]) == (5, 6)  # 未完成的挪走


def test_delete_card_detach_clamps_to_w6(client, member1_token):
    """KGN-05 detach 越界钳制:W5-6 挪 +2 后不倒挂、不超 6。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 5, 6)], "E")
    r = client.delete(f"/api/stories/{card}?undone=detach", headers=h)
    assert r.status_code == 200
    t = get_task(client, h, ids[0])
    assert t["week_start"] <= t["week_end"] and t["week_end"] <= 6


def test_delete_card_rejects_bad_undone(client, member1_token):
    """KGN-06 非法 undone 参数 -> 400,卡保留。"""
    h = auth(member1_token)
    card, _ = _setup_card_with_subs(client, h, [(0, 1, 2)], "F")
    r = client.delete(f"/api/stories/{card}?undone=boom", headers=h)
    assert r.status_code == 400 and "cancel/keep/detach" in r.json()["detail"]
    assert card_exists(client, h, card)


def test_delete_card_member_forbidden_403(client, member3_token):
    """KGN-07 member 删卡 -> 403,卡与建议状态不变。"""
    h_admin = auth(client.post("/api/auth/login",
                               json={"username": "成员1", "password": "123456"}).json()["access_token"])
    card, ids = _setup_card_with_subs(client, h_admin, [(0, 1, 2)], "G")
    r = client.delete(f"/api/stories/{card}?undone=keep", headers=auth(member3_token))
    assert r.status_code == 403
    assert card_exists(client, h_admin, card)
    assert get_task(client, h_admin, ids[0])["kanban_card_id"] == card  # 挂载未被动过


def test_delete_card_missing_404(client, member1_token):
    """KGN-08 删不存在的卡 -> 404。"""
    r = client.delete("/api/stories/M9999?undone=keep", headers=auth(member1_token))
    assert r.status_code == 404


def test_delete_card_log_records_cascade(client, member1_token):
    """KGN-09 审计:变更日志留痕含级联策略与条数。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 1, 2)], "H")
    client.delete(f"/api/stories/{card}?undone=cancel", headers=h)
    with TestSession() as db:
        logs = (db.query(models.StoryLog)
                .filter(models.StoryLog.story_id == card)
                .order_by(models.StoryLog.id).all())
        assert logs and any("子任务级联:cancel" in l.detail and "1 条" in l.detail for l in logs)


# ---------------------------------------------------------------- DB ----
def test_fk_on_delete_set_null_via_raw_delete(client, member1_token):
    """DB-05 FK ON DELETE SET NULL 兜底:绕过 API 直删卡行,子任务引用置空不 500。"""
    h = auth(member1_token)
    card, ids = _setup_card_with_subs(client, h, [(0, 1, 2)], "I")
    with TestSession() as db:
        db.query(models.Story).filter(models.Story.id == card).delete()
        db.commit()  # 无 IntegrityError 即 FK 生效
        t = db.get(models.Task, ids[0])
        assert t.kanban_card_id is None


def test_seed_lineage_complete(client):
    """DB-07 新库 seed_all 血缘完整:M21-23 存在,12 feature 挂 9 卡,4 管理任务。"""
    with TestSession() as db:
        # 自包含:先清场(播种前快照为空),测完恢复空场,不依赖执行顺序
        db.query(models.Task).delete()
        db.query(models.StoryLog).delete()
        db.query(models.Story).delete()
        db.commit()
        assert db.query(models.Story).count() == 0
        seed.seed_all(db)
        assert db.query(models.Story).count() == 23
        assert {s.id for s in db.query(models.Story)} >= {"M21", "M22", "M23"}
        tasks = {t.id: t for t in db.query(models.Task)}
        assert len(tasks) == 16
        expected = {"T03": "M02", "T04": "M04", "T05": "M08", "T06": "M15", "T07": "M11",
                    "T08": "M21", "T09": "M03", "T10": "M22", "T11": "M22",
                    "T12": "M23", "T13": "M23", "T14": "M21"}
        for tid, cid in expected.items():
            assert tasks[tid].kanban_card_id == cid and tasks[tid].task_type == "feature"
        for tid in ("T01", "T02", "T15", "T16"):
            assert tasks[tid].kanban_card_id is None and tasks[tid].task_type == "management"
        # DB-04 顺带断言:播种时 estimated_hours 与 hours 一致
        assert all(t.estimated_hours == t.hours for t in tasks.values())
        # 清理播种数据,避免污染同会话后续用例
        db.query(models.Task).delete()
        db.query(models.StoryLog).delete()
        db.query(models.Story).delete()
        db.commit()
