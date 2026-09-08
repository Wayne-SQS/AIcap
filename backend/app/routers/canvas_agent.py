"""画布智能体 API 契约 Stub：能力声明为真实实现，运行类端点一律 501，未来开发只填实现不改契约。"""
from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, model_validator

from .. import security
from ..meeting_agent.schemas import StrictModel

router = APIRouter(prefix='/canvas-agent', tags=['canvas-agent'])
writer = security.require_roles('admin', 'owner', 'member')

NOT_IMPLEMENTED = '服务端尚未开发画布智能体，该接口为预留契约，暂不能调用'

CAPABILITY = {
    'agent_id': 'canvas',
    'name': 'AI 项目规划画布智能体',
    'description': '把自然语言和项目数据转化为可交互的项目图',
    'status': 'planned',
    'available': False,
    'configured': False,
    'model': None,
    'worker_enabled': False,
    'supported_actions': [],
    'supported_views': ['story_map', 'gantt', 'member_load', 'uml'],
    'supported_uml_kinds': ['use_case', 'sequence', 'class'],
    'prompt_version': None,
}


class CanvasRunIn(StrictModel):
    mode: Literal['generate', 'modify']
    view: Literal['story_map', 'gantt', 'member_load', 'uml']
    instruction: str = Field(min_length=1, max_length=2000)
    sprint: int | None = Field(default=None, ge=1)
    story_ids: list[str] = Field(default_factory=list, max_length=50)
    member_ids: list[int] = Field(default_factory=list, max_length=50)
    uml_kind: Literal['use_case', 'sequence', 'class'] | None = None

    @model_validator(mode='after')
    def check_uml_kind(self):
        if self.view != 'uml' and self.uml_kind is not None:
            raise ValueError('uml_kind 仅在 view=uml 时允许')
        return self


class CanvasRunOut(BaseModel):
    id: str
    agent_id: str
    mode: str
    view: str
    instruction: str
    sprint: int | None
    story_ids: list[str]
    member_ids: list[int]
    uml_kind: str | None
    requested_by: int
    status: str
    attempt: int
    model: str | None
    prompt_version: str | None
    error_code: str | None
    error_message: str | None
    created_at: datetime
    updated_at: datetime
    result: dict | None


@router.get('/config')
def canvas_config(_=Depends(security.get_current_user)):
    return CAPABILITY


@router.post('/runs', response_model=CanvasRunOut, responses={501: {'description': NOT_IMPLEMENTED}})
def create_run(body: CanvasRunIn, _=Depends(writer)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.get('/runs', responses={501: {'description': NOT_IMPLEMENTED}})
def list_runs(view: str | None = None, mode: str | None = None, status: str | None = None,
              _=Depends(security.get_current_user)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.get('/runs/{run_id}', responses={501: {'description': NOT_IMPLEMENTED}})
def get_run(run_id: str, _=Depends(security.get_current_user)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.post('/runs/{run_id}/retry', responses={501: {'description': NOT_IMPLEMENTED}})
def retry_run(run_id: str, _=Depends(writer)):
    raise HTTPException(501, NOT_IMPLEMENTED)
