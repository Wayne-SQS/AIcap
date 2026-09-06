def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_pool_crud(client, member1_token):
    r = client.post("/api/pool", json={"title": "支持 PDF 导出", "description": "演示",
                                       "source": "会议", "priority": "Should"},
                    headers=auth(member1_token))
    assert r.status_code == 200
    pid = r.json()["id"]
    assert pid.startswith("R")

    items = client.get("/api/pool", headers=auth(member1_token)).json()
    assert any(x["id"] == pid for x in items)

    assert client.delete(f"/api/pool/{pid}", headers=auth(member1_token)).status_code == 200


def test_pool_promote_creates_story(client, member1_token):
    pid = client.post("/api/pool", json={"title": "第三方登录", "source": "会议 09-06"},
                      headers=auth(member1_token)).json()["id"]
    r = client.post(f"/api/pool/{pid}/promote", headers=auth(member1_token))
    assert r.status_code == 200
    story = r.json()
    assert story["sprint"] == 1 and story["status"] == 0
    items = client.get("/api/pool", headers=auth(member1_token)).json()
    assert all(x["id"] != pid for x in items)
