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
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$jb = Split-Path -Parent $MyInvocation.MyCommand.Path          # java-backend
$repoRoot = Split-Path -Parent $jb
Set-Location $jb

$busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($busy) { throw "8080 已被 PID $($busy.OwningProcess) 占用;java -jar 会锁住 jar,请先停掉它再启动" }

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
if (-not $map['AICAP_LLM_MODEL']) { $map['AICAP_LLM_MODEL'] = 'deepseek-v4-flash' }

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

$java = Join-Path ${env:ProgramFiles} 'Java\jdk-23\bin\java.exe'
$p = Start-Process -FilePath $java -ArgumentList '-jar','target\aicap-java-backend.jar' `
    -WorkingDirectory $jb -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $jb 'target\backend-dev.log') `
    -RedirectStandardError  (Join-Path $jb 'target\backend-dev.err.log') -PassThru

Write-Host "后端已启动 PID=$($p.Id)"
Write-Host "仓库=$($env:GITHUB_REPO)  自动同步=${env:GITHUB_AUTO_SYNC_HOURS}h  画像模型=$($env:PROFILE_LLM_MODEL)"
Write-Host "日志: java-backend\target\backend-dev.log"
