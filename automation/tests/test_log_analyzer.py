import unittest
from pathlib import Path

from automation.src.obsidian_vault.log_analyzer import (
    analyse_log_file,
    analyse_log_text,
    render_report,
)


class LogAnalyzerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.log_path = Path(__file__).resolve().parents[1] / "logs" / "llm-review.log"
        self.summary = analyse_log_file(self.log_path)

    def test_missing_backticks_are_detected(self):
        self.assertIn("Flow", self.summary.missing_backticks)
        self.assertIn("StateFlow", self.summary.missing_backticks)
        self.assertIn("SharedFlow", self.summary.missing_backticks)

    def test_unacceptable_control_character_is_reported(self):
        self.assertIn("0004", self.summary.unacceptable_control_codes)

    def test_recursion_limit_is_counted(self):
        self.assertGreaterEqual(self.summary.recursion_limit_hits, 1)

    def test_qa_verification_none_errors_are_tracked(self):
        self.assertGreaterEqual(self.summary.qa_verification_none_errors, 1)

    def test_report_contains_recommendations(self):
        report = render_report(self.summary)
        self.assertIn("Recommended follow-up actions", report)
        self.assertIn("backticks", report)
        self.assertIn("recursion limit", report)
        self.assertIn("QA verification NoneType errors", report)

    def test_analyse_log_text_matches_file(self):
        summary_from_text = analyse_log_text(self.log_path.read_text(encoding="utf-8"))
        self.assertEqual(self.summary.missing_backticks, summary_from_text.missing_backticks)
        self.assertEqual(
            self.summary.unacceptable_control_codes,
            summary_from_text.unacceptable_control_codes,
        )


if __name__ == "__main__":
    unittest.main()
