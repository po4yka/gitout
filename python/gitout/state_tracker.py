"""Repository state tracking between syncs (port of RepositoryStateTracker.kt).

Detects archived/unarchived/deleted/visibility/new changes against the previous run
and persists a JSON snapshot (camelCase keys, interchangeable with Kotlin) including an
exclusion list for deleted/inaccessible repos. ``RepositoryMetadata`` is reused from
``gitout.github``; (de)serialization here maps its snake_case fields to the camelCase
keys the Kotlin tool writes.
"""

from __future__ import annotations

import contextlib
import json
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from gitout.github import RepositoryMetadata


def metadata_to_dict(meta: RepositoryMetadata) -> dict[str, Any]:
    return {
        "name": meta.name,
        "isArchived": meta.is_archived,
        "isPrivate": meta.is_private,
        "isFork": meta.is_fork,
        "visibility": meta.visibility,
        "description": meta.description,
        "updatedAt": meta.updated_at,
        "repoType": meta.repo_type,
        "diskUsageKb": meta.disk_usage_kb,
        "defaultBranch": meta.default_branch,
        "topics": list(meta.topics),
        "language": meta.language,
    }


def metadata_from_dict(data: dict[str, Any]) -> RepositoryMetadata:
    return RepositoryMetadata(
        name=data["name"],
        is_archived=data["isArchived"],
        is_private=data["isPrivate"],
        is_fork=data["isFork"],
        visibility=data["visibility"],
        description=data.get("description"),
        updated_at=data.get("updatedAt"),
        repo_type=data["repoType"],
        disk_usage_kb=data.get("diskUsageKb"),
        default_branch=data.get("defaultBranch"),
        topics=list(data.get("topics", [])),
        language=data.get("language"),
    )


class ChangeType(Enum):
    ARCHIVED = "ARCHIVED"
    UNARCHIVED = "UNARCHIVED"
    DELETED = "DELETED"
    VISIBILITY_CHANGED = "VISIBILITY_CHANGED"
    NEW = "NEW"


@dataclass(frozen=True)
class RepositoryChange:
    name: str
    change_type: ChangeType
    previous_value: str | None
    current_value: str | None
    metadata: RepositoryMetadata


@dataclass(frozen=True)
class RepositoryChanges:
    archived: list[RepositoryChange] = field(default_factory=list)
    unarchived: list[RepositoryChange] = field(default_factory=list)
    deleted: list[RepositoryChange] = field(default_factory=list)
    visibility_changed: list[RepositoryChange] = field(default_factory=list)
    new_repos: list[RepositoryChange] = field(default_factory=list)

    def has_changes(self) -> bool:
        return bool(
            self.archived
            or self.unarchived
            or self.deleted
            or self.visibility_changed
            or self.new_repos
        )

    def total_changes(self) -> int:
        return (
            len(self.archived)
            + len(self.unarchived)
            + len(self.deleted)
            + len(self.visibility_changed)
            + len(self.new_repos)
        )


@dataclass(frozen=True)
class ExcludedRepo:
    name: str
    excluded_at: int
    reason: str
    last_metadata: RepositoryMetadata | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "excludedAt": self.excluded_at,
            "reason": self.reason,
            "lastMetadata": metadata_to_dict(self.last_metadata) if self.last_metadata else None,
        }

    @staticmethod
    def from_dict(data: dict[str, Any]) -> ExcludedRepo:
        last = data.get("lastMetadata")
        return ExcludedRepo(
            name=data["name"],
            excluded_at=data["excludedAt"],
            reason=data["reason"],
            last_metadata=metadata_from_dict(last) if last else None,
        )


def _now_ms() -> int:
    return int(time.time() * 1000)


class RepositoryStateTracker:
    def __init__(self, state_file: Path, *, now_ms: Callable[[], int] = _now_ms) -> None:
        self._state_file = state_file
        self._now_ms = now_ms

    def detect_changes(
        self, current_repos: dict[str, RepositoryMetadata]
    ) -> RepositoryChanges:
        previous = self._load_state()
        previous_repos = self._parse_repositories(previous)

        archived: list[RepositoryChange] = []
        unarchived: list[RepositoryChange] = []
        deleted: list[RepositoryChange] = []
        visibility_changed: list[RepositoryChange] = []
        new_repos: list[RepositoryChange] = []

        for name, prev in previous_repos.items():
            current = current_repos.get(name)
            if current is None:
                deleted.append(
                    RepositoryChange(name, ChangeType.DELETED, None, None, prev)
                )
                continue
            if not prev.is_archived and current.is_archived:
                archived.append(
                    RepositoryChange(name, ChangeType.ARCHIVED, "active", "archived", current)
                )
            elif prev.is_archived and not current.is_archived:
                unarchived.append(
                    RepositoryChange(name, ChangeType.UNARCHIVED, "archived", "active", current)
                )
            if prev.visibility != current.visibility:
                visibility_changed.append(
                    RepositoryChange(
                        name,
                        ChangeType.VISIBILITY_CHANGED,
                        prev.visibility,
                        current.visibility,
                        current,
                    )
                )

        if previous_repos:
            for name, current in current_repos.items():
                if name not in previous_repos:
                    new_repos.append(RepositoryChange(name, ChangeType.NEW, None, None, current))

        return RepositoryChanges(
            archived=archived,
            unarchived=unarchived,
            deleted=deleted,
            visibility_changed=visibility_changed,
            new_repos=new_repos,
        )

    def save_state(
        self,
        repositories: dict[str, RepositoryMetadata],
        excluded_repos: dict[str, ExcludedRepo] | None = None,
    ) -> None:
        excluded_repos = excluded_repos or {}
        payload = {
            "version": 1,
            "lastUpdated": self._now_ms(),
            "repositories": {n: metadata_to_dict(m) for n, m in repositories.items()},
            "excludedRepos": {n: e.to_dict() for n, e in excluded_repos.items()},
        }
        with contextlib.suppress(OSError):
            self._state_file.write_text(json.dumps(payload, indent=2))

    def get_excluded_repos(self) -> dict[str, ExcludedRepo]:
        state = self._load_state()
        if state is None:
            return {}
        return {
            n: ExcludedRepo.from_dict(e) for n, e in state.get("excludedRepos", {}).items()
        }

    def _load_state(self) -> dict[str, Any] | None:
        if not self._state_file.exists():
            return None
        try:
            result: dict[str, Any] = json.loads(self._state_file.read_text())
            return result
        except (OSError, ValueError):
            return None

    @staticmethod
    def _parse_repositories(state: dict[str, Any] | None) -> dict[str, RepositoryMetadata]:
        if state is None:
            return {}
        return {n: metadata_from_dict(m) for n, m in state.get("repositories", {}).items()}
