# ============================================================
# 爱管理 AIcap JavaWeb 后端 — 启动脚本(直接跑已打包 jar)
# 前置:已在 java-backend/ 执行过 mvn package(或 run-build.bat)
# 用法:右键 → 使用 PowerShell 运行
# ============================================================
$ErrorActionPreference = 'Stop'

# 1. 定位仓库根(本文件在 java-backend/ 下)
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here

# 2. 保留当前 PowerShell 环境；仅在变量尚未设置时，从 backend/.env 读取允许项。
$envFile = Join-Path $repo 'backend\.env'
$allowedKeys = @(
  'JWT_SECRET',
  'AICAP_LLM_API_KEY',
  'AICAP_LLM_BASE_URL',
  'AICAP_LLM_MODEL',
  'AICAP_AGENT_WORKER_ENABLED'
)
if (Test-Path $envFile) {
  foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }
    $key = $Matches[1]
    if ($allowedKeys -notcontains $key) { continue }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
      Set-Item -Path "Env:$key" -Value $Matches[2]
    }
  }
}

# 3. 查找可用 Java，优先 JAVA_HOME，再使用当前机器的 Adoptium JDK 和 PATH。
$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$javaCandidates += 'C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot\bin\java.exe'
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) { $javaCandidates += $pathJava.Source }
$java = $javaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $java) { Write-Host '[错误] 未找到 java.exe，请设置 JAVA_HOME 或安装 JDK 23'; exit 1 }

# 4. 启动；控制台输出持续写入文件，避免无人消费的 stdout/stderr 管道阻塞 Worker。
$jar = Join-Path $here 'target\aicap-java-backend.jar'
if (-not (Test-Path $jar)) { Write-Host "[错误] 找不到 $jar,请先执行 run-build.bat"; exit 1 }
$logDir = Join-Path $here 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$consoleLog = Join-Path $logDir 'backend-console.log'
Write-Host "启动后端: http://127.0.0.1:8080/api/health (Swagger: /swagger-ui/index.html)"
Write-Host "运行日志: $consoleLog"
Write-Host "按 Ctrl+C 停止`n"
& $java -jar $jar *>> $consoleLog
