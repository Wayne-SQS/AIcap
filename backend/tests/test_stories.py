def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_create_then_patch_then_logs(client, member1_token):
    r = client.post("/api/stories", json={"title": "测试故事", "description": "作为用户，我希望测试",
                                          "acceptance": "可用", "priority": "Must", "sprint": 1,
                                          "activity": 2, "status": 0, "owner_id": 1},
                    headers=auth(member1_token))
    assert r.status_code == 200
    sid = r.json()["id"]
    assert sid.startswith("US")

    r2 = client.patch(f"/api/stories/{sid}", json={"status": 2}, headers=auth(member1_token))
    assert r2.status_code == 200
    assert r2.json()["status"] == 2

    logs = client.get("/api/stories/logs", headers=auth(member1_token)).json()
    types = [x["log_type"] for x in logs]
    assert "create" in types and "move" in types


def test_filter_sprint(client, member1_token):
    client.post("/api/stories", json={"title": "S2 故事", "sprint": 2}, headers=auth(member1_token))
    data = client.get("/api/stories?sprint=2", headers=auth(member1_token)).json()
    assert any(x["title"] == "S2 故事" for x in data)
    assert all(x["sprint"] == 2 for x in data)


def test_create_and_filter_sprint_four(client, member1_token):
    created = client.post(
        "/api/stories",
        json={"title": "Sprint 4 plus story", "sprint": 4, "activity": 5},
        headers=auth(member1_token),
    )
    assert created.status_code == 200
    assert created.json()["sprint"] == 4

    data = client.get("/api/stories?sprint=4", headers=auth(member1_token)).json()
    assert any(x["id"] == created.json()["id"] for x in data)
    assert all(x["sprint"] == 4 for x in data)


def test_member_cannot_delete(client, member1_token, member3_token):
    sid = client.post("/api/stories", json={"title": "待删"}, headers=auth(member1_token)).json()["id"]
    r = client.delete(f"/api/stories/{sid}", headers=auth(member3_token))
    assert r.status_code == 403


def test_owner_can_delete(client, member1_token):
    sid = client.post("/api/stories", json={"title": "可删"}, headers=auth(member1_token)).json()["id"]
    r = client.delete(f"/api/stories/{sid}", headers=auth(member1_token))
    assert r.status_code == 200
