"""Accepted H03 offline regression checks; fixtures are not model outputs.

Run from the repository root with Python bytecode disabled:
    ai-service/.venv/Scripts/python.exe -B ai-service/tests/test_daily_h03_cases.py

The generated payloads below are deterministic fixtures for exercising the
existing contract and scorer. They are not model outputs or quality results.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
AI_SERVICE = REPO_ROOT / "ai-service"
sys.path.insert(0, str(AI_SERVICE))

from meeting_agent.contracts import ResultContractError  # noqa: E402
from meeting_agent.daily_skill import DailyScrumSkill  # noqa: E402
from meeting_agent.evaluation import (  # noqa: E402
    action_difference,
    assess,
    context_for,
)


CASES_PATH = AI_SERVICE / "evals" / "daily_h03.json"


def load_cases() -> list[dict]:
    return json.loads(CASES_PATH.read_text(encoding="utf-8"))


def fixture_for(case: dict, *, include_questions: bool = True) -> dict:
    """Build a contract-valid oracle fixture from expected actions."""
    context = context_for(case)
    segments = {segment.segment_id: segment.text for segment in context.transcript_segments}
    actions = []
    for index, (story_id, before, after) in enumerate(case["expected_actions"], 1):
        support = case["supporting_segments"][story_id][0]
        actions.append(
            {
                "proposal_id": f"fixture-{index}-{case['id']}",
                "action": "update_story_status",
                "story_id": story_id,
                "expected": {"status": before},
                "changes": {"status": after},
                "reason": "离线 Oracle fixture：动作与合成案例预期一致。",
                "evidence": [{"segment_id": support, "quote": segments[support]}],
            }
        )
    questions = ["请人工确认案例中明确列出的待跟进事项。"] if (
        include_questions and case.get("needs_question")
    ) else []
    return {
        "schema_version": "1.0",
        "meeting_id": case["id"],
        "meeting_type": "daily_scrum",
        "summary": "离线 Oracle fixture，仅用于检查现有契约和判分器。",
        "proposed_actions": actions,
        "open_questions": questions,
    }


class H03CaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = load_cases()
        cls.by_id = {case["id"]: case for case in cls.cases}

    def test_case_count_and_ids(self):
        self.assertGreaterEqual(len(self.cases), 8)
        self.assertLessEqual(len(self.cases), 12)
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))

    def test_cases_match_existing_context_and_oracle_fields(self):
        required = {"id", "transcript", "stories", "expected_actions", "rubric"}
        for case in self.cases:
            with self.subTest(case=case["id"]):
                self.assertTrue(required.issubset(case))
                self.assertTrue(case["transcript"].strip())
                self.assertTrue(case["rubric"].strip())
                self.assertTrue(case["source_issue"].strip())
                self.assertTrue(case["novelty"].strip())
                self.assertTrue(case["manual_checks"])

                context = context_for(case)
                story_by_id = {story.id: story for story in context.stories}
                self.assertEqual(len(story_by_id), len(context.stories))
                segment_ids = {segment.segment_id for segment in context.transcript_segments}

                expected_targets = set()
                for action in case["expected_actions"]:
                    self.assertIsInstance(action, list)
                    self.assertEqual(len(action), 3)
                    story_id, before, after = action
                    self.assertIn(story_id, story_by_id)
                    self.assertEqual(before, story_by_id[story_id].status)
                    self.assertIn(after, (0, 1, 2))
                    self.assertNotEqual(before, after)
                    self.assertNotIn(story_id, expected_targets)
                    expected_targets.add(story_id)

                    supports = case.get("supporting_segments", {}).get(story_id)
                    self.assertTrue(supports)
                    self.assertTrue(set(supports).issubset(segment_ids))

    def test_oracle_fixtures_pass_existing_contract_and_assess(self):
        for case in self.cases:
            with self.subTest(case=case["id"]):
                failures, result = assess(case, fixture_for(case))
                self.assertEqual(failures, [])
                self.assertEqual(result["meeting_id"], case["id"])

    def test_full_abstention_cannot_pass_positive_cases(self):
        positives = [case for case in self.cases if case["expected_actions"]]
        self.assertTrue(positives)
        for case in positives:
            with self.subTest(case=case["id"]):
                payload = fixture_for(case)
                payload["proposed_actions"] = []
                failures, _ = assess(case, payload)
                self.assertIn("unexpected_status_actions", failures)

    def test_question_cases_reject_empty_follow_up(self):
        question_cases = [case for case in self.cases if case.get("needs_question")]
        self.assertTrue(question_cases)
        for case in question_cases:
            with self.subTest(case=case["id"]):
                failures, _ = assess(case, fixture_for(case, include_questions=False))
                self.assertIn("missing_follow_up_question", failures)

    def test_non_continuous_evidence_is_rejected_by_production_contract(self):
        case = self.by_id["h03-clear-start-no-acceptance"]
        payload = fixture_for(case)
        payload["proposed_actions"][0]["evidence"][0]["quote"] = "原文中不存在的引用"
        with self.assertRaises(ResultContractError) as caught:
            assess(case, payload)
        self.assertEqual(caught.exception.code, "quote_mismatch")

    def test_reason_action_contradiction_fixture_is_still_an_unexpected_action(self):
        case = self.by_id["h03-action-reason-contradiction"]
        payload = fixture_for(case)
        segment = context_for(case).transcript_segments[0]
        payload["proposed_actions"].append(
            {
                "proposal_id": "fixture-contradictory-us49",
                "action": "update_story_status",
                "story_id": "US49",
                "expected": {"status": 1},
                "changes": {"status": 2},
                "reason": "US49没有完成，因此不应标记完成。",
                "evidence": [{"segment_id": segment.segment_id, "quote": segment.text}],
            }
        )

        failures, result = assess(case, payload)
        self.assertIn("unexpected_status_actions", failures)
        self.assertIn(["US49", 1, 2], action_difference(case, result)["unexpected"])

    def test_manual_rubric_keeps_relationship_and_terminology_checks(self):
        provider = self.by_id["h03-provider-time-scope"]
        retransmission = self.by_id["h03-network-retransmission-term"]
        self.assertIn("提供方已确定", provider["rubric"])
        self.assertIn("交付时间", provider["rubric"])
        self.assertIn("网络重传", retransmission["rubric"])
        self.assertIn("网络中继", retransmission["rubric"])

    def test_current_skill_boundary_is_unchanged(self):
        skill = DailyScrumSkill()
        self.assertEqual(skill.version, "daily-status-v8")
        self.assertEqual(skill.allowed_actions, ("update_story_status",))
        self.assertEqual(skill.approval_policy, "human_review_required")


if __name__ == "__main__":
    unittest.main(verbosity=2)

