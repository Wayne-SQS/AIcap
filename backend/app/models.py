from datetime import datetime

from sqlalchemy import Column, DateTime, ForeignKey, Integer, String, Text

from .database import Base


class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, autoincrement=True)
    username = Column(String(50), unique=True, nullable=False, index=True)
    display_name = Column(String(50), nullable=False)
    role = Column(String(20), nullable=False, default="member")
    password_hash = Column(String(255), nullable=False)
    color = Column(String(20), nullable=False, default="green")


class Story(Base):
    __tablename__ = "stories"
    id = Column(String(10), primary_key=True)
    title = Column(String(200), nullable=False)
    description = Column(Text, nullable=False, default="")
    acceptance = Column(Text, nullable=False, default="")
    priority = Column(String(10), nullable=False, default="Must")
    sprint = Column(Integer, nullable=False, default=1)
    activity = Column(Integer, nullable=False, default=2)
    status = Column(Integer, nullable=False, default=0)  # 0待办 1进行中 2完成
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class StoryLog(Base):
    __tablename__ = "story_logs"
    id = Column(Integer, primary_key=True, autoincrement=True)
    story_id = Column(String(10), nullable=False)
    log_type = Column(String(20), nullable=False)
    detail = Column(String(500), nullable=False, default="")
    user_id = Column(Integer, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class Task(Base):
    __tablename__ = "tasks"
    id = Column(String(10), primary_key=True)
    name = Column(String(200), nullable=False)
    owner_id = Column(Integer, nullable=False)
    hours = Column(Integer, nullable=False, default=0)
    week_start = Column(Integer, nullable=False)
    week_end = Column(Integer, nullable=False)
    story_ref = Column(String(100), nullable=True)
    # 看板与甘特「强制血缘对应」重构字段
    kanban_card_id = Column(String(10), ForeignKey("stories.id"), nullable=True)  # 所属看板卡(管理类任务为 NULL)
    estimated_hours = Column(Integer, nullable=False, default=0)  # 预估工时(加权进度分母)
    task_type = Column(String(10), nullable=False, default="feature")  # feature=开发任务挂卡 / management=管理任务
    status = Column(Integer, nullable=False, default=0)  # 0待办 1进行中 2完成 3已取消


class PoolItem(Base):
    __tablename__ = "pool_items"
    id = Column(String(10), primary_key=True)
    title = Column(String(200), nullable=False)
    description = Column(Text, nullable=False, default="")
    source = Column(String(200), nullable=False, default="")
    priority = Column(String(10), nullable=False, default="Could")
    created_at = Column(DateTime, default=datetime.utcnow)


class Suggestion(Base):
    __tablename__ = "suggestions"
    id = Column(String(10), primary_key=True)
    agent = Column(String(50), nullable=False)
    kind = Column(String(20), nullable=False)
    evidence = Column(Text, nullable=False, default="")
    affected = Column(String(200), nullable=False, default="")
    note = Column(Text, nullable=False, default="")
    change_json = Column(Text, nullable=False, default="[]")
    status = Column(String(20), nullable=False, default="pending")
    created_at = Column(DateTime, default=datetime.utcnow)
