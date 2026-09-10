from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy import inspect, text

from .database import Base, SessionLocal, engine
from .routers import auth, dashboard, pool, stories, tasks, meetings, agent
from .seed import seed_all
from . import config
from .meeting_agent.jobs import Worker


@asynccontextmanager
async def lifespan(_: FastAPI):
    Base.metadata.create_all(bind=engine)
    task_columns = {column["name"] for column in inspect(engine).get_columns("tasks")}
    if "depends_on" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN depends_on VARCHAR(100) NULL"))
    if "kanban_card_id" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN kanban_card_id VARCHAR(10) NULL"))
    if "estimated_hours" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN estimated_hours INTEGER NOT NULL DEFAULT 0"))
            connection.execute(text("UPDATE tasks SET estimated_hours = hours WHERE estimated_hours = 0"))
    if "task_type" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN task_type VARCHAR(10) NOT NULL DEFAULT 'feature'"))
    if "status" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN status INTEGER NOT NULL DEFAULT 0"))
    if "progress" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN progress INTEGER NOT NULL DEFAULT 0"))
    if "blocked" not in task_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE tasks ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0"))
    user_columns = {column["name"] for column in inspect(engine).get_columns("users")}
    if "capacity_hours" not in user_columns:
        with engine.begin() as connection:
            connection.execute(text("ALTER TABLE users ADD COLUMN capacity_hours INTEGER NOT NULL DEFAULT 60"))
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
