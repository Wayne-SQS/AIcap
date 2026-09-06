# 爱管理 · 团队工作台

> 支持软件团队管理需求、安排任务、四视图联动与六项 AI 能力的协作平台 —— 像素风可交互原型。
> 前端 `index.html` 已接后端 API:后端运行时为「在线 · 真多人协作」,后端不可用时自动回退「离线演示」。

## 🚀 队友首次上手

克隆本仓库后怎么跑起来(MySQL + 后端 + 前端),见 **[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)**。

## 测试交付物

前后端功能测试用例设计、执行记录与缺陷清单位于 [`qa/`](qa/)(测试用例设计 → 执行记录 → 缺陷清单 → 汇总报告)。测试入口见 `qa/README.md`。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `index.html` | 前端原型(8 视图,像素风;后端在线走 API,离线自动回退) |
| `backend/` | FastAPI 后端 + SQLAlchemy + 测试;`docker-compose.yml` 起 MySQL 8.0(库 `AIcap`,端口 3307) |
| `docs/superpowers/` | 设计规格与实施计划 |
| `qa/` | 前后端测试用例、执行记录、缺陷清单与回归 |

## 本地运行(快速)

```bash
# 只开前端(离线演示,数据在本机浏览器)
双击 index.html

# 完整模式(前端 + 后端 + MySQL)
cd backend && docker compose up -d     # 起 MySQL
copy .env.example .env                 # 首次:配置(可跳过)
pip install -r requirements.txt        # 首次:装依赖
python -m uvicorn app.main:app --reload --port 8000   # 起后端
# 然后双击 index.html → 登录(成员1-4 / 123456)
```

## 功能视图

| 视图 | 说明 |
|---|---|
| ◈ 项目总览 | 故事/任务统计、3 个 Sprint 进度、里程碑、总完成度、实时进度 |
| ◆ 需求池 | 会议/临时需求暂存,可「移入看板」成正式故事 |
| ▦ 用户故事看板 | 拖拽、编辑、故事地图、搜索筛选、JSON/CSV 导出、变更日志 |
| ▤ 甘特图 | 16 项任务、6 周排期、按成员着色、4 个里程碑 |
| ▥ 成员任务图 | 分工、负载热力图、容量 Bandwidth、贡献绿格子 |
| ◇ UML 图 | 用例图 + 时序图 |
| ✦ AI 助手 | 6 项能力、会议智能体、任务提交智能体(阶段 2 接入真实 AI) |
| ✓ AI 审核中心 | AI 建议的采纳/修改/拒绝留痕(阶段 2 接后端) |

## 默认账号(演示)

成员1(admin)/成员2(owner)/成员3/成员4(member),密码均为 `123456`。

## 技术栈

- 前端:纯 HTML/CSS/JavaScript(无框架、无构建);后端可用时走 REST API + JWT,否则回退 localStorage 演示
- 后端:Python FastAPI + SQLAlchemy + PyMySQL;MySQL 8.0(Docker)
- 测试:pytest + TestClient;Playwright E2E(`qa/ui-e2e.spec.js`)
