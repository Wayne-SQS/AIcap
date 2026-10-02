"""Retro candidate storage; shares bounded transport, never creates business actions."""
from typing import Annotated, Literal
from pydantic import Field
from .analysis_store import JavaAnalysisStore, RequestId
from .contracts import Contract, Identifier
from .retro_contracts import RetroMember, SprintRetroOutput


class StoredRetroAnalysis(Contract):
    id: Identifier
    meeting_id: Identifier
    client_request_id: RequestId
    submitted_by: Annotated[int, Field(gt=0)]
    created_at: Identifier
    status: Literal['pending', 'no_changes']
    transcript: Annotated[str, Field(min_length=1, max_length=16000)]
    result: SprintRetroOutput
    member_snapshots: Annotated[list[RetroMember], Field(max_length=20)]


class PersistedRetroResult(SprintRetroOutput):
    analysis_id: Identifier
    client_request_id: RequestId
    storage_status: Literal['pending', 'no_changes']

    @classmethod
    def from_record(cls, record: StoredRetroAnalysis):
        return cls(**record.result.model_dump(), analysis_id=record.id,
                   client_request_id=record.client_request_id, storage_status=record.status)


class JavaRetroStore(JavaAnalysisStore):
    RECORD_TYPE = StoredRetroAnalysis
    RESOURCE = 'retro-analyses'
