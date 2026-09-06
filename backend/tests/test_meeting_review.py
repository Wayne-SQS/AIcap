from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest

from app import models, security
from conftest import TestSession


def headers(token):
    return {"Authorization": f"Bearer {token}"}


def proposal(client, token, **overrides):
    h = headers(token)
    meeting = client.post('/api/meetings', headers=h, json={
        'title': '周会', 'transcript': '我们希望增加导出周报功能。负责人和 Sprint 下次再定。'})
    assert meeting.status_code == 201, meeting.text
    payload = {'meeting_id': meeting.json()['id'], 'client_request_id': str(uuid4()),
               'evidence': '我们希望增加导出周报功能。',
               'changes': {'title': '导出周报', 'description': '待进一步讨论'}}
    payload.update(overrides)
    response = client.post('/api/suggestions', headers=h, json=payload)
    return response, payload


def test_meeting_persisted_and_retrievable(client, member3_token):
    response, body = proposal(client, member3_token)
    assert response.status_code == 200
    h = headers(member3_token)
    meeting = client.get('/api/meetings/' + body['meeting_id'], headers=h).json()
    assert meeting['created_by'] == 3
    assert '导出周报' in meeting['transcript']
    assert any(m['id'] == meeting['id'] for m in client.get('/api/meetings', headers=h).json())
    with TestSession() as db:
        assert db.get(models.Meeting, meeting['id']).transcript == meeting['transcript']
    assert response.json()['status'] == 'pending'
    assert response.json()['execution_status'] == 'not_started'


def test_approve_creates_exactly_one_pool_item_and_audit(client, member1_token, member3_token):
    response, _ = proposal(client, member3_token)
    sid = response.json()['id']
    h = headers(member1_token)
    before = len(client.get('/api/pool', headers=h).json())
    approved = client.post(f'/api/suggestions/{sid}/review', headers=h,
                           json={'decision': 'approve', 'reason': '同意进入需求池'})
    assert approved.status_code == 200, approved.text
    data = approved.json()
    assert data['status'] == 'approved' and data['execution_status'] == 'succeeded'
    assert data['reviewed_by'] == 1 and data['reviewed_at']
    assert data['reason'] == '同意进入需求池'
    again = client.post(f'/api/suggestions/{sid}/review', headers=h,
                        json={'decision': 'approve', 'reason': '重试不能改审计'})
    assert again.json() == data
    items = client.get('/api/pool', headers=h).json()
    assert len(items) == before + 1
    item = next(p for p in items if p['id'] == data['pool_item_id'])
    assert item['title'] == '导出周报' and item['priority'] == 'Could'
    assert sid in item['source'] and data['meeting_id'] in item['source']


def test_reject_is_noop_and_cannot_be_changed(client, member1_token, member3_token):
    response, _ = proposal(client, member3_token)
    sid = response.json()['id']
    h = headers(member1_token)
    before = client.get('/api/pool', headers=h).json()
    result = client.post(f'/api/suggestions/{sid}/review', headers=h,
                         json={'decision': 'reject', 'reason': '范围外'}).json()
    assert result['status'] == 'rejected' and result['execution_status'] == 'not_needed'
    assert result['pool_item_id'] is None and result['reviewed_by'] == 1
    assert client.get('/api/pool', headers=h).json() == before
    assert client.post(f'/api/suggestions/{sid}/review', headers=h,
                       json={'decision': 'reject'}).json() == result
    assert client.post(f'/api/suggestions/{sid}/review', headers=h,
                       json={'decision': 'approve'}).status_code == 409


def test_member_cannot_review(client, member3_token):
    response, _ = proposal(client, member3_token)
    sid = response.json()['id']
    assert client.post(f'/api/suggestions/{sid}/review', headers=headers(member3_token),
                       json={'decision': 'approve'}).status_code == 403
    assert client.get(f'/api/suggestions/{sid}', headers=headers(member3_token)).json()['status'] == 'pending'


def test_owner_can_review(client, member3_token):
    response, _ = proposal(client, member3_token)
    token = client.post('/api/auth/login', json={'username': '成员2', 'password': '123456'}).json()['access_token']
    assert client.post('/api/suggestions/' + response.json()['id'] + '/review',
                       headers=headers(token), json={'decision': 'approve'}).json()['reviewed_by'] == 2


def test_viewer_is_read_only(client, member3_token):
    with TestSession() as db:
        user = models.User(username='viewer-' + uuid4().hex, display_name='查看者', role='viewer',
                           password_hash='not-used')
        db.add(user); db.commit()
        token = security.create_token(user.id)
    h = headers(token)
    response, payload = proposal(client, member3_token)
    assert client.get('/api/meetings', headers=h).status_code == 200
    assert client.post('/api/meetings', headers=h, json={'title': '会议', 'transcript': '文本'}).status_code == 403
    assert client.post('/api/suggestions', headers=h, json=payload).status_code == 403
    assert client.post('/api/suggestions/' + response.json()['id'] + '/review', headers=h,
                       json={'decision': 'reject'}).status_code == 403
    with TestSession() as db:
        db.delete(db.get(models.User, user.id)); db.commit()


