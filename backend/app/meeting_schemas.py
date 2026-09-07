"""Validated contracts for the project-side meeting review MVP."""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class MeetingIn(Input):
    title: str = Field(min_length=1, max_length=200)
    transcript: str = Field(min_length=1, max_length=16000)


class MeetingOut(MeetingIn):
    model_config = ConfigDict(from_attributes=True)
    id: str
    created_by: int
    created_at: datetime


class PoolChanges(Input):
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=10000)
    priority: Literal["Must", "Should", "Could"] = "Could"


class SuggestionIn(Input):
    meeting_id: str = Field(min_length=1, max_length=36)
    client_request_id: str = Field(min_length=1, max_length=80)
    action: Literal["pool.create"] = "pool.create"
    origin: Literal["manual", "agent"] = "manual"
    evidence: str = Field(min_length=1, max_length=5000)
    note: str = Field(default="", max_length=2000)
    changes: PoolChanges


class ReviewIn(Input):
    decision: Literal["approve", "reject", "modify_and_approve"]
    changes: PoolChanges | None = None
    reason: str = Field(default="", max_length=1000)

    @model_validator(mode="after")
    def validate_changes(self):
        if (self.decision == "modify_and_approve") != (self.changes is not None):
            raise ValueError("仅修改后采纳必须提供 changes")
        return self


class SuggestionOut(BaseModel):
    agent_run_id: str | None = None
    approved_changes: PoolChanges | None = None
    id: str
    meeting_id: str
    meeting_title: str
    action: Literal["pool.create"]
    origin: str
    evidence: str
    note: str
    changes: PoolChanges
    status: str
    created_at: datetime
    submitted_by: int
    reviewed_by: int | None
    reviewed_at: datetime | None
    reason: str
    execution_status: str
    pool_item_id: str | None
