"""Characterization tests for the git argv builder (port of Engine.buildGitCommand).

The exact argv is the contract git executes, so every branch is pinned by fixture.
Red until ``gitout.git_commands.build_git_command`` lands in Phase 1.
"""

from __future__ import annotations

import pytest

from gitout import git_commands
from tests.helpers import load_json

_CASES = load_json("git_commands", "argv_cases.json")["cases"]


@pytest.mark.characterization
@pytest.mark.parametrize("case", _CASES, ids=[c["name"] for c in _CASES])
def test_build_git_command(case: dict) -> None:
    assert git_commands.build_git_command(**case["params"]) == case["expected_argv"]
