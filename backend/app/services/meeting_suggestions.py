"""Proposal construction shared by human entry and Agent; caller owns transaction."""
import hashlib
import json
from uuid import uuid4
from fastapi import HTTPException
from .. import models


def stage_suggestion(db, body, user):
    meeting = db.get(models.Meeting, body.meeting_id)
    if meeting is None:
        raise HTTPException(404, "会议不存在")
    if body.evidence not in meeting.transcript:
        raise HTTPException(422, "证据必须是会议原文中的连续片段")
    fingerprint = hashlib.sha256(json.dumps(body.model_dump(), sort_keys=True,
                                            ensure_ascii=False).encode('utf-8')).hexdigest()
    record = db.query(models.MeetingSuggestionRecord).filter_by(
        meeting_id=body.meeting_id, submitted_by=user.id, client_request_id=body.client_request_id).first()
    if record:
        if record.request_hash != fingerprint:
            raise HTTPException(409, "同一 client_request_id 不能用于不同建议")
        return db.get(models.Suggestion, record.suggestion_id), record
    suggestion = models.Suggestion(
        id='S' + uuid4().hex[:9], agent='手动录入' if body.origin == 'manual' else '会议 Agent',
        kind='meeting', evidence=body.evidence, affected='新需求 → 需求池', note=body.note,
        change_json=json.dumps(body.changes.model_dump(), ensure_ascii=False), status='pending')
    db.add(suggestion)
    db.flush()
    record = models.MeetingSuggestionRecord(
        suggestion_id=suggestion.id, meeting_id=body.meeting_id, submitted_by=user.id,
        client_request_id=body.client_request_id, request_hash=fingerprint, origin=body.origin)
    db.add(record)
    db.flush()
    return suggestion, record
