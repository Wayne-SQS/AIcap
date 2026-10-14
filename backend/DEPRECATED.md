# ⚠️ 本目录已归档（DEPRECATED）

> 标记日期：2026-10-14。**不要往这里加任何功能代码。**

## 这是什么

FastAPI + SQLAlchemy 的**参考实现** —— Vue 改造之前的旧后端。它与 `java-backend/`
**不是**两条并存的后端，是同一个系统的上一代。

| 项 | 说明 |
|---|---|
| 状态 | **归档 / 只读**。功能开发一律落 `java-backend/` + `frontend/` + `ai-service/` |
| 被谁取代 | `java-backend/`（Java 23 + Spring Boot 3.5，8080）—— MySQL 的唯一写者、唯一被前端直接调的主 API 面 |
| 有无生产流量 | **无**。不在 `deploy/compose.yaml` 里，nginx 无路由，前端零引用 |

## ⚠️ 唯一仍在用的东西 —— 删除前必须先迁走

**`backend/docker-compose.yml`** 是**本机开发/测试**起 MySQL(3307) + Qdrant(6333)
的**现行方式**，不是遗留物。删本目录之前必须先把这份 dev 编排挪走（建议挪到
`deploy/`，与生产编排放一起但保持两个文件、两套用途）。

```bash
cd backend && docker compose up -d          # 起 MySQL(aiguanli-mysql, 3307)
cd backend && docker compose up -d qdrant   # 起 Qdrant(aicap-qdrant, 6333)
```

两者的区别（别混用）：

- 本目录的 `docker-compose.yml` → **本机开发**，端口 3307，数据落 `backend/mysql-data/`
- `deploy/compose.yaml` → **生产**，四容器（Nginx / Java / ai / MySQL）

另注：`mvn -B test` 全量跑需要 Qdrant 容器在跑（索引与检索两组契约测试对两套向量库
实现各跑一遍），所以这个 compose 目前是**测试前置**，不能随手删。

## 还有一处仍在被读

`app/meeting_agent/runner.py` 是 `agent-orchestrator/app/loop.py`（有界工具循环）
的**复活来源** —— 编排层把它的 `session_factory` 直连库那部分剥掉，换成
`JavaClient`。**只作为参考资料存在**，不要再往里加代码；要改循环改编排层那份。

## 删除的前提条件

- [ ] `backend/docker-compose.yml` 的 dev 编排已迁到 `deploy/`（或仓库里另有等价位置）并验证可用
- [ ] 全仓 grep 确认没有任何脚本 / 文档 / CI 引用本目录路径
- [ ] `agent-orchestrator/app/loop.py` 的注释把"复活来源"改成过去时（或补一句历史说明）

在此之前：**只读，不加东西。**
