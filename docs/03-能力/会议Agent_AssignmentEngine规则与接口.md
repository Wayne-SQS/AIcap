# Assignment Engine v1：只读候选排序

2026-10-01：原共享对话无法恢复，本地仅保留阶段路线和容量边界。用户明确授权“原对话已丢失，请自行决定吧”。以下评分规则为本轮新确定的最小实现，不冒充原对话已规定的算法。

## 当前规则

一次针对一个明确故事，调用方提供目标Sprint（未知必须传null）和所需技能。需求来源标记caller_supplied，表示本次人工输入，不声称需求已经包含于故事或会议证据中。

技能分tech_stack、capabilities、process_domains三个维度；每条指定名称和最低等级1–5。名称仅去首尾空白并casefold，不用模糊匹配、同义词或模型猜测。维度不同不互相替代。重名要求拒绝，防止通过重复技能改变权重。

每项要求等权：画像记录等级达到最低等级则计1，否则计0。输出matched_requirements/total_requirements作为匹配比例的分子/分母，按满足数降序。相同满足数并列（1、1、3）；成员ID只稳定展示顺序，不表示优先选人。超出最低等级不额外加分，年限、姓名、简介和职位不进入分数。

候选范围为admin/owner/member；viewer明确排除。本轮不将权限角色当作技能证据。未找到技能记录用recorded_level=null表示，不等于实际能力为0；零项满足返回no_recorded_skill_match。要求列表为空返回requirements_needed且不排名；无可选成员返回no_eligible_members。存在匹配时只返回provisional，不自动挑选负责人。

容量始终capacity_check=unknown，候选都requires_human_review。六周容量按原单位保留，不除以3变成Sprint容量，不按任务progress推算剩余工时。Story没有估时来源；Task的hours和estimated_hours分开保留，不代替故事估时。当前不能判定某人“有空”“超载”或给出可执行分配结论。

## 数据准备

复用Java授权会议读取与已有故事、成员画像、任务Tools，不直连数据库。三个项目读取顺序执行，snapshot_consistency=sequential_reads，不是原子快照。任一步失败不返回部分结果；未找到所选故事返回422，不猜测替代对象。最多100个数据准备目标，排序仅一个。

成员事实包括已关联故事编号、未完成任务编号（status0/1）、与目标Sprint重叠的任务编号。任务2/3不列入active，但原始任务仍完整返回；跨Sprint任务不分摊工时。目标Sprint未知或Sprint4时，目标任务列表为null；已知1–3且读取成功没有对应任务才为[]。Task周排期仅涵盖前三个Sprint，不能把Sprint4解释为无人有负载。

空画像维度、无成员、失效owner_id、失效kanban_card_id、故事估时/容量缺失均列出gap。story_ref、depends_on仍为原始文本，未声称已解析全部依赖或检测环路。未分配owner_id=null保留，不当作失效成员。报告包括所有成员事实，不能将context.members当作可分配候选。

## API

均为Python服务端点，沿用Java身份/会议授权：admin/owner/member可调用，viewer403，未登录401。POST只用于提交计算条件，内部只有GET读取，无模型调用、持久化或业务写入。

`POST /api/meetings/{meeting_id}/assignment/context`

```json
{"story_ids":["US13","US14"],"target_sprint":2}
```

返回selected_stories、members、tasks、gaps。status=not_ready表示执行所需数据未完整，即使成员事实存在也不能视为可直接分配。

`POST /api/meetings/{meeting_id}/assignment/recommendations`

```json
{
  "story_ids":["US13"],
  "target_sprint":2,
  "requirements":[
    {"dimension":"tech_stack","name":"Python","minimum_level":3},
    {"dimension":"capabilities","name":"测试","minimum_level":2}
  ]
}
```

返回rule_version=assignment-skills-v1、status、context、candidates、excluded_viewer_ids及writes_performed=false。每个候选含rank、匹配数量和逐项requirement/recorded_level/meets_requirement。本接口不接受approved、owner_id、客户端画像/容量等额外字段。需求列表必须显式提供，可以为空。

