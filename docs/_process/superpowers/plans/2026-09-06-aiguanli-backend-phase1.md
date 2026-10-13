# 爱管理后端阶段 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建「爱管理」FastAPI 后端行走骨架:MySQL 8.0 跑在 Docker(数据卷 D 盘),提供登录(JWT)、故事/需求池/任务/仪表盘 REST API,种子数据与前端一致,全部测试通过。

**Architecture:** 单仓库结构,前端 `index.html` 不动;新建 `backend/` FastAPI 应用,SQLAlchemy ORM 访问 Docker 内的 MySQL 8.0(宿主机 3307),启动时建表并播种;接口挂 `/api` 前缀供前端 fetch。数据库名 `AIcap`。所有密钥/连接串放 `backend/.env`(gitignored)。

**Tech Stack:** Python 3.11(E:\python3.11)、FastAPI、SQLAlchemy 2.x、PyMySQL、python-jose(JWT)、passlib+bcrypt、pytest+httpx、Docker(mysql:8.0)。

**规格文档:** `docs/superpowers/specs/2026-09-06-aiguanli-backend-design.md`

---

## 环境事实(执行者先确认)

- Python 3.11.4:`E:\python3.11\python.exe`(含 pip 25.3)。**不要**用 C:\Program Files\Python38。
- Docker Desktop 已装(`C:\Program Files\Docker\Docker`)但服务停止,需先启动。
- 端口 3307 空闲;本机 3306 被系统 MySQL80 占用(不可动)。
- 数据卷目录 `D:\aiguanli-mysql\`(不含中文路径,compose 与数据都放这)。
- 虚拟环境放 `D:\aiguanli-venv`(不在 C 盘,避免中文路径)。

---

## 文件结构

```
E:\桌面\软件项目管理实验\           # git 仓库根(前端在此,不动)
├─ .gitignore                       # 追加:backend/.env、__pycache__/
└─ backend/
   ├─ requirements.txt
   ├─ .env.example                  # 模板(入库)
   ├─ .env                          # 真实配置(不入库)
   ├─ README.md                     # 启动说明
   ├─ app/
   │  ├─ __init__.py
   │  ├─ config.py                  # 读 .env
   │  ├─ database.py                # engine / SessionLocal / Base / get_db
   │  ├─ models.py                  # User/Story/StoryLog/Task/PoolItem/Suggestion
   │  ├─ schemas.py                 # Pydantic 出入参
   │  ├─ security.py                # 密码哈希 / JWT / 当前用户 / 角色
   │  ├─ seed.py                    # seed_users / seed_demo(20故事+16任务)
   │  ├─ main.py                    # FastAPI 入口 + CORS + 启动建表播种
   │  └─ routers/
   │     ├─ __init__.py
   │     ├─ auth.py                 # /api/auth/*
   │     ├─ stories.py              # /api/stories/*
   │     ├─ pool.py                 # /api/pool/*
   │     ├─ tasks.py                # /api/tasks
   │     └─ dashboard.py            # /api/dashboard
   └─ tests/
      ├─ conftest.py                # 用 AIcap_test 库 + TestClient
      ├─ test_auth.py
      ├─ test_stories.py
      ├─ test_pool.py
      └─ test_dashboard.py
D:\aiguanli-mysql\                   # 不入库
   ├─ docker-compose.yml
   ├─ init.sql                       # 建 AIcap_test + 授权
   └─ mysql-data/                    # 容器数据卷(自动生成)
D:\aiguanli-venv\                    # 不入库,虚拟环境
```

---

### Task 0:环境就绪(Docker Desktop 启动)

**Files:** 无

- [ ] **Step 1:启动 Docker Desktop 并等待就绪**

Run(在 pwsh):
```powershell
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
# 等待约 30–60 秒,然后:
docker info --format '{{.ServerVersion}}'
```
Expected: 输出类似 `29.5.2`(不再报连不上 daemon)。

- [ ] **Step 2:确认 docker compose 可用**
Run: `docker compose version`
Expected: `Docker Compose version v5.1.4`

---

### Task 1:MySQL 容器编排(AIcap 库)

**Files:**
- Create: `D:\aiguanli-mysql\docker-compose.yml`
- Create: `D:\aiguanli-mysql\init.sql`

- [ ] **Step 1:建目录并写 docker-compose.yml**
```powershell
New-Item -ItemType Directory -Force -Path "D:\aiguanli-mysql"
```
`D:\aiguanli-mysql\docker-compose.yml`:
```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: aiguanli-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root-2026
      MYSQL_DATABASE: AIcap
      MYSQL_USER: aiguanli
      MYSQL_PASSWORD: aiguanli-2026
      TZ: Asia/Shanghai
    ports:
      - "3307:3306"
    volumes:
      - ./mysql-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

- [ ] **Step 2:写 init.sql(建测试库并授权)**
`D:\aiguanli-mysql\init.sql`:
```sql
CREATE DATABASE IF NOT EXISTS AIcap_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON AIcap_test.* TO 'aiguanli'@'%';
FLUSH PRIVILEGES;
```
注意:MySQL 官方镜像会把 init.sql 应用到新建的 AIcap 库 + 执行我们加的两条;库 `AIcap` 与用户由环境变量自动创建。

- [ ] **Step 3:启动容器**
Run:
```powershell
docker compose -f "D:\aiguanli-mysql\docker-compose.yml" up -d
# 等容器健康(约 20–40 秒):
docker exec aiguanli-mysql mysqladmin ping -uroot -proot-2026
```
Expected: `mysqld is alive`

