# Daily / Planning / Review / Retro / Refinement 真实服务联调

## 可选：Refinement真实模型闭环

默认运行仍使用固定模型，不产生供应商调用。要验证真实模型到页面审核执行的完整链路，在仓库根目录明确指定：

```powershell
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite refinement --real-model --env-file backend/.env
```

此模式仅支持Refinement，模型配置只在测试网关进程读取；子服务使用本地网关及假密钥。通过生产ChatModelClient转发生产Skill消息，不替换模型内容。输入为合成会议和新建隔离库的种子故事，最多一次真实供应商尝试；失败也消耗预算，后续请求429，不自动重复付费调用。不指定--real-model时不会读取--env-file；错误参数组合在建库前拒绝。

浏览器脚本自动操作人工审核界面，测试填写预先确定的补充内容，随后单独执行。这是对人工审核流程的自动化验证，不代表模型得到自行批准权限，也不是真实用户现场验收。模型随机提案编号由保存结果读取，不再固定为p1。若模型遗漏候选、编造字段或生成不同标题，测试失败，不能用夹具替代后声称通过。

2026-10-01归档：真实模型deepseek-v4-flash闭环1/1通过，调用1次，MySQL8.0.31，counts为1/1/1/1/1。证据目录 `qa/.daily-live/77fda1eea578435089ab528b0d8ffa09/`。原始报告和日志保留其生成时间；本次归档不改写时间戳。

逐项复核：模型提出“导出周报CSV”，描述/验收/优先级/Sprint/活动全部null；引用S1连续原文，追问缺失字段，无默认排期。页面补齐后批准，故事仍不变；执行创建US38（待办、无负责人）、创建日志#1。并发重复执行返回同一结果，分析重试不重调模型；原始分析未变，原故事/任务/成员未变，权限及刷新后看板审计检查通过。

| 证据文件 | SHA256 |
| --- | --- |
| model-candidate.json（原模型解析结果） | 2A82FE37543CB88788861A77A6FE6CA18F2CA21DF94362E759E25EFF022EA25C |
| refinement-analysis.json（已保存分析） | 2D4C1B21EEEEFA56B3A7D594157E25F85B24E7516854297162ACE2625A1719CB |
| refinement-execution.json（审核/执行/故事/日志） | 33D9C798EE6E0B6C40BC26CBB0122621129A2E43C0EF3D17871A1F4B4EE4BE5F |

两项网关测试验证成功和失败均只能调用一次且不回显供应商异常：`ai-service/.venv/Scripts/python.exe -B -m unittest discover -s qa -p test_live_model_gate.py -v`，2/2通过。服务编排已采用start_service.py --environment-only，在隔离真实Java环境验证启动入口。单次简短正例不证明长会议、复杂争议或所有模型输出稳定；质量报告中的语义待办仍保留。

## 默认固定模型模式

`run_daily_live.py` 启动独立 MySQL 数据目录、真实 Spring Boot、FastAPI、Vite 和 Edge，使用本机 HTTP 模型夹具，运行分析保存 → 人工审核 → 执行 → 看板核对。浏览器 API 不做 mock，模型输出固定用于核对业务链路，不代表真实模型效果。

## 运行

前置：Windows、MySQL Server 8.0、Java21+、Node、Edge；已安装 frontend 与 ai-service/.venv 依赖。MySQL 默认使用本机安装目录，可通过 `AICAP_TEST_MYSQL_HOME` 覆盖；Java 默认使用本机 IDEA jbr，可通过 `AICAP_TEST_JAVA_HOME` 覆盖。

先在 java-backend 打包（使用可用 Java21，仅本次编译覆盖默认 Java23）：

```powershell
$env:JAVA_HOME='D:/LHWYAN/download/IntelliJ IDEA 2025.1.2/jbr'
& 'D:/LHWYAN/download/Java/apache-maven-3.9.15/bin/mvn.cmd' -B '-Djava.version=21' '-Dmaven.compiler.source=21' '-Dmaven.compiler.target=21' '-DskipTests' package
```

再在仓库根目录执行：

```powershell
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py
# Planning 使用另一份全新数据库，端口相同，两套用例应顺序运行：
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite planning
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite review
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite retro
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite refinement
```

## 隔离与结果

- 使用专用端口：MySQL 13317、Java 18180、Python 18190、模型夹具 18191、Vite 15173。端口存在监听则停止，不复用现有服务。
- 每次创建 `qa/.daily-live/<随机编号>/mysql`；使用空库 `aicap_daily_e2e`。不连接已有 3306/3307 数据库，不运行旧清库脚本。
- 临时数据库只监听 loopback，初始化 root 空密码仅供本轮实例；Java 使用种子演示用户和独立测试 JWT secret。
- 结束时核对 `@@datadir` 后关闭该 MySQL，并停止本轮创建的服务。数据和日志保留在被 Git 忽略的运行目录，方便复核；运行目录会占用磁盘空间。
- `browser.log` 保存浏览器报告；成功时生成 `result.json`，记录 MySQL 版本、模型调用次数以及数据库计数。
- 验证故事在分析/审核后仍为 0，执行后为 1；分析、审核、执行和 move 日志各一条。已保存分析重试不再调用模型；执行重试返回首次结果；匿名/只读越权请求被拒绝；切换看板可见最新状态。

## Planning 验证范围

