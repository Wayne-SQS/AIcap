# -*- coding: utf-8 -*-
"""
爱管理 · 血缘迁移校验脚本(只读)
================================
固化 v2 用例文档 DB-01~04 校验,对开发库(或 --qa 指定 QA 库)执行只读检查:
  DB-01 孤儿任务 = 0(feature 且 kanban_card_id 为空)
  DB-02 挂载分布 = 9 卡 × 12 条 feature;看板故事总数 = 23
  DB-03 管理任务 = 恰好 T01/T02/T15/T16
  DB-04 estimated_hours 与 hours 一致

用法(PowerShell,backend 目录):
  D:\\aiguanli-venv\\Scripts\\python.exe -c "import sys; sys.path.insert(0,'<repo>/backend'); sys.path.insert(0,'<repo>/qa'); import verify_migration"
默认连 backend/.env 的 DATABASE_URL(开发库);加环境变量 QA_DB=1 连 AIcap_qa。
每次重建库或执行迁移后运行,全绿方可放行。
"""
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent / "backend"
sys.path.insert(0, str(BACKEND))

from sqlalchemy import create_engine, text  # noqa: E402

from app import config  # noqa: E402

URL = (config.DATABASE_URL.replace("/AIcap?", "/AIcap_qa?")
       if "--qa" in sys.argv or "QA_DB" in sys.argv else config.DATABASE_URL)

CHECKS = [
    ("DB-01 孤儿任务=0",
     "SELECT COUNT(*) FROM tasks WHERE task_type='feature' AND kanban_card_id IS NULL",
     lambda n: n == 0),
    ("DB-02 挂载卡数=9",
     "SELECT COUNT(DISTINCT kanban_card_id) FROM tasks WHERE task_type='feature'",
     lambda n: n == 9),
    ("DB-02 feature 子任务=12",
     "SELECT COUNT(*) FROM tasks WHERE task_type='feature'",
     lambda n: n == 12),
    ("DB-02 看板故事总数=23",
     "SELECT COUNT(*) FROM stories",
     lambda n: n == 23),
    ("DB-03 管理任务=T01/T02/T15/T16",
     "SELECT GROUP_CONCAT(id ORDER BY id) FROM tasks WHERE task_type='management'",
     lambda s: s == "T01,T02,T15,T16"),
    ("DB-04 estimated_hours=hours",
     "SELECT COUNT(*) FROM tasks WHERE estimated_hours <> hours",
     lambda n: n == 0),
]


def main():
    print(f"目标库: {URL.split('@')[-1].split('?')[0]}")
    engine = create_engine(URL)
    failed = 0
    with engine.connect() as conn:
        for name, sql, ok in CHECKS:
            value = conn.execute(text(sql)).scalar()
            verdict = "PASS" if ok(value) else "FAIL"
            failed += verdict == "FAIL"
            print(f"  [{verdict}] {name} :: 实际值 = {value!r}")
    print(f"===== 迁移校验: {len(CHECKS) - failed}/{len(CHECKS)} 通过 =====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
