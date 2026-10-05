# AIcap 生产形态部署

这一编排把四个运行部分放入独立容器：MySQL、Spring Boot、会议 Agent 和 Nginx 前端网关。浏览器只访问 Nginx；`/api/`转发到Java，`/meeting-ai/`去掉前缀后转发到Python。数据库没有宿主机端口，Java与Python也不发布端口。

## 准备

需要 Docker Engine、Compose v2 和 Python 3.12。克隆后先在仓库根目录建立用于模型准备与部署检查的虚拟环境，并安装语音模型准备依赖：

```powershell
py -3.12 -m venv ai-service/.venv
ai-service/.venv/Scripts/python.exe -m pip install -r ai-service/requirements.txt -r ai-service/requirements-stt.txt -r ai-service/requirements-diarization.txt
Push-Location ai-service
.venv/Scripts/python.exe prepare_stt.py
.venv/Scripts/python.exe prepare_diarization.py
Pop-Location
```

模型文件保存在 `ai-service/.models/`，不会提交到 Git。准备模型需要能访问 Hugging Face 与模型发布站点；如果已有符合检查器所需文件的模型目录，可以按下文 `--model-root` 指向该目录。

复制配置模板并替换全部占位值：

```powershell
Copy-Item deploy/.env.example deploy/.env
```

`DB_ROOT_PASSWORD`和`DB_PASSWORD`至少16字符，`JWT_SECRET`至少32字符。`deploy/.env`已被Git忽略。`AICAP_SEED_ON_START=true`会为全新演示库创建默认账号；有正式用户初始化流程后应改为`false`。画像模型密钥可以留空，会议模型密钥不可为空。

提交构建或启动前先执行不联网、不调用模型的检查：

```powershell
ai-service/.venv/Scripts/python.exe -B deploy/verify.py --env-file deploy/.env
```

## 启动与验证

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
ai-service/.venv/Scripts/python.exe -B deploy/verify.py `
  --env-file deploy/.env --live-url http://127.0.0.1:8088
```

验证同时检查首页、Java与数据库健康、Python健康。首次构建会下载基础镜像和依赖；模型在宿主机只读挂载，不写入镜像。会议音频与MySQL分别保存在命名卷`audio-data`和`mysql-data`。

若部署的是启用播种的演示环境，还可通过进程环境提供账号，追加登录、JWT当前用户和会议列表的只读冒烟；检查器不会输出账号、密码或令牌：

```powershell
$env:AICAP_SMOKE_USERNAME='成员5'
$env:AICAP_SMOKE_PASSWORD='123456'
ai-service/.venv/Scripts/python.exe -B deploy/verify.py `
  --env-file deploy/.env --live-url http://127.0.0.1:8088 --smoke-login
Remove-Item Env:AICAP_SMOKE_USERNAME,Env:AICAP_SMOKE_PASSWORD
```

### 真实浏览器闭环（验收环境）

live 用例默认仍连接 `qa/run_daily_live.py` 启动的隔离服务，也可以通过环境变量改为验证当前容器的 Nginx 同源入口。用例会创建会议并修改演示故事，因此只应对可恢复的验收数据运行；每个用例前从同一份已验证备份恢复基线。

推荐使用编排器一次运行全部七条闭环。它会先校验备份，在每条用例前恢复基线，并在成功、失败或中断后再次恢复；结果保存在已忽略的 `deploy/acceptance-runs/`：

```powershell
ai-service/.venv/Scripts/python.exe -B deploy/run_live_acceptance.py `
  --backup deploy/backups/2026-10-04 --confirm-data-loss
```

可重复传入 `--suite` 只运行部分流程，例如 `--suite assignment --suite daily`。默认转写样本是仓库本机缓存中的四人中文 MP3，也可用 `--stt-audio`、`--stt-language`和`--speaker-count`替换。命令退出码非零表示至少一项失败或最终基线恢复失败，具体结果见 `report.json` 和各套件的 `browser.log`。

需要单独排查某条流程时，也可以手动设置入口：

