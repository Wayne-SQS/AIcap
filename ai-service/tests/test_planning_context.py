import unittest
import warnings
from unittest.mock import Mock

from pydantic import ValidationError

from meeting_agent.story_tool import PlanningStorySnapshot
from meeting_agent.member_tool import MemberProfileSnapshot, MemberToolError
from meeting_agent.story_tool import StoryToolError
from meeting_agent.task_tool import TaskSnapshot, TaskToolError
from test_task_tool import task_row
from meeting_agent.planning_context import PlanningContextReader, PlanningContextError, SprintPlanningInput


class PlanningContextTests(unittest.TestCase):
    def setUp(self):
        self.story = PlanningStorySnapshot(id='US13', title='登录', status=0, sprint=2, owner_id=7, description='登录范围', acceptance='验证登录', priority='Must', activity=2)
        self.member = MemberProfileSnapshot(user_id=7, display_name='成员甲', role='member',
            six_week_capacity_hours=60, title='', tech_stack=[], capabilities=[], process_domains=[],
            summary='', years_experience=0)
        self.stories, self.members = Mock(), Mock()
        self.stories.get_planning_stories.return_value = [self.story]
        self.members.get_member_profiles.return_value = [self.member]
        self.tasks = Mock()
        self.tasks.get_tasks.return_value = [TaskSnapshot.model_validate(task_row())]
        self.reader = PlanningContextReader(self.stories, self.members, self.tasks)

    def read(self, **overrides):
        return self.reader.load(**dict({'meeting_id': 'p1', 'transcript': '讨论登录范围。\n\n尚需确认目标。',
                                      'access_token': 'secret-token'}, **overrides))

    def test_load_preserves_scope_evidence_and_explicit_unknowns(self):
        result = self.read(current_sprint=2, target_sprint=3)
        self.assertEqual('sprint_planning', result.meeting_type)
        self.assertEqual((2, 3), (result.current_sprint, result.target_sprint))
        self.assertEqual(['S1', 'S3'], [s.segment_id for s in result.transcript_segments])
        self.assertEqual('尚需确认目标。', result.transcript_segments[1].text)
        self.assertEqual(60, result.members[0].six_week_capacity_hours)
        self.assertEqual('T01', result.tasks[0].id)
        self.tasks.get_tasks.assert_called_once_with(access_token='secret-token')
        for key in ('sprint_goal', 'sprint_dates', 'story_estimates', 'sprint_remaining_capacity'):
            self.assertIsNone(result.model_dump()[key])
        self.assertNotIn('secret-token', result.model_dump_json())
        self.stories.get_planning_stories.assert_called_once_with(access_token='secret-token')
        self.members.get_member_profiles.assert_called_once_with(access_token='secret-token')

    def test_does_not_infer_current_or_target_sprint_from_story(self):
        result = self.read()
        self.assertIsNone(result.current_sprint)
        self.assertIsNone(result.target_sprint)
        self.assertEqual(2, result.stories[0].sprint)

    def test_planning_content_survives_and_daily_only_snapshot_is_rejected(self):
        result = self.read()
        self.assertEqual(('登录范围', '验证登录', 'Must', 2),
                         (result.stories[0].description, result.stories[0].acceptance,
                          result.stories[0].priority, result.stories[0].activity))
        from meeting_agent.contracts import StorySnapshot
        self.stories.get_planning_stories.return_value = [
            StorySnapshot(id='US13', title='登录', status=0, sprint=2, owner_id=7)]
        with self.assertRaises(PlanningContextError):
            self.read()

    def test_invalid_request_rejected_before_any_read(self):
        for overrides in ({'transcript': ''}, {'transcript': None}, {'transcript': 'x' * 16001},
                          {'transcript': 'x\n' * 101}, {'current_sprint': True}, {'target_sprint': '2'},
                          {'target_sprint': 5}, {'meeting_id': ' '}):
            with self.subTest(overrides=str(overrides)[:80]), self.assertRaises(PlanningContextError) as caught:
                self.read(**overrides)
            self.assertEqual('invalid_planning_input', caught.exception.code)
        self.stories.get_planning_stories.assert_not_called()
        self.members.get_member_profiles.assert_not_called()
        self.tasks.get_tasks.assert_not_called()

    def test_read_failures_never_return_partial_context(self):
        self.stories.get_planning_stories.side_effect = StoryToolError('permission_denied')
        with self.assertRaises(StoryToolError):
            self.read()
        self.members.get_member_profiles.assert_not_called()
        self.stories.get_planning_stories.side_effect = None
        self.members.get_member_profiles.side_effect = MemberToolError('backend_unavailable')
        with self.assertRaises(MemberToolError):
            self.read()

    def test_duplicate_and_mutated_nested_context_rejected(self):
        self.stories.get_planning_stories.return_value = [self.story, self.story]
        with self.assertRaises(PlanningContextError):
            self.read()
        self.stories.get_planning_stories.return_value = [self.story]
        self.members.get_member_profiles.return_value = [self.member, self.member]
        with self.assertRaises(PlanningContextError):
            self.read()
        self.members.get_member_profiles.return_value = [self.member]
        self.member.tech_stack.append({'name': 'bad', 'level': 99})
        with warnings.catch_warnings(record=True) as emitted:
            warnings.simplefilter('always')
            with self.assertRaises(PlanningContextError):
                self.read()
        self.assertEqual([], emitted)

    def test_no_cache_and_missing_owner_not_fabricated(self):
        self.read()
        self.members.get_member_profiles.return_value = []
        result = self.read(access_token='second-token')
        self.assertEqual([], result.members)
        self.assertEqual(7, result.stories[0].owner_id)
        self.members.get_member_profiles.assert_called_with(access_token='second-token')
        self.stories.get_planning_stories.return_value = []
        self.assertEqual([], self.read().stories)

    def test_unknown_fields_cannot_be_filled_with_guesses_or_write_actions(self):
        data = self.read().model_dump()
        for key, value in (('tasks', None), ('sprint_goal', '猜测目标'), ('sprint_dates', {}),
                           ('story_estimates', {}), ('sprint_remaining_capacity', 0),
                           ('proposed_actions', []), ('approved', True), ('meeting_type', 'daily_scrum')):
            with self.subTest(key=key), self.assertRaises(ValidationError):
                SprintPlanningInput.model_validate(dict(data, **{key: value}))

    def test_task_failure_duplicates_and_mutation(self):
        self.tasks.get_tasks.side_effect = TaskToolError('permission_denied')
        with self.assertRaises(TaskToolError):
            self.read()
        self.tasks.get_tasks.side_effect = None
        task = TaskSnapshot.model_validate(task_row())
        self.tasks.get_tasks.return_value = [task, task]
        with self.assertRaises(PlanningContextError):
            self.read()
        self.tasks.get_tasks.return_value = [task]
        task.sprints.append(3)
        with self.assertRaises(PlanningContextError):
            self.read()
        self.tasks.get_tasks.return_value = []
        self.assertEqual([], self.read(access_token='second').tasks)
        self.tasks.get_tasks.assert_called_with(access_token='second')

    def test_unresolved_task_links_and_scope_mismatch_are_retained(self):
        result = self.read(target_sprint=4)
        self.assertEqual([1, 2, 3], result.tasks[0].sprints)
        self.assertEqual('T99', result.tasks[0].depends_on)
        self.assertEqual('US13,US14', result.tasks[0].story_ref)
        self.assertIsNone(result.sprint_remaining_capacity)

    def test_serialization_and_cross_segment_size_limit(self):
        result = self.read()
        self.assertEqual(result, SprintPlanningInput.model_validate_json(result.model_dump_json()))
        data = result.model_dump()
        data['transcript_segments'] = [{'segment_id': 'S1', 'text': 'a' * 8001},
                                       {'segment_id': 'S2', 'text': 'b' * 8000}]
        with self.assertRaises(ValidationError):
            SprintPlanningInput.model_validate(data)
        data['transcript_segments'] = [{'segment_id': 'S1', 'text': 'a'}, {'segment_id': 'S1', 'text': 'b'}]
        with self.assertRaises(ValidationError):
            SprintPlanningInput.model_validate(data)


if __name__ == '__main__':
    unittest.main()

