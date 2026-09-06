import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import config, models, security
from app.database import Base, get_db
from app.main import app

test_engine = create_engine(config.TEST_DATABASE_URL, pool_pre_ping=True)
TestSession = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


def seed_test_users(db):
    rows = [("成员1", "成员1", "admin", "green"), ("成员2", "成员2", "owner", "orange"),
            ("成员3", "成员3", "member", "blue"), ("成员4", "成员4", "member", "pink")]
    for username, display, role, color in rows:
        db.add(models.User(username=username, display_name=display, role=role, color=color,
                           password_hash=security.hash_password("123456")))
    db.commit()


@pytest.fixture(scope="session", autouse=True)
def db_setup():
    Base.metadata.drop_all(bind=test_engine)
    Base.metadata.create_all(bind=test_engine)
    s = TestSession()
    seed_test_users(s)
    s.close()


@pytest.fixture()
def client():
    def override():
        db = TestSession()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


@pytest.fixture()
def member1_token(client):
    r = client.post("/api/auth/login", json={"username": "成员1", "password": "123456"})
    assert r.status_code == 200
    return r.json()["access_token"]


@pytest.fixture()
def member3_token(client):
    r = client.post("/api/auth/login", json={"username": "成员3", "password": "123456"})
    assert r.status_code == 200
    return r.json()["access_token"]