前端通过现有/meeting-ai代理及登录令牌调用。在AI页面选择已保存会议后，点击“打开分配候选”，选择故事、目标Sprint（可保持未知），逐项填写技能维度、名称和最低等级（可调整的初始值为3）。至少填写一项，最多20项；同维度重名拒绝提交。页面显示同分并列排名、逐项匹配依据、关联故事/未完成任务及容量缺口。画像等级超过阈值不增加分数。

只读查询结果仅保留在当前页面，修改条件、切换会议、收起或刷新后需重新查询；失败保留输入以便重试。旧会议的迟到响应不会显示到新会议。页面另有“保存分配建议”，会重新读取事实、计算并保存；在下方历史记录中核对保存时的技能和任务负载，再选择候选审核，最后单独执行。不得将只读结果直接提交普通故事PATCH代替审核。

## 建议持久化和审核API（第六十二轮）

Python新增 `POST /api/meetings/{meeting_id}/assignment/suggestions`，沿用上面的story_ids、target_sprint和requirements，额外要求client_request_id（小写字母/数字/连字符，1–80字符）。身份要求admin/owner/member。此接口重新读取事实并计算候选，保存本次快照；不接收浏览器提供的排名或批准字段，不保证与之前只读查询的快照相同。无模型调用、无故事变更。

先按会议、提交人和请求号查询已有记录，输入相同则恢复原记录，不读取新故事或覆盖快照；输入不同返回409。保存瞬时失败只重发完全相同载荷一次，仍失败报告storage_outcome_unknown，客户端必须使用原请求号恢复，不能把未收到响应视为未保存。返回id、meeting_id、client_request_id、submitted_by、created_at、input、result和review（未审时null）。result.writes_performed=false描述候选计算未写业务数据，本接口本身会写建议表。

Java端点：

| 方法与路径（相对 `/api/meetings/{meetingId}/assignment-suggestions`） | 作用及角色 |
| --- | --- |
| POST `/` | 保存 `{client_request_id,input,result}` 快照；admin/owner/member |
| GET `/`、GET `/{id}` | 当前会议列表/详情；所有登录角色 |
| GET `/by-request/{key}` | 按当前提交人恢复请求；admin/owner/member |
| POST `/{id}/review` | 选择候选并批准或拒绝；仅admin/owner |
| POST `/{id}/execute` | 执行已批准分配，请求体仅 `{}`；仅admin/owner |

Java保存端点沿用既有分析入库模式，接受授权提交方的快照，校验会议/故事归属、要求、阈值依据、排序/并列名次和结果状态，不将其宣称为数据库原子事实。首次保存锁定会议和故事，核对id/title/status/sprint/owner_id；重复提交校验完整输入和结果一致性，保留原记录。Python还会按保存响应中的输入和上下文重新计算并校验候选结果。

审核请求例：

```json
{"decision":"approve","member_id":7,"reason":"已人工核对分工；容量仍待执行前确认","capacity_acknowledged":true}
```

批准要求成员来自保存的候选列表、当前角色仍为admin/owner/member，故事title/status/sprint/owner_id仍与快照一致；必须填写理由并明确知悉容量未验证。可选择非第一名或无技能匹配记录的候选，决定由审核人承担并记录理由。capacity_acknowledged仅表示知悉未知，不把容量标为可行。

拒绝使用decision=reject、member_id=null，仍须理由；capacity_acknowledged为显式布尔值，可以false。拒绝不要求故事仍存在。审核决定一经保存不可覆盖；同审核人相同请求返回原决定，其他请求409。审核本身不修改故事或生成故事日志。

`db/assignment-analyses.sql`由Java启动自举建表，已纳入application.yml。外键及MeetingDeletionGuard保护已保存建议的会议；只读查询仍不阻止删除。需要重新打包、重启Java与Python服务后新API才可用。