`--suite planning` 只运行 `frontend/e2e-live/planning-flow.spec.js`，默认 Daily 只运行原 Daily 用例。两者复用隔离基础设施，每次均生成新数据目录。

- 真实登录、保存会议转录、读取故事/成员/任务、Python 分析并由 Java 保存。
- 模型夹具建议 US13 从 Sprint 2 调整到 Sprint 3；人工修改为 Sprint 4 并批准，点击执行后才写入。分析和审核后故事完整记录保持不变。
- 执行只改变故事 Sprint，故事状态及任务列表保持不变；数据库分析、审核、执行、edit 日志各一条，日志 ID 与执行审计匹配。
- 重复执行返回首次结果；重试已保存分析返回原记录，模型总计仅调用一次；原始分析保持不变。
- 匿名执行返回 401，只读用户执行和分析返回 403；页面刷新仍显示审计日志，故事地图中 US13 位于 Sprint 4。
- Planning 的 `result.json` 中 `counts` 依次为故事 Sprint、故事状态、分析数、审核数、执行数、edit 日志数，应为 `["4", "0", "1", "1", "1", "1"]`。

2026-09-28：Planning 在本机 MySQL 8.0.31 上通过（浏览器 1/1，模型夹具调用 1 次）。本地证据目录：`qa/.daily-live/9837015ff42f4cdb9639289e4b6c08a1/`，包含服务日志、浏览器报告和 `result.json`，按现有规则忽略，不提交测试数据库。本次验证业务闭环，真实 DeepSeek 输出质量由独立模型评测覆盖。

同日 Daily 回归通过（浏览器 1/1，模型夹具调用 1 次）；证据目录：`qa/.daily-live/fc548cedb2f546d8b6fda32c3b7199b9/`。

`frontend/playwright.live.config.js` 应由脚本调用，避免误将 live 用例指向日常数据。模拟接口回归另用 `playwright.status.config.js`。

## Review 验证范围

`--suite review` 只运行 `frontend/e2e-live/review-flow.spec.js`，同样使用全新数据目录；三套场景使用相同测试端口，应顺序执行。

- 真实登录并保存“US13已完成开发并验收通过。”，通过Review页面触发Python分析及Java保存。
- 种子故事仍为待办0；会议陈述与旧看板状态不同，通过人工审核决定是否同步为完成2。此场景不证明模型能自行识别验收。
- 人工修订提案理由并说明修改原因，批准前后故事完整记录保持不变；单独执行后仅status变成2，Sprint及任务列表不变。
- 验证原始分析不可变、执行重试返回相同审计、分析重试不重调模型、日志ID对应、匿名401/只读403。
- 刷新后审计仍可见，故事地图显示已完成且仍在Sprint2。
- `result.json` 的counts依次为故事状态、Sprint、分析数、审核数、执行数、move日志数，应为 `["2", "2", "1", "1", "1", "1"]`。

模型仍为确定性HTTP夹具，浏览器业务接口不mock；真实Review模型语义质量需独立验收。

2026-09-28：Review在MySQL 8.0.31上通过（浏览器1/1、模型调用1次），结果counts为2/2/1/1/1/1。本地证据：`qa/.daily-live/e3edc5ca620f40f79a87de6173e17c50/`，含result.json、browser.log和各服务日志；测试库数据留在忽略目录中。

## Refinement 验证范围

运行 `ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite refinement`（先打包Java），使用相同隔离端口，需与其他live套件顺序执行。

真实登录、保存需求会议、Python分析及Java保存；模型夹具保留描述/验收/优先级/Sprint/活动为null。页面人工补齐并批准，故事列表仍不变；单独执行后创建待办、未分配负责人的新故事。核对全部批准字段、原有故事/任务/成员列表不变、创建日志载荷、并发重复执行和分析重试。匿名执行401，只读审核/执行/分析403。刷新后审计仍可见，故事地图展示新卡片。

2026-09-30：MySQL8.0.31、真实MyBatis及IdAllocator联调通过，浏览器1/1，固定模型调用1次。counts依次为分析、审核、执行、create日志、目标标题故事数，结果1/1/1/1/1。证据 `qa/.daily-live/9c18c665ff514c1cbfc25e7987af2b9f/`，含result.json及服务日志。未修改日常库；本验证不代表真实模型语义验收，也不证明不同业务入口并发创建无冲突。

## Retro 验证范围

`--suite retro` 运行 `frontend/e2e-live/retro-flow.spec.js`，使用独立新数据库，不能与其他套件并行占用相同端口。

- 真实登录、保存复盘原文，经Python读取成员目录、生成行动候选并由Java保存。模型HTTP夹具返回明确行动，负责人/截止时间为null。
- 页面人工修改事项、选择真实负责人、补充截止文本并批准。分析和审核后业务行动项仍为空，待审执行返回409；单独执行后创建open行动项。
- 核对批准内容、行动项ID和创建审计ID；两个并发重复执行请求返回首次结果，行动项及日志不增加。重试已保存分析返回原ID，模型总调用仅一次。
- 原分析不变，故事、任务、成员资料完整列表不变。匿名执行401，只读审核/执行/分析403，只读查询行动项200。
- 页面刷新后仍显示执行审计与业务行动项，点击可读取创建审计；已执行提案没有再次执行入口。
- `result.json` 的counts依次为Retro分析、审核、执行、行动项、行动项日志、故事日志数，应为 `["1", "1", "1", "1", "1", "0"]`。

