# 爱管理 · QA 目录说明

测试人员工作产物与执行入口。

---

## 现行基线前端 E2E(Java Spring Boot 后端 + Vue3 前端)—— 当前有效入口

> `qa/` 下的 5 个旧 spec(`ui-e2e.spec.js`、`kanban-gantt-e2e.spec.js`、`meeting-review-e2e.spec.js`、
> `meeting-improvements-e2e.spec.js`、`meeting-agent-e2e.spec.js`)与 `e2e-helpers.js` 针对**已归档**的
> `legacy/index.html`(旧 M01–M23 编号基线)与 FastAPI 后端(8001/8090/8092);最新提交已把数据基线整体换成
> US01–US37,legacy 页面里不存在 `M02`/`subprog`/`gbar.parent` 等锚点,**它们已整体失效**。
> 这些文件按要求保留归档、不删除,但新套件完全不依赖它们(config 里用 `testMatch` 把它们排除在收集范围外)。

自包含 Playwright 工程(自带 `qa/package.json` 与 `qa/node_modules`,与 `frontend/` 的依赖互不干扰):

| 文件 | 说明 |
|---|---|
| `package.json` | `aicap-qa` 独立测试工程;`@playwright/test` 锁定 `1.63.0`;scripts:`test` / `test:headed` / `report` |
| `playwright.config.js` | baseURL `http://localhost:5173`、系统 Edge(`channel: msedge`)、headless、虚拟麦克风;`testDir: '.'` + `testMatch` 只收集新套件;产物写在 `qa/test-results` 与 `qa/report` |
| `vue-baseline-e2e.spec.js` | 现行基线端到端回归套件:**35 条** `[FE-XXX-NN]` 用例,按 9 个模块 `test.describe` 分组 |

用例编号是稳定契约:`[FE-XXX-NN]` 与 `qa/测试用例设计_当前基线_v3.md`(用例设计:步骤/预期/实测)、
`qa/测试执行记录_当前基线_v3.md`(执行记录)中的编号**一一对应**,可双向追溯;批量编排入口见 `qa/run-all.ps1`。

前置(套件直连真实服务,不 mock 后端):

```powershell
# 1) 后端:java-backend(Spring Boot 3.5.3)→ http://localhost:8080(连开发库 AIcap)
#    修改后端源码后必须重新打包并重启:& mvn -o -B package -DskipTests → java -jar target\aicap-java-backend.jar
#    (Windows 下 jar 被运行中的实例占用会导致打包失败;启动前需注入 backend/.env 的 JWT_SECRET / AICAP_LLM_*)
# 2) 前端:frontend Vite dev server → http://localhost:5173
cd frontend; npm run dev
```

> ⚠️ **独占约束**:本套件假设**独占开发库 `AIcap` 与这两个服务**。用例里有基于精确条数的断言
> (看板卡片数、成员任务数、日志行数),因此**不要与他人手工操作或另一个 E2E 进程并发执行**,
> 否则会出现"数据被别人改了"的偶发红灯(实测发生过一次:并发跑第二个 Playwright 进程时
> `FE-BRD-05`/`FE-GNT-04` 失败,单独复跑与随后连续 3 轮 35/35 全绿)。

执行:

```powershell
cd qa
npm install        # 仅首次;浏览器不用下载,套件用系统 Edge
npm test           # = npx playwright test
npm run report     # 查看 HTML 报告(open: never,产物在 qa/report)
```

用例清单(35 条):

