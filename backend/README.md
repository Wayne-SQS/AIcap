# 爱管理 · 后端(FastAPI + MySQL/Docker)

阶段 1 行走骨架:登录、故事/需求池/任务/仪表盘 REST API。

## 启动

1. 启动 Docker Desktop,然后:

```powershell
docker compose -f D:\aiguanli-mysql\docker-compose.yml up -d
```

2. 启动后端(工作目录为 backend):

```powershell
D:\aiguanli-venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

3. 打开 http://127.0.0.1:8000/docs 查看接口文档。

## 默认账号(演示用)

| 账号 | 角色 | 密码 |
|---|---|---|
| 成员1 | admin | 123456 |
| 成员2 | owner | 123456 |
| 成员3 | member | 123456 |
| 成员4 | member | 123456 |

## 测试

```powershell
D:\aiguanli-venv\Scripts\python.exe -m pytest tests -q
```

## 配置

- 连接串/密钥在 `backend/.env`(不入库),模板见 `.env.example`。
- MySQL:库 `AIcap`,容器 `aiguanli-mysql`,宿主机端口 3307,数据卷 `D:\aiguanli-mysql\mysql-data`。
- 启动时自动建表并播种(4 成员 / 20 故事 M01–M20 / 16 任务 T01–T16)。
