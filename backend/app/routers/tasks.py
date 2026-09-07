from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("", response_model=list[schemas.TaskOut])
def list_tasks(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.Task).order_by(models.Task.id).all()


@router.patch("/{task_id}", response_model=schemas.TaskOut)
def patch_task(task_id: str, body: schemas.TaskPatch, db: Session = Depends(get_db),
               _=Depends(security.get_current_user)):
    """任务局部更新:status/kanban_card_id/week_start/week_end。

    用途:关卡三选一的子任务级联(cancel/keep/detach)、跨 Sprint 挪动未完成子任务。
    kanban_card_id 传 null 表示解绑(脱离看板卡)。
    """
    task = db.get(models.Task, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    data = body.model_dump(exclude_unset=True)
    if not data:
        return task
    if "kanban_card_id" in data and data["kanban_card_id"] is not None:
        if db.get(models.Story, data["kanban_card_id"]) is None:
            raise HTTPException(status_code=400, detail="所属看板卡不存在")
    ws = data.get("week_start", task.week_start)
    we = data.get("week_end", task.week_end)
    if we < ws:
        raise HTTPException(status_code=400, detail="结束周不能早于开始周")
    for key, value in data.items():
        setattr(task, key, value)
    db.commit()
    db.refresh(task)
    return task
