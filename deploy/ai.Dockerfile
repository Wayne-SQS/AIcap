FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY ai-service/requirements.txt ai-service/requirements-stt.txt ai-service/requirements-diarization.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r requirements-stt.txt -r requirements-diarization.txt
COPY ai-service/meeting_agent meeting_agent
COPY ai-service/start_service.py start_service.py
RUN chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8090
CMD ["python", "-B", "start_service.py", "--environment-only", "--java-url", "http://java:8080", "--host", "0.0.0.0", "--port", "8090"]
