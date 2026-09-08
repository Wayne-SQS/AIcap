import json
from datetime import datetime
from uuid import uuid4
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .. import config, models, security
from ..database import get_db
from . import canvas_agent, profile_agent
from ..meeting_agent import jobs
from ..meeting_agent.model_client import settings_ready
from ..meeting_agent.prompts import PROMPT_VERSION

router = APIRouter(tags=['meeting-agent'])
writer = security.require_roles('admin', 'owner', 'member')


def output(run):
    return {'id': run.id, 'meeting_id': run.meeting_id, 'requested_by': run.requested_by,
            'status': run.status, 'attempt': run.attempt, 'model': run.model,
            'prompt_version': run.prompt_version, 'error_code': run.error_code,
            'error_message': run.error_message, 'created_at': run.created_at,
            'updated_at': run.updated_at,
            'result': json.loads(run.result_json) if run.result_json else None}


@router.get('/agent/config')
def agent_config(_=Depends(security.get_current_user)):
    return {'configured': settings_ready(), 'model': config.AICAP_LLM_MODEL,
            'worker_enabled': config.AICAP_AGENT_WORKER_ENABLED,
            'supported_actions': ['pool.create'], 'prompt_version': PROMPT_VERSION}


@router.post('/meetings/{meeting_id}/runs')
def create_run(meeting_id: str, db: Session = Depends(get_db), user=Depends(writer)):
    if db.get(models.Meeting, meeting_id) is None:
        raise HTTPException(404, '会议不存在')
    jobs.expire_runs(db)
    existing = db.query(models.MeetingAgentRun).filter_by(meeting_id=meeting_id).first()
    if existing:
        return output(existing)
    if not settings_ready():
        raise HTTPException(503, '服务端尚未配置 DeepSeek 密钥，手动建议仍可使用')
    run = models.MeetingAgentRun(id=str(uuid4()), meeting_id=meeting_id, requested_by=user.id,
                                model=config.AICAP_LLM_MODEL, prompt_version=PROMPT_VERSION)
    try:
        db.add(run)
        db.flush()
        jobs.event(db, run, 'queued', {'requested_by': user.id})
        db.commit()
    except IntegrityError:
        db.rollback()
        run = db.query(models.MeetingAgentRun).filter_by(meeting_id=meeting_id).first()
        if run is None:
            raise HTTPException(409, '创建分析任务冲突，请重试')
    return output(run)


@router.get('/meetings/{meeting_id}/runs')
def meeting_runs(meeting_id: str, db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    if db.get(models.Meeting, meeting_id) is None:
        raise HTTPException(404, '会议不存在')
    jobs.expire_runs(db)
    return [output(run) for run in db.query(models.MeetingAgentRun).filter_by(meeting_id=meeting_id).all()]


@router.get('/agent-runs/{run_id}')
def get_run(run_id: str, db: Session = Depends(get_db), _=Depends(security.get_current_user)):
    jobs.expire_runs(db)
    run = db.get(models.MeetingAgentRun, run_id)
    if run is None:
        raise HTTPException(404, '分析记录不存在')
    data = output(run)
    data['events'] = [{'id': e.id, 'attempt': e.attempt, 'kind': e.kind,
                       'detail': json.loads(e.detail_json), 'created_at': e.created_at}
                      for e in db.query(models.MeetingAgentEvent).filter_by(run_id=run_id)
                      .order_by(models.MeetingAgentEvent.id).all()]
    return data


@router.post('/agent-runs/{run_id}/retry')
def retry_run(run_id: str, db: Session = Depends(get_db), user=Depends(writer)):
    jobs.expire_runs(db)
    run = db.get(models.MeetingAgentRun, run_id)
    if run is None:
        raise HTTPException(404, '分析记录不存在')
    if run.status != 'failed':
        raise HTTPException(409, '只能重试失败的分析；已生成的建议不会再次生成')
    if not settings_ready():
        raise HTTPException(503, '服务端尚未配置模型密钥')
    claimed = db.execute(update(models.MeetingAgentRun).where(
        models.MeetingAgentRun.id == run_id, models.MeetingAgentRun.status == 'failed').values(
        status='queued', requested_by=user.id, attempt=models.MeetingAgentRun.attempt + 1,
        error_code=None, error_message=None, worker_token=None, lease_until=None,
        model=config.AICAP_LLM_MODEL, prompt_version=PROMPT_VERSION, updated_at=datetime.utcnow())
        .execution_options(synchronize_session=False)).rowcount
    if not claimed:
        db.rollback()
        raise HTTPException(409, '任务状态已变化，请刷新')
    db.refresh(run)
    jobs.event(db, run, 'queued', {'requested_by': user.id, 'retry': True})
    db.commit()
    return output(run)


@router.get('/agents', tags=['agents'])
def list_agents(_=Depends(security.get_current_user)):
    meeting = {'agent_id': 'meeting', 'name': 'AI 会议执行智能体',
               'description': '把会议内容转化为任务、负责人和执行计划',
               'status': 'available', 'available': True, 'configured': settings_ready(),
               'model': config.AICAP_LLM_MODEL, 'worker_enabled': config.AICAP_AGENT_WORKER_ENABLED,
               'supported_actions': ['pool.create'], 'prompt_version': PROMPT_VERSION,
               'config_path': '/api/agent/config'}
    return {'agents': [meeting,
                       {**canvas_agent.CAPABILITY, 'config_path': '/api/canvas-agent/config'},
                       {**profile_agent.CAPABILITY, 'config_path': '/api/profile-agent/config'}]}
