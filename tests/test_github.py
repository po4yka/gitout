"""Characterization tests for GraphQL repository parsing (port of GitHub.kt).

Exercises the pure paging/dedupe fold over canned GraphQL `data` payloads — no
network. Red until ``gitout.github.parse_user_repositories`` lands in Phase 1.
"""

from __future__ import annotations

import dataclasses

import pytest

from gitout import github
from tests.helpers import load_json

_FIXTURE = load_json("github", "user_repos_pages.json")


@pytest.fixture
def parsed() -> github.UserRepositories:
    return github.parse_user_repositories(_FIXTURE["pages"])


@pytest.mark.characterization
@pytest.mark.parametrize("bucket", ["owned", "starred", "watching", "gists"])
def test_repository_sets(parsed: github.UserRepositories, bucket: str) -> None:
    assert getattr(parsed, bucket) == set(_FIXTURE["expected"][bucket])


@pytest.mark.characterization
def test_metadata_matches_expected(parsed: github.UserRepositories) -> None:
    actual = {name: dataclasses.asdict(meta) for name, meta in parsed.metadata.items()}
    assert actual == _FIXTURE["expected"]["metadata"]


@pytest.mark.characterization
def test_owned_priority_over_starred(parsed: github.UserRepositories) -> None:
    # repo-a appears in both owned and starred; metadata must remain "owned".
    assert "octocat/repo-a" in parsed.starred
    assert parsed.metadata["octocat/repo-a"].repo_type == "owned"
