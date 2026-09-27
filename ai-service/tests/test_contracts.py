import copy
import unittest

from meeting_agent.contracts import DailyScrumInput, DailyScrumOutput, validate_daily_result


class DailyContractTests(unittest.TestCase):
    def setUp(self):
        self.input = {
            "meeting_id": "meeting-1",
            "transcript_segments": [{"segment_id": "S1", "text": "US13 已验收完成。US14 今天开始开发。"}],
            "stories": [{"id": "US13", "title": "登录接口", "status": 1, "sprint": 2, "owner_id": 3}],
        }
        self.context = DailyScrumInput.model_validate(self.input)
        self.payload = {
            "meeting_id": "meeting-1", "summary": "登录接口已验收完成。",
            "proposed_actions": [{
                "proposal_id": "p1", "action": "update_story_status", "story_id": "US13",
                "expected": {"status": 1}, "changes": {"status": 2},
                "reason": "会议明确确认验收完成",
                "evidence": [{"segment_id": "S1", "quote": "US13 已验收完成。"}],
            }], "open_questions": [],
        }

    def test_valid_result_is_only_a_proposal_and_preserves_input(self):
        original = copy.deepcopy(self.payload)
        result = validate_daily_result(self.context, self.payload)
        self.assertEqual(2, result.proposed_actions[0].changes.status)
        self.assertEqual(original, self.payload)
        self.assertEqual(1, self.context.stories[0].status)
        self.assertIsNone(self.context.current_sprint)

    def test_no_change_meeting_is_valid(self):
        self.payload["proposed_actions"] = []
        self.assertEqual([], validate_daily_result(self.context, self.payload).proposed_actions)

    def test_rejects_unapproved_fields_and_unsupported_actions(self):
        for field, value in (("approved", True), ("reviewer_id", 1), ("execution_results", [])):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_daily_result(self.context, {**self.payload, field: value})
        for action in ("pool.create", "delete_story", "update_story_estimate"):
            with self.subTest(action=action), self.assertRaises(ValueError):
                payload = copy.deepcopy(self.payload)
                payload["proposed_actions"][0]["action"] = action
                validate_daily_result(self.context, payload)
        self.payload["proposed_actions"][0]["changes"]["owner_id"] = 4
        with self.assertRaises(ValueError):
            validate_daily_result(self.context, self.payload)

    def test_rejects_invalid_status_types_and_blocked_status(self):
        for status in (-1, 3, "2", True, 2.0, None):
            with self.subTest(status=status), self.assertRaises(ValueError):
                payload = copy.deepcopy(self.payload)
                payload["proposed_actions"][0]["changes"]["status"] = status
                validate_daily_result(self.context, payload)

    def test_rejects_wrong_meeting_missing_story_stale_snapshot_and_noop(self):
        variants = []
        wrong_meeting = copy.deepcopy(self.payload)
        wrong_meeting["meeting_id"] = "another-meeting"
        variants.append(wrong_meeting)
        for key, value in (("story_id", "US99"), ("expected", {"status": 0}), ("changes", {"status": 1})):
            payload = copy.deepcopy(self.payload)
            payload["proposed_actions"][0][key] = value
            variants.append(payload)
        for payload in variants:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                validate_daily_result(self.context, payload)

    def test_rejects_fabricated_missing_and_blank_evidence(self):
        for evidence in ([], [{"segment_id": "S2", "quote": "US13 已验收完成。"}],
                         [{"segment_id": "S1", "quote": "US13 已完成。"}],
                         [{"segment_id": "S1", "quote": " "}]):
            with self.subTest(evidence=evidence), self.assertRaises(ValueError):
                payload = copy.deepcopy(self.payload)
                payload["proposed_actions"][0]["evidence"] = evidence
                validate_daily_result(self.context, payload)

    def test_rejects_duplicate_proposals_and_conflicting_targets(self):
        for proposal_id in ("p1", "p2"):
            payload = copy.deepcopy(self.payload)
            duplicate = copy.deepcopy(payload["proposed_actions"][0])
            duplicate["proposal_id"] = proposal_id
            payload["proposed_actions"].append(duplicate)
            with self.subTest(proposal_id=proposal_id), self.assertRaises(ValueError):
                validate_daily_result(self.context, payload)

    def test_rejects_ambiguous_or_oversized_context(self):
        for key in ("stories", "transcript_segments"):
            data = copy.deepcopy(self.input)
            data[key].append(copy.deepcopy(data[key][0]))
            with self.subTest(key=key), self.assertRaises(ValueError):
                DailyScrumInput.model_validate(data)
        self.input["transcript_segments"] = [
            {"segment_id": "S1", "text": "字" * 8001},
            {"segment_id": "S2", "text": "字" * 8000},
        ]
        with self.assertRaises(ValueError):
            DailyScrumInput.model_validate(self.input)

    def test_schema_and_json_round_trip(self):
        schema = DailyScrumOutput.model_json_schema()
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual("update_story_status", schema["$defs"]["StoryStatusProposal"]["properties"]["action"]["const"])
        result = validate_daily_result(self.context, self.payload)
        self.assertEqual(result, DailyScrumOutput.model_validate_json(result.model_dump_json()))


if __name__ == "__main__":
    unittest.main()
