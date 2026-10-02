"""Refinement records use the shared bounded transport and exact-payload retries."""
from typing import Annotated, Literal
from pydantic import Field

from .analysis_store import JavaAnalysisStore, StoredAnalysis, RequestId
from .contracts import Identifier, StorySnapshot
from .refinement_contracts import BacklogRefinementOutput


class StoredRefinementAnalysis(StoredAnalysis):
    result: BacklogRefinementOutput
    story_snapshots: Annotated[list[StorySnapshot], Field(max_length=1000)]


class PersistedRefinementResult(BacklogRefinementOutput):
    analysis_id: Identifier
    client_request_id: RequestId
    storage_status: Literal['pending', 'no_changes']

    @classmethod
    def from_record(cls, record: StoredRefinementAnalysis):
        return cls(**record.result.model_dump(), analysis_id=record.id,
                   client_request_id=record.client_request_id, storage_status=record.status)


class JavaRefinementStore(JavaAnalysisStore):
    RECORD_TYPE = StoredRefinementAnalysis
    RESOURCE = 'refinement-analyses'
