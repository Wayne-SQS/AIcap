from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("", response_model=list[schemas.TaskOut])
def list_tasks(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.Task).order_by(models.Task.id).all()
