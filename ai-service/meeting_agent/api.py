"""Authenticated, synchronous text-analysis API. No project writes or approvals."""

import os
from typing import Annotated, Callable, Literal

from fastapi import Depends, FastAPI, Path
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import Field

from .contracts import Contract
from .analysis_store import JavaAnalysisStore, PersistedResult, RequestId, StorageError
from .meeting_access import AccessError, JavaMeetingAccess
from .model_client import ChatModelClient, ModelError, ModelSettings
from .story_tool import StoryReadTool, StoryToolError
from .workflow import CompletionModel, DailyScrumWorkflow, WorkflowError
from .planning_context import PlanningContextReader, PlanningContextError
from .planning_workflow import SprintPlanningWorkflow
from .planning_store import JavaPlanningStore, PersistedPlanningResult
from .review_store import JavaReviewStore, PersistedReviewResult
from .review_workflow import SprintReviewWorkflow
from .retro_store import JavaRetroStore, PersistedRetroResult
from .retro_workflow import SprintRetroWorkflow
from .refinement_store import JavaRefinementStore, PersistedRefinementResult
from .refinement_workflow import BacklogRefinementWorkflow
from .member_tool import MemberReadTool, MemberToolError
from .task_tool import TaskReadTool, TaskToolError
from .assignment_context import AssignmentContextRequest, AssignmentContextReport, AssignmentContextError, prepare_assignment
from .assignment_engine import AssignmentRecommendationRequest, AssignmentRecommendations, recommend
from .assignment_store import AssignmentSaveRequest, StoredAssignment, JavaAssignmentStore
from .audio_tool import AudioReadTool, AudioReadError, AudioInputRequest, PreparedAudio
from .transcription import LocalTranscriber, TranscriptionRequest, TranscriptionDraft, TranscriptionError
from .diarization import LocalDiarizer, DiarizationRequest, DiarizationPreview


class AnalyzeRequest(Contract):
    client_request_id: RequestId
    meeting_type: Literal["daily_scrum"]
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None


class PlanningAnalyzeRequest(Contract):
    client_request_id: RequestId
    meeting_type: Literal['sprint_planning']
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None = None


class ReviewAnalyzeRequest(AnalyzeRequest):
    meeting_type: Literal['sprint_review']


class RetroAnalyzeRequest(AnalyzeRequest):
    meeting_type: Literal['sprint_retrospective']


class RefinementAnalyzeRequest(AnalyzeRequest):
    meeting_type: Literal['backlog_refinement']


