# 实验三回顾问题闭环记录

依据 `实验3/7_Sprint 2回顾会议纪要.docx` 的三项未闭环问题。本目录把代码、自动化验证和人工签认分开记录；只有三项证据都齐全后才能宣称整体闭环。

| 问题 | 可复现检查 | 证据 |
|---|---|---|
| 零容量规则证据归档不足 | `frontend/e2e-verify/capacity-boundary.spec.js` 验证界面保存、默认回退和超载；`frontend/e2e-live/boundary-flow.spec.js` 用独立 MySQL、真实 Java 服务与浏览器验证数据库持久化、正工时超载、刷新恢复及跨项目隔离 | `evidence/run-status.json`、`evidence/browser.log`、`evidence/zero-capacity.png` |
| 项目入口空数据在联调才暴露 | `frontend/e2e-verify/projects.spec.js` 前置回归空列表、503、重试和登录态；真实服务测试使用没有项目权限的种子账号验证空列表 | `evidence/run-status.json`、`evidence/browser.log` |
| 审查签认分散 | `review-signoff-pending.csv` 集中列出 13 次 AI 互动的原始时间、编号、任务场景及团队处置，签认字段留空；`check_signoffs.py` 阻止未签认记录被误报为闭环 | 原始 `实验3/7_AI互动原始记录.docx`，SHA-256 `151e2b369a07a020f95765e554126d944633d2d986ddf16f8b44100874616e79` |

## 复现

在仓库根目录执行：

```powershell
cd frontend
npx playwright test e2e-verify/projects.spec.js e2e-verify/capacity-boundary.spec.js --config=playwright.verify.config.js
cd ../java-backend
mvn -o -B package -DskipTests
cd ..
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite boundary
python qa/sprint3-closure/check_signoffs.py
```

真实服务脚本创建并关闭独立的临时 MySQL/Java/Python/Vite 进程，不接触开发库。每轮临时日志保存在 `qa/.daily-live/<run-id>/`；成功后复制关键结果到本目录的 `evidence/`。浏览器模拟测试用于前置回归，真实服务测试用于联调反例。

2026-10-22 重新使用独立 MySQL 8.0.31、真实 Java/Python/Vite/JWT 与浏览器完成边界联调：两条浏览器测试通过，数据库汇总为 `0/1/15/0`（P003 零容量、1 个正工时任务；P004 默认 15h、0 任务）。`evidence/run-status.json` 与 `evidence/result.json` 记录结果，浏览器日志和空项目、零容量截图均已归档。空项目用例使用启动播种完成后创建的无项目成员关系账号，避免把已加入 P001 的种子账号误当作空账号。

## 人工签认

`review-signoff-pending.csv` 的 13 行与原始记录表逐行对应。第 4 次调用因模型输出截断而失败，原始记录没有成功 ID，清单使用 `call-04` 标识；它仍需审查其“不作为验收证据”的团队处置。团队处置是原文记录，**不代表已经由具体审查人签认**。

2026-10-22 修复该 CSV 的编码并校正状态：此前 13 行标为 `signed`，但签认人、时间、证据均为空；现统一为 `pending`。执行 `check_signoffs.py` 报告 13 项待签认，退出码为 1，符合门禁预期。

每位实际审查人核对原始互动与团队处置后，在对应行填写 `reviewer_name`、带时区的 ISO 8601 `signed_at`、指向真实签认文件的 `signoff_evidence`，再把 `status` 改为 `signed`。没有真人签认材料时保持 `pending`。校验脚本对缺项、重复、来源缺失和不存在的签认文件报错；仍有待签认项时返回非零退出码。
