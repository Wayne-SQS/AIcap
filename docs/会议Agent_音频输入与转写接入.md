# 会议Agent音频输入与转写接入

第六十五轮接通本地语音识别；第六十六轮加入版本保存和人工核对；第六十七轮加入录音内匿名说话人时间段预览。标签不代表成员身份，尚未自动绑定转写文字。原会议保持不变，新会议可显式发起既有分析流程。

## 当前接口

`POST /api/meetings/{meeting_id}/transcription/prepare`（Python服务，前端代理前缀 `/meeting-ai`）。需当前登录Bearer令牌，admin/owner/member可调用，viewer拒绝。

```json
{"audio_id":"已上传音频ID"}
```

依次读取Java `/api/auth/me`、`/api/meetings/{id}/audio`、`/api/audio/{audio_id}`。只有目录内音频且meeting_id一致才下载，不采用元数据中的url，不将JWT转发至其他服务。读取使用可信配置的Java地址、超时和字节上限，禁止跟随重定向。

Python最多接收25MiB音频，目录JSON最多256KiB。要求audio/mpeg，实际字节数、SHA-256及Java响应的X-Audio-Sha256均与元数据一致；缺失、重复归属、过大、内容类型错误或校验不一致不返回成功。它只证明所读字节与存储记录一致，不证明音频可解码或包含可识别语音。

返回meeting_id、audio_id、sha256、byte_size及以下状态，不返回音频字节、下载URL、文件路径或令牌：

```json
{
  "reported_duration_ms": null,
  "duration_source": "upload_metadata_unverified",
  "input_status": "verified_bytes",
  "transcription_status": "not_started",
  "diarization_status": "not_started"
}
```

reported_duration_ms保留上传时的声明值（未知为null），不是解码实测时长，不能拿它证明时间戳正确。接口不会把会议已有原文当作转写结果，也不要求会议已有非空原文才能准备音频。

常见失败：401未登录；403角色不能转写；404会议/文件不存在或音频不属于会议；422请求结构不符；502元数据/内容完整性不符；503/504Java服务不可用或超时。错误不回显上游内容。接口无业务写入，可重试；没有新增后台任务或外部供应商调用。

## 验证与下一步

新增test_audio_tool.py九项，覆盖角色、归属、固定来源、未知时长、重复目录、大小限制、字节和哈希不符、重定向、服务错误及API校验。Python全量234项通过。

Assignment隔离真实服务套件附带音频准备验证：通过真实JWT向Java上传2048字节合成MP3头/帧同步夹具，再由Python读取并校验；成功输出未开始转写状态，匿名/只读/不在目录的ID分别拒绝。此文件只用于存储完整性测试，不是语音识别质量样本。证据目录及复现命令见[真实服务验收](../qa/DAILY_LIVE.md)。

## 本地转写（第六十五轮，2026-10-02）