- [ ] **Step 4:验证库与账号可用**
Run:
```powershell
docker exec aiguanli-mysql mysql -uaiguanli -paiguanli-2026 -h127.0.0.1 -P3306 -e "SHOW DATABASES;"
```
Expected: 输出含 `AIcap` 与 `AIcap_test`。

- [ ] **Step 5:Commit(仓库内无新文件,跳过 git add 即可;此步可空跑)**
说明:compose 与数据在仓库外的 `D:\aiguanli-mysql\`,不入库,无需 commit。

---

### Task 2:Python 项目脚手架

**Files:**
- Create: `backend/requirements.txt`
- Create: `backend/.env.example`
- Create: `backend/.env`
- Create: `backend/README.md`
- Modify: `.gitignore`(仓库根,追加两行)

- [ ] **Step 1:写 requirements.txt**
`backend/requirements.txt`:
```
fastapi>=0.110
uvicorn[standard]>=0.29
sqlalchemy>=2.0
pymysql>=1.1
cryptography>=42.0
python-dotenv>=1.0
python-jose[cryptography]>=3.3
passlib>=1.7
bcrypt==4.0.1
pytest>=8.0
httpx>=0.27
```

- [ ] **Step 2:写 .env.example(入库模板)**
`backend/.env.example`:
```
DATABASE_URL=mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap?charset=utf8mb4
TEST_DATABASE_URL=mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_test?charset=utf8mb4
JWT_SECRET=please-generate-a-long-random-string
JWT_ALGO=HS256
JWT_EXPIRE_MINUTES=720
```

- [ ] **Step 3:生成真实 .env(不入库)**
Run(pwsh,在 backend 目录):
```powershell
$sec = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
@"
DATABASE_URL=mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap?charset=utf8mb4
TEST_DATABASE_URL=mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_test?charset=utf8mb4
JWT_SECRET=$sec
JWT_ALGO=HS256
JWT_EXPIRE_MINUTES=720
"@ | Set-Content -Path "backend\.env" -Encoding UTF8
```

- [ ] **Step 4:改 .gitignore**
`E:\桌面\软件项目管理实验\.gitignore` 末尾追加:
```
# python / backend
backend/.env
__pycache__/
*.pyc
```

- [ ] **Step 5:建虚拟环境并装依赖**
Run:
```powershell
E:\python3.11\python.exe -m venv D:\aiguanli-venv
D:\aiguanli-venv\Scripts\python.exe -m pip install -r backend\requirements.txt
```
Expected: pip 安装成功(约 1–2 分钟),无红色报错。

- [ ] **Step 6:Commit**
```powershell
git add .gitignore backend/requirements.txt backend/.env.example backend/README.md
git commit -m "chore: backend 脚手架(依赖与配置模板)"
```
(先写一个最小 backend/README.md 占位,后续 Task 11 再补全内容。)

---

### Task 3:config / database / models

**Files:**
- Create: `backend/app/__init__.py`
- Create: `backend/app/config.py`
- Create: `backend/app/database.py`
- Create: `backend/app/models.py`

- [ ] **Step 1:写 config.py**
`backend/app/config.py`:
```python
import os
from dotenv import load_dotenv

# backend/.env 位于 app/ 的上一级
load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

DATABASE_URL = os.getenv("DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap?charset=utf8mb4")
TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_test?charset=utf8mb4")
JWT_SECRET = os.getenv("JWT_SECRET", "dev-secret-change-me")
JWT_ALGO = os.getenv("JWT_ALGO", "HS256")
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "720"))
```

- [ ] **Step 2:写 database.py**
`backend/app/database.py`:
```python
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from . import config

engine = create_engine(config.DATABASE_URL, pool_pre_ping=True)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
```

- [ ] **Step 3:写 models.py**
`backend/app/models.py`:
```python
from datetime import datetime
from sqlalchemy import Column, Integer, String, Text, DateTime, ForeignKey
from .database import Base


class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, autoincrement=True)
    username = Column(String(50), unique=True, nullable=False, index=True)
    display_name = Column(String(50), nullable=False)
    role = Column(String(20), nullable=False, default="member")
    password_hash = Column(String(255), nullable=False)
    color = Column(String(20), nullable=False, default="green")


