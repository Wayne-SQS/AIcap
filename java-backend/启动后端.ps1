# 带密钥启动爱管理 Java 后端(GitHub 活动同步 + 两个智能体的 LLM 内核)
#
# 为什么需要这个脚本:Java 后端不读 .env,所有密钥只能靠环境变量注入;
# 手动敲一次很容易漏(实测就漏过 PROFILE_LLM_* 导致画像智能体静默降级成规则引擎)。
# 本脚本里的密钥一律【运行时读取】,不写进任何文件、不进仓库:
#   GitHub token → Windows 凭据管理器(git:https://github.com,由 Git Credential Manager 维护)
#   LLM 密钥     → backend\.env 的 AICAP_LLM_API_KEY
#
# 用法:powershell -ExecutionPolicy Bypass -File "java-backend\启动后端.ps1"
#   加 -Build 可顺带重新构建(注意:java -jar 会锁住 jar,必须先把旧进程停掉再构建)
#   默认**连会议智能体一起起**(它是独立进程,见 ai-service\启动会议智能体.ps1);
#   只要 Java 用 -SkipAi。规划/画像/项目生成是 Java 进程内的 worker,不需要单独起。
#   8080 已在跑时**跳过启动并照常返回**(不带 -Build 的前提),见下面端口检查处的注释。
param([switch]$Build, [switch]$SkipAi, [int]$AiPort = 8090)

$ErrorActionPreference = 'Stop'
$jb = Split-Path -Parent $MyInvocation.MyCommand.Path          # java-backend
$repoRoot = Split-Path -Parent $jb
Set-Location $jb

# 端口已被占用时**不当成失败**：多服务的编排脚本(启动全部.ps1)会在 8080 起来之后再回来
# 等健康检查，一个已经在跑的实例本来就是它要的结果；直接中止会把后面的
# 主管编排层(8100)、前端(5173)一起带走 —— 实测就这么被带走了一整段。
# 但 -Build 是例外：构建要覆盖 target\aicap-java-backend.jar，而 java -jar 正锁着它，
# 这时必须由人先停进程(实测 mvn repackage 的报错是
# "Unable to rename ... .jar to ....jar.original"，从现象看不出是被锁)。
$busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    if ($Build) {
        throw "8080 已被 PID $($busy.OwningProcess) 占用，而 -Build 需要覆盖 target\aicap-java-backend.jar（它正锁着该文件）。请先停掉那个进程再带 -Build 启动。"
    }
    Write-Host "8080 已在运行(PID $($busy.OwningProcess))，跳过启动(沿用既有实例)"
    return
}

# 端口写死:shell 里可能残留 SERVER_PORT(实测撞过 60076 —— WorkBuddy 自己的端口),
# 后端会起在别的端口上并且因端口被占而启动失败,报错还看不出是端口变量的问题。
$env:SERVER_PORT = '8080'

if ($Build) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'            # pom 用 release 23,JDK 8/17 会编译失败
    Remove-Item 'target\aicap-java-backend.jar','target\aicap-java-backend.jar.original' -Force -ErrorAction SilentlyContinue
    & 'E:\apache-maven-3.9.9\bin\mvn.cmd' -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败' }
}

if (-not (Test-Path 'target\aicap-java-backend.jar')) { throw '未找到 target\aicap-java-backend.jar,请加 -Build 构建' }

# --- GitHub token:从凭据管理器现取 ---
$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$tok = ($cred | Where-Object { $_ -like 'password=*' } | Select-Object -First 1) -replace '^password=',''
if (-not $tok) { throw 'GitHub token 未取到;请在凭据管理器确认存在 git:https://github.com' }

# --- LLM 密钥:从 backend\.env 解析(Java 后端不读 .env) ---
$map = @{}
Get-Content (Join-Path $repoRoot 'backend\.env') -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
}
$key = $map['AICAP_LLM_API_KEY']
if (-not $key) { throw 'backend\.env 里没有 AICAP_LLM_API_KEY' }
if (-not $map['AICAP_LLM_BASE_URL']) { $map['AICAP_LLM_BASE_URL'] = 'https://api.deepseek.com' }
if (-not $map['AICAP_LLM_MODEL']) { $map['AICAP_LLM_MODEL'] = 'deepseek-flash' }

# --- JWT 密钥:必须注入,否则后端用 application.yml 里硬编码的默认 secret。
#     后果是"换一种方式起服务,之前登录的 token 就全失效",且默认 secret 是明文进仓库的。 ---
if ($map['JWT_SECRET']) { $env:JWT_SECRET = $map['JWT_SECRET'] }
if ($map['JWT_EXPIRE_MINUTES']) { $env:JWT_EXPIRE_MINUTES = $map['JWT_EXPIRE_MINUTES'] }