复用 [faster-whisper](https://github.com/SYSTRAN/faster-whisper) 的CPU int8推理和PyAV解码，使用多语言base模型；不需要单独安装系统FFmpeg。音频只在本地处理，转写请求不会下载模型或调用DeepSeek。首次安装需联网，在 `ai-service/` 执行：

```powershell
.venv/Scripts/python.exe -m pip install -r requirements-stt.txt
.venv/Scripts/python.exe prepare_stt.py
.venv/Scripts/python.exe start_service.py
```

模型下载至 `.models/faster-whisper-base`，下载脚本将实际仓库revision写入 `aicap-model.json`。本轮验收revision为 `ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66`，faster-whisper为1.2.1。可通过启动进程的 `AICAP_STT_MODEL_DIR` 环境变量指定已有模型目录（该变量不由启动器的.env白名单加载）。模型、工作目录和本地验收音频均被Git忽略。未安装可选依赖/模型时，转写返回503；文本功能保持可用，启动器仍按原规则检查文本模型配置。

调用 `POST /api/meetings/{meeting_id}/transcription/run`，请求示例 `{"audio_id":"已上传音频ID","language":"zh"}`。language支持zh/en/null，null表示自动识别。角色、归属与完整性检查复用prepare接口。页面在每条已上传音频下提供转写入口，结果显示时间片段、未知说话人和可复制全文；切换会议后不会显示迟到的上一会议结果。

返回字段：audio为输入准备快照（其中not_started表示准备阶段状态）；顶层status为draft或no_speech，duration_ms为解码实测时长，segments含S1起连续编号、start_ms/end_ms/text及始终为null的speaker_id，text为片段按换行连接。顶层diarization_status=not_available、storage_status=not_saved、requires_human_review=true。不把匿名标签或成员姓名伪装成模型已经识别的说话人。

限制：MP3最多25MiB、解码时长最多10分钟；每个Python服务进程同时只运行一个转写子进程，CPU线程4，180秒超时终止，结束清理临时文件。全文最多16000字符、2000片段；无法识别语音返回no_speech，不伪造文本。忙碌返回429，超长413，不可解码422，执行超时504；缺少模型/依赖503。未保存草稿刷新即失，不自动写入会议原文，需人工核对。

验证：新增Python8项（全量242通过），浏览器7项覆盖结果展示、归属/时间戳拒绝、只读角色、无语音、忙碌重试及切换会议隔离。实际中英文合成语音在本机CPU执行，证据 `.stt-eval/70277e2733e34c408d413b9ab688d9f5/results.json`；耗时约4.7/2.7秒。中文有“登录→登陆”同音误识别。此结果只验证清晰合成语音链路，不代表嘈杂多人会议准确率。真实服务结果见[验收记录](../qa/DAILY_LIVE.md)。

## 保存版本、人工核对与分析（第六十六轮）

1. 上传音频并完成转写，点击“保存转写版本”。页面按草稿内容生成稳定请求编号；相同用户、会议、内容重试返回同一版本。未识别到语音的空结果不提供保存入口。
2. 刷新页面后，先选择原会议，再在原音频下查看已保存版本（页面默认选择最新会议）。可以回放原音频、展开原始时间片段，并修改“人工核对文本”和分析会议标题。原始草稿及片段保持不可变；核对文本不冒充已重新对齐的时间片段。
3. 勾选已核对录音，点击“确认并创建分析会议”。Java同事务保存核对人、核对时间、确认文本和新会议关联；同一版本仅确认一次，相同人相同请求重试返回原结果，不同内容返回409。确认响应不明时页面冻结原提交，可重试或刷新历史恢复，不盲目新建会议。
4. 点击“打开分析会议”，选择Daily/Planning/Review/Retro/Refinement入口显式分析。分析读取新会议的确认文本，并沿用既有人工审核和执行规则。原会议、音频、草稿、确认文本和新会议ID之间的关联保留在版本记录中。

Java新增 `meeting_transcript_versions` 表，随启动的SQL初始化自动建表。必须重新打包并重启Java后使用新页面。接口均位于 `/api/meetings/{meetingId}/transcript-versions`：GET列出版本，POST保存，POST `/{id}/confirm`人工确认。保存/确认需admin、owner或member，查看需登录，viewer不能写。

保存请求为 `{client_request_id,draft}`；draft包含audio_id、sha256、duration_ms、language、text、segments，并可选包含同音频的diarization匿名时间段快照。服务端检查音频归属/hash、非空全文、片段编号/时间范围/全文一致；若有分离快照，还检查固定引擎、同一时长、匿名身份状态、人数提示、连续SPK标签和时间范围。返回provenance=caller_submitted：它是当前用户提交的转写快照，Java不把前端载荷认证为服务端模型原始输出。确认请求严格保存标题、文本、确认标记及可选对齐；返回confirmation包含input、confirmed_by、confirmed_at、analysis_meeting_id。模型草稿来源与人工确认身份分开记录。

转写关联的原音频、原会议和新分析会议均保留：删除返回409，外键保护并发情况下的来源。当前没有删除版本或撤销确认入口；确认后的再次修订可手动新建会议，暂不提供同版本的多轮修订。同用户同草稿及分离快照再次保存会去重，不生成新版本。无自动触发模型、无覆盖原会议、无自动执行提案。只保存匿名时间段和人工文字归属，不识别成员身份。

验证：Java相关事务/契约回归178项通过（本轮新增版本7项、删除保护1项），浏览器转写/版本13项和Refinement/删除7项通过。真实MySQL/Java/Python/Vite/Edge链路验证保存、刷新、人工确认、幂等、删除保护以及核对文本进入Daily分析，证据见[第六十六轮验收记录](../qa/DAILY_LIVE.md)。Python业务逻辑本轮未改，不重复运行上轮242项单测。

## 匿名说话人时间段预览（第六十七轮）

采用 [sherpa-onnx离线说话人分离](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html)，CPU本地运行pyannote segmentation 3.0 ONNX与3D-Speaker ERes2Net embedding。模型请求期间不联网。首次配置在 `ai-service/` 执行：

```powershell
.venv/Scripts/python.exe -m pip install -r requirements-diarization.txt
.venv/Scripts/python.exe prepare_diarization.py
```

`prepare_diarization.py`只从sherpa-onnx官方GitHub release和3D-Speaker官方仓库下载，保存到 `.models/diarization`，并写入含来源和SHA-256的manifest；`--proxy URL`可显式配置下载代理，`--sample`额外下载官方公开四人中文样本。生产请求不读取令牌，不下载模型。可用进程环境变量 `AICAP_DIARIZATION_MODEL_DIR` 指定已有目录。

接口：`POST /api/meetings/{meeting_id}/diarization/run`，请求 `{ "audio_id": "...", "num_speakers": 4 }`；num_speakers可为null自动估计，或1–8的严格整数。鉴权、角色、音频归属、25MiB和10分钟限制复用转写边界。转写与分离共用每服务进程一个工作槽；并发忙碌返回429，超时180秒返回504。结果未保存，含duration_ms、speaker_count和按开始时间排列的turns；turn允许重叠，标签只允许SPK1起连续匿名编号。identity_status固定anonymous_only、requires_human_review=true。

页面每条音频提供预计人数和“分析说话人时间段”。自动人数只是聚类估计；若会议人数已知，应选择1–8。输出与转写分开显示，不把SPK标签写进转写版本，不猜测姓名，不把重叠片段强行分配给一段文字。切换会议会丢弃迟到结果。

公开四人样本实际验收位于 `.stt-eval/feb3b85c968746538023736e92ff9be0/results.json`：指定4人得到4人/10段/12.69秒；自动估计得到6人/10段/13.16秒，说明自动人数在该样本上高估，不能作为真实身份或准确人数证据。真实应用链路证据见QA第六十七轮：单人合成语音指定1人得到1人/2段；它验证接口和页面，不代表多人会议准确率。

## 转写片段与匿名说话人对齐（第六十八轮）

运行转写和说话人分离后，先保存转写版本。版本核对区会按每个转写片段与匿名时间段的最大重叠时长预填SPK标签；这只是界面建议，不是自动确认。用户可以逐片段选择SPK1–SPK32或“未知”，并继续修改会议全文。切换分离人数会清空旧预览，避免沿用不同聚类的标签。

点击“确认并创建分析会议”时，speaker_alignment随人工确认一起保存，包含固定引擎名、音频SHA-256、实测时长、说话人数、完整匿名时间段快照和按S1顺序排列的片段选择。Java逐项验证：哈希必须与版本音频相同，时长必须与转写草稿一致，标签必须SPK1起连续且确实出现在时间段中，assignment必须覆盖全部转写片段；姓名或未知引擎均拒绝。未运行分离时speaker_alignment=null，保留既有纯文本核对流程。

对齐与确认同事务持久化，同一确认请求继续保持幂等。确认响应不明时，页面冻结标题、文本和对齐选择并重试同一载荷；已确认历史重新打开后显示保存的选择。保存的是人工核对依据，不改变原始转写片段中的speaker_id=null，也不宣称文本时间戳已被重新切分。

从第六十九轮起，保存版本时会把当前同音频分离预览写入不可变draft。刷新后直接从版本恢复时间段并重新计算SPK预填，无需再次运行分离；确认载荷的引擎、时长、人数和turns必须与已存快照完全一致，防止确认阶段替换核对依据。没有快照的旧版本继续兼容纯文本确认，也可使用当前同音频临时预览。

真实服务证据 `qa/.daily-live/4c7199c866214fffbc29edbde8443401/`：合成单人音频执行STT和一次分离，保存版本后刷新，页面直接恢复SPK1预填并确认，随后Daily分析读取人工核对全文。speech_model_calls=2代表STT和分离各一次，文本夹具一次；数据库计数为1/1/0/1/1。当前仍需使用获得授权的真实多人会议样本评估DER和自动预填准确率，不做成员身份识别。

官方公开四人样本的完整页面验收见 `qa/.daily-live/0535c057b46449ff901c0d026ef626e9/`。中文STT生成12段，指定4人分离生成10个时间段；保存并刷新后12段全部得到预填，标签集合覆盖SPK1–SPK4，确认快照和assignments完整落库并进入Daily分析。该样本没有逐毫秒真值/逐句身份标注，不能计算DER或把覆盖率当准确率；实际转写也有明显错字，产品仍要求逐段人工核对。
