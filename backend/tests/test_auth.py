def test_login_ok(client):
    r = client.post("/api/auth/login", json={"username": "成员1", "password": "123456"})
    assert r.status_code == 200
    data = r.json()
    assert data["token_type"] == "bearer"
    assert data["user"]["role"] == "admin"
    assert data["access_token"]


def test_login_bad_password(client):
    r = client.post("/api/auth/login", json={"username": "成员1", "password": "wrong"})
    assert r.status_code == 401


def test_me_requires_token(client):
    assert client.get("/api/auth/me").status_code == 401


def test_me_ok(client, member1_token):
    r = client.get("/api/auth/me", headers={"Authorization": f"Bearer {member1_token}"})
    assert r.status_code == 200
    assert r.json()["username"] == "成员1"
