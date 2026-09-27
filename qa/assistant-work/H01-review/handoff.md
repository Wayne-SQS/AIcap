# H01 交接

## 1. 任务状态

- 任务：H01 现有模型结果独立复核
- 完成时间：2026-09-22T22:19:45+08:00
- 状态：`ready`
- 覆盖：40/40 个模型输出、20/20 个状态动作
- 说明：本包完成不表示已集成或正式质量验收通过；主流程核对后再决定是否采纳。

## 2. 读取基线

报告共同元数据：

- `skill_version`：`daily-status-v7`
- `model`：`deepseek-v4-flash`
- `data`：`synthetic_only`
- `repeat`：2

源文件首次读取 SHA-256：

- `ai-service/evals/daily_quality.json`  
  `42F3CAF3F663C8B9E952F0537300C7939F36DF6A40EDF93B641226AF47133E95`
- `ai-service/evals/daily_extended.json`  
  `CD2AE5F7A5D979468F42370BE0374B98106AE771FF1FB60A93DE5455B93CA721`
- `ai-service/evals/daily_consistency.json`  
  `86410C85F42502B9D96ACF3AFBD8AF56FA51356E16F03E3C5558454D873BA19E`
- `qa/.daily-quality/acceptance-v7.json`  
  `A7ADFCC077634319034B0AF629DFF81566899C8C2B01968EC79D47EEC7633EFC`
- `qa/.daily-quality/extended-v7.json`  
  `484214BEF602DBBD5C25E0BAA79849FD8CAFDFB440FBE027CD97A7CED46B8CCA`
- `qa/.daily-quality/consistency-v7.json`  
  `BCFCAD216504F98FC95C98B79B9C06CB88072A4F222E3D254221D53AF52F6BA8`
- `ai-service/meeting_agent/daily_skill.py`  
  `E9FAF3ECFB7F9591542A19C81EF4686EC34DBD1A52B49FC10F3C114A37F41B38`

每个 case/repetition 的覆盖结论见 `coverage.md`。复核未依据报告自带的 `status` / `failures` 直接判定。

**基线变化，需要复核**：交付前再次计算哈希时，六份数据集/报告哈希均未变化，但 `daily_skill.py` 已由上述初始哈希变为
`EEEFEBFAF39A4CAA5DA588E118888A233F4C318E376770AA22E25427D09FD7CC`，文件内版本已更新为 `daily-status-v8`。本包仍只复核既有
v7 报告，不追随主流程修改重新调用模型；主流程需判断下列 v7 发现是否已被 v8 规则覆盖，并通过后续独立报告验证。

## 3. 实际修改文件

本任务包内新增：

- `qa/assistant-work/H01-review/findings.json`
- `qa/assistant-work/H01-review/coverage.md`
- `qa/assistant-work/H01-review/handoff.md`

另按任务书维护：

- `qa/assistant-work/STATUS.md`

未修改生产 Skill、正式测试、数据集、质量总表、交接文档、Java、前端、配置或依赖文件。

## 4. 核心结论

### 数量

- 确定发现：9 个 JSON 字段位置，分布在 7 个输出。
- 待主流程判断：5 个 JSON 字段位置。
- 20 个状态动作的目标、`expected.status`、`changes.status`、reason 和 evidence 均已核对；未发现错误动作、漏提/多提、理由与数字动作矛盾、跨故事证据或错误 S 编号。

### 最值得先处理的 3 组问题

1. **长文本中对象关系和确定性被改写**  
   `long-multi-story / repetition 2` 把“向提供方确认时间”写成“提供方也待确认”，并把“验收安排仍需协商”写成“测试尚未安排验收”。见 H01-F006、F007、F008。该组直接改变原文事实关系，优先级最高。

2. **事实模态升级或补造验收信息**  
   `clear-start / repetition 1` 在 reason 中补写“未验收”；`clear-completion / repetition 2` 把“确认交付”写成已经交付。见 H01-F001、F002。动作本身正确，但解释和摘要会误导审核者。

3. **`open_questions` 混入已回答事实或陈述句**  
   包括重复询问明确阻塞影响、明确依赖关系、已知的“今天开始/尚未验收”，以及把“原文未说明阻塞”直接放入问题栏。见 H01-F003、F004、F005、F009。建议在生成前增加“是否真是问题、是否已被原文回答”的逐句检查。

### 与既有质量记录的关系

既有问题，本次独立确认：

- clear-start 的“未验收”扩写（H01-F001）。
- long-multi-story 的提供方/时间关系错位及验收安排过强（H01-F006、F007）。
- 问题栏存在陈述式内容（H01-F005）。

本次新增或进一步定位：

- “确认交付”被写成已交付（H01-F002）。
- 多个开放问题重复询问已明确事实（H01-F003、F004、F009）。
- 同一提供方误判同时出现在摘要和问题栏（H01-F008）。

以上均未宣称已解决；`findings.json` 保存逐项原句、S 编号/快照依据及建议验收条件。

## 5. 已运行检查

已执行：

- 逐条人工语义复核：40/40 输出。
- 状态动作人工核对：20/20。
- 七个源文件 SHA-256 首次记录及交付前复核。
- 交付前确认三份数据集和三份 v7 报告未变化；检测并记录生产 Skill 已切换到 v8。
- `findings.json` JSON 解析、必填字段、发现计数检查。
- `coverage.md` 的 40 个 case/repetition 唯一覆盖检查。
- 修改范围检查：仅 `qa/assistant-work/`。

未执行：

- 未调用真实模型；复用三份现有 v7 报告。
- 未运行生产 Python 测试、Java 测试或前端浏览器测试；H01 不修改相关代码。
- 未读取 `.env` 或密钥。
- 未连接业务数据库，未启动/停止任何共享服务。

## 6. 留给主流程的决定

请先决定以下产品策略，再把待判断项转为正式缺陷或放行：

1. `open_questions` 是否只允许原文触发的问题，还是允许主动做通用阻塞/风险盘点（H01-D002、D004）。
2. `current_sprint=null` 时，能否依据故事的 `sprint` 字段使用“本 Sprint”措辞（H01-D003）。
3. 问题是否可以表述潜在后续动作，还是必须避免“核对后更新状态”“完成解析器以进入验收”等路径预设（H01-D001、D005）。

建议最小集成步骤：

1. 主流程核对 H01-F001～F009，选取确定需要约束的事实关系。
2. 对 H01-D001～D005 给出产品判断，不由助手擅自定规则。
3. 确认缺陷后再分派 H03 制作换编号、换措辞的正反例；不要直接把本报告原句复制成唯一测试。

本包现已冻结；后续更正应使用单独补充文件并注明替代关系。