```powershell
$env:AICAP_LIVE_WEB_BASE='http://127.0.0.1:8088'
$env:AICAP_LIVE_JAVA_BASE='http://127.0.0.1:8088'
$env:AICAP_LIVE_AI_BASE='http://127.0.0.1:8088/meeting-ai'
Push-Location frontend
# 每次只运行一个，并在下一项前重新恢复同一份基线备份。
npx.cmd playwright test --config=playwright.live.config.js assignment-flow.spec.js
# 其余会议闭环：daily、planning、review、retro、refinement、transcription
Pop-Location
```

Refinement 的真实模型验收原文会明确哪些字段未讨论，以检查模型保持未知值；运行该项时再设置：

```powershell
$env:AICAP_LIVE_REAL_MODEL='1'
```

转写版本闭环还需要指定本机音频、语言和预期说话人数：

```powershell
$env:AICAP_STT_EVAL_AUDIO=(Resolve-Path 'ai-service/.stt-eval/feb3b85c968746538023736e92ff9be0/four-speakers.mp3').Path
$env:AICAP_STT_EVAL_LANGUAGE='zh'
$env:AICAP_STT_EVAL_SPEAKERS='4'
Push-Location frontend
npx.cmd playwright test --config=playwright.live.config.js transcription-flow.spec.js
Pop-Location
Remove-Item Env:AICAP_LIVE_WEB_BASE,Env:AICAP_LIVE_JAVA_BASE,Env:AICAP_LIVE_AI_BASE,Env:AICAP_LIVE_REAL_MODEL,Env:AICAP_STT_EVAL_AUDIO,Env:AICAP_STT_EVAL_LANGUAGE,Env:AICAP_STT_EVAL_SPEAKERS -ErrorAction SilentlyContinue
```

查看日志及停止：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml logs -f --tail 200
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

`down`不删除数据卷。只有明确需要清空数据库和音频时才使用`down --volumes`。

## HTTPS 与公网边界

默认只把HTTP网关绑定到`127.0.0.1:8088`。公网服务器应由云负载均衡、Caddy或主机Nginx负责证书和HTTPS，再反向代理到这个地址；不要直接把8088暴露到公网。若仅做受信局域网演示，可在`deploy/.env`设置`AICAP_BIND_ADDRESS=0.0.0.0`，并由主机防火墙限制来源。

备份至少包含两个部分：MySQL逻辑备份，以及`audio-data`卷。应用镜像、前端静态文件和语音模型都可由仓库、构建文件及模型准备脚本重新生成，不应代替业务数据备份。

创建备份时Java和MySQL保持运行；输出目录必须不存在。每份备份包含数据库SQL、音频压缩包和带SHA-256的manifest：

```powershell
ai-service/.venv/Scripts/python.exe -B deploy/data_backup.py `
  --env-file deploy/.env backup --output deploy/backups/2026-10-04
ai-service/.venv/Scripts/python.exe -B deploy/data_backup.py `
  --env-file deploy/.env verify --input deploy/backups/2026-10-04
```

恢复会停止Web、Python和Java，删除并重建`AIcap`数据库，同时清空音频卷后解包，因此必须显式提供数据丢失开关。恢复结束会重新启动三个应用服务；随后仍须运行live验证：

```powershell
ai-service/.venv/Scripts/python.exe -B deploy/data_backup.py `
  --env-file deploy/.env restore --input deploy/backups/2026-10-04 `
  --confirm-data-loss
ai-service/.venv/Scripts/python.exe -B deploy/verify.py `
  --env-file deploy/.env --live-url http://127.0.0.1:8088
```

## 当前验证边界

当前开发机已实际构建并启动四容器编排，完成首页、Java/数据库、会议 Agent、登录读链路验证，并在 Nginx 同源入口上通过 Assignment、Daily、Planning、Review、Retro、Refinement 和四人中文音频转写版本七条浏览器闭环。除 Assignment 外的会议分析均调用当前真实模型配置。该结果证明当前本机配置可运行，不替代目标部署机的模型文件、密钥、持久卷备份恢复及 `verify.py --live-url` 验收。
