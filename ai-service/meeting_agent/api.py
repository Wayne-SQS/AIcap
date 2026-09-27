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
from .member_tool import MemberReadTool, MemberToolError
from .task_tool import TaskReadTool, TaskToolError


class AnalyzeRequest(Contract):
    client_request_id: RequestId
    meeting_type: Literal["daily_scrum"]
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None


class PlanningAnalyzeRequest(Contract):
    client_request_id: RequestId
    meeting_type: Literal['sprint_planning']
    current_sprint: Annotated[int, Field(ge=1, le=4)] | None = None
    target_sprint: Annotated[int, Field(ge=1, le=4)] | None = None


def create_app(*, backend_origin: str | None = None,
               model_factory: Callable[[], CompletionModel] | None = None) -> FastAPI:
    origin = backend_origin if backend_origin is not None else os.environ.get("AICAP_JAVA_BASE_URL", "http://localhost:8080")
    access = JavaMeetingAccess(origin)
    stories = StoryReadTool(origin)
    store = JavaAnalysisStore(origin)
    planning_store = JavaPlanningStore(origin)
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

    return app
