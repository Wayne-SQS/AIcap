# 爱管理 · QA 目录说明

测试人员工作产物与执行入口。

## 结构

| 文件 | 说明 |
|---|---|
| `测试用例设计_前后端_v1.md` | 前后端全部测试用例设计(编号/优先级/步骤/预期/实测) |
| `测试用例设计_前后端_v2.md` | v2 用例设计:看板↔甘特血缘重构 + 会议智能体审核工作流(含复测修订) |
| `ui-e2e.spec.js` | 前端 UI E2E(Playwright):离线演示模式 FE 套件 + 在线联调 OE 套件 + 清理 |
| `kanban-gantt-e2e.spec.js` | 血缘 E2E:FE-KGN 离线套件(徽章/加权/甘特层级/三选一/跨 Sprint 挪动) + OE-KGN 在线可逆套件 |
| `meeting-review-e2e.spec.js` | 会议审核工作流 E2E |
| `meeting-improvements-e2e.spec.js` | 会议改进项 E2E(修改后采纳/Sprint 必选/owner 可空) |
| `meeting-agent-e2e.spec.js` | 会议 Agent E2E(fixture 供应商/失败重试/证据伪造拒绝) |
| `e2e-helpers.js` | E2E 双模式公共助手:按 `AICAP_UI_FLAVOR` 读 legacy/vue 页面并注入 API_BASE(见下「E2E 双模式」) |
| `agent_provider_fixture.py` | 本地 LLM fixture 供应商(端口 9009,会议 Agent E2E 依赖) |
| `reset_qa_db.py` | 重置 QA 库 `AIcap_qa` 到干净播种状态(重复执行 E2E 前使用) |
| `verify_migration.py` | 血缘迁移校验(只读,固化 v2 用例 DB-01~04;对 dev 库或 `--qa` QA 库执行) |
| `测试执行记录_后端.md` | 后端 pytest 扩展套件执行输出摘要与映射 |

## 后端自动化执行

```powershell
# 前置:docker mysql:3307 在跑;backend/.env 存在
cd backend
D:\aiguanli-venv\Scripts\python.exe -m pytest tests -q
# 当前:117 passed(auth4 + stories4 + dashboard1 + pool2 + qa_extended23 + regression7
#        + kanban_gantt21 + meeting_agent24 + meeting_review22 + meeting_improvements9)
```

扩展套件为 `backend/tests/test_qa_extended.py` 与 `backend/tests/test_kanban_gantt.py`(血缘:PATCH /tasks、删卡三选一级联、FK SET NULL、seed 映射),与既有 `conftest.py` 共用独立测试库 `AIcap_test`。

## 前端 E2E 执行

```powershell
# 0) 运行前置(重要):
#    a. 合并/迁移后必须先重建 QA 库,否则后端启动即报 1054 Unknown column 'tasks.kanban_card_id':
cd backend
$env:DATABASE_URL="mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_qa?charset=utf8mb4"
D:\aiguanli-venv\Scripts\python.exe -c "import sys; sys.path.insert(0,'<repo>/backend'); sys.path.insert(0,'<repo>/qa'); import reset_qa_db"
#    b. 本机有系统代理时,后端访问本地 fixture(9009)/自身须绕过代理,否则 502:
$env:NO_PROXY="127.0.0.1,localhost"; $env:HTTP_PROXY=""; $env:HTTPS_PROXY=""

# 1) 起 QA 后端(独立库,避免污染开发库)
cd backend
$env:DATABASE_URL="mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_qa?charset=utf8mb4"
# 会议 Agent E2E 还须覆写 LLM 指向本地 fixture(否则会调用真实 DeepSeek):
$env:AICAP_LLM_BASE_URL="http://127.0.0.1:9009"; $env:AICAP_LLM_API_KEY="fixture-test-key"
$env:NO_PROXY="127.0.0.1,localhost"; $env:HTTP_PROXY=""; $env:HTTPS_PROXY=""
D:\aiguanli-venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001

# 2) 起静态服务器(页面来源;meeting-improvements spec 使用 8091,其余使用 8090)
cd <repo 根>
python -m http.server 8090 --bind 127.0.0.1
python -m http.server 8091 --bind 127.0.0.1

# 2b) 会议 Agent E2E 前置:起本地 fixture 供应商(监听 9009;注意必须经 uvicorn 启动,直接运行 py 文件会立即退出)
cd <repo>\qa
$env:AICAP_TEST_FIXTURE="1"; D:\aiguanli-venv\Scripts\python.exe -m uvicorn agent_provider_fixture:app --host 127.0.0.1 --port 9009

# 3) 在临时目录执行(node_modules 在此,须用 NODE_PATH 指过去)
cd %TEMP%\aiguanli-qa     # 已 npm install playwright-core + install chromium
$env:NODE_PATH="$env:TEMP\aiguanli-qa\node_modules"
node <repo>\qa\ui-e2e.spec.js              # FE+OE 全量(48 用例)
node <repo>\qa\kanban-gantt-e2e.spec.js    # 血缘 FE-KGN+OE-KGN(36 用例,在线部分自带恢复)
node <repo>\qa\meeting-review-e2e.spec.js  # 会议审核(需先起 agent_provider_fixture 的场合见各 spec 头注释)
```

