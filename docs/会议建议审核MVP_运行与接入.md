# 会议建议审核 MVP：运行与 Agent 接入

本分支实现项目侧最小闭环：保存会议转写 → 保存待审建议 → 负责人采纳/拒绝 → 批准后新增需求池条目 → 保存审核与执行记录。

**更新：会议 Agent 代码现已实现，配置与验证边界见 [DeepSeek 运行与验收](会议Agent_DeepSeek运行与验收.md)。** 在线页面提供明确标记的手动建议录入；外部 Agent 可以调用下列接口提交待审建议。origin=agent 只是提交方声明，不是模型调用真实性证明。

## 运行

沿用根目录上手指南启动 MySQL 和后端：

```powershell
cd backend
docker compose up -d
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

在另一个终端从仓库根目录启动静态服务：

```powershell
backend/.venv/Scripts/python.exe -m http.server 8090 --bind 127.0.0.1
```

打开 http://127.0.0.1:8090/index.html。浏览器询问本地网络访问时允许访问本机后端。部署/复制前端时需同时包含 index.html、meeting-review.js 与 meeting-agent.js。

成员3登录后进入「AI 助手」保存会议，选择会议、录入需求标题和原文证据，提交待审建议。成员1或成员2登录后进入「AI 审核中心」刷新，选择采纳或拒绝。采纳后到需求池查看实际新增条目；拒绝不创建需求。现有演示账号密码见上手指南。

## 权限

| 角色 | 查看会议/建议 | 保存会议/提交建议 | 审核 |
|---|---|---|---|
| admin / owner | 是 | 是 | 是 |
| member | 是 | 是 | 否 |
| viewer | 是 | 否 | 否 |
| 未登录 | 否 | 否 | 否 |

保持现有单项目共享数据边界。审核人从 JWT 登录态读取，不能从请求体指定。

## 已实现接口

除登录外，均需 Authorization: Bearer <access_token>。

| 方法/路径 | 输入/用途 |
|---|---|
| POST /api/auth/login | 现有 JSON 登录：username、password |
| GET /api/auth/users | 现有成员列表 |
| POST /api/meetings | title、transcript；创建成功返回 201 |
| GET /api/meetings | 会议列表，最新在前 |
| GET /api/meetings/{id} | 会议原文和创建信息 |
| POST /api/suggestions | 保存建议，或返回同请求的既有结果 |
| GET /api/suggestions?meeting_id=... | 查询会议建议，可省略筛选 |
| GET /api/suggestions/{id} | 建议、审核及执行详情 |
| POST /api/suggestions/{id}/review | decision=approve/reject、reason |

只接受 pool.create 动作，不支持直接修改任务/故事、批量批准或修改后采纳。输入不合法返回 422；不存在返回 404；无权限 403；重复键载荷不同或审核结论冲突返回 409。

### Agent 调用示例（Python/httpx）

以下假设项目已经运行，环境变量 AICAP_MEMBER_TOKEN 是成员身份的登录 token；示例本身不调用模型。

```python
import os
import uuid
import httpx

client = httpx.Client(
    base_url="http://127.0.0.1:8000",
    headers={"Authorization": "Bearer " + os.environ["AICAP_MEMBER_TOKEN"]},
    timeout=20,
)
response = client.post("/api/meetings", json={
    "title": "Sprint 周会",
    "transcript": "我们希望增加导出周报功能。具体排期下次再定。",
})
response.raise_for_status()
meeting_id = response.json()["id"]

