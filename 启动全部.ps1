# ============================================================
# 开发态一键启动(Windows)
#
# 为什么需要它:这套系统是**多进程**的,少一个就有一块功能不可用 ——
#   8080 Java 平台      ← 规划/画像/项目生成三个智能体是它**进程内的 worker**(不用单独起)
#   8090 会议智能体      ← 独立 Python/LangGraph 进程(不持数据库连接,经 Java 接口写回)
#   8100 主管(编排层)    ← 独立 Python 进程
#   5173 前端(Vite)
#   3307/6333 MySQL+Qdrant(Docker Desktop;它不自启)
# 生产态不用这个:deploy\compose.yaml 五个服务 + healthcheck + depends_on,一条 compose up 全起。
#
# 用法:powershell -ExecutionPolicy Bypass -File "启动全部.ps1"
#   -Build             顺带重建 Java jar(先停机,jar 会被 java -jar 锁住)
#   -SkipAi            不起会议智能体
#   -SkipOrchestrator  不起主管编排层
#   -SkipFrontend      不起前端
#   -NoDocker          跳过 Docker 检查(容器已在跑时更快)
# ============================================================
param(
    [switch]$Build,
    [switch]$SkipAi,
    [switch]$SkipOrchestrator,
    [switch]$SkipFrontend,
    [switch]$NoDocker
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$results = New-Object System.Collections.ArrayList

function Add-Result([string]$name, [string]$port, [string]$state, [string]$note) {
    [void]$results.Add([pscustomobject]@{ 服务 = $name; 端口 = $port; 状态 = $state; 说明 = $note })
}

function Wait-Port([int]$port, [int]$seconds = 60) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-Http([string]$url, [int]$seconds = 60) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try { return Invoke-RestMethod -Uri $url -TimeoutSec 3 } catch { Start-Sleep -Seconds 2 }
    }
    return $null
}

# ---------------------------------------------------------------- Docker
if (-not $NoDocker) {
    Write-Host "[1/4] Docker: 检查 MySQL(3307) 与 Qdrant(6333) ..." -ForegroundColor Cyan
    $dockerUp = $false
    try { $null = docker info --format '{{.ServerVersion}}' 2>$null; $dockerUp = ($LASTEXITCODE -eq 0) } catch { $dockerUp = $false }
    if (-not $dockerUp) {
        $exe = Join-Path ${env:ProgramFiles} 'Docker\Docker\Docker Desktop.exe'
        if (Test-Path $exe) {
            Write-Host "      Docker Desktop 没在跑(它不自启),拉起它并等就绪(~30-90s) ..."
            Start-Process $exe
            $deadline = (Get-Date).AddSeconds(180)
            while ((Get-Date) -lt $deadline) {
                try { $null = docker info --format '{{.ServerVersion}}' 2>$null; if ($LASTEXITCODE -eq 0) { $dockerUp = $true; break } } catch { }
                Start-Sleep -Seconds 5
            }
        } else {
            Write-Host "      [警告] 找不到 Docker Desktop,跳过" -ForegroundColor Yellow
        }
    }
    foreach ($p in 3307, 6333) {
        $ok = Wait-Port -port $p -seconds 120
        $label = if ($p -eq 3307) { 'MySQL' } else { 'Qdrant' }
        Add-Result $label "$p" $(if ($ok) { 'OK' } else { 'FAIL' }) $(if ($ok) { 'Docker 容器' } else { '容器没起来:docker ps 看看' })
    }
} else {
    Add-Result 'Docker' '3307/6333' 'SKIP' '-NoDocker'
}

# ------------------------------------------------- Java 平台 + 会议智能体
Write-Host "[2/4] Java 平台(8080) + 会议智能体(8090) ..." -ForegroundColor Cyan
$backendLauncher = Join-Path $repo 'java-backend\启动后端.ps1'
& $backendLauncher -Build:$Build -SkipAi:$SkipAi -AiPort 8090
$javaOk = $null -ne (Wait-Http -url 'http://127.0.0.1:8080/api/health' -seconds 30)
Add-Result 'Java 平台' '8080' $(if ($javaOk) { 'OK' } else { 'FAIL' }) '规划/画像/项目生成是它的进程内 worker'
if (-not $SkipAi) {
    $aiHealth = Wait-Http -url 'http://127.0.0.1:8090/health' -seconds 30
    Add-Result '会议智能体' '8090' $(if ($aiHealth) { 'OK' } else { 'FAIL' }) $(if (Test-Path (Join-Path $repo 'ai-service\.models')) { '文本+音频' } else { '仅文本分析(缺 .models)' })
} else {
    Add-Result '会议智能体' '8090' 'SKIP' '-SkipAi'
}

