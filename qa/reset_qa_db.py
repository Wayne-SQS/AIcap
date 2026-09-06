# -*- coding: utf-8 -*-
"""重置 QA 库 AIcap_qa 到干净播种状态(供前端 E2E 重复执行)。"""
from app.database import Base, SessionLocal, engine
from app.seed import seed_all

Base.metadata.drop_all(bind=engine)
Base.metadata.create_all(bind=engine)
s = SessionLocal()
seed_all(s)
s.close()
print("QA DB reset & seeded: users/stories/tasks ready")
