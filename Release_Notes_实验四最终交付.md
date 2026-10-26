# AIcap（爱管理）实验四最终交付 Release Notes

## 发布基本信息

- 项目：AIcap（爱管理）
- 发布用途：东北大学软件项目管理课程实验四最终交付
- 课程最终交付统计基准日期：2026 年 10 月 25 日
- GitHub Release 正式发布日期：2026 年 10 月 26 日（GitHub `published_at`：2026-10-25 16:44:54 UTC）
- 正式发布版本：[`v1.0.6`](https://github.com/Wayne-SQS/AIcap/releases/tag/v1.0.6)
- 发布 Tag 已创建，指向包含本发布说明和归档清单的文档提交 [`b3db29c404cc7f74e9453319f2ad601457e665ae`](https://github.com/Wayne-SQS/AIcap/commit/b3db29c404cc7f74e9453319f2ad601457e665ae)
- 经过 CI 验证的代码基准：[`a49cc3525ce7902c2f71fccd4a5cf6bffeb0b57c`](https://github.com/Wayne-SQS/AIcap/commit/a49cc3525ce7902c2f71fccd4a5cf6bffeb0b57c)
- CI：[`40192979032`](https://github.com/Wayne-SQS/AIcap/actions/runs/40192979032)，对应代码基准 `a49cc35`，结果 success；该 CI 不是在文档提交上执行

## 项目简介

AIcap（爱管理）是面向软件项目协作的 Web 系统。系统以项目、成员、用户故事、任务、排期和活动数据为基础，提供可视化项目管理、会议协作和 AI 辅助分析能力。AI 输出以证据和待确认建议为主，涉及正式项目数据的动作仍需人工审核。

## 本次发布范围

本次课程交付按独立用户故事计数：

- Sprint 1：12 条
- Sprint 2：9 条
- Sprint 3：12 条
- 三个 Sprint 累计独立发布：33 条
- Sprint 3 原计划 13 条；US37 并入 US36 交付，不单独计数，因此 Sprint 3 独立发布 12 条
- US09、US15、US21 属于 Sprint 4+ 后续范围，不计入本次发布

发布范围和验收口径见 [`qa/sprint3-closure/2026-10-22_六项能力验收对照.md`](qa/sprint3-closure/2026-10-22_六项能力验收对照.md)。

## 主要功能

- 项目、成员、需求池、用户故事和任务管理
- 故事地图、看板、甘特图、成员负载和 UML 等项目视图
- 会议工作区及提案审核流程，支持保存、分析、人工审核、执行和审计回读
- AI 需求拆解与粗估（US14）
- 智能排期建议（US18）
- 进度预测（US19）
- 风险预警与审核闭环（US20）
- GitHub 仓库活动同步、任务提交分析和成员工作状态分析（US34–US36）
- 质量分析与改进任务回流（US22）
- 项目效率分析、瓶颈识别、优化建议、人工确认和改进结果回读（US23）

US37 的项目层面协调建议并入 US36 交付，不作为独立发布项。以上范围依据代码、测试和 Sprint 3 验收记录整理，不扩大为未完成的后续能力。

## 测试与质量保证

经过 CI 验证的代码基准对应 GitHub Actions：

- [CI 40192979032](https://github.com/Wayne-SQS/AIcap/actions/runs/40192979032)，提交 `a49cc35`
- Java 契约测试：932 项，0 failures，0 errors，1 skipped
- AI 服务测试：297 项通过
- Agent 编排层测试：266 项通过
- 部署配置自检：18 项通过
- 前端生产构建：通过

上述为 GitHub Actions 自动执行结果。独立浏览器验收不属于该 CI workflow，仓库另有记录：

- 跨 US 定向浏览器验收：21/21
- US23 浏览器验收：3/3
- 记录位置：[`qa/sprint3-closure/2026-10-22_六项能力验收对照.md`](qa/sprint3-closure/2026-10-22_六项能力验收对照.md)
- E2E 测试文件：[`frontend/e2e-status/`](frontend/e2e-status/)

`SemanticReviewLiveTest` 的 1 项跳过必须保留在测试统计中，不能表述为“全部测试零跳过”。

## 已知限制与后续改进

- UML 跨设备共享已有服务端保存能力；多人实时编辑增强仍属于后续完善内容。
- AI 只提供分析、解释和建议，正式项目数据变更需要人工确认，不代表自动执行已发生。
- US23 的长期效率收益、诊断阈值和因果效果需要持续积累真实项目数据验证，不能由一次信号变化直接归因。
- 长上下文真实模型质量、综合排期质量和更完整的自动化 E2E 纳入后续验证范围。
- GitHub Actions 当前覆盖 Java、Python/编排层、部署自检和前端构建，不包含完整 Playwright E2E 或目标部署机的 live 验收。

## 获取代码与使用说明

- GitHub 仓库：[Wayne-SQS/AIcap](https://github.com/Wayne-SQS/AIcap)
- 基准提交：[`a49cc35`](https://github.com/Wayne-SQS/AIcap/commit/a49cc3525ce7902c2f71fccd4a5cf6bffeb0b57c)
- 项目说明：[`README.md`](README.md)
- 克隆与运行：[`上手指南_克隆后如何运行.md`](上手指南_克隆后如何运行.md)
- 部署说明：[`deploy/README.md`](deploy/README.md)
- 部署编排：[`deploy/compose.yaml`](deploy/compose.yaml)
- 发布范围与验收：[`qa/sprint3-closure/2026-10-22_六项能力验收对照.md`](qa/sprint3-closure/2026-10-22_六项能力验收对照.md)
- 回归与 CI 说明：[`docs/_process/2026-10-19_回归复测与CI收口.md`](docs/_process/2026-10-19_回归复测与CI收口.md)

本文档为 AIcap 实验四正式发布说明。正式发布版本 `v1.0.6` 已发布，Tag 指向文档提交 `b3db29c`。功能代码及测试结论以 `a49cc35` 为基准，CI `40192979032` 验证的是该代码提交，不是后续文档提交。发布后的状态文字修订作为普通文档更新进入 `main`，不移动已发布的 `v1.0.6` Tag，也不表示重新执行了 CI。
