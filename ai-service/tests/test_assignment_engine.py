import unittest
from pydantic import ValidationError
from meeting_agent.assignment_engine import AssignmentRecommendationRequest, recommend
from meeting_agent.assignment_context import AssignmentContextRequest, prepare_assignment
from meeting_agent.planning_context import SprintPlanningInput
from test_assignment_context import story, member


class AssignmentEngineTests(unittest.TestCase):
    def run_engine(self, members, requirements=None):
        source = SprintPlanningInput(meeting_id='m1',transcript_segments=[{'segment_id':'S1','text':'讨论'}],
            stories=[story()],members=members,tasks=[])
        context=prepare_assignment(source,AssignmentContextRequest(story_ids=['US13'],target_sprint=2))
        request=AssignmentRecommendationRequest(story_ids=['US13'],target_sprint=2, requirements=
            requirements if requirements is not None else [{'dimension':'tech_stack','name':'Python','minimum_level':3}])
        return recommend(context,request)

    def test_exact_match_threshold_ties_and_deterministic_order(self):
        result=self.run_engine([member(user_id=9,tech_stack=[{'name':'Python','level':4}]),
            member(user_id=8,tech_stack=[{'name':' python ','level':3}]),
            member(user_id=7,tech_stack=[{'name':'Python','level':2}])])
        self.assertEqual([(8,1,1),(9,1,1),(7,3,0)],[(c.member_id,c.rank,c.matched_requirements) for c in result.candidates])
        self.assertEqual('provisional',result.status)
        self.assertTrue(all(c.capacity_check=='unknown' for c in result.candidates))
        self.assertFalse(result.writes_performed)

    def test_missing_profile_is_unknown_not_zero_skill(self):
        result=self.run_engine([member(years_experience=40)])
        self.assertEqual('no_recorded_skill_match',result.status)
        self.assertIsNone(result.candidates[0].matches[0].recorded_level)

    def test_no_requirements_and_no_eligible_members_do_not_choose_someone(self):
        self.assertEqual([],self.run_engine([member()],[]).candidates)
        result=self.run_engine([member(role='viewer')])
        self.assertEqual('no_eligible_members',result.status)
        self.assertEqual([7],result.excluded_viewer_ids)
        self.assertEqual([],result.candidates)

    def test_dimensions_are_distinct_and_no_synonym_inference(self):
        result=self.run_engine([member(tech_stack=[{'name':'Py','level':5}],capabilities=[{'name':'Python','level':5}])])
        self.assertEqual(0,result.candidates[0].matched_requirements)

    def test_each_requirement_has_equal_weight(self):
        result=self.run_engine([member(tech_stack=[{'name':'Python','level':5}],capabilities=[{'name':'测试','level':1}])],
            [{'dimension':'tech_stack','name':'Python','minimum_level':3},{'dimension':'capabilities','name':'测试','minimum_level':2}])
        self.assertEqual((1,2),(result.candidates[0].matched_requirements,result.candidates[0].total_requirements))

    def test_bad_requirements_rejected(self):
        base={'dimension':'tech_stack','name':'Python','minimum_level':3}
        for requirements in ([base,{**base,'name':' python '}],[{**base,'minimum_level':True}],[{**base,'dimension':'age'}]):
            with self.assertRaises(ValidationError):
                AssignmentRecommendationRequest(story_ids=['US13'],target_sprint=2,requirements=requirements)
