"""All model-produced content is untrusted until validated against this schema."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Evidence(StrictModel):
    segment_id: str = Field(min_length=1, max_length=30)
    quote: str = Field(min_length=1, max_length=2000)


class Fact(StrictModel):
    text: str = Field(min_length=1, max_length=1000)
    evidence: Evidence


class ActionItem(StrictModel):
    description: str = Field(min_length=1, max_length=1000)
    owner_mention: str | None = Field(default=None, max_length=100)
    deadline_text: str | None = Field(default=None, max_length=100)
    evidence: Evidence


class Proposal(StrictModel):
    action: Literal["pool.create"]
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=3000)
    note: str = Field(default="", max_length=1000)
    evidence: Evidence


class Analysis(StrictModel):
    summary: str = Field(min_length=1, max_length=2000)
    decisions: list[Fact] = Field(max_length=20)
    action_items: list[ActionItem] = Field(max_length=20)
    coordination_items: list[ActionItem] = Field(default_factory=list, max_length=20)
    status_constraints: list[Fact] = Field(default_factory=list, max_length=20)
    source_notes: list[Fact] = Field(default_factory=list, max_length=100)
    risks: list[Fact] = Field(max_length=20)
    unresolved_questions: list[str] = Field(max_length=20)
    proposals: list[Proposal] = Field(max_length=10)


class AgentError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message
