from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import text

from .database import Base, SessionLocal, engine
from .routers import auth, dashboard, pool, stories, tasks, meetings, agent
from .seed import seed_all
from . import config
from .meeting_agent.jobs import Worker


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        seed_all(db)
    finally:
        db.close()
    worker = Worker(SessionLocal) if config.AICAP_AGENT_WORKER_ENABLED else None
    if worker:
        worker.start()
    try:
        yield
    finally:
        if worker:
            worker.close()


app = FastAPI(title="爱管理 API", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 开发期放开;上线前收紧为前端域名
    allow_methods=["*"],
    allow_headers=["*"],
)

for router in (auth.router, stories.router, pool.router, tasks.router, dashboard.router, meetings.router, agent.router):
    app.include_router(router, prefix="/api")


@app.get("/api/health")
def health():
    ok = False
    db = SessionLocal()
    try:
        db.execute(text("SELECT 1"))
        ok = True
    except Exception:
        ok = False
    finally:
        db.close()
    return {"status": "ok" if ok else "db_error", "db": ok}
