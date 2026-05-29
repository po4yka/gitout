"""Async GitHub GraphQL client: pages the UserRepos query and folds the result.

Port of the networking half of ``GitHub.kt`` (the pure fold lives in ``github.py``).
The query is the same document as ``src/main/graphql/GitHub.graphql``.
"""

from __future__ import annotations

from typing import Any

import httpx

from gitout import __version__
from gitout.github import UserRepositories, parse_user_repositories

GITHUB_GRAPHQL_ENDPOINT = "https://api.github.com/graphql"

USER_REPOS_QUERY = """
query UserRepos(
  $login: String!,
  $ownerAfter: String,
  $starredAfter: String,
  $watchingAfter: String,
  $gistsAfter: String,
) {
  user(login: $login) {
    ownedRepositories: repositories(first: 100, ownerAffiliations: OWNER, after: $ownerAfter) {
      ownedEdges: edges {
        cursor
        node { ...RepoFields }
      }
    }
    starredRepositories(first: 100, after: $starredAfter) {
      starredEdges: edges {
        cursor
        node { ...RepoFields }
      }
    }
    watchingRepositories: watching(first: 100, after: $watchingAfter) {
      watchingEdges: edges {
        cursor
        node { ...RepoFields }
      }
    }
    gistRepositories: gists(first: 100, privacy: ALL, after: $gistsAfter) {
      gistEdges: edges {
        cursor
        node { name isPublic description updatedAt }
      }
    }
  }
}

fragment RepoFields on Repository {
  nameWithOwner
  isArchived
  isPrivate
  isFork
  visibility
  description
  updatedAt
  diskUsage
  defaultBranchRef { name }
  repositoryTopics(first: 10) { nodes { topic { name } } }
  primaryLanguage { name }
}
"""


def _edges(connection: dict[str, Any] | None, key: str) -> list[dict[str, Any]]:
    if not connection:
        return []
    return connection.get(key) or []


async def load_repositories(
    user: str,
    token: str,
    *,
    client: httpx.AsyncClient | None = None,
    endpoint: str = GITHUB_GRAPHQL_ENDPOINT,
) -> UserRepositories:
    """Page through every owned/starred/watching/gist connection and fold the result."""
    owned_managed = client is None
    http = client or httpx.AsyncClient(timeout=60.0)
    headers = {
        "Authorization": f"Bearer {token}",
        "User-Agent": f"gitout/{__version__}",
    }
    cursors: dict[str, str | None] = {
        "ownerAfter": None,
        "starredAfter": None,
        "watchingAfter": None,
        "gistsAfter": None,
    }
    pages: list[dict[str, Any]] = []
    try:
        while True:
            variables = {"login": user, **cursors}
            response = await http.post(
                endpoint,
                json={"query": USER_REPOS_QUERY, "variables": variables},
                headers=headers,
            )
            response.raise_for_status()
            body = response.json()
            if body.get("errors"):
                raise RuntimeError(f"GitHub GraphQL errors: {body['errors']}")

            data = body["data"]
            user_node = data.get("user")
            if user_node is None:
                raise RuntimeError(f"GitHub user not found: {user}")

            owned = _edges(user_node.get("ownedRepositories"), "ownedEdges")
            starred = _edges(user_node.get("starredRepositories"), "starredEdges")
            watching = _edges(user_node.get("watchingRepositories"), "watchingEdges")
            gists = _edges(user_node.get("gistRepositories"), "gistEdges")
            if not owned and not starred and not watching and not gists:
                break

            pages.append(data)
            if owned:
                cursors["ownerAfter"] = owned[-1]["cursor"]
            if starred:
                cursors["starredAfter"] = starred[-1]["cursor"]
            if watching:
                cursors["watchingAfter"] = watching[-1]["cursor"]
            if gists:
                cursors["gistsAfter"] = gists[-1]["cursor"]
    finally:
        if owned_managed:
            await http.aclose()

    return parse_user_repositories(pages)
