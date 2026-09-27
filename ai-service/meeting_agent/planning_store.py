"""Planning persistence reuses Daily's bounded transport and exact-payload retry."""
from .analysis_store import JavaAnalysisStore, StoredAnalysis, RequestId
from .contracts import Identifier
from .planning_contracts import SprintPlanningOutput
from typing import Literal


class StoredPlanningAnalysis(StoredAnalysis):
    # Java captures targeted stories at submission, not full model context.
    result: SprintPlanningOutput


class PersistedPlanningResult(SprintPlanningOutput):
    analysis_id: Identifier
    client_request_id: RequestId
    storage_status: Literal['pending', 'no_changes']

    @classmethod
    def from_record(cls, record: StoredPlanningAnalysis):
        return cls(**record.result.model_dump(), analysis_id=record.id,
                   client_request_id=record.client_request_id, storage_status=record.status)


class JavaPlanningStore(JavaAnalysisStore):
    RECORD_TYPE = StoredPlanningAnalysis
    RESOURCE = 'planning-analyses'
