# 实验三回顾问题闭环记录

本目录保存实验三回顾中零容量规则与项目入口空数据两项技术问题的回归证据。

| 问题 | 可复现检查 | 证据 |
|---|---|---|
| 零容量规则证据归档不足 | `frontend/e2e-verify/capacity-boundary.spec.js` 验证界面保存、默认回退和超载；`frontend/e2e-live/boundary-flow.spec.js` 用独立 MySQL、真实 Java 服务与浏览器验证数据库持久化、正工时超载、刷新恢复及跨项目隔离 | `evidence/run-status.json`、`evidence/browser.log`、`evidence/zero-capacity.png` |
| 项目入口空数据在联调才暴露 | `frontend/e2e-verify/projects.spec.js` 前置回归空列表、503、重试和登录态；真实服务测试使用没有项目成员关系的账号验证空列表 | `evidence/run-status.json`、`evidence/browser.log`、`evidence/empty-projects.png` |

## 复现

在仓库根目录执行：

```powershell
cd frontend
npx playwright test e2e-verify/projects.spec.js e2e-verify/capacity-boundary.spec.js --config=playwright.verify.config.js
cd ../java-backend
mvn -o -B package -DskipTests
cd ..
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite boundary
```

真实服务脚本创建并关闭独立的临时 MySQL/Java/Python/Vite 进程，不接触开发库。每轮临时日志保存在 `qa/.daily-live/<run-id>/`；成功后复制关键结果到本目录的 `evidence/`。浏览器模拟测试用于前置回归，真实服务测试用于联调反例。

2026-10-22 重新使用独立 MySQL 8.0.31、真实 Java/Python/Vite/JWT 与浏览器完成边界联调：两条浏览器测试通过，数据库汇总为 `0/1/15/0`（P003 零容量、1 个正工时任务；P004 默认 15h、0 任务）。`evidence/run-status.json` 与 `evidence/result.json` 记录结果，浏览器日志和空项目、零容量截图均已归档。空项目用例使用启动播种完成后创建的无项目成员关系账号，避免把已加入 P001 的种子账号误当作空账号。
