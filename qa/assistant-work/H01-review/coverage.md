# H01 复核覆盖

状态：`ready`

## 复核口径

- 覆盖三份 v7 报告中的全部 40 个输出，逐条检查 `summary`、全部 `proposed_actions` 的目标/数字/reason/evidence，以及 `open_questions`。
- 不采用报告自带的 `status` / `failures` 作为人工结论。
- “通过”表示本次未定位到可操作问题，不代表生产语义全面通过。
- “确定问题”包括事实扩写、关系错位、已回答问题或问题栏结构质量问题；“待判断”表示取决于主流程对主动追问范围的产品策略。
- 共复核 20 个状态动作；未发现错误目标、错误数字、漏提/多提、理由否定动作、跨故事证据或非连续引用。

## 40/40 覆盖表

| # | 报告 | case_id | repetition | 动作数 | 结论 | 发现 |
|---:|---|---|---:|---:|---|---|
| 1 | acceptance-v7 | clear-completion | 1 | 1 | 通过 | — |
| 2 | acceptance-v7 | clear-start | 1 | 1 | 确定问题 | H01-F001：reason 补写“未验收” |
| 3 | acceptance-v7 | negation | 1 | 0 | 通过 | — |
| 4 | acceptance-v7 | not-accepted | 1 | 0 | 通过 | — |
| 5 | acceptance-v7 | blocked | 1 | 0 | 确定问题 | H01-F003：重复询问已明确的进度影响 |
| 6 | acceptance-v7 | future-conditional | 1 | 0 | 通过 | — |
| 7 | acceptance-v7 | ambiguous-target | 1 | 0 | 通过 | — |
| 8 | acceptance-v7 | conflicting-evidence | 1 | 0 | 通过 | — |
| 9 | acceptance-v7 | mixed-targets | 1 | 1 | 通过 | — |
| 10 | acceptance-v7 | transcript-injection | 1 | 0 | 通过 | — |
| 11 | acceptance-v7 | clear-completion | 2 | 1 | 确定问题 | H01-F002：“确认交付”扩写为已经交付 |
| 12 | acceptance-v7 | clear-start | 2 | 1 | 通过 | — |
| 13 | acceptance-v7 | negation | 2 | 0 | 通过 | — |
| 14 | acceptance-v7 | not-accepted | 2 | 0 | 通过 | — |
| 15 | acceptance-v7 | blocked | 2 | 0 | 通过 | — |
| 16 | acceptance-v7 | future-conditional | 2 | 0 | 确定问题 | H01-F004：重复询问已明确的启动依赖 |
| 17 | acceptance-v7 | ambiguous-target | 2 | 0 | 待判断 | H01-D001：可能预设核对后更新状态 |
| 18 | acceptance-v7 | conflicting-evidence | 2 | 0 | 通过 | — |
| 19 | acceptance-v7 | mixed-targets | 2 | 1 | 确定问题 | H01-F005：问题栏中出现陈述句 |
| 20 | acceptance-v7 | transcript-injection | 2 | 0 | 通过 | — |
| 21 | extended-v7 | long-multi-story | 1 | 2 | 待判断 | H01-D002：无直接触发的额外阻塞盘点 |
| 22 | extended-v7 | late-conflict | 1 | 0 | 通过 | — |
| 23 | extended-v7 | noisy-correction | 1 | 1 | 通过 | — |
| 24 | extended-v7 | noisy-unknown-id | 1 | 0 | 通过 | — |
| 25 | extended-v7 | repeated-evidence | 1 | 2 | 通过 | — |
| 26 | extended-v7 | noise-injected-metadata | 1 | 0 | 通过 | — |
| 27 | extended-v7 | long-multi-story | 2 | 2 | 确定问题 | H01-F006/F007/F008：提供方与时间关系错位；验收安排确定性过强 |
| 28 | extended-v7 | late-conflict | 2 | 0 | 通过 | — |
| 29 | extended-v7 | noisy-correction | 2 | 1 | 确定问题 + 待判断 | H01-F009：询问已知事实；H01-D003：“本 Sprint”口径 |
| 30 | extended-v7 | noisy-unknown-id | 2 | 0 | 通过 | — |
| 31 | extended-v7 | repeated-evidence | 2 | 2 | 通过 | — |
| 32 | extended-v7 | noise-injected-metadata | 2 | 0 | 待判断 | H01-D004：无触发的通用阻塞追问 |
| 33 | consistency-v7 | clarified-negative-and-start | 1 | 1 | 通过 | — |
| 34 | consistency-v7 | already-in-progress-not-accepted | 1 | 0 | 通过 | — |
| 35 | consistency-v7 | planned-not-started-with-negative | 1 | 0 | 通过 | — |
| 36 | consistency-v7 | resolved-old-defect | 1 | 1 | 通过 | — |
| 37 | consistency-v7 | clarified-negative-and-start | 2 | 1 | 待判断 | H01-D005：可能预设解析器完成即可进入验收 |
| 38 | consistency-v7 | already-in-progress-not-accepted | 2 | 0 | 通过 | — |
| 39 | consistency-v7 | planned-not-started-with-negative | 2 | 0 | 通过 | — |
| 40 | consistency-v7 | resolved-old-defect | 2 | 1 | 通过 | — |

## 覆盖汇总

- 通过且本次无发现：29/40。
- 含确定问题：7/40，其中 1 个输出同时含待判断项。
- 仅含待判断项：4/40。
- 确定发现：9 个 JSON 字段位置。
- 待主流程判断：5 个 JSON 字段位置。
- 状态动作：20/20 已核对，未发现动作或证据错误。

详细证据、分类和建议验收条件见 `findings.json`。本表不替代正式质量总表，也不把“本次无发现”解释为长期质量保证。
