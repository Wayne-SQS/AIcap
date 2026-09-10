from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class LoginIn(BaseModel):
    username: str
    password: str


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    display_name: str
    role: str
    color: str
    capacity_hours: int


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserOut


class StoryIn(BaseModel):
    title: str = Field(..., max_length=200)
    description: str = ""
    acceptance: str = ""
    priority: Literal["Must", "Should", "Could"] = "Must"
    sprint: int = Field(default=1, ge=1, le=4)
    activity: int = Field(default=2, ge=1, le=5)
    status: int = Field(default=0, ge=0, le=2)
    owner_id: Optional[int] = None


class StoryPatch(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    acceptance: Optional[str] = None
    priority: Optional[Literal["Must", "Should", "Could"]] = None
    sprint: Optional[int] = Field(default=None, ge=1, le=4)
    activity: Optional[int] = Field(default=None, ge=1, le=5)
    status: Optional[int] = Field(default=None, ge=0, le=2)
    owner_id: Optional[int] = None


class StoryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    title: str
    description: str
    acceptance: str
    priority: str
    sprint: int
    activity: int
    status: int
    owner_id: Optional[int]


class PoolIn(BaseModel):
    title: str = Field(..., max_length=200)
    description: str = ""
    source: str = ""
    priority: Literal["Must", "Should", "Could"] = "Could"


class PoolOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    title: str
    description: str
    source: str
    priority: str


class TaskOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    owner_id: int
    hours: int
    week_start: int
    week_end: int
    story_ref: Optional[str]
    kanban_card_id: Optional[str] = None
    estimated_hours: int = 0
    task_type: str = "feature"
    depends_on: Optional[str]
    status: int
    progress: int
    blocked: bool
    sprints: list[int]


class TaskPatch(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=200)
    owner_id: Optional[int] = None
    hours: Optional[int] = Field(default=None, ge=0, le=999)
    week_start: Optional[int] = Field(default=None, ge=1, le=6)
    week_end: Optional[int] = Field(default=None, ge=1, le=6)
    story_ref: Optional[str] = Field(default=None, max_length=100)
    kanban_card_id: Optional[str] = None
    estimated_hours: Optional[int] = Field(default=None, ge=0, le=999)
    task_type: Optional[Literal["feature", "management"]] = None
    depends_on: Optional[str] = Field(default=None, max_length=100)
    status: Optional[int] = Field(default=None, ge=0, le=3)
    progress: Optional[int] = Field(default=None, ge=0, le=100)
    blocked: Optional[bool] = None


class LogOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    story_id: str
    log_type: str
    detail: str
    created_at: datetime


class DashboardOut(BaseModel):
    total: int
    done: int
    doing: int
    todo: int
    percent: int
    by_sprint: list[dict]


class PoolPromoteIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sprint: int = Field(ge=1, le=4)
    owner_id: int | None = Field(default=None, ge=1)
    activity: int = Field(default=2, ge=1, le=5)
