"""Database-backed queue with atomic claims, leases and all-or-nothing proposal persistence."""
import json
import threading
from datetime import datetime, timedelta
from uuid import uuid4
from sqlalchemy import update
from .. import config, models
from ..meeting_schemas import SuggestionIn
from ..services.meeting_suggestions import stage_suggestion
from .model_client import ModelClient
from .runner import analyze
from .schemas import AgentError


def event(db, run, kind, detail):
    db.add(models.MeetingAgentEvent(run_id=run.id, attempt=run.attempt, kind=kind,
                                   detail_json=json.dumps(detail, ensure_ascii=False)))


def expire_runs(db):
    now = datetime.utcnow()
    expired = db.query(models.MeetingAgentRun).filter(
        models.MeetingAgentRun.status == 'running', models.MeetingAgentRun.lease_until < now).all()
    for run in expired:
        claimed = db.execute(update(models.MeetingAgentRun).where(
            models.MeetingAgentRun.id == run.id, models.MeetingAgentRun.status == 'running',
            models.MeetingAgentRun.lease_until < now).values(status='failed', worker_token=None,
            error_code='interrupted', error_message='分析进程中断或超时，可重试', updated_at=now)
            .execution_options(synchronize_session=False)).rowcount
        if claimed:
            event(db, run, 'failed', {'code': 'interrupted', 'message': '分析进程中断或超时，可重试'})
    db.commit()


def run_next(session_factory, model=None, stop=None):
    with session_factory() as db:
        expire_runs(db)
        candidate = db.query(models.MeetingAgentRun).filter_by(status='queued').order_by(
            models.MeetingAgentRun.created_at).first()
        if candidate is None:
            return False
        run_id = candidate.id
        token = str(uuid4())
        claimed = db.execute(update(models.MeetingAgentRun).where(
            models.MeetingAgentRun.id == run_id, models.MeetingAgentRun.status == 'queued').values(
            status='running', worker_token=token,
            lease_until=datetime.utcnow() + timedelta(seconds=config.AICAP_AGENT_LEASE_SECONDS),
            updated_at=datetime.utcnow()).execution_options(synchronize_session=False)).rowcount
        db.commit()
        if not claimed:
            return True
        db.refresh(candidate)
        user_id = candidate.requested_by
        model_name = candidate.model
        transcript = db.get(models.Meeting, candidate.meeting_id).transcript
        event(db, candidate, 'started', {'model': candidate.model, 'prompt_version': candidate.prompt_version})
        db.commit()

    def active(db):
        run = db.get(models.MeetingAgentRun, run_id)
        if (stop is not None and stop.is_set()) or run is None or run.status != 'running' or run.worker_token != token:
            raise AgentError('interrupted', '分析已中断，可重试')
        if run.lease_until < datetime.utcnow():
            raise AgentError('interrupted', '分析租约超时，可重试')
        return run

    def ensure_active():
        with session_factory() as db:
            active(db)

    def emit(kind, detail):
        with session_factory() as db:
            run = active(db)
            event(db, run, kind, detail)
            db.commit()

    try:
        analysis = analyze(transcript, user_id, session_factory, model or ModelClient(model_name), emit, ensure_active)
        with session_factory() as db:
            run = active(db)
            # Acquire the run row until the proposals and final result commit together.
            claimed = db.execute(update(models.MeetingAgentRun).where(
                models.MeetingAgentRun.id == run_id, models.MeetingAgentRun.status == 'running',
                models.MeetingAgentRun.worker_token == token,
                models.MeetingAgentRun.lease_until >= datetime.utcnow()).values(updated_at=datetime.utcnow())).rowcount
            if not claimed:
                raise AgentError('interrupted', '分析已中断，可重试')
            user = db.get(models.User, user_id)
            if user is None or user.role not in ('admin', 'owner', 'member'):
                raise AgentError('permission_changed', '发起人已失去提交权限')
            existing_titles = {title.strip().casefold() for (title,) in db.query(models.Story.title).all()}
            existing_titles.update(title.strip().casefold() for (title,) in db.query(models.PoolItem.title).all())
            for (raw,) in db.query(models.Suggestion.change_json).filter(models.Suggestion.status == 'pending').all():
                try:
                    data = json.loads(raw)
                    if isinstance(data, dict) and isinstance(data.get('title'), str):
                        existing_titles.add(data['title'].strip().casefold())
                except ValueError:
                    pass
            ids, skipped = [], []
            for index, proposal in enumerate(analysis.proposals):
                normalized = proposal.title.casefold()
                if normalized in existing_titles:
                    skipped.append({'title': proposal.title, 'reason': '已有同名故事、需求或待审建议，请人工核对'})
                    continue
                existing_titles.add(normalized)
                body = SuggestionIn(meeting_id=run.meeting_id, client_request_id=f'agent:{run.id}:{index}',
                    origin='agent', evidence=proposal.evidence.quote,
                    note=(proposal.note + '\n初始优先级 Could 为系统默认，待审核确认。'),
                    changes={'title': proposal.title, 'description': proposal.description, 'priority': 'Could'})
                suggestion, _ = stage_suggestion(db, body, user)
                ids.append(suggestion.id)
            result = analysis.model_dump()
            result.update(suggestion_ids=ids, skipped_proposals=skipped,
                          limitations=['未配置真实成员容量/任务状态/当前Sprint日期；不自动修改任务和故事'])
            run.result_json = json.dumps(result, ensure_ascii=False)
            run.status = 'awaiting_review' if ids else 'completed'
            run.worker_token = None
            run.lease_until = None
            run.updated_at = datetime.utcnow()
            event(db, run, 'completed', {'suggestion_ids': ids, 'skipped': skipped})
            db.commit()
    except Exception as exc:
        code = exc.code if isinstance(exc, AgentError) else 'internal_error'
        message = exc.message if isinstance(exc, AgentError) else '分析失败，未保存建议；请稍后重试或检查服务端'
        with session_factory() as db:
            run = db.get(models.MeetingAgentRun, run_id)
            if run:
                claimed = db.execute(update(models.MeetingAgentRun).where(
                    models.MeetingAgentRun.id == run_id, models.MeetingAgentRun.status == 'running',
                    models.MeetingAgentRun.worker_token == token).values(status='failed',
                    worker_token=None, lease_until=None, error_code=code, error_message=message,
                    updated_at=datetime.utcnow()).execution_options(synchronize_session=False)).rowcount
                if claimed:
                    event(db, run, 'failed', {'code': code, 'message': message})
                    db.commit()
    return True


class Worker:
    def __init__(self, session_factory):
        self.session_factory = session_factory
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.loop, name='meeting-agent', daemon=True)

    def loop(self):
        while not self.stop.is_set():
            try:
                worked = run_next(self.session_factory, stop=self.stop)
            except Exception:
                # DB outages are retried; provider failures are recorded on the run, not hot-looped.
                worked = False
            self.stop.wait(0.2 if worked else 1)

    def start(self):
        self.thread.start()

    def close(self):
        self.stop.set()
        self.thread.join(timeout=2)