## E2E 双模式(legacy / vue)

自 Vue3 化改造(`frontend/`,分支 `feat/vue-frontend`)起,全部 5 个 E2E spec 同时支持两种页面形态,由环境变量切换:

| 变量 | 默认 | 说明 |
|---|---|---|
| `AICAP_UI_FLAVOR` | `legacy` | `legacy`=`legacy/index.html`(Vue 改造前的旧版前端,已归档到 `legacy/`,静态源 8090/8091);`vue`=`frontend/dist` 构建产物(Vue3 现行前端,静态源 8092) |
| `AICAP_UI_URL` | 按 flavor | 覆盖页面地址:legacy 默认 `http://127.0.0.1:8090/legacy/index.html`,vue 默认 `http://127.0.0.1:8092/index.html` |

```powershell
# vue 模式运行前置:先构建产物,再起 dist 静态服务(8092)
cd frontend
npm install        # 仅首次
npm run build      # 产物输出 frontend/dist
cd dist
python -m http.server 8092 --bind 127.0.0.1

# 同一套 spec,两套页面各跑一遍即为双模式回归:
$env:AICAP_UI_FLAVOR="vue"       # 缺省或置 legacy 即旧版单页
node <repo>\qa\ui-e2e.spec.js
```

- 注入方式与 legacy 一致:`e2e-helpers.js` 读取对应 HTML,把 API 锚点(legacy `const API_BASE='...'` / vue `window.__AICAP_API_BASE__='...'`)替换为 QA 后端后经 Playwright route 拦截下发,不修改源文件。
- vue 为 SPA,首屏需加载模块脚本,等待超时自动放宽 2.5 倍(6s→15s);登录就绪信标统一改用常驻顶栏 `#mode-chip`(`已连接后端` 文案两版一致)。
- 双模式基线(2026-09-08):legacy 与 vue 各 120 用例全通过(48 ui-e2e + 36 kanban-gantt + 12 meeting-review + 13 meeting-agent + 11 meeting-improvements)。

## 迁移/重建库后的校验

```powershell
cd backend
D:\aiguanli-venv\Scripts\python.exe <repo>\qa\verify_migration.py        # dev 库(只读)
D:\aiguanli-venv\Scripts\python.exe <repo>\qa\verify_migration.py --qa   # QA 库(只读)
# 预期 6/6:0 孤儿 / 9 挂载卡 / 12 feature 子任务 / 23 故事 / 管理任务 T01,T02,T15,T16 / eh=hours
```

## 已知说明

- `index.html` 内 `API_BASE` 固定 `127.0.0.1:8000`;E2E 通过改写内存 HTML 指向 8001,不修改源文件。
- 页面经 `http://127.0.0.1:8090` 打开以提供 localStorage 源;离线用例将 API_BASE 指向无人监听端口(59999),触发前端自动回退。
- 会议 Agent E2E 依赖:fixture 供应商(9009,uvicorn 启动见 2b)、QA 后端带 `NO_PROXY` 且 `AICAP_LLM_BASE_URL` 覆写为 `http://127.0.0.1:9009`(否则后端会按 `.env` 调用真实 DeepSeek,分析结果不可复现)。
- vue 模式前提是 `frontend/dist` 为最新构建(`npm run build`);spec 读取 `dist/index.html` 中的 `window.__AICAP_API_BASE__` 锚点注入 QA 后端地址。
