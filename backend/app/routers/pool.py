import re

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/pool", tags=["pool"])


def _next_pool_id(db: Session) -> str:
    nums = []
    for (pid,) in db.query(models.PoolItem.id).all():
        m = re.match(r"^R(\d+)$", pid or "")
        if m:
            nums.append(int(m.group(1)))
    return f"R{((max(nums) if nums else 0) + 1):02d}"


@router.get("", response_model=list[schemas.PoolOut])
def list_pool(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.PoolItem).order_by(models.PoolItem.id).all()


@router.post("", response_model=schemas.PoolOut)
def create_pool(body: schemas.PoolIn, db: Session = Depends(get_db),
                _=Depends(security.require_roles("admin", "owner", "member"))):
    pid = _next_pool_id(db)
    item = models.PoolItem(id=pid, **body.model_dump())
    db.add(item)
    db.commit()
    db.refresh(item)
    return item


@router.delete("/{pool_id}")
def delete_pool(pool_id: str, db: Session = Depends(get_db),
                _=Depends(security.require_roles("admin", "owner", "member"))):
    item = db.get(models.PoolItem, pool_id)
    if item is None:
        raise HTTPException(status_code=404, detail="需求池条目不存在")
    db.delete(item)
    db.commit()
    return {"ok": True}


@router.post("/{pool_id}/promote", response_model=schemas.StoryOut)
def promote_pool(pool_id: str, body: schemas.PoolPromoteIn, db: Session = Depends(get_db),
                 user: models.User = Depends(security.require_roles("admin", "owner", "member"))):
    item = db.get(models.PoolItem, pool_id)
    if item is None:
        raise HTTPException(status_code=404, detail="需求池条目不存在")

    def _next_story_id():
        nums = []
        for (sid,) in db.query(models.Story.id).all():
            m = re.match(r"^M(\d+)$", sid or "")
            if m:
                nums.append(int(m.group(1)))
        return f"M{((max(nums) if nums else 0) + 1):02d}"

    if body.owner_id is not None and db.get(models.User, body.owner_id) is None:
        raise HTTPException(422, "所选负责人不存在")
    sid = _next_story_id()
    story = models.Story(id=sid, title=item.title, description=item.description or "（待补充描述）",
                         acceptance=f"来源：{item.source or '需求池'}（待补充验收条件）",
                         priority=item.priority, sprint=body.sprint, activity=body.activity, status=0, owner_id=body.owner_id)
    db.add(story)
    db.delete(item)
    db.add(models.StoryLog(story_id=sid, log_type="create", detail=f"由需求池 {pool_id} 移入 Sprint {body.sprint}，负责人 {body.owner_id or '未分配'}", user_id=user.id))
    db.commit()
    db.refresh(story)
    return story
