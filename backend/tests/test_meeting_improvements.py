import copy
import json
import pytest
from test_meeting_review import headers, proposal
from test_meeting_agent import analysis, ScriptedModel, TRANSCRIPT
from app.meeting_agent import runner
from app.meeting_agent.schemas import AgentError
from conftest import TestSession


def test_modified_approval_keeps_original_and_is_idempotent(client, member1_token, member3_token):
    created, _ = proposal(client, member3_token)
    original = created.json()
    path = '/api/suggestions/' + original['id'] + '/review'
    body = {'decision':'modify_and_approve', 'reason':'修正范围',
            'changes':{'title':'仅导出本项目周报', 'description':'不含跨项目数据', 'priority':'Should'}}
    assert client.post(path, headers=headers(member3_token), json=body).status_code == 403
    h = headers(member1_token)
    response = client.post(path, headers=h, json=body)
    assert response.status_code == 200, response.text
    approved = response.json()
    assert approved['changes'] == original['changes']
    assert approved['evidence'] == original['evidence']
    assert approved['approved_changes'] == body['changes']
    assert approved['reason'] == '修正范围'
    pool = client.get('/api/pool', headers=h).json()
    assert next(p for p in pool if p['id'] == approved['pool_item_id'])['title'] == body['changes']['title']
    assert client.post(path, headers=h, json=body).json() == approved
    body['changes']['title'] = '另一版本'
    assert client.post(path, headers=h, json=body).status_code == 409
    assert client.post(path, headers=h, json={'decision':'approve'}).status_code == 409
    assert len(client.get('/api/pool', headers=h).json()) == len(pool)


@pytest.mark.parametrize('body', [
    {'decision':'modify_and_approve'},
    {'decision':'approve','changes':{'title':'不能悄悄修改'}},
    {'decision':'reject','changes':{'title':'不允许'}},
    {'decision':'modify_and_approve','changes':{'title':' '}},
])
def test_invalid_edit_does_not_review(client, member1_token, body):
    created, _ = proposal(client, member1_token)
    path = '/api/suggestions/' + created.json()['id']
    h = headers(member1_token)
    assert client.post(path+'/review', headers=h, json=body).status_code == 422
    assert client.get(path, headers=h).json()['status'] == 'pending'


def test_promotion_requires_sprint_validates_owner_and_preserves_unassigned(client, member1_token):
    h = headers(member1_token)
    pid = client.post('/api/pool', headers=h, json={'title':'排期测试'}).json()['id']
    path = '/api/pool/' + pid + '/promote'
    for body in ({}, {'sprint':0}, {'sprint':4}, {'sprint':2,'owner_id':999999}):
        assert client.post(path, headers=h, json=body).status_code == 422
    assert any(p['id'] == pid for p in client.get('/api/pool', headers=h).json())
    story = client.post(path, headers=h, json={'sprint':3,'activity':4}).json()
    assert (story['sprint'],story['activity'],story['owner_id'],story['status']) == (3,4,None,0)
    pid = client.post('/api/pool', headers=h, json={'title':'明确负责人'}).json()['id']
    story = client.post('/api/pool/'+pid+'/promote', headers=h, json={'sprint':2,'owner_id':3}).json()
    assert story['sprint'] == 2 and story['owner_id'] == 3


def test_same_paragraph_is_split_and_incomplete_analysis_rejected():
    segments = runner.segments_for(TRANSCRIPT.replace('\n',''))
    assert len(segments) == 3
    body = analysis()
    body['action_items'] = []
    with pytest.raises(AgentError) as error:
        runner.validate_analysis(json.dumps(body), segments)
    assert error.value.code == 'incomplete_analysis' and 'seg-2' in error.value.message


def test_incomplete_output_gets_one_repair_attempt(client, member1_token):
    class RepairModel(ScriptedModel):
        def complete(self, messages, tools, *, final=False):
            result, usage = super().complete(messages, tools, final=final)
            if final and len(self.calls) == 3:
                body = copy.deepcopy(self.result)
                body['action_items'] = []
                result['content'] = json.dumps(body)
            return result, usage
    model = RepairModel()
    output = runner.analyze(TRANSCRIPT,1,TestSession,model,lambda *args:None,lambda:None)
    assert len(model.calls) == 4 and output.action_items[0].owner_mention == '成员3'
    bad = analysis(); bad['action_items'] = []
    model = ScriptedModel(bad)
    with pytest.raises(AgentError) as error:
        runner.analyze(TRANSCRIPT,1,TestSession,model,lambda *args:None,lambda:None)
    assert error.value.code == 'incomplete_analysis' and len(model.calls) == 4


def test_coordination_owner_cannot_be_invented():
    body = analysis()
    body['coordination_items'] = [{'description':'请负责人确认接手', 'owner_mention':'负责人', 'deadline_text':None,
                                  'evidence':{'segment_id':'seg-4','quote':'成员3说本周比较忙，请负责人确认能否接下额外工作。'}}]
    segments = runner.segments_for(TRANSCRIPT+'成员3说本周比较忙，请负责人确认能否接下额外工作。')
    parsed = runner.validate_analysis(json.dumps(body),segments)
    assert parsed.coordination_items[0].owner_mention == '负责人'
    body['coordination_items'][0]['owner_mention'] = '成员1'
    with pytest.raises(AgentError) as error:
        runner.validate_analysis(json.dumps(body),segments)
    assert error.value.code == 'invented_assignment'
