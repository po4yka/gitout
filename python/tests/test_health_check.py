"""Characterization tests for the Healthchecks.io ping client (port of HealthCheckService.kt)."""

from __future__ import annotations

import httpx

from gitout.health_check import HealthCheckService


class Recorder:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.paths: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.paths.append(request.url.path)
        if self.fail:
            raise httpx.ConnectError("boom")
        return httpx.Response(200, text="OK")


def _service(rec: Recorder) -> HealthCheckService:
    transport = httpx.MockTransport(rec)
    return HealthCheckService("https://hc.test", client=httpx.AsyncClient(transport=transport))


async def test_start_then_complete_hits_expected_paths() -> None:
    rec = Recorder()
    check = _service(rec).new_check("my-check-id")
    started = await check.start()
    await started.complete()
    assert rec.paths == ["/my-check-id/start", "/my-check-id"]


async def test_network_failure_is_swallowed() -> None:
    rec = Recorder(fail=True)
    check = _service(rec).new_check("id")
    # Neither call raises despite the transport erroring.
    started = await check.start()
    await started.complete()
    assert rec.paths == ["/id/start", "/id"]
