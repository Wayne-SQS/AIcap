# Sprint 2 在线 DoD 复测

这些用例使用独立的 `aicap_java_test` MySQL 库、当前分支 Java 服务（8080）、AI 服务（8090）和 Vite 前端（5173）。不要在日常业务库运行夹具；两组依赖 T996—T998 的用例与 Java 全量契约测试也不要并发运行。

1. 确认 P002 只有成员 1、2，且没有 T996、T997、T998；在 `aicap_java_test` 执行 [夹具 SQL](sprint2-dod-fixtures.sql)。脚本固定选择该库，写入三条临时成员关系及三条真实任务。
2. 启动上述三个服务。Java 与 AI 服务必须指向同一隔离库。AI 服务的本地语音模型使用现有 faster-whisper 缓存；`AICAP_STT_EVAL_AUDIO` 指向已存在的评估 MP3，只读使用。
3. 在 `frontend` 目录明确指定三个服务地址后运行用例；`playwright.live.config.js` 的前端默认值是 15173，不能直接当作本指南的 5173 使用：

   ```powershell
   $env:AICAP_LIVE_WEB_BASE = 'http://127.0.0.1:5173'
   $env:AICAP_LIVE_JAVA_BASE = 'http://127.0.0.1:8080'
   $env:AICAP_LIVE_AI_BASE = 'http://127.0.0.1:8090'
   npx playwright test --config playwright.live.config.js e2e-live/sprint2-persisted-task-views.spec.js e2e-live/sprint2-assignment-review.spec.js e2e-live/sprint2-map-cascade.spec.js --workers=1
   ```

   其余 Sprint 2 spec 可用同一配置逐个运行；审核中心另有 `sprint2-review-scope.spec.js`。运行本地识别用例前，还需设置 `AICAP_STT_EVAL_AUDIO` 为评估 MP3 的绝对路径。
4. 用 [清理 SQL](sprint2-dod-fixtures-cleanup.sql)移除三条任务与临时成员关系。录音确认、分析和审核用例会有意保留审计链；只对隔离库运行。

本轮实际执行结果：持久化任务四视图 1/1、Assignment 审核执行 1/1、故事地图跨 Sprint 级联 1/1、真实活动热力图 1/1、录音转写留存 1/1、本地语音识别与人工确认 1/1、双故事 UML 1/1、审核中心 2/2；离线拖拽交互 6/6。Java 全量 736/736、AI 全量 268/268、前端构建通过。语音识别样本是本地评估音频，不等同于团队会议录音；测试中的会议内容也不是教师反馈。