## 页面闭环与独立执行（第六十三轮）

保存前将完整输入和请求号写入按用户/会议隔离的sessionStorage；浏览器不允许写入时不发请求。结果不确定时可刷新页面，点击“重试保存分配建议”恢复原请求，即使表单条件变化也不替换原请求内容。浏览器会话存储清除后可从已保存历史核对结果。

历史区显示记录号、提交人、时间、故事、技能依据、保存时任务负载。所有登录角色可读；只有admin/owner能填写审核理由、选择成员并批准或拒绝。未确认的审核可重试同一决定，也可刷新记录查看服务端决定。切换会议后旧组件的异步结果不更新新会议。

点击“执行已批准分配”只发送空对象，服务端在锁定建议后读取已保存审核；拒绝、未审或客户端带owner_id等额外字段均不能执行。再次锁定并检查所选成员当前角色、故事title/status/sprint/owner_id，已变化返回409；负责人未变化也返回409，不生成虚假变更日志。只更新owner_id，不变更故事Sprint/状态，不分配关联任务。

负责人更新、story_logs的edit日志及review_json中的execution审计同事务提交；任何一步失败均回滚。审核input/reviewed_by/reviewed_at保留，执行后execution_status=succeeded并追加execution对象，包含suggestion_id、meeting_id、story_id、previous_owner_id、new_owner_id、story_log_id、executed_by、executed_at。并发或重复执行返回同一审计，不重复写日志。结果中的writes_performed=false仍表示最初候选计算过程只读，不表示后续执行未变更业务。

页面校验保存输入、审核决定、执行归属与日志/执行人/时间后才显示成功。成功后刷新看板；若看板刷新失败，只重试读取。网络响应丢失可刷新历史恢复已提交审计。此阶段仍未自动计算容量可行性，也不重新核验技能画像/任务负载是否变化；它们是人工参考快照，执行硬约束是故事快照和成员角色。

## 验证范围

后端17项测试：6项数据规则、5项鉴权/API、6项排序。覆盖原单位/未知数据、多故事负载、任务完成/取消、Sprint4、悬空关联、角色、同分、阈值、维度隔离、空要求及非法输入。使用真实FastAPI与本地Java HTTP夹具，以及确定性纯函数。

前端新增8项Edge浏览器夹具测试，覆盖并列排名及输入传递、未知容量、条件变化清空结果、重复要求、服务失败重试、错误归属/技能依据、只读角色、切换会议隔离迟到响应、目录失败重试。Assignment尚未进行真实Java/MySQL端到端联调，不调用真实模型。

第六十二轮新增Python持久化5项、Java保存审核9项，并扩展会议删除保护1项。Python全量225项，Java隔离分配/五类会议/删除回归105项通过；Java打包、前端构建和删除提示浏览器定向1项通过。Java完整测试集因旧MySQL集成用例数据库连接失败未通过；本轮使用H2真实事务和本地Java HTTP夹具验证，未运行真实MySQL端到端。

第六十三轮新增Java执行5项（并发、幂等、权限/非法输入、审核后业务冲突、日志失败回滚），隔离回归110/110。新增浏览器闭环10项；全量78/78后，补充负载展示及两项故障测试，定向10/10通过，合计80个独立用例已验证。Java打包、最终前端构建和git diff --check通过。本轮Python未改，沿用前轮225项基线；仍未进行Assignment真实MySQL端到端联调。

第六十四轮完成真实浏览器/Python/Java/JWT/MySQL8.0.31闭环，包含并发重试、拒绝、审核后故事冲突、权限、删除保护和刷新审计。最终1/1通过，3条建议中执行成功1条、拒绝1条、冲突未执行1条，分配日志1条，模型调用0；证据qa/.daily-live/237afe831f7a4fcbb75b374baf67e110/，复现及限制见[真实服务验收](../qa/DAILY_LIVE.md)。
