"""Meetings and proposal-only ingestion; no model or automatic write access."""
import hashlib
import json
from datetime import datetime
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import models, security
from ..database import get_db
from ..meeting_schemas import MeetingIn, MeetingOut, ReviewIn, SuggestionIn, SuggestionOut

router = APIRouter(tags=["meeting-review"])
writer = security.require_roles("admin", "owner", "member")
reviewer = security.require_roles("admin", "owner")


def _rows(db, suggestion_id):
    record = db.query(models.MeetingSuggestionRecord).filter_by(suggestion_id=suggestion_id).first()
    if record is None:
        raise HTTPException(404, "会议建议不存在")
    return db.get(models.Suggestion, suggestion_id), record


def _out(db, suggestion, record):
    meeting = db.get(models.Meeting, record.meeting_id)
    return SuggestionOut(
        id=suggestion.id, meeting_id=meeting.id, meeting_title=meeting.title,
        action="pool.create", origin=record.origin, evidence=suggestion.evidence,
        note=suggestion.note, changes=json.loads(suggestion.change_json),
        status=suggestion.status, created_at=suggestion.created_at,
        submitted_by=record.submitted_by, reviewed_by=record.reviewed_by,
        reviewed_at=record.reviewed_at, reason=record.reason,
        execution_status=record.execution_status, pool_item_id=record.pool_item_id,
    )


@router.post("/meetings", response_model=MeetingOut, status_code=201)
def create_meeting(body: MeetingIn, db: Session = Depends(get_db), user=Depends(writer)):
    meeting = models.Meeting(id=str(uuid4()), created_by=user.id, **body.model_dump())
    db.add(meeting)
    db.commit()
    db.refresh(meeting)
    return meeting


@router.get("/meetings", response_model=list[MeetingOut])
def list_meetings(db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    return db.query(models.Meeting).order_by(models.Meeting.created_at.desc(), models.Meeting.id).all()


@router.get("/meetings/{meeting_id}", response_model=MeetingOut)
def get_meeting(meeting_id: str, db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    meeting = db.get(models.Meeting, meeting_id)
    if meeting is None:
        raise HTTPException(404, "会议不存在")
    return meeting


@router.post("/suggestions", response_model=SuggestionOut)
def submit_suggestion(body: SuggestionIn, db: Session = Depends(get_db), user=Depends(writer)):
    meeting = db.get(models.Meeting, body.meeting_id)
    if meeting is None:
        raise HTTPException(404, "会议不存在")
    if body.evidence not in meeting.transcript:
        raise HTTPException(422, "证据必须是会议原文中的连续片段")
    fingerprint = hashlib.sha256(
        json.dumps(body.model_dump(), sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()

    def previous():
        return db.query(models.MeetingSuggestionRecord).filter_by(
            meeting_id=body.meeting_id, submitted_by=user.id,
            client_request_id=body.client_request_id).first()

    def replay(record):
        if record.request_hash != fingerprint:
            raise HTTPException(409, "同一 client_request_id 不能用于不同建议")
        return _out(db, db.get(models.Suggestion, record.suggestion_id), record)

    existing = previous()
    if existing:
        return replay(existing)
    # Retry the rare short-ID collision without changing the ingestion key.
    for _ in range(3):
        suggestion = models.Suggestion(
            id="S" + uuid4().hex[:9], agent="手动录入" if body.origin == "manual" else "外部 Agent（提交方标记）",
            kind="meeting", evidence=body.evidence, affected="新需求 → 需求池",
            note=body.note, change_json=json.dumps(body.changes.model_dump(), ensure_ascii=False),
            status="pending")
        record = models.MeetingSuggestionRecord(
            suggestion_id=suggestion.id, meeting_id=body.meeting_id,
            submitted_by=user.id, client_request_id=body.client_request_id,
            request_hash=fingerprint, origin=body.origin)
        try:
            db.add(suggestion)
            db.flush()
            db.add(record)
            db.commit()
            return _out(db, suggestion, record)
        except IntegrityError:
            db.rollback()
            existing = previous()
            if existing:
                return replay(existing)
    raise HTTPException(409, "建议编号冲突，请重试")


@router.get("/suggestions", response_model=list[SuggestionOut])
def list_suggestions(meeting_id: str | None = None, db: Session = Depends(get_db),
                     _=Depends(security.get_current_user)):
    query = db.query(models.Suggestion, models.MeetingSuggestionRecord).join(
        models.MeetingSuggestionRecord,
        models.MeetingSuggestionRecord.suggestion_id == models.Suggestion.id)
    if meeting_id:
        query = query.filter(models.MeetingSuggestionRecord.meeting_id == meeting_id)
    return [_out(db, suggestion, record) for suggestion, record in
            query.order_by(models.Suggestion.created_at.desc(), models.Suggestion.id).all()]


@router.get("/suggestions/{suggestion_id}", response_model=SuggestionOut)
def get_suggestion(suggestion_id: str, db: Session = Depends(get_db),
                   _=Depends(security.get_current_user)):
    return _out(db, *_rows(db, suggestion_id))


@router.post("/suggestions/{suggestion_id}/review", response_model=SuggestionOut)
def review_suggestion(suggestion_id: str, body: ReviewIn, db: Session = Depends(get_db),
                      user=Depends(reviewer)):
    suggestion, record = _rows(db, suggestion_id)
    target_status = "approved" if body.decision == "approve" else "rejected"
    # Atomic claim works on MySQL and SQLite. Business write and audit share this transaction.
    claimed = db.execute(update(models.Suggestion).where(
        models.Suggestion.id == suggestion_id, models.Suggestion.status == "pending"
    ).values(status=target_status).execution_options(synchronize_session=False)).rowcount
    if not claimed:
        db.rollback()
        suggestion, record = _rows(db, suggestion_id)
        if suggestion.status != target_status:
            raise HTTPException(409, "建议已审核，不能改变审核结论")
        return _out(db, suggestion, record)

    record.reviewed_by = user.id
    record.reviewed_at = datetime.utcnow()
    record.reason = body.reason
    if body.decision == "approve":
        # Dedicated A namespace avoids the legacy R max+1 allocator; one row per proposal.
        pool_id = f"A{record.id:09d}"
        if len(pool_id) > 10:
            db.rollback()
            raise HTTPException(409, "需求池编号容量已满")
        changes = json.loads(suggestion.change_json)
        db.add(models.PoolItem(id=pool_id, source=f"会议 {record.meeting_id} / 建议 {suggestion_id}",
                               **changes))
        record.pool_item_id = pool_id
        record.execution_status = "succeeded"
    else:
        record.execution_status = "not_needed"
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "执行冲突，未写入任何变更；请检查需求池后重试")
    db.refresh(suggestion)
    return _out(db, suggestion, record)
