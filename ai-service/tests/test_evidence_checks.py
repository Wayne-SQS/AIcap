"""运行时证据校验（三条检查）的单元测试。

为什么单独成文件而不是并入 test_contracts.py：这三条是**跨链路共享**的判据，
它们被 retro / daily / review / planning / refinement 五条链路各自调用。
并入某一条链路的测试文件，会让人误以为它只属于那条链路。
"""
import os
import unittest

from meeting_agent.contracts import Evidence
from meeting_agent.evidence_checks import (ResultContractError, require_grounded_assignment,
                                           require_quoted_evidence, require_segment_coverage,
                                           segment_coverage_enabled)


def evidence(segment_id="S1", quote="原文"):
    return Evidence(segment_id=segment_id, quote=quote)


class QuoteChecksTest(unittest.TestCase):
    """invalid_evidence：引用必须逐字出现在它引用的片段里。"""

    def setUp(self):
        self.segments = {"S1": "US13 已验收完成。", "S2": "昨天在调接口。"}

    def test_verbatim_quote_passes(self):
        require_quoted_evidence([evidence("S1", "已验收")], self.segments, ("proposed_actions", 0))

    def test_forged_quote_is_rejected(self):
        with self.assertRaises(ResultContractError) as caught:
            require_quoted_evidence([evidence("S1", "伪造证据")], self.segments, ("proposed_actions", 0))
        self.assertEqual("quote_mismatch", caught.exception.code)
        self.assertEqual(("proposed_actions", 0, "evidence", 0, "quote"), caught.exception.path)

    def test_unknown_segment_is_rejected(self):
        with self.assertRaises(ResultContractError) as caught:
            require_quoted_evidence([evidence("S9", "任何")], self.segments, ("proposed_actions", 0))
        self.assertEqual("unknown_segment", caught.exception.code)

    def test_quote_from_another_segment_is_rejected(self):
        """引用了真实原文,但挂在了错误的片段上 —— 同样必须拦下。"""
        with self.assertRaises(ResultContractError) as caught:
            require_quoted_evidence([evidence("S1", "昨天在调接口")], self.segments, ("proposed_actions", 0))
        self.assertEqual("quote_mismatch", caught.exception.code)


class AssignmentChecksTest(unittest.TestCase):
    """invented_assignment：负责人与日期必须是被引用的原文里出现过的字面片段。"""

    def test_owner_and_deadline_present_in_quote_pass(self):
        require_grounded_assignment([evidence(quote="李锐铭 下周三前完成")], ("proposed_actions", 0),
                                    owner_display_name="李锐铭", deadline_text="下周三")

    def test_owner_absent_from_quote_is_rejected(self):
        with self.assertRaises(ResultContractError) as caught:
            require_grounded_assignment([evidence(quote="下周三前完成")], ("proposed_actions", 0),
                                        owner_display_name="李锐铭")
        self.assertEqual("unresolved_owner", caught.exception.code)

    def test_deadline_absent_from_quote_is_rejected(self):
        with self.assertRaises(ResultContractError) as caught:
            require_grounded_assignment([evidence(quote="李锐铭 负责")], ("proposed_actions", 0),
                                        deadline_text="下周三")
        self.assertEqual("deadline_not_quoted", caught.exception.code)

    def test_absent_values_are_not_checked(self):
        """字段为 None = 模型没有提负责人/日期,那就没有可核对的对象,不该报错。"""
        require_grounded_assignment([evidence(quote="随便一句")], ("proposed_actions", 0))


class CoverageChecksTest(unittest.TestCase):
    """incomplete_analysis：片段覆盖率。默认关闭(理由见实现里的注释)。"""

    def setUp(self):
        self.segments = {"S1": "第一句。", "S2": "第二句。"}
        self.saved = os.environ.get("AICAP_REQUIRE_SEGMENT_COVERAGE")

    def tearDown(self):
        if self.saved is None:
            os.environ.pop("AICAP_REQUIRE_SEGMENT_COVERAGE", None)
        else:
            os.environ["AICAP_REQUIRE_SEGMENT_COVERAGE"] = self.saved

    def test_disabled_by_default(self):
        self.assertFalse(segment_coverage_enabled())
        # 只有 S1 被引用,默认不该拦 —— 否则"这段没有产出提案"会被判成失败
        require_segment_coverage(self.segments, [evidence("S1", "第一句")])

    def test_enabled_flags_uncovered_segments(self):
        os.environ["AICAP_REQUIRE_SEGMENT_COVERAGE"] = "1"
        with self.assertRaises(ResultContractError) as caught:
            require_segment_coverage(self.segments, [evidence("S1", "第一句")])
        self.assertEqual("incomplete_analysis", caught.exception.code)
        self.assertIn("S2", str(caught.exception))

    def test_enabled_passes_when_all_covered(self):
        os.environ["AICAP_REQUIRE_SEGMENT_COVERAGE"] = "1"
        require_segment_coverage(self.segments, [evidence("S1", "第一句"), evidence("S2", "第二句")])


if __name__ == "__main__":
    unittest.main()
