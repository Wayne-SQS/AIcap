# ============================================================
# 爱管理 AIcap JavaWeb 后端 — 启动脚本(直接跑已打包 jar)
# 前置:已在 java-backend/ 执行过 mvn package(或 run-build.bat)
# 用法:右键 → 使用 PowerShell 运行
# ============================================================
$ErrorActionPreference = 'Stop'

# 1. 定位仓库根(本文件在 java-backend/ 下)
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here

# 2. 从 backend/.env 读取 JWT_SECRET(与 FastAPI 同一把,兼容存量 token)
$envFile = Join-Path $repo 'backend\.env'
$secret = $null
if (Test-Path $envFile) {
  $line = Get-Content $envFile | Where-Object { $_ -match '^JWT_SECRET=' } | Select-Object -First 1
  if ($line) { $secret = ($line -split '=', 2)[1] }
}
if ($secret) { $env:JWT_SECRET = $secret; Write-Host "JWT_SECRET 已从 backend\.env 注入" }
else { Write-Host "警告: 未读到 JWT_SECRET,使用内置默认值(仅开发)" }

# 3. 启动
$jar = Join-Path $here 'target\aicap-java-backend.jar'
if (-not (Test-Path $jar)) { Write-Host "[错误] 找不到 $jar,请先执行 run-build.bat"; exit 1 }
Write-Host "启动后端: http://127.0.0.1:8080/api/health (Swagger: /swagger-ui/index.html)"
Write-Host "按 Ctrl+C 停止`n"
& 'C:\Program Files\Java\jdk-23\bin\java.exe' -jar $jar
