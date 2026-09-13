# ============================================================
# 爱管理 AIcap JavaWeb 后端 — 构建脚本(打包 jar)
# 用法:右键 → 使用 PowerShell 运行;产物在 target/aicap-java-backend.jar
# ============================================================
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
Write-Host '编译打包中(跳过测试)...'
& 'E:\apache-maven-3.9.9\bin\mvn.cmd' -B -q -DskipTests package
if ($LASTEXITCODE -eq 0) {
  Write-Host "[完成] jar: $here\target\aicap-java-backend.jar"
  Write-Host "启动: 右键 run.ps1 → 使用 PowerShell 运行"
} else {
  Write-Host "[失败] 打包出错,见上方日志"
}
