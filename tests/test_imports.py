"""Sanity check that the package and its public API surface import cleanly.

This is the one test that must pass in Phase 0 — it proves the scaffold is wired up.
"""

from __future__ import annotations


def test_public_modules_import() -> None:
    from gitout import config, errors, git_commands, github, retry

    # Public symbols the characterization suite depends on exist.
    assert errors.ErrorCategory.UNKNOWN
    assert config.Config(version=0)
    assert retry.BackoffStrategy.LINEAR
    assert github.UserRepositories
    assert callable(git_commands.build_git_command)
