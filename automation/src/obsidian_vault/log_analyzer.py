"""Utilities for analyzing llm-review automation logs.

The log output of the obsidian vault automation is notoriously noisy and hard to
triage manually.  The helper in this module parses the text logs and extracts a
structured summary focused on the chronic failures surfaced in recent runs:

* Oscillating validators that keep reintroducing the same warning
* Lint warnings about missing backticks around Kotlin type names
* Metadata warnings that require manual intervention (future dates, draft status)
* Low level validator crashes caused by stray control characters
* LangGraph recursion limit errors triggered by self-healing loops

By codifying these patterns we can surface stable remediation advice and feed it
back into the automation pipeline before the issues grow into failures that
block the rest of the queue.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Sequence, Set
import re

_MISSING_BACKTICKS_RE = re.compile(r"Type name '([^']+)' found without backticks")
_OSCILLATION_RE = re.compile(r"Oscillation detected.*")
_UNACCEPTABLE_CHAR_RE = re.compile(r"unacceptable character #x([0-9A-Fa-f]{4})")
_RECURSION_LIMIT_RE = re.compile(r"Recursion limit of (\d+) reached")
_STATUS_DRAFT_RE = re.compile(r"status set to 'draft'", re.IGNORECASE)
_FUTURE_DATE_RE = re.compile(r"future(?:-looking)? dates?", re.IGNORECASE)


@dataclass
class IssueSummary:
    """Structured summary of the common failure patterns in the log."""

    oscillation_messages: List[str] = field(default_factory=list)
    missing_backticks: Set[str] = field(default_factory=set)
    unacceptable_control_codes: Set[str] = field(default_factory=set)
    recursion_limit_hits: int = 0
    qa_verification_none_errors: int = 0
    draft_status_notes: int = 0
    future_dated_metadata_notes: int = 0
    notes_processed: int | None = None
    notes_with_errors: int | None = None

    def recommendations(self) -> List[str]:
        """Provide concrete follow-up steps derived from the captured issues."""
        advice: List[str] = []
        if self.missing_backticks:
            types = ", ".join(sorted(self.missing_backticks))
            advice.append(
                "Wrap Kotlin type identifiers in backticks during automated fixes to "
                f"avoid validator oscillation (missing: {types})."
            )
        if self.oscillation_messages:
            advice.append(
                "Tune the fixer/validator handshake: automatically short-circuit after "
                "two oscillating iterations and flag the note for manual review with "
                "the concrete validator payload."
            )
        if self.future_dated_metadata_notes:
            advice.append(
                "Allow the metadata fixer to normalise future-dated timestamps when "
                "they exceed the allowed threshold instead of repeatedly deferring to "
                "manual review."
            )
        if self.draft_status_notes:
            advice.append(
                "Escalate draft-status warnings with context so reviewers can make a "
                "publishability decision without combing through raw logs."
            )
        if self.unacceptable_control_codes:
            hex_codes = ", ".join(sorted(self.unacceptable_control_codes))
            advice.append(
                "Strip non-printable control characters (e.g. 0x{}).".format(hex_codes)
            )
        if self.recursion_limit_hits:
            advice.append(
                "Increase the LangGraph recursion limit or implement stronger guard "
                "rails on fix-planning loops to prevent runaway iterations."
            )
        if self.qa_verification_none_errors:
            advice.append(
                "Guard QA verification against None results before membership tests "
                "so automation no longer crashes on 'NoneType' iterable checks."
            )
        if (
            self.notes_processed is not None
            and self.notes_with_errors is not None
            and self.notes_with_errors > 0
        ):
            rate = self.notes_with_errors / self.notes_processed
            advice.append(
                f"Investigate systemic issues: {self.notes_with_errors} of {self.notes_processed} "
                f"notes ({rate:.1%}) still end in errors despite modifications."
            )
        return advice


def analyse_lines(lines: Sequence[str]) -> IssueSummary:
    """Analyse the provided log lines and return a structured summary."""

    summary = IssueSummary()

    def _extract_last_int_from(line: str) -> int | None:
        """Return the last integer-like token contained in ``line``."""

        for token in reversed(line.split()):
            token = token.strip("│")
            if not token:
                continue
            try:
                return int(token)
            except ValueError:
                continue
        return None

    for line in lines:
        if match := _MISSING_BACKTICKS_RE.search(line):
            summary.missing_backticks.add(match.group(1))
        if match := _OSCILLATION_RE.search(line):
            summary.oscillation_messages.append(match.group(0))
        if match := _UNACCEPTABLE_CHAR_RE.search(line):
            summary.unacceptable_control_codes.add(match.group(1).lower())
        if match := _RECURSION_LIMIT_RE.search(line):
            summary.recursion_limit_hits += 1
        if "argument of type 'NoneType' is not iterable" in line:
            summary.qa_verification_none_errors += 1
        if _STATUS_DRAFT_RE.search(line):
            summary.draft_status_notes += 1
        if _FUTURE_DATE_RE.search(line):
            summary.future_dated_metadata_notes += 1
        if "Notes Processed" in line:
            if (value := _extract_last_int_from(line)) is not None:
                summary.notes_processed = value
        if "Errors" in line and "Metric" not in line:
            if (value := _extract_last_int_from(line)) is not None:
                summary.notes_with_errors = value

    return summary


def analyse_log_text(text: str) -> IssueSummary:
    """Convenience wrapper around :func:`analyse_lines`."""

    return analyse_lines(text.splitlines())


def analyse_log_file(path: Path) -> IssueSummary:
    """Load a log file and return the extracted summary."""

    content = path.read_text(encoding="utf-8")
    return analyse_log_text(content)


def render_report(summary: IssueSummary) -> str:
    """Render a deterministic human-readable report for the provided summary."""

    lines: List[str] = []
    lines.append("LLM review log analysis")
    lines.append("========================")
    if summary.notes_processed is not None:
        processed = summary.notes_processed
        errors = summary.notes_with_errors or 0
        lines.append(f"Processed notes: {processed} (errors: {errors})")
    if summary.oscillation_messages:
        lines.append("")
        lines.append("Oscillation detected in validators:")
        for msg in summary.oscillation_messages:
            lines.append(f"  - {msg}")
    if summary.missing_backticks:
        lines.append("")
        missing = ", ".join(sorted(summary.missing_backticks))
        lines.append(f"Missing backticks around Kotlin types: {missing}")
    if summary.unacceptable_control_codes:
        codes = ", ".join(sorted(summary.unacceptable_control_codes))
        lines.append(f"Control character crashes encountered: {codes}")
    if summary.recursion_limit_hits:
        lines.append(f"Recursion limit hit: {summary.recursion_limit_hits} time(s)")
    if summary.qa_verification_none_errors:
        lines.append(
            "QA verification NoneType errors: "
            f"{summary.qa_verification_none_errors} occurrence(s)"
        )
    if summary.future_dated_metadata_notes:
        lines.append(
            f"Future-dated metadata warnings: {summary.future_dated_metadata_notes} occurrence(s)"
        )
    if summary.draft_status_notes:
        lines.append(
            f"Draft-status metadata warnings: {summary.draft_status_notes} occurrence(s)"
        )
    if advice := summary.recommendations():
        lines.append("")
        lines.append("Recommended follow-up actions:")
        for item in advice:
            lines.append(f"  * {item}")
    return "\n".join(lines)


__all__ = [
    "IssueSummary",
    "analyse_lines",
    "analyse_log_text",
    "analyse_log_file",
    "render_report",
]


def _build_arg_parser():
    import argparse

    parser = argparse.ArgumentParser(
        description="Analyse llm-review logs and print a stability report.",
    )
    parser.add_argument(
        "log_file",
        type=Path,
        help="Path to the llm-review log file to analyse.",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)
    summary = analyse_log_file(args.log_file)
    print(render_report(summary))
    return 0


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    raise SystemExit(main())
