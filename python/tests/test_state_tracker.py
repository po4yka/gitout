"""Characterization tests for RepositoryStateTracker (port of RepositoryStateTrackerTest.kt
plus change-detection coverage)."""

from __future__ import annotations

from pathlib import Path

from gitout.github import RepositoryMetadata
from gitout.state_tracker import (
    ExcludedRepo,
    RepositoryStateTracker,
    metadata_from_dict,
    metadata_to_dict,
)


def _meta(name: str, **overrides: object) -> RepositoryMetadata:
    base: dict = {
        "name": name,
        "is_archived": False,
        "is_private": False,
        "is_fork": False,
        "visibility": "PUBLIC",
        "description": None,
        "updated_at": "2024-01-01T00:00:00Z",
        "repo_type": "owned",
    }
    base.update(overrides)
    return RepositoryMetadata(**base)


# --- ported from RepositoryStateTrackerTest.kt ---


def test_metadata_round_trip_preserves_topics_and_language() -> None:
    original = _meta(
        "kotlin-cli",
        disk_usage_kb=100,
        default_branch="main",
        topics=["kotlin", "cli"],
        language="Kotlin",
    )
    restored = metadata_from_dict(metadata_to_dict(original))
    assert restored.topics == ["kotlin", "cli"]
    assert restored.language == "Kotlin"


def test_metadata_defaults_when_topics_and_language_absent() -> None:
    old = {
        "name": "old-repo",
        "isArchived": False,
        "isPrivate": True,
        "isFork": False,
        "visibility": "PRIVATE",
        "description": None,
        "updatedAt": None,
        "repoType": "owned",
    }
    restored = metadata_from_dict(old)
    assert restored.topics == []
    assert restored.language is None


# --- change detection ---


def test_no_previous_state_reports_no_new(tmp_path: Path) -> None:
    tracker = RepositoryStateTracker(tmp_path / "state.json")
    changes = tracker.detect_changes({"a/b": _meta("a/b")})
    assert changes.has_changes() is False  # first run: no NEW spam


def test_detects_archived_deleted_visibility_and_new(tmp_path: Path) -> None:
    state = tmp_path / "state.json"
    tracker = RepositoryStateTracker(state)
    tracker.save_state(
        {
            "a/keep": _meta("a/keep"),
            "a/archiveme": _meta("a/archiveme"),
            "a/deleteme": _meta("a/deleteme"),
            "a/flip": _meta("a/flip", visibility="PUBLIC"),
        }
    )

    current = {
        "a/keep": _meta("a/keep"),
        "a/archiveme": _meta("a/archiveme", is_archived=True),
        "a/flip": _meta("a/flip", visibility="PRIVATE"),
        "a/brandnew": _meta("a/brandnew"),
    }
    changes = tracker.detect_changes(current)

    assert [c.name for c in changes.archived] == ["a/archiveme"]
    assert [c.name for c in changes.deleted] == ["a/deleteme"]
    assert [c.name for c in changes.visibility_changed] == ["a/flip"]
    assert [c.name for c in changes.new_repos] == ["a/brandnew"]
    assert changes.total_changes() == 4
    vis = changes.visibility_changed[0]
    assert (vis.previous_value, vis.current_value) == ("PUBLIC", "PRIVATE")


def test_unarchived_detected(tmp_path: Path) -> None:
    tracker = RepositoryStateTracker(tmp_path / "state.json")
    tracker.save_state({"a/b": _meta("a/b", is_archived=True)})
    changes = tracker.detect_changes({"a/b": _meta("a/b", is_archived=False)})
    assert [c.name for c in changes.unarchived] == ["a/b"]


def test_excluded_repos_round_trip(tmp_path: Path) -> None:
    state = tmp_path / "state.json"
    tracker = RepositoryStateTracker(state)
    excluded = {
        "a/gone": ExcludedRepo(
            name="a/gone", excluded_at=123, reason="deleted", last_metadata=_meta("a/gone")
        )
    }
    tracker.save_state({"a/b": _meta("a/b")}, excluded_repos=excluded)

    loaded = RepositoryStateTracker(state).get_excluded_repos()
    assert set(loaded) == {"a/gone"}
    assert loaded["a/gone"].reason == "deleted"
    assert loaded["a/gone"].last_metadata is not None
    assert loaded["a/gone"].last_metadata.name == "a/gone"
