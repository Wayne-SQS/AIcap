# ⚠️ 本目录已归档（DEPRECATED）

> 标记日期：2026-10-14。**不要往这里加任何代码。**

## 这是什么

Vue 改造**之前**的旧版前端 —— 单文件 `index.html` 内联 CSS + JS，外加三个按需加载的
会议相关脚本。

| 文件 | 说明 |
|---|---|
| `index.html` | 旧版整站单文件（现 `frontend/` 的 `src/styles/index.css` 就是从它的内联 CSS 整块迁移来的） |
| `meeting-agent.js` | 旧会议 Agent 前端脚本 |
| `meeting-improvements.js` | 旧会议改进脚本 |
| `meeting-review.js` | 旧会议审核脚本 |

## 被谁取代

`frontend/`（Vue3 + Vite + Pinia，像素风，15 视图）。**入口已不在本目录** ——
开发起 `frontend/` 的 dev server，生产走 `deploy/compose.yaml` 的 Nginx。

## 有没有残留引用

**无。** `frontend/` 不引用本目录，`deploy/` 不打包本目录，`qa/` 的 Playwright 用例
打的是 `frontend/` 的 dev/preview server。

## 删除前提

- [ ] 全仓 grep `legacy/` 确认只剩文档里的历史叙述（可以留，说明来路）
- [ ] 确认 `frontend/` 的像素风样式迁移已完整 —— 若将来要补某个旧视觉细节，
      这份 `index.html` 是最后一个参照物

**可以整体删。** 删之前把这份标记的「还有谁引用」一节核对一遍即可。
