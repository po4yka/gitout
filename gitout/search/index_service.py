"""Indexing/search orchestration (port of SearchIndexService.kt).

Builds a per-repo document (name + description + topics + language + README),
content-hashes it to skip unchanged repos, embeds via Gemini, and upserts into
Qdrant. ``search`` embeds the query and returns Qdrant hits. Errors on one repo are
isolated so the rest still index. The point id matches Java's
``UUID.nameUUIDFromBytes`` so the index is interchangeable with the Kotlin tool.
"""

from __future__ import annotations

import hashlib
import logging
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

from gitout.config import Search
from gitout.github import RepositoryMetadata
from gitout.search.exceptions import SearchException
from gitout.search.gemini import GeminiEmbeddingClient
from gitout.search.qdrant import QdrantClient, QdrantPoint, SearchResult
from gitout.search.readme_extractor import ReadmeExtractor

log = logging.getLogger(__name__)

EMBEDDING_DIMENSIONS = 3072


def _name_uuid_from_bytes(data: bytes) -> str:
    """Equivalent of Java's UUID.nameUUIDFromBytes (MD5, version 3)."""
    digest = bytearray(hashlib.md5(data).digest())  # noqa: S324 - matching Java, not security
    digest[6] = (digest[6] & 0x0F) | 0x30  # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80  # IETF variant
    return str(uuid.UUID(bytes=bytes(digest)))


def _now_iso() -> str:
    return datetime.now(UTC).isoformat()


class SearchIndexService:
    def __init__(
        self,
        gemini_client: GeminiEmbeddingClient,
        qdrant_client: QdrantClient,
        readme_extractor: ReadmeExtractor,
        config: Search,
        *,
        now_iso: Callable[[], str] = _now_iso,
    ) -> None:
        self._gemini = gemini_client
        self._qdrant = qdrant_client
        self._readme = readme_extractor
        self._config = config
        self._now_iso = now_iso

    async def index_repositories(
        self, repos: dict[str, RepositoryMetadata], backup_dir: Path
    ) -> None:
        try:
            await self._qdrant.ensure_collection(self._config.collection_name, EMBEDDING_DIMENSIONS)
        except Exception as exc:  # noqa: BLE001 - abort indexing, but don't crash the sync
            log.warning(
                "Failed to ensure Qdrant collection '%s': %s", self._config.collection_name, exc
            )
            return

        for repo in repos.values():
            try:
                await self._index_single_repo(repo, backup_dir)
            except Exception as exc:  # noqa: BLE001 - isolate per-repo failures
                log.warning("Unexpected error indexing '%s': %s", repo.name, exc)

    @staticmethod
    def _document_text(repo: RepositoryMetadata, readme: str) -> str:
        return (
            f"{repo.name}\n"
            f"{repo.description or ''}\n"
            f"topics: {', '.join(repo.topics)}\n"
            f"language: {repo.language or 'unknown'}\n"
            f"---\n"
            f"{readme}"
        )

    async def _index_single_repo(self, repo: RepositoryMetadata, backup_dir: Path) -> None:
        readme = self._readme.extract(backup_dir / repo.name)
        document_text = self._document_text(repo, readme)
        sha = hashlib.sha256(document_text.encode()).hexdigest()
        point_id = _name_uuid_from_bytes(f"gitout-repo:{repo.name}".encode())

        existing = await self._qdrant.get_payload(self._config.collection_name, point_id)
        if existing is not None and existing.get("content_sha") == sha:
            return  # content unchanged

        try:
            embedding = await self._gemini.embed(document_text)
        except SearchException as exc:
            log.warning("Failed to embed '%s': %s", repo.name, exc)
            return

        payload = {
            "name": repo.name,
            "description": repo.description or "",
            "language": repo.language or "unknown",
            "topics": list(repo.topics),
            "url": f"https://github.com/{repo.name}",
            "content_sha": sha,
            "indexed_at": self._now_iso(),
        }
        point = QdrantPoint(id=point_id, vector=embedding, payload=payload)

        try:
            await self._qdrant.upsert(self._config.collection_name, [point])
        except SearchException as exc:
            log.warning("Failed to upsert '%s' into Qdrant: %s", repo.name, exc)
            return

        log.info("Indexed %s", repo.name)

    async def search(self, query: str) -> list[SearchResult]:
        try:
            embedding = await self._gemini.embed(query)
        except SearchException as exc:
            log.warning("Failed to embed search query: %s", exc)
            return []
        try:
            return await self._qdrant.search(
                self._config.collection_name, embedding, self._config.top_k
            )
        except SearchException as exc:
            log.warning("Failed to search Qdrant: %s", exc)
            return []