# ---------------------------------------------------------------- 主管编排层
if (-not $SkipOrchestrator) {
    Write-Host "[3/4] 主管编排层(8100) ..." -ForegroundColor Cyan
    $orch = Join-Path $repo 'agent-orchestrator'
    if (Get-NetTCPConnection -LocalPort 8100 -State Listen -ErrorAction SilentlyContinue) {
        Add-Result '主管编排层' '8100' 'OK' '已在运行,跳过启动'
    } else {
        $python = Join-Path $orch '.venv\Scripts\python.exe'
        if (-not (Test-Path $python)) {
            Add-Result '主管编排层' '8100' 'FAIL' "缺 venv: cd agent-orchestrator; python -m venv .venv; .venv\Scripts\python -m pip install -e `"[dev]`""
        } else {
            $logDir = Join-Path $orch 'logs'
            New-Item -ItemType Directory -Force -Path $logDir | Out-Null
            $env:JAVA_BASE_URL = 'http://127.0.0.1:8080'
            $p = Start-Process -FilePath $python -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8100' `
                -WorkingDirectory $orch -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logDir 'orchestrator.log') `
                -RedirectStandardError (Join-Path $logDir 'orchestrator.err.log') -PassThru
            # 8100 在 Java 没起时**照样返回 200**(java:false)—— 所以这里必须看 java 字段,不能只看 200
            $h = Wait-Http -url 'http://127.0.0.1:8100/health' -seconds 60
            if ($h) {
                Add-Result '主管编排层' '8100' 'OK' $(if ($h.java) { 'java 可达' } else { 'java 不可达(平台没起?主管只会如实报错)' })
            } else {
                Add-Result '主管编排层' '8100' 'FAIL' '看 agent-orchestrator\logs\orchestrator.err.log'
            }
        }
    }
} else {
    Add-Result '主管编排层' '8100' 'SKIP' '-SkipOrchestrator'
}

# ---------------------------------------------------------------- 前端
if (-not $SkipFrontend) {
    Write-Host "[4/4] 前端(5173) ..." -ForegroundColor Cyan
    if (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue) {
        Add-Result '前端' '5173' 'OK' '已在运行,跳过启动(Vite 只监听 [::1],用 localhost 访问)'
    } else {
        $frontend = Join-Path $repo 'frontend'
        $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
        if (-not $npm) { $npm = Join-Path ${env:ProgramFiles} 'nodejs\npm.cmd' }
        if (-not (Test-Path $npm)) {
            Add-Result '前端' '5173' 'FAIL' '找不到 npm.cmd;确认 Node 已装'
        } else {
            $logDir = Join-Path $frontend 'logs'
            New-Item -ItemType Directory -Force -Path $logDir | Out-Null
            $p = Start-Process -FilePath $npm -ArgumentList 'run', 'dev' -WorkingDirectory $frontend -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logDir 'vite.log') -RedirectStandardError (Join-Path $logDir 'vite.err.log') -PassThru
            $ok = Wait-Port -port 5173 -seconds 60
            Add-Result '前端' '5173' $(if ($ok) { 'OK' } else { 'FAIL' }) $(if ($ok) { 'http://localhost:5173' } else { '看 frontend\logs\vite.err.log' })
        }
    }
} else {
    Add-Result '前端' '5173' 'SKIP' '-SkipFrontend'
}

# ---------------------------------------------------------------- 状态表
Write-Host ""
Write-Host "================= 启动结果 =================" -ForegroundColor Green
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "入口: http://localhost:5173   (Vite 只监听 [::1],127.0.0.1:5173 连不上)"
Write-Host "健康: Java 8080/api/health(看 db=true) · 会议智能体 8090/health · 主管 8100/health(看 java=true)"
Write-Host "生产跑法见 deploy\README.md(compose 五服务一条命令,不挨个起)"
