FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# 故意**不**整目录 COPY agent-orchestrator/：该目录下有本地 .env（里面是真实密钥），
# 而根 .dockerignore 并不挡 .env —— 整目录拷会把密钥烤进镜像层，
# 且镜像推到任何地方都还能翻出来。只拷"构建与运行必需的那几样"。
COPY agent-orchestrator/pyproject.toml ./
COPY agent-orchestrator/app ./app

# 依赖与源码一起装成包：`pyproject.toml` 里的区间（含上界）是
# tests/test_dependencies.py 钉住的唯一真源，Dockerfile 里再抄一份依赖列表
# 就是给它开一条绕过测试的旁路。
RUN pip install --no-cache-dir .

RUN chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8100

# -B 避免写 .pyc：只读 rootfs 之外没有可写目录时 pip/uvicorn 会告警，
# 且容器重建时残留的字节码没有任何收益。
CMD ["python", "-B", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