# --- GitHub 活动同步 ---
$env:GITHUB_ENABLED      = 'true'
$env:GITHUB_REPO         = 'Wayne-SQS/AIcap'
$env:GITHUB_TOKEN        = $tok
# 键可以是 GitHub 登录名 / 提交者邮箱 / 提交者姓名任一:
# 未关联账号的提交者拿不到登录名(如 mcc@mcc.mcc → author=null),只能靠姓名键归属
$env:GITHUB_USER_MAPPING = '{"lili618li":"李锐铭","Wayne-SQS":"孙秋实","LHWYAN":"罗子涵","13555853258":"高思晗","mcc":"高思晗"}'
# 定时自动同步(小时),0=不自动。当前口径:谁提交 → 谁到自己任务点完成 → 再手动点一次同步
$env:GITHUB_AUTO_SYNC_HOURS = '0'

# --- 会议智能体(前缀 aicap.llm.*) ---
$env:AICAP_LLM_API_KEY   = $key
$env:AICAP_LLM_BASE_URL  = $map['AICAP_LLM_BASE_URL']
$env:AICAP_LLM_MODEL     = $map['AICAP_LLM_MODEL']

# --- 画像智能体(独立前缀 profile-llm.*;不设这一组会【静默】降级为规则引擎) ---
$env:PROFILE_LLM_API_KEY  = $key
$env:PROFILE_LLM_BASE_URL = $map['AICAP_LLM_BASE_URL']
$env:PROFILE_LLM_MODEL    = $map['AICAP_LLM_MODEL']

# --- RAG 检索层 embedding(必须是【另一家】供应商:DeepSeek 没有 /embeddings 端点,
#     用 AICAP_LLM_API_KEY 顶替的表现是每次索引都 HTTP 404。当前默认硅基流动 BAAI/bge-m3,
#     免费且 1024 维,与 application.yml 的 dimension 默认值一致) ---
$embReady = $false
if ($map['AICAP_EMBEDDING_API_KEY']) {
    if (-not $map['AICAP_EMBEDDING_BASE_URL'])  { $map['AICAP_EMBEDDING_BASE_URL']  = 'https://api.siliconflow.cn/v1' }
    if (-not $map['AICAP_EMBEDDING_MODEL'])     { $map['AICAP_EMBEDDING_MODEL']     = 'BAAI/bge-m3' }
    if (-not $map['AICAP_EMBEDDING_DIMENSION']) { $map['AICAP_EMBEDDING_DIMENSION'] = '1024' }
    $env:AICAP_EMBEDDING_API_KEY     = $map['AICAP_EMBEDDING_API_KEY']
    $env:AICAP_EMBEDDING_BASE_URL    = $map['AICAP_EMBEDDING_BASE_URL']
    $env:AICAP_EMBEDDING_MODEL       = $map['AICAP_EMBEDDING_MODEL']
    $env:AICAP_EMBEDDING_DIMENSION   = $map['AICAP_EMBEDDING_DIMENSION']
    $embReady = $true
}

$java = Join-Path ${env:ProgramFiles} 'Java\jdk-23\bin\java.exe'
$p = Start-Process -FilePath $java -ArgumentList '-jar','target\aicap-java-backend.jar' `
    -WorkingDirectory $jb -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $jb 'target\backend-dev.log') `
    -RedirectStandardError  (Join-Path $jb 'target\backend-dev.err.log') -PassThru

Write-Host "后端已启动 PID=$($p.Id)"
Write-Host "仓库=$($env:GITHUB_REPO)  自动同步=${env:GITHUB_AUTO_SYNC_HOURS}h  画像模型=$($env:PROFILE_LLM_MODEL)"
if ($embReady) {
    Write-Host "RAG 检索层=已启用  embedding=$($env:AICAP_EMBEDDING_MODEL) @ $($env:AICAP_EMBEDDING_BASE_URL)"
} else {
    Write-Host "RAG 检索层=未启用(backend\.env 缺 AICAP_EMBEDDING_API_KEY;注意它不能用 DeepSeek 的 key)"
}
Write-Host "日志: java-backend\target\backend-dev.log"

# --- 等 Java 健康:起得来 ≠ 能服务(DB 没就绪时它会"半死":只有 /api/health 响应) ---
$deadline = (Get-Date).AddSeconds(60)
$h = $null
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 3
        if ($h.status -eq 'ok') { break }
    } catch { Start-Sleep -Seconds 2 }
}
if ($h -and $h.status -eq 'ok') {
    $names = ($h.workers.PSObject.Properties | ForEach-Object { $_.Name }) -join ','
    Write-Host "Java 健康: ok  db=$($h.db)  进程内 worker=[$names]"
} else {
    Write-Host "[警告] 8080 健康检查未通过;看 java-backend\target\backend-dev.err.log" -ForegroundColor Yellow
}

# --- 会议智能体:与 Java 是两个进程/两种技术栈(且它不持数据库连接,写回经 Java 接口),
#     所以它**必须**单独起 —— 这里替用户带起来,免得每次都要多开一个终端。 ---
if ($SkipAi) {
    Write-Host "会议智能体: 已按 -SkipAi 跳过(前端会议面板与五类分析将不可用)"
} else {
    $aiLauncher = Join-Path $repoRoot 'ai-service\启动会议智能体.ps1'
    if (Test-Path $aiLauncher) {
        Write-Host ""
        & $aiLauncher -Port $AiPort
    } else {
        Write-Host "[警告] 找不到 $aiLauncher,跳过会议智能体" -ForegroundColor Yellow
    }
}