def create_app(*, backend_origin: str | None = None,
               model_factory: Callable[[], CompletionModel] | None = None) -> FastAPI:
    origin = backend_origin if backend_origin is not None else os.environ.get("AICAP_JAVA_BASE_URL", "http://localhost:8080")
    access = JavaMeetingAccess(origin)
    stories = StoryReadTool(origin)
    store = JavaAnalysisStore(origin)
    planning_store = JavaPlanningStore(origin)
    review_store = JavaReviewStore(origin)
    retro_store = JavaRetroStore(origin)
    refinement_store = JavaRefinementStore(origin)
    assignment_store = JavaAssignmentStore(origin)
    audio_reader = AudioReadTool(origin)
    transcriber = LocalTranscriber()
    diarizer = LocalDiarizer()
    members = MemberReadTool(origin)
    planning_reader = PlanningContextReader(stories, MemberReadTool(origin), TaskReadTool(origin))
    make_model = model_factory or (lambda: ChatModelClient(ModelSettings.from_env()))
    app = FastAPI(title="AIcap Meeting Agent", version="0.1.0")
    bearer = HTTPBearer(auto_error=False)

    def error_response(code: str, status: int):
        return JSONResponse({"detail": code}, status_code=status,
                            headers={"WWW-Authenticate": "Bearer"} if status == 401 else None)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # Default validation errors can echo rejected fields (including secrets).
        return error_response("invalid_request", 422)

    @app.exception_handler(AccessError)
    async def access_error(request, exc):
        return error_response(exc.code, exc.status)

    @app.exception_handler(StoryToolError)
    @app.exception_handler(MemberToolError)
    @app.exception_handler(TaskToolError)
    async def story_error(request, exc):
        status = {"authentication_required": 401, "permission_denied": 403,
                  "backend_unavailable": 503}.get(exc.code, 502)
        return error_response(exc.code, status)

    @app.exception_handler(PlanningContextError)
    async def planning_context_error(request, exc):
        return error_response(exc.code, 422 if exc.code == 'invalid_planning_input' else 502)

    @app.exception_handler(ModelError)
    async def model_error(request, exc):
        status = {"model_not_configured": 503, "invalid_model_config": 503,
                  "provider_timeout": 504, "provider_unavailable": 503}.get(exc.code, 502)
        return error_response(exc.code, status)

    @app.exception_handler(StorageError)
    async def storage_error(request, exc):
        return JSONResponse({"detail": exc.code, "client_request_id": exc.request_id},
                            status_code=exc.status,
                            headers={"WWW-Authenticate": "Bearer"} if exc.status == 401 else None)

    @app.exception_handler(AudioReadError)
    @app.exception_handler(TranscriptionError)
    async def audio_error(request, exc):
        return error_response(exc.code, exc.status)

    @app.exception_handler(WorkflowError)
    async def workflow_error(request, exc):
        return error_response(exc.code, 422 if exc.code == "invalid_meeting_input" else 502)

    def current_token(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> str:
        if (credentials is None or not credentials.credentials
                or any(not 33 <= ord(c) <= 126 for c in credentials.credentials)):
            raise AccessError("authentication_required", 401)
        return credentials.credentials

    @app.get("/health")
    def health():
        return {"status": "ok"}  # Liveness only, not provider/database readiness.

    @app.post('/api/meetings/{meeting_id}/assignment/context', response_model=AssignmentContextReport)
    def assignment_context(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                           body: AssignmentContextRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        context = planning_reader.load(meeting_id=meeting.id, transcript=meeting.transcript,
            access_token=token, target_sprint=body.target_sprint)
        try:
            return prepare_assignment(context, body)
        except AssignmentContextError as error:
            return error_response(error.code, 422)

    @app.post('/api/meetings/{meeting_id}/transcription/prepare', response_model=PreparedAudio)
    def prepare_transcription(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                              body: AudioInputRequest, token: Annotated[str, Depends(current_token)]):
        prepared, _ = audio_reader.read(meeting_id=meeting_id, audio_id=body.audio_id, token=token)
        return prepared

    @app.post('/api/meetings/{meeting_id}/diarization/run', response_model=DiarizationPreview)
    def run_diarization(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                        body: DiarizationRequest, token: Annotated[str, Depends(current_token)]):
        prepared, content = audio_reader.read(meeting_id=meeting_id, audio_id=body.audio_id, token=token)
        return diarizer.run(prepared, content, body.num_speakers)

    @app.post('/api/meetings/{meeting_id}/transcription/run', response_model=TranscriptionDraft)
    def run_transcription(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                          body: TranscriptionRequest, token: Annotated[str, Depends(current_token)]):
        prepared, content = audio_reader.read(meeting_id=meeting_id, audio_id=body.audio_id, token=token)
        return transcriber.run(prepared, content, body.language)

    @app.post('/api/meetings/{meeting_id}/assignment/recommendations', response_model=AssignmentRecommendations)
    def assignment_recommendations(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                                   body: AssignmentRecommendationRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        source = planning_reader.load(meeting_id=meeting.id, transcript=meeting.transcript,
            access_token=token, target_sprint=body.target_sprint)
        try:
            context = prepare_assignment(source, AssignmentContextRequest(story_ids=body.story_ids, target_sprint=body.target_sprint))
            return recommend(context, body)
        except AssignmentContextError as error:
            return error_response(error.code, 422)

    @app.post('/api/meetings/{meeting_id}/assignment/suggestions', response_model=StoredAssignment)
    def save_assignment(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                        body: AssignmentSaveRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        request = AssignmentRecommendationRequest.model_validate(body.model_dump(exclude={'client_request_id'}))
        existing = assignment_store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            if existing.input != request:
                raise StorageError('storage_conflict', 409, body.client_request_id)
            return existing
        source = planning_reader.load(meeting_id=meeting.id, transcript=meeting.transcript,
            access_token=token, target_sprint=body.target_sprint)
        try:
            context = prepare_assignment(source, AssignmentContextRequest(story_ids=body.story_ids, target_sprint=body.target_sprint))
            result = recommend(context, request)
        except AssignmentContextError as error:
            return error_response(error.code, 422)
        return assignment_store.save_suggestion(token=token, meeting_id=meeting.id, request=body, result=result)

    @app.post("/api/meetings/{meeting_id}/analyze", response_model=PersistedResult)
    def analyze(meeting_id: Annotated[str, Path(pattern=r"^[A-Za-z0-9_-]{1,80}$")],
                body: AnalyzeRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        existing = store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            return PersistedResult.from_record(existing)
        workflow = DailyScrumWorkflow(stories, make_model())
        result = workflow.run(meeting_id=meeting.id, transcript=meeting.transcript,
                            access_token=token, current_sprint=body.current_sprint)
        saved = store.save(token=token, request_id=body.client_request_id, result=result)
        return PersistedResult.from_record(saved)

    @app.post('/api/meetings/{meeting_id}/planning/analyze', response_model=PersistedPlanningResult)
    def analyze_planning(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                         body: PlanningAnalyzeRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        existing = planning_store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            return PersistedPlanningResult.from_record(existing)
        result = SprintPlanningWorkflow(planning_reader, make_model()).run(
            meeting_id=meeting.id, transcript=meeting.transcript, access_token=token,
            current_sprint=body.current_sprint, target_sprint=body.target_sprint)
        saved = planning_store.save(token=token, request_id=body.client_request_id, result=result)
        return PersistedPlanningResult.from_record(saved)

    @app.post('/api/meetings/{meeting_id}/review/analyze', response_model=PersistedReviewResult)
    def analyze_review(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                       body: ReviewAnalyzeRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        existing = review_store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            return PersistedReviewResult.from_record(existing)
        result = SprintReviewWorkflow(stories, make_model()).run(meeting_id=meeting.id,
            transcript=meeting.transcript, access_token=token, current_sprint=body.current_sprint)
        saved = review_store.save(token=token, request_id=body.client_request_id, result=result)
        return PersistedReviewResult.from_record(saved)

    @app.post('/api/meetings/{meeting_id}/retro/analyze', response_model=PersistedRetroResult)
    def analyze_retro(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                      body: RetroAnalyzeRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        existing = retro_store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            return PersistedRetroResult.from_record(existing)
        result = SprintRetroWorkflow(members, make_model()).run(meeting_id=meeting.id,
            transcript=meeting.transcript, access_token=token, current_sprint=body.current_sprint)
        saved = retro_store.save(token=token, request_id=body.client_request_id, result=result)
        return PersistedRetroResult.from_record(saved)

    @app.post('/api/meetings/{meeting_id}/refinement/analyze', response_model=PersistedRefinementResult)
    def analyze_refinement(meeting_id: Annotated[str, Path(pattern=r'^[A-Za-z0-9_-]{1,80}$')],
                          body: RefinementAnalyzeRequest, token: Annotated[str, Depends(current_token)]):
        meeting = access.authorize_and_load(token=token, meeting_id=meeting_id)
        existing = refinement_store.find(token=token, meeting_id=meeting.id, request_id=body.client_request_id)
        if existing is not None:
            return PersistedRefinementResult.from_record(existing)
        result = BacklogRefinementWorkflow(stories, make_model()).run(meeting_id=meeting.id,
            transcript=meeting.transcript, access_token=token, current_sprint=body.current_sprint)
        saved = refinement_store.save(token=token, request_id=body.client_request_id, result=result)
        return PersistedRefinementResult.from_record(saved)

    return app
