def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_dashboard_matches_stories(client, member1_token):
    h = auth(member1_token)
    client.post("/api/stories", json={"title": "仪表盘故事", "status": 2}, headers=h)
    d = client.get("/api/dashboard", headers=h).json()
    total = len(client.get("/api/stories", headers=h).json())
    assert d["total"] == total
    assert d["done"] >= 0
    assert isinstance(d["percent"], int)
    assert len(d["by_sprint"]) == 3