# 正式 Agent 应将这份请求（包含请求键）持久化，重试时复用同一份。
body = {
    "meeting_id": meeting_id,
    "client_request_id": str(uuid.uuid4()),
    "action": "pool.create",
    "origin": "manual",  # 真正外部 Agent 的输出可标记 agent
    "evidence": "我们希望增加导出周报功能。",
    "note": "会议未定 Sprint，建议先进入需求池；Could 为初始建议优先级。",
    "changes": {
        "title": "导出周报",
        "description": "支持导出项目周报，范围待细化。",
        "priority": "Could",
    },
}
response = client.post("/api/suggestions", json=body)
response.raise_for_status()
print(response.json()["id"])  # 到此仅保存建议，需求池没有变化。
client.close()
```

将上面的手工 body 替换为 Agent 经过校验的输出即可接入。不要把负责人的审核 token 提供给模型。正式批准由审核页面或经过授权的审核调用完成：

```json
{"decision": "approve", "reason": "同意先纳入需求池"}
```

建议响应包含 changes、evidence、meeting_id、submitted_by、status、reviewed_by、reviewed_at、reason、execution_status、pool_item_id。时间为 UTC。证据必须是已保存转写中的连续原文片段。

## 数据与可靠性

- 新增 meetings 和 meeting_suggestion_records 两张表；复用 suggestions 作为建议主表。启动时现有 create_all 只补建新表，不改已有表结构、不清空数据。此版本没有 ALTER 迁移；未来需要改已有字段时再采用版本化迁移。
- 会议原文在本轮不可修改/删除；修订时新建会议，以保持证据可追溯。上限 16000 字符，兼容 MySQL TEXT 的字节容量。
- 提交去重键是 meeting_id + submitted_by + client_request_id。相同键和相同内容返回原建议，不同内容返回冲突。不同键视为新建议，不进行语义去重。
- 建议创建后不可修改。误录可以拒绝后提交新建议。本轮不提供修改后采纳。
- 审核使用数据库条件更新抢占 pending 状态；审核、业务写入和审计在同一事务中完成。并发/重复批准只生成一次条目，重复请求保留第一次审核人的理由和时间。
- 执行失败回滚，建议保持 pending，可检查错误后重试；本轮不另存失败尝试历史，也不支持执行后撤销。
- Agent 来源需求使用 A 开头编号，避免与现有手工需求 R 编号分配冲突。pool_item_id 是历史结果引用，不作外键；需求移入看板或删除后仍保留审核记录，重复批准也不会重新创建。
- 未明确优先级默认 Could，属于系统初始值，不代表会议已明确这一优先级；建议正文应标明这一点。需求池本来就不含 Sprint 承诺。
- 在线审核只展示本接口保存的真实会议建议，不将旧 localStorage 样例送入后端；退出账号后恢复本地演示。任务提交智能体仍为样例，在线模式禁用其送审按钮。

## 测试

后端测试会重建专用测试数据库，配置名必须以 _test 结尾。测试请求、启动流程和 health 均已隔离到测试数据库，不访问开发库。

```powershell
cd backend
.venv/Scripts/python.exe -m pytest tests -q
```

前端测试在隔离测试后端 :8001 和静态页面 :8090 上执行。可以在临时 SQLite 文件上启动页面测试后端（此文件仅用于页面联调，正式后端回归使用 MySQL）：

```powershell
cd backend
$env:DATABASE_URL="sqlite:///./meeting-ui.sqlite3"
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

另一个终端从仓库根目录运行，Node 环境需能解析 playwright-core，且安装了 Edge。可以在仓库外安装依赖并设置 NODE_PATH；不要求把 node_modules 放进仓库。

```powershell
node qa/meeting-review-e2e.spec.js
node qa/ui-e2e.spec.js
```

新增页面测试使用正常浏览器安全设置，只给隔离的测试浏览器授予本地网络访问权限。原有 E2E 仍沿用其既有启动参数。原有在线 E2E 假设测试库有 20 条种子故事，因此只在干净测试环境运行；新增测试会在专用页面测试库保留会议及审核数据供核对。

2026-09-06 验证：MySQL 8.0 后端 63 项通过（原有 41 + 新增 22）；原有前端 E2E 48 项通过，新增 Edge 页面检查 12 项通过。依赖环境出现两项测试客户端弃用提示，不影响执行。
