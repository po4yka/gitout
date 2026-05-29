"""Healthchecks.io ping client (port of HealthCheckService.kt).

``start()`` POSTs to ``{host}/{id}/start`` and returns a handle whose ``complete()``
POSTs to ``{host}/{id}``. Network failures are logged and swallowed (a failed ping
must never fail the backup). The httpx client is injectable for tests.
"""

from __future__ import annotations

import logging

import httpx

DEFAULT_HEALTHCHECK_HOST = "https://hc-ping.com"

log = logging.getLogger(__name__)


async def _post(client: httpx.AsyncClient | None, url: str, what: str) -> None:
    owned = client is None
    http = client or httpx.AsyncClient(timeout=30.0)
    try:
        await http.post(url)
    except httpx.HTTPError as exc:
        log.warning("Healthcheck %s request failed: %s", what, exc)
    finally:
        if owned:
            await http.aclose()


class StartedHealthCheck:
    def __init__(self, url: str, client: httpx.AsyncClient | None) -> None:
        self._url = url
        self._client = client

    async def complete(self) -> None:
        await _post(self._client, self._url, "complete")


class HealthCheck:
    def __init__(self, url: str, client: httpx.AsyncClient | None) -> None:
        self._url = url
        self._client = client

    async def start(self) -> StartedHealthCheck:
        await _post(self._client, f"{self._url}/start", "start")
        return StartedHealthCheck(self._url, self._client)


class HealthCheckService:
    def __init__(
        self, host: str = DEFAULT_HEALTHCHECK_HOST, *, client: httpx.AsyncClient | None = None
    ) -> None:
        self._host = host.rstrip("/")
        self._client = client

    def new_check(self, check_id: str) -> HealthCheck:
        return HealthCheck(f"{self._host}/{check_id}", self._client)
