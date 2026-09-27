# 每日站会真实服务联调

`run_daily_live.py` 启动独立 MySQL 数据目录、真实 Spring Boot、FastAPI、Vite 和 Edge，使用本机 HTTP 模型夹具，运行分析保存 → 人工审核 → 执行 → 看板核对。浏览器 API 不做 mock，模型输出固定用于核对业务链路，不代表真实模型效果。

## 运行

前置：Windows、MySQL Server 8.0、Java21+、Node、Edge；已安装 frontend 与 ai-service/.venv 依赖。MySQL 默认使用本机安装目录，可通过 `AICAP_TEST_MYSQL_HOME` 覆盖；Java 默认使用本机 IDEA jbr，可通过 `AICAP_TEST_JAVA_HOME` 覆盖。

先在 java-backend 打包（使用可用 Java21，仅本次编译覆盖默认 Java23）：

```powershell
$env:JAVA_HOME='D:/LHWYAN/download/IntelliJ IDEA 2025.1.2/jbr'
& 'D:/LHWYAN/download/Java/apache-maven-3.9.15/bin/mvn.cmd' -B '-Djava.version=21' '-Dmaven.compiler.source=21' '-Dmaven.compiler.target=21' '-DskipTests' package
```

再在仓库根目录执行：

```powershell
ai-service/.venv/Scripts/python.exe qa/run_daily_live.py
```

## 隔离与结果

- 使用专用端口：MySQL 13317、Java 18180、Python 18190、模型夹具 18191、Vite 15173。端口存在监听则停止，不复用现有服务。
- 每次创建 `qa/.daily-live/<随机编号>/mysql`；使用空库 `aicap_daily_e2e`。不连接已有 3306/3307 数据库，不运行旧清库脚本。
- 临时数据库只监听 loopback，初始化 root 空密码仅供本轮实例；Java 使用种子演示用户和独立测试 JWT secret。
- 结束时核对 `@@datadir` 后关闭该 MySQL，并停止本轮创建的服务。数据和日志保留在被 Git 忽略的运行目录，方便复核；运行目录会占用磁盘空间。
- `browser.log` 保存浏览器报告；成功时生成 `result.json`，记录 MySQL 版本、模型调用次数以及数据库计数。
- 验证故事在分析/审核后仍为 0，执行后为 1；分析、审核、执行和 move 日志各一条。已保存分析重试不再调用模型；执行重试返回首次结果；匿名/只读越权请求被拒绝；切换看板可见最新状态。

`frontend/playwright.live.config.js` 应由脚本调用，避免误将 live 用例指向日常数据。前端 28 项模拟接口回归另用 `playwright.status.config.js`。
