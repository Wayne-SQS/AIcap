"""Review records use the shared bounded transport and exact-payload retries."""
from typing import Literal

from .analysis_store import JavaAnalysisStore, StoredAnalysis, RequestId
from .contracts import Identifier
from .review_contracts import SprintReviewOutput


class StoredReviewAnalysis(StoredAnalysis):
    result: SprintReviewOutput


class PersistedReviewResult(SprintReviewOutput):
    analysis_id: Identifier
    client_request_id: RequestId
    storage_status: Literal['pending', 'no_changes']

    @classmethod
    def from_record(cls, record: StoredReviewAnalysis):
        return cls(**record.result.model_dump(), analysis_id=record.id,
                   client_request_id=record.client_request_id, storage_status=record.status)


class JavaReviewStore(JavaAnalysisStore):
    RECORD_TYPE = StoredReviewAnalysis
    RESOURCE = 'review-analyses'
