import re
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/stories", tags=["stories"])


def _next_story_id(db: Session) -> str:
    nums = []
    for (sid,) in db.query(models.Story.id).all():
        m = re.match(r"^US(\d+)$", sid or "")
        if m:
            nums.append(int(m.group(1)))
    return f"US{((max(nums) if nums else 0) + 1):02d}"


def _add_log(db: Session, story_id: str, log_type: str, detail: str, user_id: Optional[int]):
    db.add(models.StoryLog(story_id=story_id, log_type=log_type, detail=detail, user_id=user_id))
    db.commit()


@router.get("/logs", response_model=list[schemas.LogOut])
def recent_logs(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.StoryLog).order_by(models.StoryLog.id.desc()).limit(50).all()


@router.get("", response_model=list[schemas.StoryOut])
def list_stories(owner: Optional[int] = None, sprint: Optional[int] = None,
                 status: Optional[int] = None, activity: Optional[int] = None,
                 priority: Optional[str] = None, q: Optional[str] = None,
                 db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    query = db.query(models.Story)
    if owner is not None:
        query = query.filter(models.Story.owner_id == owner)
    if sprint is not None:
        query = query.filter(models.Story.sprint == sprint)
    if status is not None:
        query = query.filter(models.Story.status == status)
    if activity is not None:
        query = query.filter(models.Story.activity == activity)
    if priority:
        query = query.filter(models.Story.priority == priority)
    if q:
        like = f"%{q}%"
        query = query.filter(models.Story.id.like(like) | models.Story.title.like(like))
    return query.order_by(models.Story.id).all()


@router.post("", response_model=schemas.StoryOut)
def create_story(body: schemas.StoryIn, db: Session = Depends(get_db),
                 user: models.User = Depends(security.require_roles("admin", "owner", "member"))):
    if body.owner_id is not None and db.get(models.User, body.owner_id) is None:
        raise HTTPException(status_code=400, detail="负责人不存在")
    sid = _next_story_id(db)
    story = models.Story(id=sid, **body.model_dump())
    db.add(story)
    db.commit()
    db.refresh(story)
    _add_log(db, sid, "create", story.title, user.id)
    return story


@router.patch("/{story_id}", response_model=schemas.StoryOut)
def patch_story(story_id: str, body: schemas.StoryPatch, db: Session = Depends(get_db),
                user: models.User = Depends(security.require_roles("admin", "owner", "member"))):
    story = db.get(models.Story, story_id)
    if story is None:
        raise HTTPException(status_code=404, detail="故事不存在")
    data = body.model_dump(exclude_unset=True)
    if not data:
        return story
    if data.get("owner_id") is not None and db.get(models.User, data["owner_id"]) is None:
        raise HTTPException(status_code=400, detail="负责人不存在")
    old_status = story.status
    for key, value in data.items():
        setattr(story, key, value)
    db.commit()
    db.refresh(story)
    if "status" in data and old_status != data["status"]:
        _add_log(db, story_id, "move", f"{story.title} → 状态 {story.status}", user.id)
    else:
        _add_log(db, story_id, "edit", story.title, user.id)
    return story


@router.delete("/{story_id}")
def delete_story(story_id: str, undone: str = "keep", db: Session = Depends(get_db),
                 user: models.User = Depends(security.require_roles("admin", "owner"))):
    """删除看板卡,并按 undone 策略级联处理其未完成子任务:
    - cancel: 标记已取消(status=3),真废弃
    - keep:   保留当前状态,仅解绑(脱离该卡继续跟踪)
    - detach: 解绑并整体挪到下个 Sprint 时间轴(+2 周,脱离该卡独立推进)
    已完成子任务一律保留原记录不动。
    """
    story = db.get(models.Story, story_id)
    if story is None:
        raise HTTPException(status_code=404, detail="故事不存在")
    if undone not in ("cancel", "keep", "detach"):
        raise HTTPException(status_code=400, detail="undone 策略仅支持 cancel/keep/detach")
    subs = db.query(models.Task).filter(models.Task.kanban_card_id == story_id).all()
    undone_rows = [t for t in subs if t.status not in (2, 3)]  # 未完成且未取消
    if undone == "cancel":
        for t in undone_rows:
            t.status = 3
            t.kanban_card_id = None  # 解绑,避免 FK 指向已删卡
    else:
        for t in undone_rows:
            t.kanban_card_id = None
            if undone == "detach":
                t.week_start = min(t.week_start + 2, 6)
                t.week_end = min(t.week_end + 2, 6)
    db.delete(story)
    db.commit()
    detail = f"{story.title} · 子任务级联:{undone}({len(undone_rows)} 条)"
    _add_log(db, story_id, "del", detail, user.id)
    return {"ok": True, "undone": undone, "affected": len(undone_rows)}