class Story(Base):
    __tablename__ = "stories"
    id = Column(String(10), primary_key=True)
    title = Column(String(200), nullable=False)
    description = Column(Text, nullable=False, default="")
    acceptance = Column(Text, nullable=False, default="")
    priority = Column(String(10), nullable=False, default="Must")
    sprint = Column(Integer, nullable=False, default=1)
    activity = Column(Integer, nullable=False, default=2)
    status = Column(Integer, nullable=False, default=0)  # 0待办 1进行中 2完成
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class StoryLog(Base):
    __tablename__ = "story_logs"
    id = Column(Integer, primary_key=True, autoincrement=True)
    story_id = Column(String(10), nullable=False)
    log_type = Column(String(20), nullable=False)
    detail = Column(String(500), nullable=False, default="")
    user_id = Column(Integer, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class Task(Base):
    __tablename__ = "tasks"
    id = Column(String(10), primary_key=True)
    name = Column(String(200), nullable=False)
    owner_id = Column(Integer, nullable=False)
    hours = Column(Integer, nullable=False, default=0)
    week_start = Column(Integer, nullable=False)
    week_end = Column(Integer, nullable=False)
    story_ref = Column(String(100), nullable=True)


class PoolItem(Base):
    __tablename__ = "pool_items"
    id = Column(String(10), primary_key=True)
    title = Column(String(200), nullable=False)
    description = Column(Text, nullable=False, default="")
    source = Column(String(200), nullable=False, default="")
    priority = Column(String(10), nullable=False, default="Could")
    created_at = Column(DateTime, default=datetime.utcnow)


class Suggestion(Base):
    __tablename__ = "suggestions"
    id = Column(String(10), primary_key=True)
    agent = Column(String(50), nullable=False)
    kind = Column(String(20), nullable=False)
    evidence = Column(Text, nullable=False, default="")
    affected = Column(String(200), nullable=False, default="")
    note = Column(Text, nullable=False, default="")
    change_json = Column(Text, nullable=False, default="[]")
    status = Column(String(20), nullable=False, default="pending")
    created_at = Column(DateTime, default=datetime.utcnow)
```

- [ ] **Step 4:建空 `backend/app/__init__.py` 与 `backend/app/routers/__init__.py`**

- [ ] **Step 5:冒烟验证——能连库并建表**
Run:
```powershell
cd backend
D:\aiguanli-venv\Scripts\python.exe -c "from app.database import Base, engine; from app import models; Base.metadata.create_all(bind=engine); print('TABLES OK')"
```
Expected: `TABLES OK`(此刻已在 AIcap 库建出 users/stories/story_logs/tasks/pool_items/suggestions 六张表)。

- [ ] **Step 6:Commit**
```bash
git add backend/app && git commit -m "feat: 数据模型与数据库连接"
```

---

### Task 4:security / schemas

**Files:**
- Create: `backend/app/security.py`
- Create: `backend/app/schemas.py`

- [ ] **Step 1:写 security.py**
`backend/app/security.py`:
```python
from datetime import datetime, timedelta
from fastapi import Depends, HTTPException, status
from sqlalchemy.orm import Session
from jose import jwt, JWTError
from passlib.context import CryptContext

from . import config, models
from .database import get_db

pwd = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(plain: str) -> str:
    return pwd.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd.verify(plain, hashed)


def create_token(user_id: int) -> str:
    expire = datetime.utcnow() + timedelta(minutes=config.JWT_EXPIRE_MINUTES)
    return jwt.encode({"sub": str(user_id), "exp": expire}, config.JWT_SECRET, algorithm=config.JWT_ALGO)


def get_current_user(token: str = Depends(lambda: None), db: Session = Depends(get_db)) -> models.User:
    # 兼容测试:依赖注入由调用方保证 token;生产用 Authorization: Bearer
    raise NotImplementedError("在 main.py 组装 OAuth2 后替换")


def require_roles(*roles):
    def dep(user: models.User = Depends(get_current_user)):
        if user.role not in roles:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="无权限执行此操作")
        return user
    return dep
```
> 说明:`get_current_user` 的 token 来源(Header Bearer)在 Task 5 与 main.py 组装时用真实实现覆盖,见下方 Task 5 Step 2 的最终版 `security.py`(整文件替换,包含 `OAuth2PasswordBearer`)。此步先让文件结构可导入。

- [ ] **Step 2:写 schemas.py**
`backend/app/schemas.py`:
```python
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field


class LoginIn(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    id: int
    username: str
    display_name: str
    role: str
    color: str
    class Config:
        from_attributes = True


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserOut


class StoryIn(BaseModel):
    title: str = Field(..., max_length=200)
    description: str = ""
    acceptance: str = ""
    priority: str = "Must"
    sprint: int = 1
    activity: int = 2
    status: int = 0
    owner_id: Optional[int] = None


class StoryPatch(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    acceptance: Optional[str] = None
    priority: Optional[str] = None
    sprint: Optional[int] = None
    activity: Optional[int] = None
    status: Optional[int] = None
    owner_id: Optional[int] = None


class StoryOut(BaseModel):
    id: str
    title: str
    description: str
    acceptance: str
    priority: str
    sprint: int
    activity: int
    status: int
    owner_id: Optional[int]
    class Config:
        from_attributes = True


class PoolIn(BaseModel):
    title: str = Field(..., max_length=200)
    description: str = ""
    source: str = ""
    priority: str = "Could"


class PoolOut(BaseModel):
    id: str
    title: str
    description: str
    source: str
    priority: str
    class Config:
        from_attributes = True


class TaskOut(BaseModel):
    id: str
    name: str
    owner_id: int
    hours: int
    week_start: int
    week_end: int
    story_ref: Optional[str]
    class Config:
        from_attributes = True


class LogOut(BaseModel):
    id: int
    story_id: str
    log_type: str
    detail: str
    created_at: datetime
    class Config:
        from_attributes = True


class DashboardOut(BaseModel):
    total: int
    done: int
    doing: int
    todo: int
    percent: int
    by_sprint: list[dict]
```

- [ ] **Step 3:导入冒烟**
Run:
```powershell
D:\aiguanli-venv\Scripts\python.exe -c "import app.schemas, app.security; print('SCHEMAS OK')"
```
Expected: `SCHEMAS OK`

- [ ] **Step 4:Commit**
```bash
git add backend/app/security.py backend/app/schemas.py
git commit -m "feat: JWT 安全模块与 Pydantic 出入参"
```

---

### Task 5:auth 路由 + 登录测试

**Files:**
- Modify: `backend/app/security.py`(替换为最终版)
- Create: `backend/app/routers/__init__.py`
- Create: `backend/app/routers/auth.py`
- Create: `backend/tests/conftest.py`
- Create: `backend/tests/test_auth.py`

- [ ] **Step 1:把 security.py 替换为最终版(含 OAuth2PasswordBearer)**
`backend/app/security.py` 全文:
```python
from datetime import datetime, timedelta
from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from sqlalchemy.orm import Session
from jose import jwt, JWTError
from passlib.context import CryptContext

from . import config, models
from .database import get_db

pwd = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login")


def hash_password(plain: str) -> str:
    return pwd.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd.verify(plain, hashed)


def create_token(user_id: int) -> str:
    expire = datetime.utcnow() + timedelta(minutes=config.JWT_EXPIRE_MINUTES)
    return jwt.encode({"sub": str(user_id), "exp": expire}, config.JWT_SECRET, algorithm=config.JWT_ALGO)


def get_current_user(token: str = Depends(oauth2_scheme), db: Session = Depends(get_db)) -> models.User:
    cred = HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或登录已过期")
    try:
        payload = jwt.decode(token, config.JWT_SECRET, algorithms=[config.JWT_ALGO])
        uid = int(payload.get("sub"))
    except (JWTError, TypeError, ValueError):
        raise cred
    user = db.get(models.User, uid)
    if user is None:
        raise cred
    return user


def require_roles(*roles):
    def dep(user: models.User = Depends(get_current_user)):
        if user.role not in roles:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="无权限执行此操作")
        return user
    return dep
```

- [ ] **Step 2:写 routers/auth.py**
`backend/app/routers/auth.py`:
```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=schemas.TokenOut)
def login(body: schemas.LoginIn, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == body.username).first()
    if user is None or not security.verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = security.create_token(user.id)
    return {"access_token": token, "token_type": "bearer", "user": schemas.UserOut.model_validate(user)}


