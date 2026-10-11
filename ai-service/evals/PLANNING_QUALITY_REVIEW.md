# Planning 真实模型首轮验收（2026-09-26）

模型deepseek-v4-flash；只发送8例合成数据，无业务写入。报告保存在Git忽略目录ai-service/.planning-quality/。

## v1
planning-v1-first.json：8/8自动通过，人工不通过。known-relative摘要及问题、capacity-unknown问题把Story.sprint=2与Task.sprints=[2]称为不同步；快照实际一致。move-out-of-target用“无额外待确认事项”占位，其他案例有大量模板化缺字段提问。

## v2
planning_skill.py提升至planning-sprint-v2：明确现有快照比较与未执行提案目标的区别，周数不能当Sprint；无问题返回[]，只提相关待确认事项。
planning-v2-repeat.json：8例各2次，16/16自动通过。逐份复核全部摘要、理由、问题及证据：
- 3类正例均提出正确Sprint调整；未知相对范围、冲突、否定、无变化、容量未知均无错误动作。
- 现有Sprint一致性误报未重现；未用六周容量/任务工时推算剩余容量，未声称已批准或执行。
- 未出现“无待确认事项”占位问题。
- 残留事实范围问题：conditional-negative第1次将依赖称为“外部依赖”（原文未限定）；第2次问题将未说明的依赖内容/责任/满足条件写为“尚不明确”，扩大了原文事实。
- 仍有冗余缺字段说明、较长核对问题和审批提醒，不将自动通过包装为全文语义通过。

结论：保留v2（修复已观察到的快照错误），人工质量仍需改进。样本小且只有2次重复，不代表普遍可靠性。下一轮针对残留问题再做限定改进；不得通过更改Oracle掩盖问题。

报告SHA256：
- planning-v1-first.json: B7B9B7F6C7C85567F2636D2B5ED0D4584EF8E7CCE5AFEE638EFE490954D3DBAE
- planning-v2-repeat.json: 1AF54BD46EC90740E1A91F6C7F0B20E7CB80A2E0523E275239A1BDDB66FEC170

## v3：依赖范围修正与回归

planning-sprint-v3补充：未说明不等于尚未明确；不推断依赖内外部；直接询问缺失细节，不在问题前提断言责任未知；保留已知提供方/负责人及仅待确认的时间范围。

新增planning_dependency.json共4例：未注明依赖类型、明确外部提供方但只待交付时间、内部依赖已解决、外部依赖已解决。后两例为必须生成调整的正例，防止通过全弃权规避错误。旧8例及Oracle未改。

真实模型deepseek-v4-flash：
- planning-v3-dependency.json：4例各2次，8/8自动通过。
- planning-v3-baseline.json：原8例各2次，15/16自动通过。move-out-of-target第1次invalid_model_response，无可验证result；第2次正常通过。没有重跑覆盖失败，也不猜测错误是JSON、信封或字段哪一种，现有适配器错误粒度无法区分。
- 人工逐份核对23个有效结果的摘要/理由/问题/引用：已观察到的擅加外部依赖、把未说明责任写成尚不明确、将已知提供方写成未知，均未重现。明确内外部和依赖已解决的事实均保留；没有错误迁移、全弃权正例、虚构容量或Sprint一致性误报。
- 尚有冗余缺字段说明；known-relative第2次摘要在列举快照内容后写“未说明负责人”，会议确实未提但快照owner_id=7已提供，来源表述容易混淆，列为残留质量问题。explicit-external-provider第1次摘要“未说明该故事的Sprint调整”也不够准确（原文明确暂不调整）；前句保留了不调整约束，但仍需精简重复概括。

保留v3作为本轮依赖范围改善版本，不宣称整体质量通过或生产就绪。129项Python测试通过；离线fixture通过不计真实模型通过率。下一步完善模型返回格式错误的脱敏诊断，查清invalid_model_response后再安排有针对性的复验，不扩大输出日志到密钥/原始HTTP报文。

本轮报告SHA256：
- planning-v3-dependency.json: DE24857703571FA27C8113DDA4F5310AD9366B49C9FF4940B55B696A4D3BA615
- planning-v3-baseline.json: 10AA79F16B997C0D1CC76A326202F288294D2FEDCF4392EB0BABBCDE9B55ABD3

## 格式诊断补强（第三十三轮）

model_client.py保留原有ModelError.code/异常文字，对invalid_model_response新增白名单diagnostic标签：envelope/content的json_syntax、duplicate_key、invalid_encoding、too_deep、structure，以及choices_structure、finish_reason_missing、message_structure、unexpected_message、content_not_text。仅输出固定标签，不记录供应商正文、任意字段名、令牌或原始异常消息。Planning评价报告新增model_diagnostic；Daily API/错误码兼容，Skill仍v3。

新增test_model_diagnostics.py验证11类异常、标签白名单与有效结果；Planning报告测试检查错误标签落盘且无无效result。Python131项通过。测试控制台的0/8与provider_timeout来自故障注入，不是真实模型结果。

将原move-out-of-target案例原样提取到.planning-quality/format-target-case.json，计划定向运行3次。真实调用收到provider_http_error，入口立即停止，无有效case结果；报告planning-v3-format-target.json保留。不能把本次阻断解释成原来的格式错误，也不能声称错误已经修复或3次通过。旧planning-v3-baseline.json的1次invalid_model_response仍无法追溯细分原因，因为当时没有诊断标签和原始报文，未覆盖旧报告。

下一步：核对供应商请求可用性并在恢复后完成这3次定向复验；若格式错误再现，依据新标签做有界修复，不盲目重跑到全绿。仍保留此前摘要冗余/来源混淆待办。
- planning-v3-format-target.json SHA256: C3B981A0FD1EAA3F6CA4A16A0603445E74CA5941C56BDDE7863A4DFF379785AB

## 2026-09-27 API恢复检查
用户更新API配置后，planning-v3-new-key-20260927.json单次真实调用1/1通过；人工核对目标4、证据、摘要通过。旧invalid_model_response仍保留为历史失败，不继续为追求通过率反复运行。本轮转向Planning后端闭环。
