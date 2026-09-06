# 爱管理 · QA 目录说明

测试人员工作产物与执行入口。

## 结构

| 文件 | 说明 |
|---|---|
| `测试用例设计_前后端_v1.md` | 前后端全部测试用例设计(编号/优先级/步骤/预期/实测) |
| `ui-e2e.spec.js` | 前端 UI E2E(Playwright):离线演示模式 FE 套件 + 在线联调 OE 套件 + 清理 |
| `reset_qa_db.py` | 重置 QA 库 `AIcap_qa` 到干净播种状态(重复执行 E2E 前使用) |
| `测试执行记录_后端.md` | 后端 pytest 扩展套件执行输出摘要与映射 |

## 后端自动化执行

```powershell
# 前置:docker mysql:3307 在跑;backend/.env 存在
cd backend
D:\aiguanli-venv\Scripts\python.exe -m pytest tests -q
# 当前:34 passed(auth 8 + stories 12 + pool 5 + tasks 1 + dashboard 2 + health 1 等)
```

扩展套件为 `backend/tests/test_qa_extended.py`,与既有 `conftest.py` 共用独立测试库 `AIcap_test`。

## 前端 E2E 执行

```powershell
# 1) 起 QA 后端(独立库,避免污染开发库)
cd backend
$env:DATABASE_URL="mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_qa?charset=utf8mb4"
D:\aiguanli-venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001

# 2) 重置 QA 库(每次全新执行前)
cd backend
$env:DATABASE_URL="mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_qa?charset=utf8mb4"
D:\aiguanli-venv\Scripts\python.exe -c "import sys; sys.path.insert(0,'<repo>/qa'); import reset_qa_db"

# 3) 起静态服务器(页面来源)
cd <repo 根>
python -m http.server 8090 --bind 127.0.0.1

# 4) 安装浏览器运行时并在临时目录执行
cd %TEMP%\aiguanli-qa     # 已 npm install playwright-core + install chromium
node E:\桌面\软件项目管理实验\qa\ui-e2e.spec.js
```

## 已知说明

- `index.html` 内 `API_BASE` 固定 `127.0.0.1:8000`;E2E 通过改写内存 HTML 指向 8001,不修改源文件。
- 页面经 `http://127.0.0.1:8090` 打开以提供 localStorage 源;离线用例将 API_BASE 指向无人监听端口(59999),触发前端自动回退。
