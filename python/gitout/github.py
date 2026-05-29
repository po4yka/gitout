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


def _edges(connection: dict[str, Any] | None, key: str) -> list[dict[str, Any]]:
    if not connection:
        return []
    return connection.get(key) or []


def _repo_metadata(node: dict[str, Any], repo_type: str) -> RepositoryMetadata:
    topic_nodes = (node.get("repositoryTopics") or {}).get("nodes") or []
    topics = [t["topic"]["name"] for t in topic_nodes if t and t.get("topic")]
    default_branch_ref = node.get("defaultBranchRef")
    primary_language = node.get("primaryLanguage")
    return RepositoryMetadata(
        name=node["nameWithOwner"],
        is_archived=node["isArchived"],
        is_private=node["isPrivate"],
        is_fork=node["isFork"],
        visibility=node["visibility"],
        description=node.get("description"),
        updated_at=node["updatedAt"],
        repo_type=repo_type,
        disk_usage_kb=node.get("diskUsage"),
        default_branch=default_branch_ref["name"] if default_branch_ref else None,
        topics=topics,
        language=primary_language["name"] if primary_language else None,
    )


def _gist_metadata(node: dict[str, Any]) -> RepositoryMetadata:
    is_public = node["isPublic"]
    return RepositoryMetadata(
        name=node["name"],
        is_archived=False,  # gists have no archive status
        is_private=not is_public,
        is_fork=False,  # gists have no fork status
        visibility="PUBLIC" if is_public else "PRIVATE",
        description=node.get("description"),
        updated_at=node["updatedAt"],
        repo_type="gist",
    )


def parse_user_repositories(pages: list[dict[str, Any]]) -> UserRepositories:
    """Fold successive GraphQL ``data`` payloads into a :class:`UserRepositories`.

    Each element of ``pages`` is one query response's ``data`` object (containing a
    ``user`` key). Metadata dedupes with owned > starred > watching priority; owned
    and gist metadata overwrite unconditionally. Mirrors ``GitHub.loadRepositories``.
    """
    owned: set[str] = set()
    starred: set[str] = set()
    watching: set[str] = set()
    gists: set[str] = set()
    metadata: dict[str, RepositoryMetadata] = {}

    for page in pages:
        user = page.get("user")
        if user is None:
            continue

        for edge in _edges(user.get("ownedRepositories"), "ownedEdges"):
            node = edge.get("node") if edge else None
            if node:
                owned.add(node["nameWithOwner"])
                metadata[node["nameWithOwner"]] = _repo_metadata(node, "owned")

        for edge in _edges(user.get("starredRepositories"), "starredEdges"):
            node = edge.get("node") if edge else None
            if node:
                starred.add(node["nameWithOwner"])
                if node["nameWithOwner"] not in metadata:
                    metadata[node["nameWithOwner"]] = _repo_metadata(node, "starred")

        for edge in _edges(user.get("watchingRepositories"), "watchingEdges"):
            node = edge.get("node") if edge else None
            if node:
                watching.add(node["nameWithOwner"])
                if node["nameWithOwner"] not in metadata:
                    metadata[node["nameWithOwner"]] = _repo_metadata(node, "watching")

        for edge in _edges(user.get("gistRepositories"), "gistEdges"):
            node = edge.get("node") if edge else None
            if node:
                gists.add(node["name"])
                metadata[node["name"]] = _gist_metadata(node)

    return UserRepositories(
        owned=owned,
        starred=starred,
        watching=watching,
        gists=gists,
        metadata=metadata,
    )
