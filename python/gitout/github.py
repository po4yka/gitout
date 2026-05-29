"""GitHub repository discovery via the GraphQL API.

Port of ``GitHub.kt``. The data containers are the spec; ``parse_user_repositories``
(the pure paging/dedupe logic over GraphQL ``user`` payloads) is implemented in Phase 1.
The query document is reused verbatim from ``src/main/graphql/GitHub.graphql``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class RepositoryMetadata:
    name: str
    is_archived: bool
    is_private: bool
    is_fork: bool
    visibility: str
    description: str | None
    updated_at: str
    repo_type: str  # "owned" | "starred" | "watching" | "gist"
    disk_usage_kb: int | None = None
    default_branch: str | None = None
    topics: list[str] = field(default_factory=list)
    language: str | None = None


@dataclass(frozen=True)
class UserRepositories:
    owned: set[str]
    starred: set[str]
    watching: set[str]
    gists: set[str]
    metadata: dict[str, RepositoryMetadata]


def parse_user_repositories(pages: list[dict[str, Any]]) -> UserRepositories:
    """Fold successive GraphQL ``data`` payloads into a :class:`UserRepositories`.

    Each element of ``pages`` is one query response's ``data`` object (containing a
    ``user`` key). Metadata dedupes with owned > starred > watching priority; gists
    are keyed by name. Mirrors the loop in ``GitHub.loadRepositories``.
    """
    raise NotImplementedError("Phase 1: port GitHub.loadRepositories parsing")
