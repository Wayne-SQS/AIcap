from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas, security
from ..database import get_db

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login", response_model=schemas.TokenOut)
def login(body: schemas.LoginIn, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == body.username).first()
    if user is None or not security.verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    token = security.create_token(user.id)
    return {"access_token": token, "token_type": "bearer", "user": schemas.UserOut.model_validate(user)}


@router.get("/me", response_model=schemas.UserOut)
def me(user: models.User = Depends(security.get_current_user)):
    return user


@router.get("/users", response_model=list[schemas.UserOut])
def list_users(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.User).order_by(models.User.id).all()