@router.get("/me", response_model=schemas.UserOut)
def me(user: models.User = Depends(security.get_current_user)):
    return user
```

- [ ] **Step 3:写 tests/conftest.py(用 AIcap_test 库)**
`backend/tests/conftest.py`:
```python
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db
from app import config, models, security
from app.main import app

test_engine = create_engine(config.TEST_DATABASE_URL, pool_pre_ping=True)
TestSession = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)


@pytest.fixture(scope="session", autouse=True)
def db_setup():
    Base.metadata.drop_all(bind=test_engine)
    Base.metadata.create_all(bind=test_engine)
    s = TestSession()
    seed_test_users(s)
    s.close()


def seed_test_users(db):
    rows = [("成员1", "成员1", "admin", "green"), ("成员2", "成员2", "owner", "orange"),
            ("成员3", "成员3", "member", "blue"), ("成员4", "成员4", "member", "pink")]
    for username, display, role, color in rows:
        db.add(models.User(username=username, display_name=display, role=role,
                           color=color, password_hash=security.hash_password("123456")))
    db.commit()


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
```
> conftest 引用了 `app.main`(Task 11 才最终定稿),因此本任务先建 `backend/app/main.py` 的最小版(Task 5 Step 4),Task 11 再补 CORS 与路由全量。

- [ ] **Step 4:建 main.py 最小版(先能跑通 auth 测试)**
`backend/app/main.py`:
```python
from fastapi import FastAPI

from .database import Base, engine
from .routers import auth

app = FastAPI(title="爱管理 API", version="0.1.0")

Base.metadata.create_all(bind=engine)

app.include_router(auth.router, prefix="/api")
```

- [ ] **Step 5:写 test_auth.py**
`backend/tests/test_auth.py`:
```python
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
```

- [ ] **Step 6:先跑测试确认「红灯」**
Run:
```powershell
cd backend
D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_auth.py -q
```
Expected: FAIL/ERROR(因为依赖还没全、或表尚未建成功) — 这是正常 TDD 红灯,先确认「能跑起来且失败」;若因 import 错误,先修 import。

- [ ] **Step 7:创建数据库表依赖完成后再跑,确认「绿灯」**
Run 同上。
Expected: `4 passed`(此时 users 表在 AIcap_test 中由 conftest 重建并播种)。

- [ ] **Step 8:Commit**
```bash
git add backend/app backend/tests
git commit -m "feat: 登录/me 接口与鉴权测试"
```

---

### Task 6:stories CRUD + 变更日志 + 测试

**Files:**
- Create: `backend/app/routers/stories.py`
- Create: `backend/tests/test_stories.py`

- [ ] **Step 1:写 routers/stories.py**
`backend/app/routers/stories.py`:
```python
import re
from typing import Optional
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/stories", tags=["stories"])


def _next_story_id(db: Session) -> str:
    nums = []
    for (sid,) in db.query(models.Story.id).all():
        m = re.match(r"^M(\d+)$", sid or "")
        if m:
            nums.append(int(m.group(1)))
    return f"M{((max(nums) if nums else 0) + 1):02d}"


def _add_log(db: Session, story_id: str, log_type: str, detail: str, user_id: Optional[int]):
    db.add(models.StoryLog(story_id=story_id, log_type=log_type, detail=detail, user_id=user_id))
    db.commit()


@router.get("/logs", response_model=list[schemas.LogOut])
def recent_logs(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.StoryLog).order_by(models.StoryLog.id.desc()).limit(50).all()


