# AIcap 生产形态部署

这一编排把四个运行部分放入独立容器：MySQL、Spring Boot、会议 Agent 和 Nginx 前端网关。浏览器只访问 Nginx；`/api/`转发到Java，`/meeting-ai/`去掉前缀后转发到Python。数据库没有宿主机端口，Java与Python也不发布端口。

## 准备

需要 Docker Engine 与 Compose v2。先在仓库根目录准备离线语音模型：

```powershell
cd ai-service
.venv/Scripts/python.exe prepare_stt.py
.venv/Scripts/python.exe prepare_diarization.py
cd ..
```

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

仓库验证覆盖Dockerfile/Compose引用、密钥强度、模型文件存在、生产前端构建、Java 21字节码构建和三条线上路由。由于开发机Docker Engine未运行，本轮没有声称容器已在该机器实际启动；在部署机上必须以`verify.py --live-url`成功作为上线完成证据。
