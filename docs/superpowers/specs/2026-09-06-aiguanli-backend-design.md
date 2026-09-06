# 爱管理 · 后端设计规格(阶段 1 行走骨架)

> 日期:2026-09-06 · 状态:待团队审阅
> 依据:前端 `index.html`(像素风 8 视图原型)、《爱管理_需求基线与AI智能体需求交接_v2.0.md》、《爱管理_依据实验指导书的详细规划_v1(1).md》

## 1. 背景与目标

前端目前把全部数据存在浏览器 `localStorage`,4 人各自浏览器互相看不见改动,AI 密钥也无法安全存放。本设计为「爱管理」增加后端,使产品从"单机演示原型"升级为"可多人在线协作"的真实应用,同时保留现有的像素风 UI 不重写。

目标(阶段 1 内):
1. 一套共享数据库,替代 localStorage 承载故事、任务、需求池、进度统计。
2. 最小登录与权限(管理员/负责人/成员),对应 US01/US02 最小可运行版。
3. 前端通过 HTTP API 读写数据,UI 与像素风格保持不变。
4. 全流程可在本机(免费)运行;架构上保证日后可无痛迁移到"租服务器 + PostgreSQL + 多人访问"。

## 2. 范围

### 阶段 1 做(本次行走骨架)
- FastAPI 服务 + SQLAlchemy + SQLite
- 数据模型:用户/成员、故事、任务、需求池、变更日志、AI 建议(仅建表与查询,决策动作留待阶段 2 接通)
- 登录 + JWT + 最小权限校验
- 种子数据:4 名成员、20 条故事(M01–M20,与前端 seed 一致)、16 个任务(T01–T16)
- API:故事 CRUD / 状态拖拽更新 / 需求池 CRUD / 移入看板 / 任务读取 / 进度统计
- 前端接入:新增数据层,后端可用时走 API,不可用时回退 localStorage(静态演示仍能独立跑)

### 阶段 1 不做(明确排除,后续阶段做)
- AI 六项能力的真实调用(阶段 2–3)
- 会议智能体录音/转写(阶段 2)
- 任务提交智能体 + GitHub 事件同步(阶段 3)
- 实时 WebSocket 推送(先轮询;阶段 4 前不引入)
- 文件上传、复杂 RBAC、多项目/多租户

## 3. 架构总览

```
[像素风前端 index.html]   ←── 改造:数据层 DataStore(API 可达走 API,否则 localStorage)
        │ fetch /api (JWT)
        ▼
  FastAPI (backend/app)   ←── CORS 允许本机与 GitHub Pages 源
   ├─ auth.py  登录/校验/角色
   ├─ stories.py / pool.py / tasks.py / dashboard.py
   ▼
 SQLAlchemy → SQLite(本地) → 以后换 PostgreSQL(只改 .env 一行)
```

- 前端目录保持不动;后端独立为 `backend/` 文件夹。
- 前后端通过 REST + JSON 通信;密钥只在服务端使用(阶段 2 接入 AI 时)。

## 4. 目录结构

```
backend/
├─ app/
│  ├─ main.py            # FastAPI 入口、CORS、路由挂载
│  ├─ database.py        # SQLAlchemy engine/Session(读取 .env 的 DATABASE_URL)
│  ├─ models.py          # ORM 模型
│  ├─ schemas.py         # Pydantic 出入参
│  ├─ security.py        # JWT 签发/校验、密码哈希、依赖注入
│  ├─ seed.py            # 建库后播种 4 成员/20 故事/16 任务
│  └─ routers/
│     ├─ auth.py         # POST /api/auth/login, GET /api/me
│     ├─ stories.py      # 故事 CRUD + 状态更新 + 变更日志
│     ├─ pool.py         # 需求池 CRUD + 移入看板
│     ├─ tasks.py        # 任务读取(甘特/成员图用)
│     └─ dashboard.py    # 进度统计(实时进度面板用)
├─ requirements.txt
├─ .env.example          # 模板(含 DATABASE_URL、JWT_SECRET);.env 本身进 .gitignore
└─ README.md             # 怎么起、怎么演示
```

## 5. 数据模型(阶段 1)

| 表 | 关键字段 | 说明 |
|---|---|---|
| users | id, username, display_name, role, password_hash, color | 预置 4 名成员;role ∈ admin/owner/member/viewer |
| stories | id(PK 字符串如 M21), title, description, acceptance, priority(Must/Should/Could), sprint(1–3), activity(1–5), status(0待办/1进行中/2完成), owner_id(FK) | 对应前端 20 条故事结构 |
| story_logs | id, story_id, type, detail, user_id, created_at | 拖拽/编辑变更历史(对应前端变更记录) |
| tasks | id(T01…), name, owner_id, hours, week_start, week_end, story_ref | 甘特图与成员负载数据 |
| pool_items | id(R01…), title, description, source, created_at, priority, status | 需求池;Sprint/负责人允许为空 |
| suggestions | id(SG01…), agent, kind, evidence, affected, note, change_json, status(pending/approved/rejected/modified), created_at | AI 建议审核中心(阶段 1 建表+查询,阶段 2 接决策) |