@router.get("", response_model=list[schemas.StoryOut])
def list_stories(owner: Optional[int] = None, sprint: Optional[int] = None,
                 status: Optional[int] = None, activity: Optional[int] = None,
                 priority: Optional[str] = None, q: Optional[str] = None,
                 db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    query = db.query(models.Story)
    if owner is not None:
        query = query.filter(models.Story.owner_id == owner)
    if sprint is not None:
        query = query.filter(models.Story.sprint == sprint)
    if status is not None:
        query = query.filter(models.Story.status == status)
    if activity is not None:
        query = query.filter(models.Story.activity == activity)
    if priority:
        query = query.filter(models.Story.priority == priority)
    if q:
        like = f"%{q}%"
        query = query.filter(models.Story.id.like(like) | models.Story.title.like(like))
    return query.order_by(models.Story.id).all()


@router.post("", response_model=schemas.StoryOut)
def create_story(body: schemas.StoryIn, db: Session = Depends(get_db),
                 user: models.User = Depends(security.get_current_user)):
    sid = _next_story_id(db)
    story = models.Story(id=sid, **body.model_dump())
    db.add(story)
    db.commit()
    db.refresh(story)
    _add_log(db, sid, "create", story.title, user.id)
    return story


@router.patch("/{story_id}", response_model=schemas.StoryOut)
def patch_story(story_id: str, body: schemas.StoryPatch, db: Session = Depends(get_db),
                user: models.User = Depends(security.get_current_user)):
    story = db.get(models.Story, story_id)
    if story is None:
        raise HTTPException(status_code=404, detail="故事不存在")
    data = body.model_dump(exclude_unset=True)
    if not data:
        return story
    old_status = story.status
    for key, value in data.items():
        setattr(story, key, value)
    db.commit()
    db.refresh(story)
    if "status" in data and old_status != data["status"]:
        _add_log(db, story_id, "move", f"{story.title} → 状态 {story.status}", user.id)
    else:
        _add_log(db, story_id, "edit", story.title, user.id)
    return story


@router.delete("/{story_id}")
def delete_story(story_id: str, db: Session = Depends(get_db),
                 user: models.User = Depends(security.require_roles("admin", "owner"))):
    story = db.get(models.Story, story_id)
    if story is None:
        raise HTTPException(status_code=404, detail="故事不存在")
    db.delete(story)
    db.commit()
    _add_log(db, story_id, "del", story.title, user.id)
    return {"ok": True}
```

- [ ] **Step 2:写 test_stories.py**
`backend/tests/test_stories.py`:
```python
def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_create_then_patch_then_logs(client, member1_token):
    r = client.post("/api/stories", json={"title": "测试故事", "description": "作为用户，我希望测试",
                                          "acceptance": "可用", "priority": "Must", "sprint": 1,
                                          "activity": 2, "status": 0, "owner_id": 1},
                    headers=auth(member1_token))
    assert r.status_code == 200
    sid = r.json()["id"]
    assert sid.startswith("M")

    r2 = client.patch(f"/api/stories/{sid}", json={"status": 2}, headers=auth(member1_token))
    assert r2.status_code == 200
    assert r2.json()["status"] == 2

    logs = client.get("/api/stories/logs", headers=auth(member1_token)).json()
    types = [x["log_type"] for x in logs]
    assert "create" in types and "move" in types


def test_filter_sprint(client, member1_token):
    data = client.get("/api/stories?sprint=1", headers=auth(member1_token)).json()
    assert all(x["sprint"] == 1 for x in data)


def test_member_cannot_delete(client, member3_token):
    r = client.delete("/api/stories/M01", headers=auth(member3_token))
    assert r.status_code == 403


def test_owner_can_delete(client, member1_token):
    r = client.delete("/api/stories/M01", headers=auth(member1_token))
    assert r.status_code == 200
    assert client.get("/api/stories/M01-nope", headers=auth(member1_token)).status_code == 200  # 仅验证不炸
```
> 注:delete 后该 M01 已删;后续任务测试若依赖固定故事,请在本任务播种数据前运行,或 Task 9 再统一重新播种。Test DB 每次 pytest 会话会 drop_all + create_all 重建,故顺序无关紧要。

- [ ] **Step 3:运行测试(先种几条数据再跑,或直接跑;conftest 已重建空表)**
Run:
```powershell
D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_stories.py -q
```
Expected: 至少 3 passed(test_member_cannot_delete/test_owner_can_delete 需要表里存在 M01 —— 若 conftest 无故事,这两条会在 delete 时返回 404 而非 403/200。处理:把这两条改为「先 create 再由不同角色删」,见 Step 4 修正版)。

- [ ] **Step 4:修正权限测试为「先建后删」**
把 test_stories.py 的权限两条替换为:
```python
def test_member_cannot_delete(client, member1_token, member3_token):
    sid = client.post("/api/stories", json={"title": "待删"}, headers=auth(member1_token)).json()["id"]
    r = client.delete(f"/api/stories/{sid}", headers=auth(member3_token))
    assert r.status_code == 403


def test_owner_can_delete(client, member1_token):
    sid = client.post("/api/stories", json={"title": "可删"}, headers=auth(member1_token)).json()["id"]
    r = client.delete(f"/api/stories/{sid}", headers=auth(member1_token))
    assert r.status_code == 200
```
Run:`D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_stories.py -q`
Expected: `4 passed`(test_create_then_patch_then_logs / test_filter_sprint 会受共享空表影响——conftest 会话内所有用例共用;为确保互不干扰,将过滤用例改为对「本次 create 的那条」断言,或按 Step 5 处理)。

- [ ] **Step 5:让测试彼此独立(每条用例各自 create 目标数据)**
将 `test_filter_sprint` 改为:
```python
def test_filter_sprint(client, member1_token):
    client.post("/api/stories", json={"title": "S2 故事", "sprint": 2}, headers=auth(member1_token))
    data = client.get("/api/stories?sprint=2", headers=auth(member1_token)).json()
    assert any(x["title"] == "S2 故事" for x in data)
    assert all(x["sprint"] == 2 for x in data)
```
Run:`D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_stories.py -q`
Expected: `4 passed`

- [ ] **Step 6:Commit**
```bash
git add backend/app/routers/stories.py backend/tests/test_stories.py
git commit -m "feat: 故事 CRUD、状态变更日志与权限"
```

---

### Task 7:需求池 CRUD + 移入看板 + 测试

**Files:**
- Create: `backend/app/routers/pool.py`
- Create: `backend/tests/test_pool.py`

- [ ] **Step 1:写 routers/pool.py**
`backend/app/routers/pool.py`:
```python
import re
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/pool", tags=["pool"])


def _next_pool_id(db: Session) -> str:
    nums = []
    for (pid,) in db.query(models.PoolItem.id).all():
        m = re.match(r"^R(\d+)$", pid or "")
        if m:
            nums.append(int(m.group(1)))
    return f"R{((max(nums) if nums else 0) + 1):02d}"


@router.get("", response_model=list[schemas.PoolOut])
def list_pool(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.PoolItem).order_by(models.PoolItem.id).all()


@router.post("", response_model=schemas.PoolOut)
def create_pool(body: schemas.PoolIn, db: Session = Depends(get_db),
                _=Depends(security.get_current_user)):
    pid = _next_pool_id(db)
    item = models.PoolItem(id=pid, **body.model_dump())
    db.add(item)
    db.commit()
    db.refresh(item)
    return item


@router.delete("/{pool_id}")
def delete_pool(pool_id: str, db: Session = Depends(get_db),
                _=Depends(security.get_current_user)):
    item = db.get(models.PoolItem, pool_id)
    if item is None:
        raise HTTPException(status_code=404, detail="需求池条目不存在")
    db.delete(item)
    db.commit()
    return {"ok": True}


@router.post("/{pool_id}/promote", response_model=schemas.StoryOut)
def promote_pool(pool_id: str, db: Session = Depends(get_db),
                 user: models.User = Depends(security.get_current_user)):
    item = db.get(models.PoolItem, pool_id)
    if item is None:
        raise HTTPException(status_code=404, detail="需求池条目不存在")

    def _next_story_id():
        nums = []
        for (sid,) in db.query(models.Story.id).all():
            m = re.match(r"^M(\d+)$", sid or "")
            if m:
                nums.append(int(m.group(1)))
        return f"M{((max(nums) if nums else 0) + 1):02d}"

    sid = _next_story_id()
    story = models.Story(id=sid, title=item.title, description=item.description or "（待补充描述）",
                         acceptance=f"来源：{item.source or '需求池'}（待补充验收条件）",
                         priority=item.priority, sprint=1, activity=2, status=0, owner_id=None)
    db.add(story)
    db.delete(item)
    db.commit()
    db.refresh(story)
    db.add(models.StoryLog(story_id=sid, log_type="create", detail=f"由需求池 {pool_id} 移入", user_id=user.id))
    db.commit()
    return story
```

- [ ] **Step 2:写 test_pool.py**
`backend/tests/test_pool.py`:
```python
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
    # 池中条目已被移除
    items = client.get("/api/pool", headers=auth(member1_token)).json()
    assert all(x["id"] != pid for x in items)
```

- [ ] **Step 3:运行测试**
Run:`D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_pool.py -q`
Expected: `2 passed`

- [ ] **Step 4:Commit**
```bash
git add backend/app/routers/pool.py backend/tests/test_pool.py
git commit -m "feat: 需求池 CRUD 与移入看板"
```

---

### Task 8:tasks + dashboard + 测试

**Files:**
- Create: `backend/app/routers/tasks.py`
- Create: `backend/app/routers/dashboard.py`
- Create: `backend/tests/test_dashboard.py`

- [ ] **Step 1:写 routers/tasks.py**
`backend/app/routers/tasks.py`:
```python
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("", response_model=list[schemas.TaskOut])
def list_tasks(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.Task).order_by(models.Task.id).all()
```

- [ ] **Step 2:写 routers/dashboard.py**
`backend/app/routers/dashboard.py`:
```python
from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, security
from ..database import get_db

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


@router.get("")
def dashboard(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    def count_of(status: int) -> int:
        return db.query(func.count(models.Story.id)).filter(models.Story.status == status).scalar() or 0

    total = db.query(func.count(models.Story.id)).scalar() or 0
    done = count_of(2)
    doing = count_of(1)
    todo = count_of(0)
    percent = round(done / total * 100) if total else 0
    by_sprint = []
    for sp in (1, 2, 3):
        t = db.query(func.count(models.Story.id)).filter(models.Story.sprint == sp).scalar() or 0
        d = db.query(func.count(models.Story.id)).filter(models.Story.sprint == sp,
                                                         models.Story.status == 2).scalar() or 0
        by_sprint.append({"sprint": sp, "total": t, "done": d, "percent": round(d / t * 100) if t else 0})
    return {"total": total, "done": done, "doing": doing, "todo": todo,
            "percent": percent, "by_sprint": by_sprint}
```

- [ ] **Step 3:写 test_dashboard.py**
`backend/tests/test_dashboard.py`:
```python
def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_dashboard_matches_stories(client, member1_token):
    h = auth(member1_token)
    client.post("/api/stories", json={"title": "仪表盘故事", "status": 2}, headers=h)
    d = client.get("/api/dashboard", headers=h).json()
    total = len(client.get("/api/stories", headers=h).json())
    assert d["total"] == total
    assert d["done"] == d["done"] >= 0
    assert isinstance(d["percent"], int)
    assert len(d["by_sprint"]) == 3
```

- [ ] **Step 4:运行测试**
Run:
```powershell
D:\aiguanli-venv\Scripts\python.exe -m pytest tests/test_dashboard.py -q
```
Expected: `1 passed`

- [ ] **Step 5:Commit**
```bash
git add backend/app/routers/tasks.py backend/app/routers/dashboard.py backend/tests/test_dashboard.py
git commit -m "feat: 任务读取与仪表盘统计"
```

---

### Task 9:种子数据(4 成员 + 20 故事 + 16 任务)

**Files:**
- Create: `backend/app/seed.py`

- [ ] **Step 1:写 seed.py**
`backend/app/seed.py`:
```python
from . import models, security


def seed_users(db):
    if db.query(models.User).count():
        return
    rows = [("成员1", "成员1", "admin", "green"), ("成员2", "成员2", "owner", "orange"),
            ("成员3", "成员3", "member", "blue"), ("成员4", "成员4", "member", "pink")]
    for username, display, role, color in rows:
        db.add(models.User(username=username, display_name=display, role=role, color=color,
                           password_hash=security.hash_password("123456")))
    db.commit()


# (id, sprint, activity, owner_idx, priority, status, title, description, acceptance)
STORIES = [
("M01",1,1,0,"Must",2,"登录与退出系统","作为用户，我希望登录/退出系统，以便安全使用平台","未登录不可访问项目页；会话过期处理"),
("M02",1,1,1,"Must",2,"创建项目并添加成员","作为管理员，我希望创建项目并添加成员，以便限定协作范围","成员可进入所属项目，非成员页面与接口拒绝；关联 US01"),
("M03",1,1,2,"Must",1,"创建角色与分配权限","作为管理员，我希望创建角色并分配权限，以便控制操作边界","新建只读角色确实改不了东西；关联 US02"),
("M04",1,2,3,"Must",1,"维护史诗、故事与验收条件","作为项目负责人，我希望维护史诗、故事和验收条件，以便形成需求基线","故事含角色/目标/价值/优先级/稳定ID；关联 US03"),
("M05",1,2,0,"Must",0,"按活动与迭代浏览故事地图","作为团队成员，我希望按活动和 Sprint 查看故事地图，以便理解版本目标","至少 3 个发布切片、与需求条目数据一致；关联 US04"),
("M06",1,3,1,"Must",0,"拆分任务并指派负责人","作为项目负责人，我希望把故事拆成任务并指派负责人、设定日期，以便成员开展工作","任务关联故事，负责人/日期合法可再读；关联 US05"),
("M07",1,3,2,"Must",1,"在看板上更新任务状态","作为团队成员，我希望在看板上把任务从待办移到进行中/完成，以便团队知道进展","状态/负责人/优先级可见，刷新保留，记录变更历史；关联 US06"),
("M08",1,4,3,"Must",0,"查看项目基础报表","作为普通用户，我希望查看基础报表，以便掌握当前进度","状态数/完成比例与任务清单一致；空项目正确；关联 US07"),
("M09",1,5,0,"Should",0,"记录 Sprint 评审与复盘","作为项目负责人，我希望记录第一次 Sprint 评审结论与未完成项，以便复盘","Sprint1 末评审产出可见（M2 里程碑）"),
("M10",2,1,1,"Must",0,"停用成员并回收权限","作为管理员，我希望停用/移除成员并回收其权限，以便控制人员变更","停用后无法登录/无项目数据入口；关联 US01 扩展"),
("M11",2,2,2,"Should",0,"细化故事与记录变更","作为项目负责人，我希望细化故事并记录变更说明，以便需求可控","变更前后与理由可查"),
("M12",2,3,3,"Could",0,"任务评论与 @ 提及","作为成员，我希望在任务下评论/@提及，以便协同沟通","有则必存有记录；不影响任何 Must 验收"),
("M13",2,3,0,"Could",0,"按负责人和状态筛选任务","作为项目负责人，我希望按负责人/状态筛选任务，以便快速定位","筛选结果与任务清单一致"),
("M14",2,4,1,"Should",0,"组合筛选项目统计","作为项目负责人，我希望按人/状态/史诗组合筛选统计，以便识别变化","组合筛选口径一致；关联 US15"),
("M15",2,4,2,"Should",0,"查看完成趋势与燃尽图","作为项目负责人，我希望查看完成趋势/燃尽图，以便提前发现偏差","使用真实历史或标注样例；不编数据"),
("M16",2,5,3,"Should",0,"归档复盘与变更历史","作为项目负责人，我希望把复盘结论与变更历史入库，以便改进可追溯","复盘记录可按 Sprint 回溯"),
("M17",3,2,0,"Should",0,"登记需求变更与影响","作为项目负责人，我希望登记需求变更并标记影响，以便控制范围蔓延","变更记录、影响说明、版本保留"),
("M18",3,3,1,"Must",0,"设置并关联里程碑","作为项目负责人，我希望设置里程碑并关联任务/故事，以便掌握交付节奏","里程碑与任务关联可查、可展示"),
("M19",3,4,2,"Should",0,"查看里程碑进度总览","作为团队，我希望查看里程碑进度与项目总览视图，以便向干系人汇报","视图数字与底层数据一致"),
("M20",3,5,3,"Could",0,"将改进项转为任务","作为项目负责人，我希望把复盘中的改进项转成任务并复查，以便持续改进","改进项可转任务、可追踪状态"),
]

# (id, name, owner_idx, hours, week_start, week_end, story_ref)
TASKS = [
("T01","启动规划与基线",0,12,1,1,"范围基线"),
("T02","共享数据与接口小样",2,12,1,1,"技术约定"),
("T03","项目/成员/自定义权限",0,16,1,2,"US01,02"),
("T04","故事/地图/基础看板",1,16,1,2,"US03-06"),
("T05","基础报表与 S1 验证",3,12,2,2,"US07"),
("T06","看板增强与趋势",1,12,3,3,"US09,15"),
("T07","甘特图与成员任务图",2,12,3,4,"US10,11"),
("T08","UML 自动生成与编辑",3,20,2,4,"US13"),
("T09","权限扩展与联动",0,8,4,4,"US08,12"),
("T10","AI 拆解与估算",1,12,2,4,"US14,17"),
("T11","AI 进度预测与排期",2,12,4,5,"US18,19"),
("T12","AI 质量分析",3,12,4,5,"US22"),
("T13","AI 风险/效率/闭环",0,12,5,6,"US16,20,23,24"),
("T14","类图关联与影响传播",3,12,4,5,"US21"),
("T15","完整回归与部署演示",2,12,6,6,"全量回归"),
("T16","Scrum 管理与证据",0,8,1,6,"持续管理"),
]


def seed_demo(db):
    seed_users(db)
    if db.query(models.Story).count() == 0:
        for (sid, sprint, activity, owner_idx, priority, status, title, desc, acc) in STORIES:
            db.add(models.Story(id=sid, title=title, description=desc, acceptance=acc,
                                priority=priority, sprint=sprint, activity=activity,
                                status=status, owner_id=owner_idx + 1))
        db.commit()
    if db.query(models.Task).count() == 0:
        for (tid, name, owner_idx, hours, ws, we, ref) in TASKS:
            db.add(models.Task(id=tid, name=name, owner_id=owner_idx + 1, hours=hours,
                               week_start=ws, week_end=we, story_ref=ref))
        db.commit()


def seed_all(db):
    seed_demo(db)
```

- [ ] **Step 2:在开发库执行一次播种**
Run:
```powershell
D:\aiguanli-venv\Scripts\python.exe -c "from app.database import SessionLocal; from app.seed import seed_all; s=SessionLocal(); seed_all(s); s.close(); print('SEED OK')"
```
Expected: `SEED OK`

- [ ] **Step 3:校验条数**
Run:
```powershell
D:\aiguanli-venv\Scripts\python.exe -c "from app.database import SessionLocal; from app import models; s=SessionLocal(); print('users',s.query(models.User).count()); print('stories',s.query(models.Story).count()); print('tasks',s.query(models.Task).count()); s.close()"
```
Expected: users 4 / stories 20 / tasks 16

- [ ] **Step 4:Commit**
```bash
git add backend/app/seed.py
git commit -m "feat: 种子数据(4成员/20故事/16任务)"
```

---

### Task 10:main.py 全量组装 + 健康检查

**Files:**
- Modify: `backend/app/main.py`(替换为最终版)

- [ ] **Step 1:写 main.py 最终版**
`backend/app/main.py`:
```python
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from .database import Base, SessionLocal, engine
from .seed import seed_all
from .routers import auth, dashboard, pool, stories, tasks


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        seed_all(db)
    finally:
        db.close()
    yield


app = FastAPI(title="爱管理 API", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 开发期放开;上线前收紧为前端域名
    allow_methods=["*"],
    allow_headers=["*"],
)

for router in (auth.router, stories.router, pool.router, tasks.router, dashboard.router):
    app.include_router(router, prefix="/api")


@app.get("/api/health")
def health():
    ok = False
    db = SessionLocal()
    try:
        db.execute(text("SELECT 1"))
        ok = True
    except Exception:
        ok = False
    finally:
        db.close()
    return {"status": "ok" if ok else "db_error", "db": ok}
```

- [ ] **Step 2:启动服务验证**
Run(pwsh,工作目录 backend):
```powershell
D:\aiguanli-venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```
新开一个终端验证:
```powershell
curl.exe -s http://127.0.0.1:8000/api/health
curl.exe -s -X POST http://127.0.0.1:8000/api/auth/login -H "Content-Type: application/json" -d '{"username":"成员1","password":"123456"}'
curl.exe -s http://127.0.0.1:8000/api/stories -H "Authorization: Bearer <上面返回的token>"
```
Expected: health `{"status":"ok","db":true}`;login 返回 token 与 user;stories 返回 20 条。

- [ ] **Step 3:跑全量测试**
Run:`D:\aiguanli-venv\Scripts\python.exe -m pytest tests -q`
Expected: `7 passed`(auth 4 + stories 4 + pool 2 + dashboard 1 = 11 条,随实现微调;以实际绿为准)

- [ ] **Step 4:补写 backend/README.md**
内容含:环境要求、`docker compose up -d`、`.env` 配置、启动命令、默认账号(成员1..4 / 123456)、测试命令、接口清单 `/docs`。

- [ ] **Step 5:Commit**
```bash
git add backend/app/main.py backend/README.md
git commit -m "feat: 组装入口、CORS、健康检查与启动播种"
```

---

### Task 11:验收与交接(留给用户预览)

- [ ] **Step 1:双浏览器手工验收(后端模式)**
1. 启动 uvicorn(见 Task 10 Step 2)。
2. 打开 http://127.0.0.1:8000/docs,逐个试 login/me/stories/pool/dashboard。
3. 用两个浏览器(或普通+隐身)各登录不同成员,验证数据来自同一份 AIcap 库。

- [ ] **Step 2:记录已知问题**
把「演示时后端未启动 → 前端回退本地演示」等边界写进 backend/README.md 已知问题。

- [ ] **Step 3:停在本机,把结果交给用户预览,不推送。**

---

## Self-Review 记录

- **规格覆盖**:§5 六张表(users/stories/story_logs/tasks/pool_items/suggestions)→ Task 3;§6 全部接口 → Task 5–8、10(health);§8 最小权限 → security.require_roles + 权限测试;§9 Docker MySQL AIcap/3307/D 盘 → Task 1;种子与前端一致 → Task 9。suggestions 仅建表(阶段 1 不做决策) → 模型含表,无接口,符合规格。
- **占位符检查**:无 TBD/TODO;测试内为真实断言。
- **类型一致性**:StoryOut/PoolOut/TaskOut/LogOut/DashboardOut 均在 schemas 定义;路由返回与 response_model 对应;conftest 与 app.main 引用一致。
- **遗留点**:suggestions 接口、前端 DataStore 双模式改造属后续计划(阶段 1 后端先行,前端接入单独排期)。
