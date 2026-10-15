# ============================================================
# 会议智能体(ai-service, LangGraph 五类会议分析)启动器
#
# 为什么单独一个脚本:它与 Java 平台是**两个进程、两种技术栈**,且按架构决定
# 「不持有数据库连接」—— 写回一律经 Java 业务接口(AICAP_JAVA_BASE_URL)。
# 所以它必须独立起;启动后端.ps1 会调它,让"起后端就把会议智能体带起来"成立。
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File "ai-service\启动会议智能体.ps1"
#     -Port 8090        监听端口(默认 8090)
#     -CheckOnly        只体检(不服务、不调模型)
#     -Foreground       前台运行(直接看日志;默认后台 + 日志落盘)
#     -EnvFile <path>   三个模型环境值的来源(默认 backend\.env,与 Java 同一套)
#
# 密钥只在运行时读取,不落盘。ai-service 自己没有 .env,靠环境变量。
# ============================================================
param(
    [int]$Port = 8090,
    [switch]$CheckOnly,
    [switch]$Foreground,
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
$ai = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $ai
$python = Join-Path $ai '.venv\Scripts\python.exe'
$entry = Join-Path $ai 'start_service.py'

if (-not (Test-Path $python)) {
    throw "[错误] 找不到 ai-service 的 venv: $python`n  先建环境:`n    cd ai-service`n    python -m venv .venv`n    .venv\Scripts\python.exe -m pip install -r requirements.txt"
}
if (-not (Test-Path $entry)) { throw "[错误] 找不到入口: $entry" }

# --- 已在本端口上跑的实例视为就绪,不重复起(与 启动后端.ps1 的 8080 检查同一口径) ---
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($busy) {
    Write-Host "会议智能体已在 $Port 上运行(PID $($busy.OwningProcess)),跳过启动"
    return
}

# --- 三个模型环境值:与 Java 后端同一套来源 ---
if (-not $EnvFile) { $EnvFile = Join-Path $repo 'backend\.env' }
if (-not (Test-Path $EnvFile)) { throw "[错误] 找不到 $EnvFile(会议分析的 LLM 密钥从这里取)" }
$map = @{}
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
}
if (-not $env:AICAP_LLM_API_KEY) { $env:AICAP_LLM_API_KEY = $map['AICAP_LLM_API_KEY'] }
if (-not $env:AICAP_LLM_BASE_URL) {
    $env:AICAP_LLM_BASE_URL = if ($map['AICAP_LLM_BASE_URL']) { $map['AICAP_LLM_BASE_URL'] } else { 'https://api.deepseek.com' }
}
if (-not $env:AICAP_LLM_MODEL) {
    $env:AICAP_LLM_MODEL = if ($map['AICAP_LLM_MODEL']) { $map['AICAP_LLM_MODEL'] } else { 'deepseek-flash' }
}
if (-not $env:AICAP_LLM_API_KEY) { throw "[错误] $EnvFile 里没有 AICAP_LLM_API_KEY;文本分析会以 503(model_not_configured)拒绝,不假装能跑" }
if (-not $env:AICAP_JAVA_BASE_URL) { $env:AICAP_JAVA_BASE_URL = 'http://127.0.0.1:8080' }

# --- 能力如实报告:音频链路要 .models(不提交),没有就只做文本分析 ---
$hasModels = Test-Path (Join-Path $ai '.models')
$modelsNote = if ($hasModels) { '音频转写/说话人分离=已备模型' } else { '音频转写/说话人分离=不可用(缺 ai-service\.models;跑 prepare_stt.py / prepare_diarization.py 下载)' }

if ($CheckOnly) {
    Write-Host "体检(不服务、不调模型):" -ForegroundColor Cyan
    & $python $entry --port $Port --environment-only --check-only
    Write-Host "  $modelsNote"
    exit $LASTEXITCODE
}

$logDir = Join-Path $ai 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$outLog = Join-Path $logDir 'ai-service.log'
$errLog = Join-Path $logDir 'ai-service.err.log'

if ($Foreground) {
    Write-Host "会议智能体(前台): http://127.0.0.1:$Port  —— $modelsNote"
    Write-Host "按 Ctrl+C 停止"
    & $python $entry --port $Port --environment-only
    exit $LASTEXITCODE
}

$p = Start-Process -FilePath $python `
    -ArgumentList 'start_service.py', '--port', "$Port", '--environment-only' `
    -WorkingDirectory $ai -WindowStyle Hidden `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru

# --- 等健康(不只看进程活着:起得来 ≠ 能服务) ---
$deadline = (Get-Date).AddSeconds(60)
$ok = $false
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
        if ($h.status -eq 'ok') { $ok = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if ($ok) {
    Write-Host "会议智能体已启动 PID=$($p.Id)  http://127.0.0.1:$Port  模型=$($env:AICAP_LLM_MODEL)"
    Write-Host "  $modelsNote"
} else {
    Write-Host "[警告] 进程起了但 $Port 健康检查未通过;看日志: $errLog" -ForegroundColor Yellow
}
Write-Host "日志: $outLog"
