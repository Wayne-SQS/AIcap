"""缺陷回归用例:BUG-BE-01 / BUG-BE-02 / BUG-BE-03"""


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_status_out_of_range_rejected(client, member1_token):
    r = client.post("/api/stories", json={"title": "越界状态", "status": 5}, headers=auth(member1_token))
    assert r.status_code == 422


def test_sprint_out_of_range_rejected(client, member1_token):
    r = client.post("/api/stories", json={"title": "越界迭代", "sprint": 99}, headers=auth(member1_token))
    assert r.status_code == 422


def test_activity_out_of_range_rejected(client, member1_token):
    r = client.post("/api/stories", json={"title": "越界活动", "activity": 9}, headers=auth(member1_token))
    assert r.status_code == 422


def test_story_priority_enum_rejected(client, member1_token):
    r = client.post("/api/stories", json={"title": "坏优先级", "priority": "Wont"}, headers=auth(member1_token))
    assert r.status_code == 422


def test_pool_priority_enum_rejected(client, member1_token):
    r = client.post("/api/pool", json={"title": "坏池优先级", "priority": "Wont"}, headers=auth(member1_token))
    assert r.status_code == 422


def test_owner_not_exists_rejected(client, member1_token):
    r = client.post("/api/stories", json={"title": "坏负责人", "owner_id": 999}, headers=auth(member1_token))
    assert r.status_code == 400


def test_dashboard_total_equals_buckets(client, member1_token):
    d = client.get("/api/dashboard", headers=auth(member1_token)).json()
    assert d["total"] == d["done"] + d["doing"] + d["todo"]
