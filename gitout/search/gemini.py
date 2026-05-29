"""Gemini embedding client (port of GeminiEmbeddingClient.kt).

POSTs text to the Gemini ``embedContent`` endpoint and returns the embedding vector.
Text is truncated to 8000 chars. Any HTTP error or malformed response raises
SearchException. The httpx client is injectable for tests.
"""

from __future__ import annotations

import httpx

from gitout.search.exceptions import SearchException

EMBED_TEXT_MAX_CHARS = 8000
GEMINI_MODEL = "gemini-embedding-exp-03-07"
GEMINI_EMBED_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:embedContent"
)


class GeminiEmbeddingClient:
    def __init__(
        self,
        api_key: str,
        *,
        client: httpx.AsyncClient | None = None,
        embed_url: str = GEMINI_EMBED_URL,
    ) -> None:
        self._api_key = api_key
        self._client = client
        self._embed_url = embed_url

    async def embed(self, text: str) -> list[float]:
        truncated = text[:EMBED_TEXT_MAX_CHARS]
        body = {
            "model": f"models/{GEMINI_MODEL}",
            "content": {"parts": [{"text": truncated}]},
        }
        url = f"{self._embed_url}?key={self._api_key}"

        owned = self._client is None
        http = self._client or httpx.AsyncClient(timeout=60.0)
        try:
            try:
                response = await http.post(url, json=body)
            except httpx.HTTPError as exc:
                raise SearchException(f"Network error during Gemini embedding: {exc}", exc) from exc

            if response.status_code // 100 != 2:
                snippet = response.text[:200]
                raise SearchException(
                    f"Gemini embedding failed: HTTP {response.status_code} {snippet}"
                )
            try:
                values = response.json()["embedding"]["values"]
                return [float(v) for v in values]
            except SearchException:
                raise
            except Exception as exc:  # noqa: BLE001 - wrapped as SearchException
                raise SearchException(
                    f"Failed to parse Gemini embedding response: {exc}", exc
                ) from exc
        finally:
            if owned:
                await http.aclose()
