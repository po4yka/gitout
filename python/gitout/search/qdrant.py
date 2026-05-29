"""Qdrant REST client (port of QdrantClient.kt).

Async httpx wrapper over the Qdrant collection/points API: ensure_collection (PUT,
409 treated as success), upsert (PUT points), search (POST points/search), and
get_payload (GET point, 404 -> None). Non-2xx responses raise SearchException.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import httpx

from gitout.search.exceptions import SearchException


@dataclass(frozen=True)
class QdrantPoint:
    id: str
    vector: list[float]
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SearchResult:
    id: str
    score: float
    payload: dict[str, Any] = field(default_factory=dict)


class QdrantClient:
    def __init__(self, base_url: str, *, client: httpx.AsyncClient | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = client

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        owned = self._client is None
        http = self._client or httpx.AsyncClient(timeout=60.0)
        try:
            return await http.request(method, f"{self._base_url}{path}", **kwargs)
        finally:
            if owned:
                await http.aclose()

    async def ensure_collection(self, name: str, vector_size: int) -> None:
        body = {"vectors": {"size": vector_size, "distance": "Cosine"}}
        response = await self._request("PUT", f"/collections/{name}", json=body)
        if response.status_code == 409:
            return  # already exists
        if response.status_code // 100 != 2:
            raise SearchException(
                f"Failed to ensure collection '{name}': HTTP {response.status_code} {response.text}"
            )

    async def upsert(self, collection_name: str, points: list[QdrantPoint]) -> None:
        body = {
            "points": [
                {"id": p.id, "vector": p.vector, "payload": p.payload} for p in points
            ]
        }
        response = await self._request(
            "PUT", f"/collections/{collection_name}/points", json=body
        )
        if response.status_code // 100 != 2:
            raise SearchException(
                f"Failed to upsert points into '{collection_name}': "
                f"HTTP {response.status_code} {response.text}"
            )

    async def search(
        self, collection_name: str, vector: list[float], top_k: int
    ) -> list[SearchResult]:
        body = {"vector": list(vector), "limit": top_k, "with_payload": True}
        response = await self._request(
            "POST", f"/collections/{collection_name}/points/search", json=body
        )
        if response.status_code // 100 != 2:
            raise SearchException(
                f"Failed to search '{collection_name}': HTTP {response.status_code} {response.text}"
            )
        try:
            result = response.json()["result"]
            return [
                SearchResult(
                    id=str(item["id"]),
                    score=float(item["score"]),
                    payload=item.get("payload") or {},
                )
                for item in result
            ]
        except SearchException:
            raise
        except Exception as exc:  # noqa: BLE001 - wrapped as SearchException
            raise SearchException(f"Failed to parse Qdrant response: {exc}", exc) from exc

    async def get_payload(self, collection_name: str, point_id: str) -> dict[str, Any] | None:
        response = await self._request(
            "GET", f"/collections/{collection_name}/points/{point_id}"
        )
        if response.status_code == 404:
            return None
        if response.status_code // 100 != 2:
            raise SearchException(
                f"Failed to get point '{point_id}' from '{collection_name}': "
                f"HTTP {response.status_code} {response.text}"
            )
        try:
            result = response.json()["result"]
            return result.get("payload") or {}
        except SearchException:
            raise
        except Exception as exc:  # noqa: BLE001 - wrapped as SearchException
            raise SearchException(f"Failed to parse Qdrant response: {exc}", exc) from exc
