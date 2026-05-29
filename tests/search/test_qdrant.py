"""Characterization tests for QdrantClient (port of QdrantClientTest.kt)."""

from __future__ import annotations

import json

import httpx
import pytest

from gitout.search.exceptions import SearchException
from gitout.search.qdrant import QdrantClient, QdrantPoint


class Recorder:
    def __init__(self, status: int, body: object) -> None:
        self.status = status
        self.body = body
        self.request: httpx.Request | None = None

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.request = request
        if isinstance(self.body, (dict, list)):
            return httpx.Response(self.status, json=self.body)
        return httpx.Response(self.status, text=str(self.body))

    @property
    def json_body(self) -> dict:
        assert self.request is not None
        return json.loads(self.request.content.decode())


def _client(recorder: Recorder) -> QdrantClient:
    transport = httpx.MockTransport(recorder)
    return QdrantClient("http://qdrant.test", client=httpx.AsyncClient(transport=transport))


async def test_ensure_collection_put_request() -> None:
    rec = Recorder(200, {"result": True})
    await _client(rec).ensure_collection("repos", 768)
    assert rec.request is not None
    assert rec.request.method == "PUT"
    assert rec.request.url.path == "/collections/repos"
    assert rec.json_body == {"vectors": {"size": 768, "distance": "Cosine"}}


async def test_ensure_collection_409_does_not_raise() -> None:
    await _client(Recorder(409, {"status": {"error": "exists"}})).ensure_collection("repos", 768)


async def test_ensure_collection_500_raises() -> None:
    with pytest.raises(SearchException):
        await _client(Recorder(500, "Internal Server Error")).ensure_collection("repos", 768)


async def test_upsert_put_request() -> None:
    rec = Recorder(200, {"result": {"status": "completed"}})
    points = [
        QdrantPoint(id="abc-123", vector=[0.1, 0.2, 0.3], payload={"name": "myrepo"})
    ]
    await _client(rec).upsert("repos", points)
    assert rec.request is not None
    assert rec.request.method == "PUT"
    assert rec.request.url.path == "/collections/repos/points"
    assert rec.json_body["points"][0]["id"] == "abc-123"
    assert rec.json_body["points"][0]["payload"]["name"] == "myrepo"


async def test_upsert_failure_raises() -> None:
    with pytest.raises(SearchException):
        await _client(Recorder(400, {"status": "error"})).upsert("repos", [])


async def test_search_posts_and_parses() -> None:
    body = {
        "result": [
            {"id": "point-1", "score": 0.91, "payload": {"name": "myrepo"}},
            {"id": "point-2", "score": 0.85, "payload": {"name": "otherrepo"}},
        ],
        "status": "ok",
    }
    rec = Recorder(200, body)
    results = await _client(rec).search("repos", [0.1, 0.2, 0.3], top_k=5)
    assert rec.request is not None
    assert rec.request.method == "POST"
    assert rec.request.url.path == "/collections/repos/points/search"
    assert rec.json_body["limit"] == 5
    assert rec.json_body["with_payload"] is True
    assert [r.id for r in results] == ["point-1", "point-2"]
    assert results[0].score == pytest.approx(0.91)
    assert results[0].payload["name"] == "myrepo"


async def test_search_failure_raises() -> None:
    with pytest.raises(SearchException):
        await _client(Recorder(404, {"status": "error"})).search("repos", [0.1], top_k=5)


async def test_get_payload_404_returns_none() -> None:
    assert await _client(Recorder(404, {"status": "error"})).get_payload("repos", "nope") is None


async def test_get_payload_200_returns_map() -> None:
    body = {"result": {"id": "point-1", "payload": {"name": "myrepo", "content_sha": "abc123"}}}
    payload = await _client(Recorder(200, body)).get_payload("repos", "point-1")
    assert payload == {"name": "myrepo", "content_sha": "abc123"}


async def test_get_payload_500_raises() -> None:
    with pytest.raises(SearchException):
        await _client(Recorder(500, "Internal Server Error")).get_payload("repos", "point-1")
