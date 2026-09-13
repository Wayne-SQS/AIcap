@echo off
REM ============================================================
REM 爱管理 AIcap JavaWeb 后端 启动脚本(Windows)
REM 前置:本机已装 JDK 23 + Maven(首次需联网拉依赖)
REM 用法:双击 或 在仓库根目录执行  java-backend\run.bat
REM ============================================================
setlocal
chcp 65001 >nul
cd /d "%~dp0"

REM --- 1. 构建(首次或代码有改动时需执行;也可加 -o 离线) ---
echo [1/3] 编译打包中...
set "JAVA_HOME=C:\Program Files\Java\jdk-23"
call "E:\apache-maven-3.9.9\bin\mvn.cmd" -B -q -DskipTests package
if errorlevel 1 (
  echo [错误] 打包失败,请检查上方日志
  pause
  exit /b 1
)

REM --- 2. 从 backend\.env 读取 JWT_SECRET(与 FastAPI 同 secret,兼容存量 token) ---
echo [2/3] 读取配置...
for /f "usebackq delims=" %%L in ("..\backend\.env") do (
  echo %%L | findstr /b "JWT_SECRET=" >nul && (
    for /f "tokens=1,* delims==" %%A in ("%%L") do set "JWT_SECRET=%%B"
  )
)
if "%JWT_SECRET%"=="" (
  echo [警告] 未读到 JWT_SECRET,使用内置默认值 ^(仅开发^)
)

REM --- 3. 启动 ---
echo [3/3] 启动 Spring Boot (端口 8080)...
echo   访问: http://127.0.0.1:8080/api/health
echo   Swagger: http://127.0.0.1:8080/swagger-ui/index.html
call "C:\Program Files\Java\jdk-23\bin\java.exe" -jar "%~dp0target\aicap-java-backend.jar"
endlocal
