"""Characterization tests for SearchIndexService (port of SearchIndexServiceTest.kt).

Uses in-memory fakes for Gemini/Qdrant/README to assert the orchestration directly.
"""

from __future__ import annotations

from collections import deque
from pathlib import Path
from typing import Any

import pytest

from gitout.config import Search
from gitout.github import RepositoryMetadata
from gitout.search.exceptions import SearchException
from gitout.search.index_service import SearchIndexService
from gitout.search.qdrant import QdrantPoint, SearchResult


class FakeReadme:
    def extract(self, path: Path) -> str:
        return ""


class FakeGemini:
    def __init__(self, behaviors: list[Any] | None = None) -> None:
        # Each behavior is a vector (success) or a SearchException (failure).
        self.behaviors = deque(behaviors or [])
        self.calls: list[str] = []

    async def embed(self, text: str) -> list[float]:
        self.calls.append(text)
        behavior = self.behaviors.popleft() if self.behaviors else [0.1, 0.2, 0.3]
        if isinstance(behavior, SearchException):
            raise behavior
        return behavior


class FakeQdrant:
    def __init__(self) -> None:
        self.ensure_calls: list[tuple[str, int]] = []
        self.upserts: list[QdrantPoint] = []
        self.payload_for: dict[str, dict[str, Any]] = {}
        self.search_results: list[SearchResult] = []
        self.search_called = False

    async def ensure_collection(self, name: str, vector_size: int) -> None:
        self.ensure_calls.append((name, vector_size))

    async def get_payload(self, collection: str, point_id: str) -> dict[str, Any] | None:
        return self.payload_for.get(point_id)

    async def upsert(self, collection: str, points: list[QdrantPoint]) -> None:
        self.upserts.extend(points)

    async def search(self, collection: str, vector: list[float], top_k: int) -> list[SearchResult]:
        self.search_called = True
        return self.search_results


def _service(gemini: FakeGemini, qdrant: FakeQdrant) -> SearchIndexService:
    config = Search(enabled=True, collection_name="test-repos", top_k=5)
    return SearchIndexService(gemini, qdrant, FakeReadme(), config)  # type: ignore[arg-type]


def _repo(name: str) -> RepositoryMetadata:
    return RepositoryMetadata(
        name=name,
        is_archived=False,
        is_private=False,
        is_fork=False,
        visibility="public",
        description="A test repo",
        updated_at=None,
        repo_type="owned",
        topics=["kotlin", "test"],
        language="Kotlin",
    )


async def test_happy_path_ensures_embeds_and_upserts(tmp_path: Path) -> None:
    gemini = FakeGemini()
    qdrant = FakeQdrant()
    repos = {"user/myrepo": _repo("user/myrepo")}
    await _service(gemini, qdrant).index_repositories(repos, tmp_path)

    assert qdrant.ensure_calls == [("test-repos", 3072)]
    assert gemini.calls  # embed was called
    assert len(qdrant.upserts) == 1
    assert qdrant.upserts[0].payload["name"] == "user/myrepo"
    assert qdrant.upserts[0].payload["url"] == "https://github.com/user/myrepo"


async def test_sha_dedup_skips_embed_on_unchanged(tmp_path: Path) -> None:
    gemini = FakeGemini()
    qdrant = FakeQdrant()
    service = _service(gemini, qdrant)
    repos = {"user/deduprepo": _repo("user/deduprepo")}

    await service.index_repositories(repos, tmp_path)
    assert len(gemini.calls) == 1
    point = qdrant.upserts[0]
    qdrant.payload_for[point.id] = {"content_sha": point.payload["content_sha"]}

    await service.index_repositories(repos, tmp_path)
    assert len(gemini.calls) == 1  # second pass skipped embedding


async def test_error_isolation_one_failure_does_not_block_next(tmp_path: Path) -> None:
    gemini = FakeGemini([SearchException("boom"), [0.1, 0.2]])
    qdrant = FakeQdrant()
    repos = {"user/repo-fail": _repo("user/repo-fail"), "user/repo-ok": _repo("user/repo-ok")}
    await _service(gemini, qdrant).index_repositories(repos, tmp_path)

    assert len(gemini.calls) == 2  # both attempted
    assert [p.payload["name"] for p in qdrant.upserts] == ["user/repo-ok"]


async def test_search_returns_results() -> None:
    gemini = FakeGemini()
    qdrant = FakeQdrant()
    qdrant.search_results = [SearchResult(id="point-1", score=0.95, payload={"name": "myrepo"})]
    results = await _service(gemini, qdrant).search("kotlin libraries")
    assert [r.id for r in results] == ["point-1"]
    assert results[0].score == pytest.approx(0.95)


async def test_search_empty_when_embed_fails() -> None:
    gemini = FakeGemini([SearchException("boom")])
    qdrant = FakeQdrant()
    results = await _service(gemini, qdrant).search("query")
    assert results == []
    assert qdrant.search_called is False