| 模块 | 编号 | 覆盖要点 |
|---|---|---|
| `FE-AUTH` | 01–04 | 登录成功身份/错误密码被拒/只读查看者权限(viewer 前端拦截 + 后端 403)/退出登录 |
| `FE-OVW` | 01–02 | 概览统计口径(37 故事 / 16 任务 / 5 成员)、完成度百分比与看板口径 |
| `FE-POOL` | 01–04 | 列表与接口一致 + 空态、新增→移除清理、提升为故事(US38+)→删除清理、必填校验 |
| `FE-BRD` | 01–06 | 默认故事地图(4 切片含 Sprint 4+)、血缘徽章 `▣ x/y · %`、新建→删除、编辑 Sprint/负责人往返、负责人筛选、点卡详情 + 变更日志 |
| `FE-GNT` | 01–05 | 父卡行/子任务行 + `data-gantt-task`、任务详情(前置/后续高亮)、编辑任务往返(8h→9h→8h)、父卡故事详情、选中态互斥 |
| `FE-MBR` | 01–04 | 成员卡/容量/负载档位/负载图例、任务抽屉(列表+统计+筛选)、抽屉三种关闭方式、成员画像编辑往返 |
| `FE-UML` | 01–03 | 用例图(4 参与者/15 用例/27 连线)、用例明细面板、时序图「Spring Boot 后端」且无 FastAPI |
| `FE-AI` | 01–06 | 创建会议→落库→选中→**删除自清理**;`agent/config` 状态文案与按钮可用性;本地 mp3 上传→列表→删除;会议删除入口的权限门控(admin 可用 / member 禁用 / viewer 禁用) |
| `FE-OFF` | 01 | 离线演示:API 指向死端口 + 旧 M 编号缓存 → 仍渲染 US01–US37 |

写操作与数据安全:

- 所有造数**唯一命名**(`QA-*` + 随机后缀),用例内自清理,`finally` 兜底;绝不删除/修改种子
  US01–US37、T01–T16、5 个用户、5 条成员画像。
- 「改一项再改回」类用例(`FE-BRD-04` 编辑故事、`FE-GNT-03` 改任务工时、`FE-MBR-04` 改画像)
  都在结束时**逐字段回读校验还原**。
- 期望值优先从后端真实接口取(故事/任务/用户/画像/日志/会议数量),不写死数字,基线漂移时不会误报。
- 不真录音(录音链路由 `frontend/e2e-verify/recorder-profile.spec.js` 覆盖);音频用例用 Node 生成的
  最小合法 mp3(ID3v2 头 + MPEG1 Layer III 静音帧)走 `setInputFiles` 上传。
- **造数即清理(已闭环)**:前端现在有会议删除入口,后端也提供了 `DELETE /api/meetings/{id}`(admin/owner),
  因此 `FE-AI-01` 已改回**真删除自清理**(建会议 → 落库 → 选中 → 删除 → 断言 404/列表清空/建议队列清空);
  验收套件 `frontend/e2e-verify/recorder-profile.spec.js` 同样在结束时删除自己创建的「录音验收会议」。
  **两个套件跑完都不再累积数据**(实测:连跑后开发库会议总数不变)。
  若历史上留有残余,可人工清理:`DELETE FROM meetings WHERE title LIKE 'QA-AI-%'` 或按标题前缀「录音验收会议」删。

### 缺陷与修复状态(本轮发现 9 条,7 条已修复并回归)

| # | 现象 | 状态 / 修法 | 回归锚点 |
|---|---|---|---|
| 1 | 错误密码的提示丢失后端原因(显示「登录失败：未登录」) | ✅ 已修复:`api/client.js` 的 401 先取 `detail`;`/api/auth/login` 不再走全局会话过期处理 | `FE-AUTH-02` |
| 2 | 只读查看者无法用真名「只读查看者」登录(401) | ✅ 已修复:`AuthController.LOGIN_ALIASES` 补第 9 对映射 | `AUTH-10`、`FE-AUTH-03` |
| 3 | 会议创建后无法删除,套件每轮累积垃圾数据 | ✅ 已修复:后端新增 `DELETE /api/meetings/{id}`(admin/owner,级联清理 runs/events/audio+落盘文件/建议记录,保留已审批的池条目)+ 前端 `#meeting-delete` 入口 + 两套件自清理 | `MEET-06/07/08`、`FE-AI-01/04/05/06`、`VRF-07` |
| 4 | 只读角色的「新增/编辑」按钮未禁用,仅点击后弹 toast | ✅ 已修复:统一 `disabled + title="只读账号无写权限"`(保持可见),故事卡同时不可拖拽;`guard()` 仍作兜底 | `FE-AUTH-03` |
| 5 | 变更记录面板标称与实际不符(实际最旧在前;在线仍标「本机」) | ✅ 已修复:store 取**最新 50 条**并翻成旧→新,面板渲染最新在前;标题随在线/离线变化(连带修好总览「最近更新」取到最旧一条的问题) | `FE-BRD-06` |
| 6 | 验收套件 `recorder-profile.spec.js` 每轮留一条「录音验收会议」 | ✅ 已修复:用例结束调用删除接口自清理 | `VRF-07` |
| 7 | 9 个既有契约测试类的 worker 开关写成 `aicap.agent-worker-enabled`(真实前缀是 `aicap.llm.`),该键**从未绑定**,测试上下文里 Agent worker 实为开启状态 | ✅ 已修复:9 个类统一改为 `aicap.llm.agent-worker-enabled=false`;`AgentContractTest` 显式写 `true`,保证只有该上下文消费队列 | 全量 126 绿 + Agent 套件 14 绿 |
| 8 | 开发库需求池非空(2 条历史冒烟残留 `A000000007/A000000008`) | ⏸ **保留不动**(2026-09-13 确认口径):它们是「审核→建池」链路的真实凭证,演示时能看到端到端结果;故「池为空」空态仍用 `page.route` 拦截 `[]` 验证(`FE-POOL-01`),不回退真实空态断言 | — |
| 9 | 内联脚本硬编码 `window.__AICAP_API_BASE__`,覆盖 E2E 注入 | ⏳ 未处理:离线用例改用 `Object.defineProperty` 钉死基址(`FE-OFF-01`) | — |

