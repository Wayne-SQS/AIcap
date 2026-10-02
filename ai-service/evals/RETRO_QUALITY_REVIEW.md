# Retro 首轮真实模型验收 · 2026-09-29

结论：自动检查8/8通过，人工文本复核发现1项行动范围扩张，整体语义验收未通过。生产Skill保持`retro-action-v1`，本轮没有修改提示词或为追求通过重跑。

输入为8个新建合成案例，仅包含原文和最小成员ID/姓名；每例调用一次现有配置的`deepseek-v4-flash`，总计8次。初次启动误用不存在的ai-service/.env，读取配置前失败，未产生模型调用；随后沿用项目实际backend/.env。没有连接业务数据库或发送真实会议。

## 复现和证据

在ai-service目录执行，输出路径必须不存在：

```powershell
.venv/Scripts/python.exe -B -m meeting_agent.retro_evaluation --env-file ../backend/.env --output .retro-quality/new-report.json
```

原报告：`.retro-quality/retro-v1-20260929.json`（按忽略规则本地保留）；记录原始解析后的candidate与校验结果，即使契约失败也保留合成输出，不保存密钥或HTTP认证头。

- 报告SHA256：`53C2620A770529C6F253CEA10F0F3B32A8EAA60EA44B7954342CD9EEE39E3110`
- 案例`evals/retro_quality.json` SHA256：`F02E430557C974A19EF7F217A489D11C0A184FE0B2925EB649A7954927D0B5C1`
- Python176项通过，新增3项离线测试：案例Oracle有效性/空提案不能全过、提及姓名不等于负责人、非法预期提前拒绝。

自动检查仅证明契约、行动数量、负责人、截止文本、支持片段及必要追问存在；标题/描述/决议/摘要/追问的真实含义仍须逐条复核。

## 逐例复核

| 案例 | 自动 | 文本复核 |
|---|---|---|
| retro-explicit | 通过 | 行动为完善发布检查表，张敏ID1、下周五前；未推算日期或声称已完成，符合预期。 |
| retro-suggestion | 通过 | 保持建议未采纳，无决议/行动；未推断个人能力，符合预期。 |
| retro-rejected | 通过 | 记录不采用重写的决议，无重写行动，符合预期。 |
| retro-condition | 通过 | 保留预算未批及条件，无行动；追问仍有“如果试点”限定，未断言已批。 |
| retro-unknown | 通过 | 保留明确行动，负责人/时间null并分别追问，符合预期。 |
| retro-same-name | 通过 | **不通过**：同名ID未乱选、时间正确，但原文“整理发布检查表”被改为“整理并发布检查表”，description也增加发布动作，扩大工作范围。 |
| retro-mentioned-person | 通过 | 未把反馈人指定为负责人，null/追问正确；明确行动保留，符合预期。 |
| retro-conflict | 通过 | 保留甲乙不同说法和主持人未决定，无决议/行动；“方案具体内容及争论点尚未明确”把未提供细节概括成尚未明确，属轻微文本问题。 |

## 下一步

优先修正行动对象/动词边界：不能把“发布检查表”等名词对象拆成新增“发布”动作。补“整理发布检查表”和“整理并发布检查表”的正反对照，保证显式发布决定仍保留。进行一次有界定向复验，旧报告不可覆盖。轻微追问概括同时记录，不为措辞反复打磨。

本次为每例一次、单行动场景，未覆盖多行动、长原文、重复稳定性或全部攻击输入；不能外推为Retro全部质量通过。

## v2 定向修正与复验（2026-09-29）

retro_skill.py升级retro-action-v2，要求标题、描述、理由、决议和摘要保持原动词/对象/范围，不把事项名称拆成额外动作，也不漏掉明确要求的动作；未提供的细节不能断言现实中尚未明确。

新增retro_semantics.json四例：整理发布检查表、明确整理并发布、完善部署文档、发布建议尚未采纳。加上原retro-same-name和retro-conflict，共6例各调用一次deepseek-v4-flash；自动6/6通过，Python177项通过。测试验证正反案例预期、支持证据和时间检查；动作语义仍由逐条文本复核判断。

| 案例 | 定向复核 |
|---|---|
| retro-same-name | 标题/描述为“整理发布检查表”，未新增发布；owner为null，保留同名追问。摘要却称“参会成员中存在两位同名张敏”，将项目成员目录误当参会名单，列为残余文本问题。 |
| retro-conflict | 仍保留不同来源与未决定，无行动；不再断言方案细节尚未明确。两个追问内容相近，暂不继续优化。 |
| retro-object-noun | 正确保留“整理发布检查表”及负责人/截止时间，无新增发布步骤。 |
| retro-explicit-publish | 单个行动完整保留整理、发布两步，无漏动作。摘要仅“未记录其他复盘讨论内容”，没有概括已明确的决定，属于摘要质量缺陷。 |
| retro-deployment-doc | 仅完善部署文档，未新增部署或通知步骤；摘要概括较泛，决议及行动正确。 |
| retro-publish-not-adopted | 仍为建议，无行动或采纳决议，保留待确认。 |

目标行动范围扩张在本次6例中未重现，且没有退化成全空提案；不宣称整体语义全部通过。剩余摘要/成员身份表述问题作为待办保留，不扩大本轮范围或继续重跑。后续按既定路线进入Refinement，保留人工审核。

报告`.retro-quality/retro-v2-target-20260929.json` SHA256：`773BF94052F04E360787BFFD20719837F7150FFCA92721616CEE8DA5F86DB6D1`。
组合案例`.retro-quality/retro-v2-target-cases-20260929.json` SHA256：`DDBB4A171B8277023471C669EBBCD0F245F6CF2FAAD4A6DF2E46D5AB11804E1F`。
旧v1报告哈希复核未变。复现命令（需保留组合案例，输出使用新路径）：

```powershell
.venv/Scripts/python.exe -B -m meeting_agent.retro_evaluation --env-file ../backend/.env --cases .retro-quality/retro-v2-target-cases-20260929.json --output .retro-quality/new-target-report.json
```

未做原8例全量重跑或多次稳定性检查，模型调用没有业务写入。
