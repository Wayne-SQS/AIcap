# 会议录音(MP3)接口与验证

> 面向组长/队友的验收说明:后端已支持「网页麦克风录音转 MP3」与「本地 .mp3 文件」的上传、列表、回放、删除。
> 服务端**不需要 ffmpeg**:浏览器侧用 `lamejs` 把录音编成真 mp3 再上传,后端只做校验与落盘。

## 1. 起服务(两端任一方式)

```powershell
# MySQL 8(3307,库 AIcap)
cd backend; docker compose up -d

# 后端(8080);首次启动会自动建表 + 播种演示数据
cd ..\java-backend
mvn spring-boot:run
# 或:mvn package 后 java -jar target\aicap-java-backend.jar
```

启动日志里应能看到:

- `已播种 5 个演示用户(密码 123456)`
- `已播种 37 条用户故事(US01–US37)`、`已播种 16 条执行任务(T01–T16,…)`
- `已播种 5 条成员画像(技术栈/工作能力/开发流程领域,各成员互不相同)`

`meeting_audio` 表由 `src/main/resources/db/schema.sql` 幂等建好(`spring.sql.init.mode=always`),存量库由 `SchemaUpgrader` 自动补列,无需手工执行 DDL。

## 2. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/meetings/{meetingId}/audio` | admin/owner/member(viewer 403) | multipart:`file`(必填)、`source=recorder\|upload`(可选,默认 upload)、`duration_ms`(可选) |
| GET | `/api/meetings/{meetingId}/audio` | 任意登录用户 | 该会议音频列表(含 `sha256`、`byte_size`、`url`) |
| GET | `/api/audio/{audioId}` | 任意登录用户 | 回放字节流:`Content-Type: audio/mpeg`,`X-Audio-Sha256` 头;`?download=1` 走附件名下载 |
| DELETE | `/api/audio/{audioId}` | 仅 admin/owner(member 403) | 删除元数据 + 磁盘文件 |

字段(snake_case):`id / meeting_id / filename / content_type / byte_size / duration_ms / sha256 / source / uploaded_by / created_at / url`。

## 3. 一键复现(curl,PowerShell)

```powershell
$base = 'http://127.0.0.1:8080'

# 1) 登录拿 token(旧别名「成员1」同样可登录)
$body = '{"username":"李锐铭","password":"123456"}'
$tok = (Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body))).access_token

# 2) 先建一个会议(音频必须挂在会议下)
$mk = Invoke-RestMethod "$base/api/meetings" -Method Post -ContentType 'application/json' `
      -Headers @{ Authorization = "Bearer $tok" } `
      -Body ([Text.Encoding]::UTF8.GetBytes('{"title":"MP3 验收会","transcript":"本次会议用于验收录音上传与回放。"}'))
$mid = $mk.id

# 3) 上传 mp3(curl.exe 走 multipart;把路径换成你自己的文件)
curl.exe -s -X POST "$base/api/meetings/$mid/audio" `
  -H "Authorization: Bearer $tok" `
  -F "file=@C:\path\to\your.mp3;type=audio/mpeg" `
  -F "source=upload" -F "duration_ms=3000"

# 4) 列表
Invoke-RestMethod "$base/api/meetings/$mid/audio" -Headers @{ Authorization = "Bearer $tok" } | Format-List

# 5) 回放并逐字节比对(把 <audioId> 换成上一步的 id)
curl.exe -s -D headers.txt -o out.mp3 "$base/api/audio/<audioId>" -H "Authorization: Bearer $tok"
(Get-FileHash .\your.mp3).Hash -eq (Get-FileHash .\out.mp3).Hash    # → True
Select-String -Path headers.txt -Pattern 'X-Audio-Sha256'           # 与服务端记录一致

# 6) 删除(admin/owner)
Invoke-RestMethod "$base/api/audio/<audioId>" -Method Delete -Headers @{ Authorization = "Bearer $tok" }
```

> 只想在页面里试:`cd frontend; npm run dev` → 浏览器打开 `http://localhost:5173` → 登录 → 「AI 助手 → 会议智能体」→ 保存一个会议 → 「开始录音」(需允许麦克风)或「选择本地 .mp3」→ 提交 → 加载回放 / 下载 / 删除。

## 4. 校验规则与错误码

| 场景 | 状态码 |
|---|---|
| 未带/失效 Token | 401 |
| viewer 上传、member 删除 | 403 |
| 会议不存在、音频已删除后回放 | 404 |
| 扩展名非 `.mp3`、魔数不是 `ID3`/`0xFFEx`、`source` 非法、`duration_ms` 越界、缺 `file` 部件 | 422 |
| 单文件超过上限(默认 25MB) | 413 |

- 双校验:既看扩展名也看**文件头魔数**,改名的假 mp3 一律 422。
- 落盘路径:`${AICAP_AUDIO_DIR:./data/audio}/yyyy/MM/<uuid>.mp3`,库内只存元数据;库写入失败会回删已落盘文件。
- 文件名做过路径穿越防护(`../`、盘符、空文件名全部拒绝),中文文件名正常。

## 5. 配置项

| 变量 | 默认 | 说明 |
|---|---|---|
| `AICAP_AUDIO_DIR` | `./data/audio` | 音频落盘根目录(已 gitignore,不进仓库) |
| `AICAP_AUDIO_MAX_BYTES` | `25MB` | Spring multipart 单文件上限 |
| `AICAP_AUDIO_MAX_BYTES_BYTES` | `26214400` | 业务侧字节上限(与上一行保持一致即可) |

## 6. 已完成的验证(可复跑)

- **后端契约测试 108 项全绿**(含音频 9 项):`cd java-backend; mvn test`
  覆盖:上传→列表→回放字节与 sha256 一致→删除后 404;中文文件名;错扩展名/假魔数/非法 source/时长越界/缺 file 部件 → 422;viewer 上传 403;member 删除 403 且音频仍在;未知会议 404;未登录 401。
- **手工边界**:26MB 文件 → 413。
- **浏览器端**:系统 Edge + 虚拟麦克风录制 3 秒 → `lamejs` 编码出 56KB 真 mp3(文件头 `fffb…`)→ 提交到会议 → 列表显示 `网页录音 · 56 KB · 00:03 · sha256 c9f7321231…` → 回放成功 → 删除成功。

## 7. 已知限制

- **没有语音转写(ASR)**:`meeting_audio` 只存音频与元数据,转写仍走会议 Agent 的文本输入;录音→转写是后续项。
- 会议本身暂无删除接口(测试会议需清理时跑 SQL),音频有删除接口。
- 回放/下载需带 Token,`url` 字段是接口地址,不是公开静态地址。
