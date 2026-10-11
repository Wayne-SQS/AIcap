"""Persist deterministic suggestions in Java, preserving the exact payload on retries."""
import json
from typing import Annotated
from pydantic import Field
from .contracts import Contract, Identifier
from .analysis_store import JavaAnalysisStore, RequestId, StorageError
from .assignment_engine import AssignmentRecommendationRequest, AssignmentRecommendations, recommend


class AssignmentSaveRequest(AssignmentRecommendationRequest):
    client_request_id: RequestId


class StoredAssignment(Contract):
    id: Identifier
    meeting_id: Identifier
    client_request_id: RequestId
    submitted_by: Annotated[int, Field(gt=0)]
    created_at: Identifier
    input: AssignmentRecommendationRequest
    result: AssignmentRecommendations
    review: dict | None


class JavaAssignmentStore(JavaAnalysisStore):
    RESOURCE = 'assignment-suggestions'

    def _record(self, data, meeting_id, key):
        try:
            record = StoredAssignment.model_validate(data)
            if (record.meeting_id != meeting_id or record.result.context.meeting_id != meeting_id
                    or record.client_request_id != key
                    or recommend(record.result.context, record.input) != record.result):
                raise ValueError('record mismatch')
            return record
        except ValueError:
            raise StorageError('invalid_storage_response', 502, key) from None

    def save_suggestion(self, *, token, meeting_id, request, result):
        key = request.client_request_id
        inputs = request.model_dump(exclude={'client_request_id'})
        payload = json.dumps({'client_request_id': key, 'input': inputs,
                              'result': result.model_dump()}, ensure_ascii=False).encode()
        from urllib.parse import quote
        path = '/api/meetings/' + quote(meeting_id, safe='') + '/' + self.RESOURCE
        for attempt in range(2):
            try:
                data = self._request('POST', path, token, key, payload=payload)
                record = self._record(data, meeting_id, key)
                if record.input.model_dump() != inputs or record.result != result:
                    raise StorageError('invalid_storage_response', 502, key)
                return record
            except StorageError as error:
                if error.code not in ('storage_timeout', 'storage_unavailable'):
                    raise
                if attempt == 1:
                    raise StorageError('storage_outcome_unknown', error.status, key) from None