@pytest.mark.parametrize('path', ['/api/meetings', '/api/suggestions', '/api/meetings/missing', '/api/suggestions/missing'])
def test_reads_require_login(client, path):
    assert client.get(path).status_code == 401


def test_submission_replay_and_payload_conflict(client, member3_token):
    response, payload = proposal(client, member3_token)
    h = headers(member3_token)
    assert client.post('/api/suggestions', headers=h, json=payload).json() == response.json()
    payload['changes']['title'] = '不同的需求'
    assert client.post('/api/suggestions', headers=h, json=payload).status_code == 409
    rows = client.get('/api/suggestions', params={'meeting_id': payload['meeting_id']}, headers=h).json()
    assert len(rows) == 1


@pytest.mark.parametrize('overrides', [
    {'evidence': '会议中不存在的证据'}, {'action': 'task.update'},
    {'changes': {'title': '   '}}, {'status': 'approved'}, {'reviewed_by': 1},
    {'changes': {'title': '需求', 'priority': 'Critical'}}, {'origin': 'forged'},
])
def test_invalid_suggestions_rejected(client, member3_token, overrides):
    response, _ = proposal(client, member3_token, **overrides)
    assert response.status_code == 422


def test_missing_objects_and_invalid_review(client, member1_token):
    h = headers(member1_token)
    assert client.get('/api/meetings/missing', headers=h).status_code == 404
    assert client.get('/api/suggestions/missing', headers=h).status_code == 404
    assert client.post('/api/suggestions/missing/review', headers=h,
                       json={'decision': 'approve'}).status_code == 404
    assert client.post('/api/meetings', headers=h, json={'title': ' ', 'transcript': 'x'}).status_code == 422
    response, _ = proposal(client, member1_token, meeting_id=str(uuid4()))
    assert response.status_code == 404


def test_execution_failure_rolls_back_approval_then_retry_succeeds(client, member1_token):
    response, _ = proposal(client, member1_token)
    sid = response.json()['id']
    with TestSession() as db:
        record = db.query(models.MeetingSuggestionRecord).filter_by(suggestion_id=sid).one()
        collision = models.PoolItem(id=f'A{record.id:09d}', title='冲突测试', description='', source='', priority='Could')
        db.add(collision); db.commit()
        collision_id = collision.id
    h = headers(member1_token)
    assert client.post(f'/api/suggestions/{sid}/review', headers=h,
                       json={'decision': 'approve'}).status_code == 409
    pending = client.get(f'/api/suggestions/{sid}', headers=h).json()
    assert pending['status'] == 'pending' and pending['reviewed_by'] is None
    assert pending['execution_status'] == 'not_started' and pending['pool_item_id'] is None
    with TestSession() as db:
        db.delete(db.get(models.PoolItem, collision_id)); db.commit()
    assert client.post(f'/api/suggestions/{sid}/review', headers=h,
                       json={'decision': 'approve'}).json()['execution_status'] == 'succeeded'


def test_concurrent_approvals_and_ingestion(client, member1_token):
    response, payload = proposal(client, member1_token)
    h = headers(member1_token)
    sid = response.json()['id']
    payload['client_request_id'] = str(uuid4())
    with ThreadPoolExecutor(max_workers=4) as executor:
        ingested = list(executor.map(lambda _: client.post('/api/suggestions', headers=h, json=payload), range(4)))
        reviewed = list(executor.map(lambda _: client.post(f'/api/suggestions/{sid}/review', headers=h,
                                                          json={'decision': 'approve'}), range(4)))
    assert all(r.status_code == 200 for r in ingested + reviewed)
    assert len({r.json()['id'] for r in ingested}) == 1
    ids = {r.json()['pool_item_id'] for r in reviewed}
    assert len(ids) == 1
    assert all(r.json()['execution_status'] == 'succeeded' for r in reviewed)


def test_audit_survives_pool_promotion(client, member1_token):
    response, _ = proposal(client, member1_token)
    sid = response.json()['id']; h = headers(member1_token)
    result = client.post(f'/api/suggestions/{sid}/review', headers=h, json={'decision': 'approve'}).json()
    pid = result['pool_item_id']
    assert client.post(f'/api/pool/{pid}/promote', headers=h).status_code == 200
    assert client.get(f'/api/suggestions/{sid}', headers=h).json() == result
    assert client.post(f'/api/suggestions/{sid}/review', headers=h, json={'decision': 'approve'}).json() == result
    assert not any(p['id'] == pid for p in client.get('/api/pool', headers=h).json())