## 结构(含归档)

| 文件 | 说明 |
|---|---|
| `package.json` | **现行**:`aicap-qa` 独立 Playwright 测试工程(见上节) |
| `playwright.config.js` | **现行**:现行基线 E2E 配置(msedge/headless/虚拟麦克风,产物在 qa/ 内) |
| `vue-baseline-e2e.spec.js` | **现行**:35 条 `[FE-XXX-NN]` 现行基线回归用例 |
| `run-all.ps1` | **现行**:一体化编排(后端契约 JUnit 126 → 前端 E2E 35 → 前端验收 e2e:verify 7) |
| `测试用例设计_当前基线_v3.md` | **现行**:当前基线用例设计 168 条(契约层 126 + 前端 `[FE-XXX-NN]` 35 + 验收层 7 + 缺口与缺陷) |
| `测试执行记录_当前基线_v3.md` | **现行**:当前基线执行记录 |
| `测试用例设计_前后端_v1.md` | 前后端全部测试用例设计(编号/优先级/步骤/预期/实测) |
| `测试用例设计_前后端_v2.md` | v2 用例设计:看板↔甘特血缘重构 + 会议智能体审核工作流(含复测修订) |
| `ui-e2e.spec.js` | ⚠️**已失效(归档)**:针对 legacy/index.html + FastAPI(8090) |
| `kanban-gantt-e2e.spec.js` | ⚠️**已失效(归档)**:M01–M23 编号基线的血缘 E2E |
| `meeting-review-e2e.spec.js` | ⚠️**已失效(归档)** |
| `meeting-improvements-e2e.spec.js` | ⚠️**已失效(归档)** |
| `meeting-agent-e2e.spec.js` | ⚠️**已失效(归档)**:依赖 FastAPI 后端 + 本地 fixture 供应商 |
| `e2e-helpers.js` | ⚠️**已失效(归档)**:旧 spec 的双模式(legacy/vue)公共助手 |
| `agent_provider_fixture.py` | 本地 LLM fixture 供应商(端口 9009,旧会议 Agent E2E 依赖) |
| `reset_qa_db.py` | 重置 QA 库 `AIcap_qa` 到干净播种状态(旧 FastAPI 流水线) |
| `verify_migration.py` | 血缘迁移校验(只读,对 dev 库或 `--qa` QA 库执行) |
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

## 前端 E2E 执行(⚠️ 已归档:FastAPI 后端 + legacy/index.html 的旧流水线,勿再使用)

> 本节描述的是**改造前**的 FastAPI(8001/8090/8092)流水线。当前基线请直接看本文最上方
> 「现行基线前端 E2E」一节(`cd qa && npm test`,只需 8080 + 5173)。

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

## E2E 双模式(legacy / vue)(⚠️ 已归档:仅适用于上述旧 spec)

自 Vue3 化改造(`frontend/`;该分支已整合进 `main` 并删除)起,全部 5 个 E2E spec 同时支持两种页面形态,由环境变量切换:

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
