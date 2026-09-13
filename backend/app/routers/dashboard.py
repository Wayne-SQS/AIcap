from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session

from .. import models, security
from ..database import get_db

router = APIRouter(prefix="/dashboard", tags=["dashboard"])


@router.get("")
def dashboard(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    def count_of(status: int) -> int:
        return db.query(func.count(models.Story.id)).filter(models.Story.status == status).scalar() or 0

    total = db.query(func.count(models.Story.id)).scalar() or 0
    done = count_of(2)
    doing = count_of(1)
    todo = count_of(0)
    percent = round(done / total * 100) if total else 0
    by_sprint = []
    for sp in (1, 2, 3, 4):
        t = db.query(func.count(models.Story.id)).filter(models.Story.sprint == sp).scalar() or 0
        d = db.query(func.count(models.Story.id)).filter(models.Story.sprint == sp,
                                                         models.Story.status == 2).scalar() or 0
        by_sprint.append({"sprint": sp, "total": t, "done": d, "percent": round(d / t * 100) if t else 0})
    return {"total": total, "done": done, "doing": doing, "todo": todo,
            "percent": percent, "by_sprint": by_sprint}
