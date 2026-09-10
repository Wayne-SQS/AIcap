import re
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/tasks", tags=["tasks"])


def _parse_refs(value: str | None, prefix: str) -> list[str]:
    if not value:
        return []
    refs = [part.strip().upper() for part in value.split(",") if part.strip()]
    if len(refs) != len(set(refs)):
        raise HTTPException(status_code=400, detail="关联编号不能重复")
    pattern = r"US\d+" if prefix == "US" else rf"{prefix}\d+"
    if any(not re.fullmatch(pattern, ref) for ref in refs):
        raise HTTPException(status_code=400, detail=f"关联编号必须使用 {prefix}xx 格式，并以逗号分隔")
    return refs


def _has_dependency_cycle(db: Session, task_id: str, dependencies: list[str]) -> bool:
    dependency_map = {
        task.id: _parse_refs(task.depends_on, "T")
        for task in db.query(models.Task).all()
    }
    dependency_map[task_id] = dependencies

    def reaches_target(current: str, visited: set[str]) -> bool:
        if current == task_id:
            return True
        if current in visited:
            return False
        return any(reaches_target(item, visited | {current}) for item in dependency_map.get(current, []))

    return any(reaches_target(item, set()) for item in dependencies)


@router.get("", response_model=list[schemas.TaskOut])
def list_tasks(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.Task).order_by(models.Task.id).all()


@router.patch("/{task_id}", response_model=schemas.TaskOut)
def patch_task(task_id: str, body: schemas.TaskPatch, db: Session = Depends(get_db),
               _=Depends(security.require_roles("admin", "owner", "member"))):
    """Update the shared task record used by board, Gantt and member views."""
    task = db.get(models.Task, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")

    data = body.model_dump(exclude_unset=True)
    if not data:
        return task

    if data.get("owner_id") is not None and db.get(models.User, data["owner_id"]) is None:
        raise HTTPException(status_code=400, detail="负责人不存在")

    if "kanban_card_id" in data and data["kanban_card_id"] is not None:
        if db.get(models.Story, data["kanban_card_id"]) is None:
            raise HTTPException(status_code=400, detail="所属看板卡不存在")

    week_start = data.get("week_start", task.week_start)
    week_end = data.get("week_end", task.week_end)
    if week_start > week_end:
        raise HTTPException(status_code=400, detail="开始周不能晚于结束周")

    if "story_ref" in data:
        story_refs = _parse_refs(data["story_ref"], "US")
        existing_story_ids = {item[0] for item in db.query(models.Story.id).all()}
        missing_stories = [ref for ref in story_refs if ref not in existing_story_ids]
        if missing_stories:
            raise HTTPException(status_code=400, detail=f"关联故事不存在：{', '.join(missing_stories)}")
        data["story_ref"] = ",".join(story_refs)

    if "depends_on" in data:
        dependencies = _parse_refs(data["depends_on"], "T")
        if task_id in dependencies:
            raise HTTPException(status_code=400, detail="任务不能依赖自身")
        existing_task_ids = {item[0] for item in db.query(models.Task.id).all()}
        missing_tasks = [ref for ref in dependencies if ref not in existing_task_ids]
        if missing_tasks:
            raise HTTPException(status_code=400, detail=f"前置任务不存在：{', '.join(missing_tasks)}")
        if _has_dependency_cycle(db, task_id, dependencies):
            raise HTTPException(status_code=400, detail="任务依赖不能形成循环")
        data["depends_on"] = ",".join(dependencies)

    status = data.get("status")
    progress = data.get("progress")
    if status is not None and progress is None:
        progress = {0: 0, 1: 50, 2: 100, 3: task.progress}[status]
    elif progress is not None and status is None:
        status = 2 if progress >= 100 else (1 if progress > 0 else 0)

    for key, value in data.items():
        setattr(task, key, value)
    if "blocked" in data:
        task.blocked = int(bool(data["blocked"]))
    if status is not None:
        task.status = status
        task.progress = progress
    db.commit()
    db.refresh(task)
    return task
