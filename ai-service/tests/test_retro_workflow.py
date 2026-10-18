"""Real LangGraph with read/model fixtures; no semantic quality claim."""
import copy
import json
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from meeting_agent.member_tool import MemberToolError
from meeting_agent.model_client import ModelError
from meeting_agent.retro_workflow import SprintRetroWorkflow
from meeting_agent.workflow import WorkflowError


class RetroWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.members = Mock()
        self.members.get_member_profiles.return_value = [SimpleNamespace(user_id=7, display_name='张敏',
            summary='profile-private-data', six_week_capacity_hours=100)]
        self.model = Mock()
        self.payload = dict(meeting_id='r1', meeting_type='sprint_retrospective', summary='决定补充检查表。',
            decisions=[dict(text='补充发布检查表', evidence=[dict(segment_id='S3', quote='决定由张敏下周五前补充发布检查表。')])],
            proposed_actions=[dict(proposal_id='p1', action='create_action_item',
                changes=dict(title='补充发布检查表', description='整理发布前检查步骤', owner_id=7, deadline_text='下周五前'),
                reason='会议决定改进发布过程', evidence=[dict(segment_id='S3', quote='决定由张敏下周五前补充发布检查表。')])],
            open_questions=[])
        self.model.complete.side_effect = lambda messages: copy.deepcopy(self.payload)
        self.flow = SprintRetroWorkflow(self.members, self.model)

    def run_flow(self, **overrides):
        return self.flow.run(**dict(dict(meeting_id='r1', transcript='回顾本轮发布。\n\n决定由张敏下周五前补充发布检查表。',
            access_token='business-secret'), **overrides))

    def test_action_and_decision_preserve_owner_deadline_and_provenance(self):
        result = self.run_flow()
        self.assertEqual('下周五前', result.proposed_actions[0].changes.deadline_text)
        self.assertEqual(7, result.proposed_actions[0].changes.owner_id)
        self.members.get_member_profiles.assert_called_once_with(access_token='business-secret')
        messages = self.model.complete.call_args.args[0]
        context = json.loads(messages[1]['content'])
        self.assertIsNone(context['current_sprint'])
        self.assertEqual(['S1','S3'], [s['segment_id'] for s in context['transcript_segments']])
        self.assertEqual([{'user_id':7,'display_name':'张敏'}],context['members'])
        self.assertNotIn('business-secret',str(messages))
        self.assertNotIn('profile-private-data',str(messages))

    def test_unknown_owner_and_deadline_remain_null(self):
        self.payload['proposed_actions'][0]['changes'].update(owner_id=None, deadline_text=None)
        self.payload['open_questions'] = ['由谁负责？截止时间是什么？']
        result = self.run_flow()
        self.assertIsNone(result.proposed_actions[0].changes.owner_id)
        self.assertIsNone(result.proposed_actions[0].changes.deadline_text)

    def test_empty_decisions_and_actions_supported(self):
        self.payload.update(decisions=[], proposed_actions=[], summary='仅讨论建议，尚无决定。')
        self.assertEqual([],self.run_flow().proposed_actions)

    def test_invalid_input_stops_before_read(self):
        for override in ({'transcript':''}, {'transcript':'x'*16001}, {'transcript':'x\n'*101}, {'current_sprint':True}):
            with self.assertRaises(WorkflowError): self.run_flow(**override)
        self.members.get_member_profiles.assert_not_called()
        self.model.complete.assert_not_called()

    def test_tool_provider_failures_not_retried(self):
        self.members.get_member_profiles.side_effect=MemberToolError('permission_denied')
        with self.assertRaises(MemberToolError): self.run_flow()
        self.model.complete.assert_not_called()
        self.members.get_member_profiles.side_effect=None
        self.model.complete.side_effect=ModelError('provider_timeout')
        with self.assertRaises(ModelError): self.run_flow()
        self.assertEqual(1,self.model.complete.call_count)

    def test_invalid_actions_evidence_and_types_rejected(self):
        original=copy.deepcopy(self.payload)
        for mutation in ('meeting','owner','bool','deadline','duplicate','content','action','approved','decision_quote'):
            self.payload=copy.deepcopy(original)
            proposal=self.payload['proposed_actions'][0]
            if mutation=='meeting': self.payload['meeting_id']='other'
            if mutation=='owner': proposal['changes']['owner_id']=99
            if mutation=='bool': proposal['changes']['owner_id']=True
            if mutation=='deadline': proposal['changes']['deadline_text']='2026-10-02'
            if mutation=='duplicate': self.payload['proposed_actions'].append(copy.deepcopy(proposal))
            if mutation=='content':
                second=copy.deepcopy(proposal); second['proposal_id']='p2'; self.payload['proposed_actions'].append(second)
            if mutation=='action': proposal['action']='update_story_status'
            if mutation=='approved': proposal['approved']=True
            if mutation=='decision_quote': self.payload['decisions'][0]['evidence'][0]['quote']='不存在的决议'
            with self.subTest(mutation=mutation), self.assertRaises(WorkflowError) as caught:
                self.run_flow()
            codes = {'meeting': 'meeting_mismatch', 'owner': 'unknown_owner',
                     'bool': 'invalid_model_proposal', 'deadline': 'deadline_not_quoted',
                     'duplicate': 'duplicate_proposal_id', 'content': 'duplicate_action_item',
                     'action': 'invalid_model_proposal', 'approved': 'invalid_model_proposal',
                     'decision_quote': 'quote_mismatch'}
            self.assertEqual(codes[mutation],caught.exception.code)

    def test_ambiguous_owner_degrades_instead_of_failing_the_whole_analysis(self):
        """名册重名 → 负责人视为未知，其余照常产出（2026-10-15 由"整条 422"改为降级）。

        为什么改:重名是**名册**的属性,不是模型编造。原先硬失败的表现是整场分析以 422
        收场、连 summary 与 decisions 一起丢掉,且同一场会同一入参时好时坏（取决于模型
        这一轮给出的人是谁）。降级后 owner 视为未知,引文溯源那一层照旧校验。
        """
        self.members.get_member_profiles.return_value.append(SimpleNamespace(user_id=8, display_name='张敏'))
        result = self.run_flow()
        self.assertIsNone(result.proposed_actions[0].changes.owner_id,
                          "无法唯一解析时负责人必须置空,而不是猜一个")
        self.assertEqual('补充发布检查表', result.proposed_actions[0].changes.title,
                         "其余内容不得因为负责人存疑而丢失")
        self.assertEqual('决定补充检查表。', result.summary)
        self.members.get_member_profiles.return_value.pop()

    def test_deadline_still_must_be_quoted_even_when_owner_is_unverifiable(self):
        """降级只免除"负责人归属"这一层；引文与截止时间仍要逐字对得上。

        构造：名册重名（负责人该被置空）+ 截止时间**没有**出现在引文里 ——
        期望拿到 `deadline_not_quoted`，而不是被负责人那层拦住。这证明降级是
        "摘掉负责人后继续校验"，不是"整条放过"。
        """
        self.members.get_member_profiles.return_value.append(SimpleNamespace(user_id=8, display_name='张敏'))
        # 引文保持逐字出自原文（否则先撞 quote_mismatch，测不到 deadline 那一层），
        # 只把 deadline_text 换成原文里没有的日期
        self.payload['proposed_actions'][0]['changes']['deadline_text'] = '2026-10-02'
        with self.assertRaises(WorkflowError) as caught:
            self.run_flow()
        self.assertEqual('deadline_not_quoted', caught.exception.code)
        self.members.get_member_profiles.return_value.pop()

    def test_repeated_call_isolation(self):
        """两次调用互不串味：名册与上下文每次现取，不缓存上一次的结果。

        （原用例把名册整体翻倍来验隔离 —— 那会先在 `load_context` 的成员约束上被拒，
        测的是另一条路径，且不是真实场景：名册整体重复意味着上游数据坏了。
        "重名 → 降级"另有用例覆盖。）
        """
        first = self.run_flow()
        self.assertEqual(7, first.proposed_actions[0].changes.owner_id)
        self.members.get_member_profiles.return_value = []
        self.payload.update(meeting_id='r2', decisions=[], proposed_actions=[])
        self.run_flow(meeting_id='r2', access_token='another-token')
        self.members.get_member_profiles.assert_called_with(access_token='another-token')
        context = json.loads(self.model.complete.call_args.args[0][1]['content'])
        self.assertEqual([], context['members'])
