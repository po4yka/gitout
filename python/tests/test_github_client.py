"""GraphQL client paging test using httpx's in-memory MockTransport (no network)."""

from __future__ import annotations

import json
from typing import Any

import httpx

from gitout.github_client import load_repositories

_REPO_NODE = {
    "nameWithOwner": "me/r1",
    "isArchived": False,
    "isPrivate": False,
    "isFork": False,
    "visibility": "PUBLIC",
    "description": None,
    "updatedAt": "2024-01-01T00:00:00Z",
    "diskUsage": 10,
    "defaultBranchRef": {"name": "main"},
    "repositoryTopics": {"nodes": []},
    "primaryLanguage": None,
}


def _empty_user() -> dict[str, Any]:
    return {
        "ownedRepositories": {"ownedEdges": []},
        "starredRepositories": {"starredEdges": []},
        "watchingRepositories": {"watchingEdges": []},
        "gistRepositories": {"gistEdges": []},
    }


async def test_load_repositories_pages_until_empty() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert request.headers["Authorization"] == "Bearer tok"
        assert payload["variables"]["login"] == "me"
        calls["n"] += 1
        if calls["n"] == 1:
            user = _empty_user()
            user["ownedRepositories"] = {"ownedEdges": [{"cursor": "c1", "node": _REPO_NODE}]}
            return httpx.Response(200, json={"data": {"user": user}})
        return httpx.Response(200, json={"data": {"user": _empty_user()}})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as client:
        repos = await load_repositories("me", "tok", client=client)

    assert calls["n"] == 2  # first page has data, second is empty -> stop
    assert repos.owned == {"me/r1"}
    assert repos.metadata["me/r1"].default_branch == "main"