2026-09-28运行、2026-09-29核验归档：MySQL 8.0.31上浏览器1/1通过，模型夹具调用1次，数据库计数全部符合预期。证据目录 `qa/.daily-live/3b0311ebaf134f2f92173e549cdd4e32/` 含result.json、browser.log和服务日志，沿用忽略规则。Java21重新打包成功；本轮验证真实业务链路，不代表真实模型语义质量已验收。

## 五类会议统一验收（2026-09-30，第五十六轮）

基于当前工作区与已打包Java产物顺序执行五套现有live测试，全部通过（5/5），各套新建独立MySQL8.0.31目录。每套固定HTTP模型调用1次，共5次；未调用真实供应商、未写日常库。未修改业务代码，也未因文档更新重复运行Java/Python单元测试或前端构建。

| 套件 | 结果 | counts（定义见各套章节） | 本地证据目录 |
| --- | --- | --- | --- |
| Daily | 1/1 | 1/1/1/1/1 | qa/.daily-live/bb78bfa46b684d6b880e0a582404f519/ |
| Planning | 1/1 | 4/0/1/1/1/1 | qa/.daily-live/44238e3d7e7f4024988c21665b422f84/ |
| Review | 1/1 | 2/2/1/1/1/1 | qa/.daily-live/4b48968d66b344e6ba57e7ccba39bdff/ |
| Retro | 1/1 | 1/1/1/1/1/0 | qa/.daily-live/8a90c99003c14e718f5b99ad2f5a5fd6/ |
| Refinement | 1/1 | 1/1/1/1/1 | qa/.daily-live/de2a1cc1442243b3b0ded71963e0118b/ |

每个目录包含result.json、browser.log及服务日志。进程退出码0，测试结束清理本轮服务。覆盖实际页面审核/执行、JWT权限、MyBatis写入、幂等恢复和刷新审计；无新增业务阻塞。模型质量限制沿用各真实模型报告，不以5/5业务联调宣称模型语义全部通过。

试用入口见 [五类会议试用指南](../docs/会议Agent_五类会议试用指南.md)。根README、AI服务README和qa/README已增加现行入口，避免遗漏Python8090服务或误进旧审核链路。指引中PowerShell代码已做语法解析验证，未用该段启动日常服务。

默认模式回归补记（2026-10-01）：此前b7f3d74fb0c3488c8c8c8d0f0865245e运行未留下browser.log/result.json，未计为通过；恢复后使用固定模型重新运行，1/1通过，model_mode=fixture、counts=1/1/1/1/1。新证据目录qa/.daily-live/d03c3e36049141238dde24e6e8809835/，进程退出码0，未再次调用真实供应商。

## 会议删除保护验收（2026-10-01）

旧删除接口只清理旧建议、运行记录和录音，遇到五类分析的现有外键会失败。本轮保持这些外键的保留边界：新增MeetingDeletionGuard，在任何旧清理前锁定会议并检查五类分析；存在即409说明需保留原文及审核执行审计。无分析会议仍沿用原删除流程，已产生的需求池条目保留。未增加强制删除、软删除或数据库迁移。

新增MeetingDeletionGuardTest八项，含五类参数化保护、404、分析保存先提交后拒绝删除、删除先提交后保存读取为空。使用隔离H2与真实JDBC事务；并发测试不等同于所有生产竞争场景覆盖。与五类存储/审核/执行回归合计95项通过，Java21打包成功。

新增前端删除409用例，核对确认提示、错误反馈和当前会议/原文/提案保留；连同Refinement六项共7/7通过。五套真实服务用例共用meeting-deletion-check.js，在分析保存后、执行后分别尝试删除，验证会议、分析、审核、执行、故事、故事日志、行动项列表保持不变；另建无分析会议确认可删除且读取404。模型均为固定响应，不使用真实供应商或日常数据库。

五套真实MySQL8.0.31验收5/5通过，全部默认fixture模式，各调用一次本地模型，最终退出码0：

| 类型 | 本轮qa/.daily-live/证据目录 |
| --- | --- |
| Daily | ec8f926abf9540319c49e99ed6680543 |
| Planning | 100ea5e21b0944b482f2bc83fb7fe70e |
| Review | 149c33694e4b435b818c347ce2609e01 |
| Retro | a9d08531354949889324023878f54743 |
| Refinement | 549b234f4e944124bc12cf6a65d51c01 |

各目录包含result.json、browser.log及服务日志，数据库计数仍符合各套既有预期。此轮真实数据库测试为顺序保存/执行后删除，锁竞争用隔离H2测试覆盖，不宣称完成全部MySQL并发组合。没有调用真实模型或删除日常会议。

## Assignment及音频输入准备（2026-10-01，第六十四轮）

复现：`ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite assignment`。复用隔离MySQL/Java/Python/Vite/Edge启动器，需要先打包当前Java代码；不得与其他live套件并行占用端口。此套件不调用模型，model_mode=not_used、model=none、model_calls=0。

真实页面登录、创建会议、保存候选、人工选人并审核、独立执行后检查仅US13的owner_id改变，任务和成员画像列表保持不变。并发重复执行返回相同审计，Python相同请求号恢复原建议；日志仅一条，页面刷新后可读。拒绝建议不能执行；另一建议批准后真实修改US14标题，再执行返回409且不写分配日志。JWT匿名/只读权限与有建议会议删除保护均有验证。

