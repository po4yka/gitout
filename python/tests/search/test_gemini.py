"""Characterization tests for GeminiEmbeddingClient (port of GeminiEmbeddingClientTest.kt)."""

from __future__ import annotations

import json

import httpx
import pytest

from gitout.search.exceptions import SearchException
from gitout.search.gemini import GeminiEmbeddingClient


def _client(handler: httpx.MockTransport) -> GeminiEmbeddingClient:
    return GeminiEmbeddingClient(
        "test-api-key", client=httpx.AsyncClient(transport=handler), embed_url="https://gemini.test/embed"
    )


async def test_embed_success_returns_vector_and_sends_request() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["method"] = request.method
        captured["body"] = request.content.decode()
        return httpx.Response(200, json={"embedding": {"values": [0.123, -0.456, 0.789]}})

    result = await _client(httpx.MockTransport(handler)).embed("hello world")

    assert captured["method"] == "POST"
    assert "test-api-key" in str(captured["url"])
    body = json.loads(str(captured["body"]))
    assert body["model"] == "models/gemini-embedding-exp-03-07"
    assert body["content"]["parts"][0]["text"] == "hello world"
    assert result == [pytest.approx(0.123), pytest.approx(-0.456), pytest.approx(0.789)]


async def test_embed_truncates_to_8000_chars() -> None:
    captured: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = request.content.decode()
        return httpx.Response(200, json={"embedding": {"values": [0.1, 0.2]}})

    await _client(httpx.MockTransport(handler)).embed("a" * 10000)
    text = json.loads(captured["body"])["content"]["parts"][0]["text"]
    assert len(text) == 8000


@pytest.mark.parametrize("status", [401, 429, 500])
async def test_embed_raises_on_http_error(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, text="error")

    with pytest.raises(SearchException):
        await _client(httpx.MockTransport(handler)).embed("test")


async def test_embed_raises_on_malformed_json() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="not valid json at all {{{")

    with pytest.raises(SearchException):
        await _client(httpx.MockTransport(handler)).embed("test")
