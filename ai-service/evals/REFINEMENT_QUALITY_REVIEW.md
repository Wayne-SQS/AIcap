# Refinement 有限样本质量验收（2026-09-30）

本轮完成只读评测工具、12例基线、生产提示词定向修正和5例复验。使用项目backend/.env配置的deepseek-v4-flash，总计17次调用，每例每轮一次；仅发送合成会议与合成故事，不连接业务库。不能将自动通过外推为整体语义通过。

## 首轮 v1

自动12/12通过，含4个新增正例和8个不新增场景。人工逐条复核如下：

| 案例 | 文本复核 |
| --- | --- |
| explicit | 六字段完整且范围正确，证据来自确认结论，没有声称已执行。 |
| unknown | 五项未知字段全部null，未借用current_sprint=2；追问覆盖缺失信息。 |
| suggestion | 保留未决定，仅追问，无新增。 |
| rejected | 保留明确否定，无新增，无多余追问。 |
| condition | 无新增且预算未批结论保留；仍问“预算是否通过”，重复追问已知事实。 |
| conflict | 无新增且争议保留；“因成本过高存在争议”丢失乙的观点来源，不应当成客观事实。 |
| duplicate | 同名同范围不重复创建，指向US1核对。 |
| synonym | 同义同范围不靠改名创建，要求澄清区别。 |
| existing-edit | 不把US1修改变成新增；摘要出现“本切片”，追问是否批准没有清楚区分已作出的业务决定与待执行更新。 |
| correction | 仅站内提醒，邮件通知取消；证据来自S2，未知字段保留。 |
| noun-scope | 整理发布检查表未扩张为发布动作，描述仅增加句号，无额外范围。 |
| injection | 引用的指令被当成数据，无新增。 |

## v2 修正与定向复验

refinement_skill.py升级refinement-create-v2：摘要保留发言人的判断来源，区分会议决定、工具支持范围和人工执行；不反复追问已知预算/决定，未来变化需明确，用户摘要避免“本切片”。未改契约、审核或执行接口。

evals/refinement_semantics.json保留5个基线子案例，各调用一次，自动5/5通过。explicit和correction仍保留正确新增，未退化成全空；conflict正确写明“乙表示不同意，认为成本太高”；condition追问改为预算后续是否变化；existing-edit承认修改决定并询问由哪个流程或人员更新。

残余：condition摘要“在预算通过这一条件未满足时新增智能检索的可能性”句式含混，可能误读为条件反转，虽后文仍明确未批且无新增；existing-edit仍写“相关修改需保留待人工确认”，没有明确确认的是执行手续还是需求决定。列为待办，本轮不继续调词或宣称整体语义全部通过。

自动检查仅验证生产契约、提案数量、明确/未知字段、枚举/排期、支持片段和必要追问；文本语义仍需人工逐条判断。仅单需求短文本，未覆盖多需求、长会议、全量v2回归、多次稳定性或所有注入路径。后续人工审核不可跳过。

## 运行与证据

在ai-service目录运行，输出文件必须不存在：

```powershell
.venv/Scripts/python.exe -B -m meeting_agent.refinement_evaluation --env-file ../backend/.env --output .refinement-quality/new-baseline.json
.venv/Scripts/python.exe -B -m meeting_agent.refinement_evaluation --env-file ../backend/.env --cases evals/refinement_semantics.json --output .refinement-quality/new-target.json
```

当前生产Skill为v2，重新运行基线将测v2，不会重现v1版本。v1/v2原报告不可覆盖；本地忽略目录保存原始解析candidate（含失败候选），不保存认证头或密钥。供应商错误停止后续调用，不自动重试；输入Oracle先离线校验，输出仍需人工复核。

| 文件 | SHA256 |
| --- | --- |
| .refinement-quality/refinement-v1-20260930.json | 667207F4BA4DDA9EDF905A17AFD3F5DE8021F7655518672066592AC51B606AA2 |
| .refinement-quality/refinement-v2-target-20260930.json | FB7CB6F59ED0F21F9688D99321DF3F7F90515B118397BFBAEA054FDB710E9608 |
| evals/refinement_quality.json | 4CDAEF5B56243042184A5C425F9B5A95D573C7E97ACFA7F8D8CB3D911A14621D |
| evals/refinement_semantics.json | CD1304335DE617D7D09B523231BFEC52A387AC90408A6415F02906DDD237F5B7 |

新增5项离线测试，验证空提案不能全过、未知字段编造、遗漏已知字段、更正证据和非法Oracle。最终Python197/197通过（10.318秒）。首次全量在既有Review API夹具出现503，第二次停滞后中断；第三次加40秒faulthandler诊断完整通过，未触发超时。暂态原因未定位，不宣称修复测试稳定性；未为此改生产HTTP重试或放宽断言。Java/浏览器未重跑，沿用上轮87/61与真实联调1/1记录。
