"""成员画像智能体 API 契约 Stub：能力声明为真实实现，运行类端点一律 501，未来开发只填实现不改契约。"""
from datetime import date, datetime
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, model_validator

from .. import security
from ..meeting_agent.schemas import StrictModel

router = APIRouter(prefix='/profile-agent', tags=['profile-agent'])
writer = security.require_roles('admin', 'owner', 'member')

NOT_IMPLEMENTED = '服务端尚未开发成员画像智能体，该接口为预留契约，暂不能调用'

CAPABILITY = {
    'agent_id': 'profile',
    'name': 'AI 任务提交与成员能力画像智能体',
    'description': '分析成员工作事实、任务难度与能力画像，识别项目风险',
    'status': 'planned',
    'available': False,
    'configured': False,
    'model': None,
    'worker_enabled': False,
    'supported_actions': [],
    'supported_targets': ['member', 'team'],
    'prompt_version': None,
}


class ProfileRunIn(StrictModel):
    target: Literal['member', 'team']
    member_id: int | None = Field(default=None, ge=1)
    date_from: date
    date_to: date
    sprint: int | None = Field(default=None, ge=1)

    @model_validator(mode='after')
    def check_target(self):
        if self.target == 'member' and self.member_id is None:
            raise ValueError('成员分析必须指定 member_id')
        if self.target == 'team' and self.member_id is not None:
            raise ValueError('团队分析不能指定成员')
        if self.date_to < self.date_from:
            raise ValueError('date_to 不能早于 date_from')
        return self


class ProfileRunOut(BaseModel):
    id: str
    agent_id: str
    target: str
    member_id: int | None
    date_from: date
    date_to: date
    sprint: int | None
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
def profile_config(_=Depends(security.get_current_user)):
    return CAPABILITY


@router.post('/runs', response_model=ProfileRunOut, responses={501: {'description': NOT_IMPLEMENTED}})
def create_run(body: ProfileRunIn, _=Depends(writer)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.get('/runs', responses={501: {'description': NOT_IMPLEMENTED}})
def list_runs(target: str | None = None, member_id: int | None = None, status: str | None = None,
              _=Depends(security.get_current_user)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.get('/runs/{run_id}', responses={501: {'description': NOT_IMPLEMENTED}})
def get_run(run_id: str, _=Depends(security.get_current_user)):
    raise HTTPException(501, NOT_IMPLEMENTED)


@router.post('/runs/{run_id}/retry', responses={501: {'description': NOT_IMPLEMENTED}})
def retry_run(run_id: str, _=Depends(writer)):
    raise HTTPException(501, NOT_IMPLEMENTED)