- 阶段 1 为单项目(爱管理开发小队),暂不引入 project 表;上多项目时再拆。

## 6. API 一览(阶段 1)

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /api/auth/login | 登录返回 JWT | 公开 |
| GET | /api/me | 当前用户信息 | 登录 |
| GET | /api/stories | 故事列表(支持 sprint/owner/status/search 筛选) | 登录 |
| POST | /api/stories | 新建故事(自动分配 ID M21+、写日志) | 登录 |
| PATCH | /api/stories/{id} | 改标题/状态/负责人等,写日志 | 登录 |
| DELETE | /api/stories/{id} | 删除 | 负责人/管理员 |
| GET | /api/pool | 需求池列表 | 登录 |
| POST | /api/pool | 新增需求池条目 | 登录 |
| DELETE | /api/pool/{id} | 移除 | 登录 |
| POST | /api/pool/{id}/promote | 移入看板成为故事(保留来源) | 登录 |
| GET | /api/tasks | 任务列表(甘特/成员图/负载) | 登录 |
| GET | /api/dashboard | 进度统计(完成比例/进行中/各 Sprint) | 登录 |

错误约定:统一 `{"detail": "..."}`;401 未登录/无权限 403。

## 7. 前端改造策略(重要)

原则:**视觉与既有 8 视图零改动,只换"数据从哪来"。**

- 在 `index.html` 新增一个 `DataStore`:启动时探测 `API_BASE`(默认 `http://localhost:8000`)的 `/api/health`。
  - 可达 → 之后读写走 fetch + JWT(localStorage 只存 token)。
  - 不可达 → 沿用现 localStorage 演示逻辑(静态站/无后端时仍可独立演示)。
- `API_BASE` 为文件顶部一个常量,日后上线改一处即可。
- 登录态:进入页面若未登录且 API 可达,显示一个简易像素风登录条(预置 4 账号),登录后解锁写操作。
- 阶段 1 先接:故事看板 + 需求池 + 甘特/成员图数据来源(改为从 API 读取),其余视图(含 AI 助手演示)保持现状。

## 8. 认证与权限(最小版)

- JWT(HS256),密钥放 `.env` 的 `JWT_SECRET`。
- 角色→权限映射(最小):delete 故事/成员管理仅 admin/owner;其余登录即可读写本项目的公开对象。
- 密码用 bcrypt;演示账号密码统一 `123456`(README 注明仅演示用)。
- 演示账号:成员1(admin)/成员2(owner)/成员3(member)/成员4(member)。

## 9. 配置与密钥

- `.env`(不进 git):`DATABASE_URL=sqlite:///./aiguanli.db`、`JWT_SECRET=<随机>`。
- `.env.example` 提交进仓库作模板。
- 上线时 `DATABASE_URL` 换成 `postgresql+psycopg://…`,其余代码不变。

## 10. 可迁移性设计(为什么"单机→线上"无痛)

1. 所有数据库访问经 SQLAlchemy ORM,不写原生 SQLite 语法 → 换库只改连接串。
2. 配置全部读 `.env` / 环境变量,无硬编码路径。
3. 前端 API 地址集中在 `API_BASE` 一处。
4. uvicorn 启动方式本地与服务器完全一致。

## 11. 测试策略

- pytest + FastAPI TestClient(SQLite 临时库)。
- 覆盖:登录/鉴权、故事 CRUD 与状态变更日志、需求池移入看板生成正确 M 编号、dashboard 统计口径与数据一致。
- 验收后手动走一遍:两个浏览器同时开 → A 拖故事 → B 刷新看到变化(证明共享数据库生效)。

## 12. 阶段路线(与 Sprint 对齐)

| 阶段 | 内容 | 对应 |
|---|---|---|
| 1(本次) | 后端骨架 + 登录 + 故事/需求池 CRUD + 前端接 API | Sprint 1 |
| 2 | AI 审核中心决策落地、会议智能体(录音→转写→摘要→建议)、六项 AI 服务端调用 | Sprint 2 |
| 3 | 任务提交智能体 + GitHub 事件同步 + AI 最小闭环 | Sprint 3 |
| 4(可选) | PostgreSQL + 租服务器 + HTTPS + 真多人访问 | 上线时 |

## 13. 成功标准(阶段 1 演示口径)

1. `uvicorn app.main:app` 启动,`/docs` 可浏览,seed 后故事/任务/需求池有数据。
2. 4 名成员可分别登录。
3. 双浏览器同一时刻打开页面,A 端拖拽改状态/编辑,B 端刷新即见一致数据。
4. 后端关闭时,前端自动回退本地演示,不白屏。
