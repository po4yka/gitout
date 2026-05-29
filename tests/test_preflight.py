"""Characterization tests for the storage preflight check (port of HealthCheckTest.kt)."""

from __future__ import annotations

from pathlib import Path

from gitout.engine import preflight_storage_check


async def test_passes_on_writable_directory(tmp_path: Path) -> None:
    assert await preflight_storage_check(tmp_path, timeout_ms=5000) is None
    # Sentinel is cleaned up.
    assert list(tmp_path.iterdir()) == []  # noqa: ASYNC240 – test assertion only, not production I/O


async def test_fails_when_directory_missing(tmp_path: Path) -> None:
    result = await preflight_storage_check(tmp_path / "does-not-exist", timeout_ms=5000)
    assert result is not None
    assert "does not exist" in result


async def test_fails_when_target_is_a_file(tmp_path: Path) -> None:
    file = tmp_path / "file.txt"
    file.write_text("hello")
    result = await preflight_storage_check(file, timeout_ms=5000)
    assert result is not None
    assert "not a directory" in result


async def test_zero_timeout_fails_fast(tmp_path: Path) -> None:
    assert await preflight_storage_check(tmp_path, timeout_ms=0) is not None