同时上传2048字节合成音频头/帧同步夹具，真实Java落盘后由Python音频输入接口读取，核对字节大小、SHA-256、上传声明时长及not_started状态；匿名/只读/未归属音频ID分别返回401/403/404。该夹具不用于解码/语音识别，不能视为STT质量验收。

数据库counts含义为：建议数/成功执行建议数/拒绝建议数/分配执行日志数，预期3/1/1/1。另一条建议审核通过但因故事冲突保持not_started。

运行记录：首轮205a521bdf004b2a8b5e7b048d46dbcf因测试将整个面板拼接文字误判为成功提示而失败，未计通过。改为精确等待成功提示后，Assignment单独闭环56d17b170f5c469ab670159900aac7f5通过1/1。加入音频输入准备后的最终运行 **qa/.daily-live/237afe831f7a4fcbb75b374baf67e110/**，MySQL8.0.31、浏览器1/1、counts3/1/1/1、模型调用0、进程退出码0。目录含result.json、browser.log和各服务日志；运行结束停止本轮创建的进程，未触碰日常数据库。

Python全量234/234（新增音频9项），QA网关夹具2/2通过。Java业务逻辑本轮未改，沿用第六十三轮已打包且隔离110项通过的产物；本轮未重跑完整前端/Java单测。浏览器保存成功提示断言在live和夹具测试中均改为精确匹配。

## 本地语音转写（2026-10-02，第六十五轮）

先按[语音配置](../docs/会议Agent_音频输入与转写接入.md)安装可选依赖和模型，然后在仓库根运行：

```powershell
ai-service/.venv/Scripts/python.exe qa/run_stt_local.py
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite transcription --stt-audio ai-service/.stt-eval/70277e2733e34c408d413b9ab688d9f5/en.mp3
```

第二行路径需替换为本次第一行生成的en.mp3。合成器依赖Windows的Microsoft Huihui Desktop与Microsoft Zira Desktop语音。中英文实际模型验收产物保存在 `.stt-eval/70277e2733e34c408d413b9ab688d9f5/`，含wav/mp3/results.json；中文出现“登录→登陆”，不视为多人真实会议质量保证。

最终真实链路 **qa/.daily-live/930570f8a9c540c49235e5514ba98520/**，浏览器1/1、退出码0，MySQL8.0.31。页面上传合成MP3，经真实JWT/Java音频存储/Python读取及本地faster-whisper-base转写，显示带时间戳文本和未知说话人。读取Java会议确认原文不变，刷新确认草稿未保存。counts为音频/每日站会分析/故事日志，预期1/0/0；model_calls=0表示LLM网关未调用，speech_model_calls=1表示本套件成功的一次转写请求，不是内部模型算子计数。目录包含transcription-draft.json、result.json、browser.log和服务日志，Playwright错误附件使用独立playwright子目录，避免与前端夹具套件清理输出时互相影响。

过程失败记录保留：746f607bb4ce4469a925eee9600b8d4e浏览器通过，但最终SQL误写meeting_audios导致退出失败，已修正为meeting_audio；9e87c2a4763e44609b24a788691262c0在Java音频读取阶段返回audio_backend_unavailable（503），未进入模型，无明确底层根因，顺序重跑未复现。不把这两轮计作整体通过。

Python全量242/242（新增8项），pip check通过；前端全量首次86/87，Retro打开入口超时，随后Retro定向8/8通过，合计87个独立用例均有通过记录，但不是一次全绿运行。新增转写7项均在全量中通过。npm run build及git diff --check通过。Java业务代码本轮未改、未重跑其全量单测。实际多人说话人分离、长会议性能及转写版本持久化不在本轮通过范围。

## 转写版本与人工确认接入分析（2026-10-02，第六十六轮）

沿用上述transcription复现命令，先重新打包当前Java版本。新增数据库表meeting_transcript_versions由启动SQL建表。最终运行 **qa/.daily-live/3288200b0caf4747988047d9c5d8d866/**，浏览器1/1、进程退出0，MySQL8.0.31。保存、确认、分析均走实际JWT/Java/Python/MySQL；语音识别使用真实本地base模型，Daily文本模型使用既有本地固定响应夹具，没有外部供应商调用。

流程：上传合成en.mp3→真实转写→页面保存版本→重试同一保存→刷新恢复草稿→人工编辑测试文本并勾选核对→确认创建新会议→重试确认→不同确认返回409→原音频、原会议与新会议删除均409→确认原会议原文未变→刷新后选回原会议→打开分析会议→发起Daily分析→检查分析快照正是核对文本。测试中的人工编辑为“US13 今天开始开发。负责人和截止时间仍待确认。”，这是专用于Daily夹具的人工输入，不声称英文音频包含该句。

counts现为音频/状态分析/故事日志/转写版本/已确认版本，结果 **1/1/0/1/1**。speech_model_calls=1，model_calls=1（本地文本夹具）；报告model_mode=local_stt描述语音模型，文本调用性质以此处说明为准。没有批准或执行Daily提案，没有故事写入。目录包含transcription-draft.json、transcript-version.json（原始版本、确认记录及分析快照）、result.json和各服务/浏览器日志。

首轮b7d8b813d09c46aea1c5b8a38783c98b通过保存与确认后，在刷新时默认选中最新派生会议，测试仍寻找原会议音频按钮而超时。页面快照证实当前显示核对后的文本；修正测试为明确选择原会议后，最终整套通过。未通过增加等待时长掩盖问题。

Java本轮新增TranscriptVersionTest七项、MeetingDeletionGuardTest一项，相关14个测试类合计178/178通过并package成功；覆盖幂等、并发确认、同事务回滚、原始快照保留、音频/会议外键、权限及显式确认。浏览器转写与版本13/13、Refinement与删除7/7，共20项定向回归通过，未重跑全部前端套件。前端构建及git diff --check通过；Python业务未改，未重跑其单测。日常数据库未修改，验收结束停止本次创建的服务。

## 匿名说话人时间段（2026-10-02，第六十七轮）

新增sherpa-onnx 1.13.8可选依赖、显式模型下载脚本、独立工作进程及 `/diarization/run`。模型为sherpa官方release中的pyannote segmentation 3.0 ONNX和3D-Speaker ERes2Net embedding；manifest记录下载来源与SHA-256。请求时完全本地运行，和转写共用单进程工作槽、10分钟/180秒边界。输出只含录音内SPK匿名标签及时间段，不识别成员身份、不绑定转写文字、不写数据库。

本机公开样本命令：`ai-service/.venv/Scripts/python.exe qa/run_diarization_local.py`。样本为sherpa官方0-four-speakers-zh.wav并转为MP3，证据 `.stt-eval/feb3b85c968746538023736e92ff9be0/`。指定4人得到4人/10段/12.69秒；自动聚类得到6人/10段/13.16秒。已知真值下人数误差证明自动估计不可当作准确人数，页面保留1–8人提示和人工核对说明。

真实服务 **qa/.daily-live/5ebd472958144ce0bbc0faf797fe8cb7/** 浏览器1/1、退出0、MySQL8.0.31。上传合成英语单人MP3后实际转写、指定1人分离得到1人/2段，再完成版本保存、人工确认及Daily分析。speech_model_calls=2代表一次STT与一次分离；固定文本模型调用1次。counts仍为音频/分析/故事日志/版本/确认 **1/1/0/1/1**，无提案执行。目录新增diarization-preview.json。

Python全量245/245（新增分离3项）通过，pip check通过；浏览器分离+转写+版本18/18通过；前端build与git diff --check通过。Java生产代码本轮未改，沿用第六十六轮已打包产物。未用个人录音或日常数据库，未评估真实多人会议DER。

## 转写片段人工对齐匿名时间段（2026-10-02，第六十八轮）

真实服务 **qa/.daily-live/8799a5209dc5402b8bb1b1cdbd39a43f/** 浏览器1/1、退出0、MySQL8.0.31。流程为实际STT→指定1人实际分离→保存版本→刷新恢复→重新分离→按时间重叠预填SPK1→人工确认→读取数据库确认对齐快照→进入Daily分析。原会议原文不变，故事日志0，未执行提案。

确认记录验证audio_sha256、speaker_count=1、turns等于本次实际分离输出、assignments数量等于转写片段数且全部为SPK1。speech_model_calls=3（STT一次、刷新前后分离各一次），固定文本模型1次；counts仍为音频/状态分析/故事日志/版本/确认 **1/1/0/1/1**。由于分离预览暂不持久化，刷新后需重新运行是当前明确行为，不计为后台模型调用或自动重试。

Java TranscriptVersionTest新增对齐契约，相关会议测试14类合计179/179并package通过；单类8/8验证持久化、错误哈希、姓名标签、片段编号和引擎拒绝。浏览器分离+版本12/12通过，其中验证重叠预填可人工改选并在刷新后恢复。最终转写相关全套及build记录见本轮开发日志；Python逻辑未改，沿用第六十七轮245/245。

## 未确认分离预览随转写版本持久化（2026-10-02，第六十九轮）

真实服务 **qa/.daily-live/4c7199c866214fffbc29edbde8443401/** 浏览器1/1、退出0、MySQL8.0.31。流程为实际STT→指定1人实际分离→连同匿名时间段保存版本→刷新页面→不再调用分离模型，直接恢复SPK1预填→人工确认→Daily分析。数据库中的draft.diarization保留固定引擎、人数提示、人数、匿名状态和完整turns；确认中的turns与该快照一致。原会议原文不变，未执行提案。

speech_model_calls从3降为 **2**（STT一次、分离一次），固定文本模型调用1次；counts继续为音频/状态分析/故事日志/转写版本/确认 **1/1/0/1/1**。Java对新快照执行严格结构、时长、匿名标签和范围校验，确认时拒绝替换已存快照；旧版无diarization的转写版本仍兼容。

验证：TranscriptVersionTest 10/10、Java21 package、前端相关19/19及production build通过。相关浏览器用例完成后Playwright本地WebServer未自行退出，手动终止空闲测试会话；19项结果均为通过。Python生产逻辑未改，未重复运行Python单测。

## 官方四人音频完整页面闭环（2026-10-02，第七十轮）

Transcription真实套件新增`--stt-language`和`--speaker-count`参数，复用同一浏览器用例验证中文多人音频。复现命令：

```powershell
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py --suite transcription --stt-audio ai-service/.stt-eval/feb3b85c968746538023736e92ff9be0/four-speakers.mp3 --stt-language zh --speaker-count 4
```

最终证据 **qa/.daily-live/0535c057b46449ff901c0d026ef626e9/**：浏览器1/1、退出0、MySQL8.0.31。56.861秒音频得到12个转写片段；指定4人分离得到10个时间段。页面保存版本并刷新后，12/12片段均恢复非空预填，预填集合完整包含SPK1、SPK2、SPK3、SPK4；确认记录中的assignments逐项等于页面按最大重叠计算的结果，turns等于不可变版本快照。随后人工确认文本进入Daily分析，counts仍为 **1/1/0/1/1**，语音模型操作2次、固定文本夹具1次。

机器可读result.json新增language、requested_speakers、audio_fixture、transcript_segments、suggested_segments和suggested_labels。用例同时保留默认英文/单人参数。前端production build通过；Java和Python生产代码未改。样本没有逐毫秒说话人真值或逐句身份标注，因此12/12只代表预填覆盖率，不代表归属准确率；不能据此计算DER。实际转写文本存在明显中文识别错误，仍必须人工核对。

## 跨说话人片段保真（2026-10-02，第七十一轮）

新确认协议为`alignment_version: 2`。每个assignment保存人工主`speaker_id`及由原始STT区间和不可变分离turns决定的`overlapping_speakers`。页面对多于一个重叠标签的片段显示跨说话人提示；Java重算并严格比对集合，拒绝伪造、漏报和乱序。既有version 1的两字段assignment仍可读取及幂等重试。

最终真实证据 **qa/.daily-live/ec2b059fbf13410c965a07fbaca9fc09/**：浏览器1/1、退出0、MySQL8.0.31、speech2、固定文本夹具1、counts **1/1/0/1/1**。12个转写片段全部有主标签，其中4个被明确记录为跨说话人：S2=SPK1/SPK2、S6=SPK2/SPK3、S10=SPK1/SPK4、S11=SPK4/SPK3。result.json新增`multi_speaker_segments: 4`；确认JSON中的四个重叠集合与页面计算结果一致。

验证：TranscriptVersionTest 10/10、Java21 package、前端相关19/19及production build通过。浏览器相关用例执行完毕后测试WebServer仍未自行退出，手动终止空闲会话；19项结果全部通过。Python生产逻辑未改。当前保留完整重叠事实但不拆分文字，因为现有STT只有段级时间戳；不能将主标签视为整段唯一发言人。

## 词级时间戳持久化基础（2026-10-02，第七十二轮）

本地Whisper启用词级时间戳。Python契约、前端响应验证和Java版本保存三层均检查：词ID按`SxWy`连续，时间在所属段内单调，词非空，全部词拼接并trim后严格等于段文本。新转写版本保存完整words；旧版无words段继续兼容。本轮不按词自动切分说话人，避免在验证基础未稳定前改变人工确认语义。

最终真实证据 **qa/.daily-live/d509f15259f64be2a141aa4645a38fe6/**：官方四人中文音频浏览器1/1、退出0，12个转写片段包含 **95个词级时间戳**，12/12有主标签，SPK1–SPK4完整覆盖，2个片段仍跨说话人；speech2、文本夹具1、counts **1/1/0/1/1**。原始词时间戳、diarization、alignment v2和确认结果均在版本证据中。

验证：Python全量245/245；TranscriptVersionTest新增词时间戳契约后11/11且Java21 package通过；转写与版本浏览器15/15，相关分离加入时19/19；前端production build通过。真实中文文本仍有明显识别错误，词时间戳存在不等于文字准确。

## 按真实词边界生成对齐子段（2026-10-02，第七十三轮）

alignment v3将每个词按与分离turn的最大重叠分配主SPK，再合并同一原片段内相邻同标签词。确认assignment包含`assignment_id/segment_id/word_ids/text/speaker_id/overlapping_speakers`。Java要求全部原始词按原顺序恰好覆盖一次，并复算子段重叠集合；历史v1/v2继续兼容。

最终真实证据 **qa/.daily-live/9d66228b0619435ab48d7745bfe68859/**：浏览器1/1、退出0，12个原始片段、95个词生成13个对齐子段。S10实际拆为`S10A1=这是/SPK1`和`S10A2=我第四次半点度演讲/SPK4`；确认JSON的95个word_id完整且无重复。仍有S3A1自身跨SPK1/SPK2，说明单个Whisper词区间也可能跨分离边界，页面保留双标签提示，不按字符继续猜分。SPK1–4完整覆盖，speech2、文本夹具1、counts **1/1/0/1/1**。

验证：TranscriptVersionTest 12/12、Java21 package、转写/版本浏览器15/15、前端build通过；Python生产逻辑未再修改，沿用本轮前一阶段245/245。result.json新增alignment_units=13，multi_speaker_segments=1按仍含多标签子段的原片段去重计数。

## 人工调整词组边界（2026-10-03，第七十四轮）

词级对齐页面支持在所选词后拆分子段，或将同一原始STT片段中的相邻子段合并。编辑后统一按`SxAy`连续重编号并重算原文、时间和全部重叠SPK；不同主标签合并时清空为未知，避免静默选择其中一方。确认协议保持alignment v3，Java继续要求原始词按序恰好覆盖一次，并新增接受完整合并词组的契约用例。

最终真实证据 **qa/.daily-live/ce9022a7e9884578884a2892abc7c6d1/**：官方四人中文音频浏览器1/1、退出0，真实执行一次多词组拆分并与下一组还原合并，证据`manual_boundary_roundtrip=true`。最终仍为12个原始片段、95词、13个对齐子段，SPK1–4完整覆盖，speech2、文本夹具1、counts **1/1/0/1/1**，确认载荷与自动分组基线完全一致。

验证：TranscriptVersionTest 13/13、转写/分离/版本相关浏览器20/20、前端production build及Java21 package通过。Python和Java生产逻辑未改；本轮不重复运行Python全量测试。

## 按对齐子段试听核对（2026-10-03，第七十五轮）

转写版本核对区新增单段播放、从第一段开始以及上一段/下一段导航，复用录音列表已鉴权加载的同一个播放器。每次播放跳到子段开始时间，并在结束时间自动暂停；当前核对项高亮并显示序号。手动暂停、切换会议或组件卸载都会清理段尾监听，避免影响普通整段回放。音频读取失败保留原始错误，不伪装成播放器未就绪。

最终真实证据 **qa/.daily-live/6999f3c6f6554d1bb63dd91e81a8c683/**：官方四人中文音频浏览器1/1、退出0，真实鉴权加载MP3，跳到非首段子段开始位置并播放至段尾自动暂停，`range_playback_roundtrip=true`；随后仍完成词组拆分/合并、确认和Daily分析。最终12个原始片段、95词、13个对齐子段，SPK1–4完整覆盖，counts **1/1/0/1/1**、speech2、文本夹具1。

验证：转写/分离/版本浏览器20项全部显示通过，Playwright在用例结束后因既有WebServer退出问题未自行结束，确认20项完成后手动终止空闲进程；前端production build通过。真实服务套件正常自行退出并返回0。Java/Python生产代码未改，不重复运行其单测。

## 倍速与键盘逐段核对（2026-10-03，第七十六轮）

逐段播放新增0.75×至2×五档速度；播放控制栏聚焦后可用左/右方向键切换子段，以空格或回车重播当前段。事件只在控制栏自身获得焦点时处理，避免占用文本编辑、下拉选择或浏览器全局快捷键。每次切换会高亮活动assignment并平滑滚入可见范围，播放请求将白名单内速度传给同一个鉴权播放器。

最终真实证据 **qa/.daily-live/2ada7416ae8842fba76eae78f6a509d7/**：官方四人中文音频浏览器1/1、退出0，真实以1.25×从非首段起点播放并在该段终点自动暂停，证据记录`playback_rate=1.25`；之后继续完成拆分、合并、确认和Daily分析。最终12个原始片段、95词、13个对齐子段，SPK1–4完整覆盖，counts **1/1/0/1/1**、speech2、文本夹具1。

同时修复Windows状态回归的退出挂起：`playwright.status.config.js`改为直接启动Vite Node入口，避免`npm.cmd`遗留子进程。转写/分离/版本相关浏览器 **20/20** 全绿并自行返回退出码0；定向版本7/7、前端production build、git差异检查通过。Java/Python生产代码未改，不重复运行其单测。

## 可审计的子段文字修订（2026-10-03，第七十七轮）

确认协议升级为alignment v4：每个词级assignment保留由原始word_ids决定的`text`，并新增独立`corrected_text`。页面逐子段编辑修订文字，只读全文按原片段顺序自动生成；Java重算原文、修订全文、词覆盖、时间重叠与匿名标签后才允许创建分析会议。v1–v3历史确认继续读取。已有文字修订的多词子段禁止拆分，恢复原文后可拆；合并则按顺序保留双方修订。

最终真实证据 **qa/.daily-live/700e6c79e337443297e05948dcc21c6c/**：官方四人中文音频浏览器1/1、退出0，12原始片段、95词、13个v4 assignments同时保存原始与修订文字；合成修订全文`US13 今天开始开发。负责人和截止时间仍待确认。`与新分析会议全文完全一致。随后Daily分析成功，SPK1–4完整覆盖，counts **1/1/0/1/1**、speech2、文本夹具1。

验证：TranscriptVersionTest **14/14**，覆盖v4成功、伪造原文拒绝和全文不一致拒绝；转写版本浏览器7/7，转写/分离/版本相关全套20/20；前端production build、Java21 package及git差异检查通过。过程证据34eefaf236e8465ea6f5cc092dcd8e15为旧标签定位失败，56c70ce2248f4f8d88cbf6d7ff8e95a5为旧409探针未同步修订字段而被正确422拒绝，修正测试后最终闭环通过。Python生产代码未改。

## 真值驱动的语音质量评测（2026-10-03，第七十八轮）

新增独立离线评测入口`ai-service/evaluate_speech.py`，直接读取人工真值、现有`transcription-draft.json`和`diarization-preview.json`。报告包含中英混合转写错误率、匿名说话人最优映射后的DER，以及合法词时间戳、录音时间和说话人时间段三类覆盖率。输入时长不一致、真值为空、非法时间段和同一说话人重叠标注会明确失败，不输出部分成绩。

中文按汉字、英文数字按连续单词计分；DER使用0 ms边界容差并计入重叠语音。新增样例真值和指标文档，明确官方四人样本没有逐字及逐毫秒真值，既有真实闭环不能被包装为准确率。10项标准库单测覆盖归一化、编辑类型、匿名标签置换、32人上限、漏检/误检/混淆、覆盖率、输入拒绝和CLI报告，全部通过。项目全量Python回归因本机`.venv`固定到已删除的Python 3.11解释器而未能执行；临时Python 3.12缺少项目依赖，这属于环境限制，不影响本轮无第三方依赖的定向验证。

## 接近10分钟的语音压力与恢复边界（2026-10-03，第七十九轮）

语音工作器超时由180秒调整为600秒，与10分钟输入上限保留实际CPU余量；转写和分离继续共用单工作槽，忙碌立即429，不引入无界队列。两个子进程现在使用最小环境白名单，只继承Windows运行变量及离线标记，不继承DeepSeek密钥、AICAP配置或代理变量。父进程同时补齐非对象JSON和非法UTF-8输出的统一502边界；超时后仍清理临时目录并释放工作槽。

新增`qa/run_speech_stress.py`，将本地公开四人样本重复10次后执行真实模型。最终证据 **ai-service/.stt-eval/2380b7089b0543789f77726ebac00555/**：MP3为4,549,868字节、568.607秒；faster-whisper耗时28.52秒，输出128段/903词；sherpa分离耗时147.12秒，输出4人/101段；双方解码时长完全一致，脚本退出0并记录`passed=true`。单样本冒烟e0c528f99cbe4ecd914c012941787fa3也通过。该重复样本证明容量和时限，不代表真实长会议准确率或长时语境质量。

本地`.venv`从已删除的Python 3.11路径迁移到Python 3.12.14，并按锁定文件重装基础、STT及分离依赖。修改前恢复后的全量Python为255/255；本轮新增边界测试后全量 **256/256**，`pip check`无冲突，差异检查通过。前端与Java生产代码未改。

## AISHELL-4 中文多人会议基线（2026-10-04，第八十轮）

新增严格的长格式TextGrid与RTTM适配器、固定下载校验的AISHELL-4运行器和多报告计数汇总器。真实运行测试集录音`L_R003S01C02`的0–300、600–900、1200–1500秒三窗，共900秒；本地原始数据与逐字产物位于忽略目录`ai-service/.stt-eval/`，仓库只保存不含文本的`ai-service/evals/aishell4_speech_baseline.json`。

三个窗口WER分别41.60%/64.93%/66.77%，DER分别7.73%/30.77%/7.86%；按原始错误计数汇总WER **57.96%**、DER **16.16%**。1952个模型词的时间戳覆盖率100%，与说话人时间段重合率99.80%。这是单个会话三个窗口的基线，且单流WER会惩罚AISHELL-4重叠发言，暂不设发布阈值。运行时暴露的Whisper等起止词时间戳已在片段内安全归一为1 ms，反向时间继续拒绝。定向27项与三次真实模型执行、汇总均通过。

## 容器化生产形态与上线前验证（2026-10-04，第八十一轮）

新增MySQL、Java、Python会议Agent和Nginx前端网关的Compose编排。生产前端默认使用同源`/api`，Nginx将`/meeting-ai`去前缀转发到Python；Java/Python/数据库不发布公网端口，HTTP网关默认仅绑定宿主机127.0.0.1，留给HTTPS终止层。Java镜像用JDK 21兼容参数构建并以非root UID运行，Python模型从宿主机只读挂载，MySQL和会议音频使用独立命名卷。

`deploy/verify.py`在启动前检查真实配置文件、密钥最低强度、模型和构建文件，启动后检查首页、Java/数据库与Python三条路由；错误不回显密钥。Python启动器新增显式`--host 0.0.0.0`容器模式，本地默认仍为127.0.0.1。验证结果：Python全量265/265、部署检查器3/3、启动器7/7、前端状态/会议浏览器100/100、production build、Java 21 package、Compose config及差异检查通过；生产assets中无固定Java回环地址。开发机Docker Engine未运行，因此不把未执行的容器启动写成通过；部署机必须补跑live验证。

## 真实容器启动与备份恢复演练（2026-10-04，第八十二轮）

启动Docker Desktop 29.7.2后，使用隔离的空客户端配置绕过本机不可读Docker登录配置，匿名拉取官方镜像并真实构建`aicap-web/aicap-java/aicap-ai`。首次启动发现旧MySQL占用宿主机3307，据此移除生产MySQL不必要的宿主机端口；第二次启动四服务全部healthy：MySQL 8.0.46、Java 21、Python会议Agent及Nginx 1.27.5，唯一入口为`127.0.0.1:8088`。live验证通过首页、Java+数据库和Python，追加只读登录冒烟后还验证JWT当前用户与会议列表。

新增`deploy/data_backup.py`，原子生成数据库SQL、音频tar.gz及SHA-256 manifest；恢复要求显式`--confirm-data-loss`，停止应用、重建AIcap库、清空音频卷、恢复并重启。真实备份位于忽略目录`deploy/backups/live-smoke-20261004/`，SQL 77,565字节、空音频归档99字节。备份后写入`restore_probe`表和音频探针文件，真实恢复后两者均消失，四服务再次healthy且认证只读冒烟通过。归档验证额外拒绝绝对路径、`..`、链接及设备成员，避免恢复时路径穿越。
