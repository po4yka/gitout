"""Characterization tests for Telegram notifications (port of TelegramNotificationServiceTest.kt
plus send/auth/progress coverage)."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from gitout.config import Telegram
from gitout.search.qdrant import SearchResult
from gitout.telegram import (
    FailedRepoSummary,
    SyncStats,
    TelegramNotificationService,
    resolve_telegram_token,
)


def _service(
    config: Telegram | None, sender: list[str] | None = None
) -> TelegramNotificationService:
    sink = sender if sender is not None else []
    return TelegramNotificationService(config, environ={}, sender=sink.append)


# --- enabled logic (ported) ---


def test_disabled_when_config_none() -> None:
    assert TelegramNotificationService(None, environ={}).is_enabled() is False


def test_disabled_when_enabled_flag_false() -> None:
    config = Telegram(chat_id="123456", token="test-token", enabled=False)
    assert _service(config).is_enabled() is False


def test_disabled_when_chat_id_empty_or_blank() -> None:
    assert _service(Telegram(chat_id="", token="t", enabled=True)).is_enabled() is False
    assert _service(Telegram(chat_id="   ", token="t", enabled=True)).is_enabled() is False


def test_enabled_when_token_chat_id_and_flag_present() -> None:
    config = Telegram(chat_id="123456", token="test-token", enabled=True)
    assert _service(config).is_enabled() is True


# --- token resolution ---


def test_token_resolution_config_file_env(tmp_path: Path) -> None:
    assert resolve_telegram_token("cfg", {"TELEGRAM_BOT_TOKEN": "env"}) == "cfg"
    token_file = tmp_path / "tok"
    token_file.write_text("  file-token\n")
    env = {"TELEGRAM_BOT_TOKEN_FILE": str(token_file)}
    assert resolve_telegram_token(None, env) == "file-token"
    assert resolve_telegram_token(None, {"TELEGRAM_BOT_TOKEN": "env"}) == "env"
    assert resolve_telegram_token(None, {}) is None


# --- SyncStats (ported) ---


def test_sync_stats_defaults() -> None:
    stats = SyncStats()
    assert stats.last_sync_status == "No sync has been performed yet"
    assert stats.is_syncing is False
    assert stats.last_sync_time is None
    assert stats.total_repositories == 0
    assert stats.last_progress_percentage == 0


def test_sync_stats_replace_is_immutable() -> None:
    initial = SyncStats()
    updated = replace(initial, is_syncing=True, total_repositories=100)
    assert initial.is_syncing is False
    assert updated.is_syncing is True
    assert updated.total_repositories == 100


# --- notifications ---


def test_disabled_service_notifications_do_not_throw() -> None:
    service = TelegramNotificationService(None, environ={})
    service.notify_sync_start(100, 4)
    service.notify_progress(50, 100, "test-repo")
    service.notify_sync_completion(95, 5, 3600)


def test_enabled_service_sends_start_and_completion() -> None:
    sent: list[str] = []
    config = Telegram(chat_id="1", token="t", enabled=True)
    service = _service(config, sent)
    service.notify_sync_start(10, 2)
    service.notify_sync_completion(8, 2, 3661)
    assert any("Sync Started" in m for m in sent)
    assert any("Sync Completed" in m and "1h 1m 1s" in m for m in sent)


def test_progress_respects_step_threshold() -> None:
    sent: list[str] = []
    config = Telegram(chat_id="1", token="t", enabled=True, notify_progress_step_percent=10)
    service = _service(config, sent)
    service.notify_progress(1, 100)  # 1% — below 10% step, suppressed
    assert sent == []
    service.notify_progress(20, 100)  # 20% — crosses the threshold
    assert len(sent) == 1
    service.notify_progress(100, 100)  # always sends at 100%
    assert len(sent) == 2


def test_respects_notify_flags() -> None:
    sent: list[str] = []
    config = Telegram(chat_id="1", token="t", enabled=True, notify_start=False)
    service = _service(config, sent)
    service.notify_sync_start(5, 1)  # notify_start disabled -> no message
    assert sent == []
    # stats still updated despite no message
    assert service.stats.total_repositories == 5


def test_is_authorized() -> None:
    config = Telegram(chat_id="1", token="t", enabled=True, allowed_users=[42, 99])
    service = _service(config)
    assert service.is_authorized(42) is True
    assert service.is_authorized(7) is False


# --- interactive command handlers ---


class FakeSearch:
    def __init__(self, results: list[SearchResult] | None = None) -> None:
        self.results = results or []

    async def search(self, query: str) -> list[SearchResult]:
        return self.results


def _command_service(**kwargs: object) -> TelegramNotificationService:
    config = Telegram(chat_id="1", token="t", enabled=True, allowed_users=[42])
    return TelegramNotificationService(config, environ={}, sender=lambda _m: None, **kwargs)  # type: ignore[arg-type]


async def test_command_unknown_user_returns_none() -> None:
    assert await _command_service().handle_command("status", [], None) is None


async def test_command_unauthorized_user() -> None:
    reply = await _command_service().handle_command("status", [], user_id=7)
    assert reply is not None and "Unauthorized" in reply


async def test_command_ping_help_start() -> None:
    service = _command_service()
    assert "Pong" in (await service.handle_command("ping", [], 42) or "")
    assert "Bot Help" in (await service.handle_command("help", [], 42) or "")
    assert "Welcome" in (await service.handle_command("start", [], 42) or "")


async def test_command_status_and_stats() -> None:
    service = _command_service()
    status = await service.handle_command("status", [], 42)
    assert status is not None and "Last Sync Status" in status
    # Before any sync, stats has no last_sync_time.
    stats = await service.handle_command("stats", [], 42)
    assert stats is not None and "No synchronization has been performed yet" in stats
    # After a completion, stats reports counts.
    service.notify_sync_completion(8, 2, 60)
    stats2 = await service.handle_command("stats", [], 42)
    assert stats2 is not None and "Success Rate:" in stats2


async def test_command_fails() -> None:
    service = _command_service()
    assert "No recent repository failures" in (await service.handle_command("fails", [], 42) or "")
    service.record_failures(
        [FailedRepoSummary("u/r", "https://x/r.git", "boom", "Network Error", 3)]
    )
    reply = await service.handle_command("fails", [], 42)
    assert reply is not None
    assert "u/r" in reply and "Network Error" in reply and "(3 attempts)" in reply


async def test_command_info() -> None:
    reply = await _command_service().handle_command("info", [], 42)
    assert reply is not None
    assert "Bot Information" in reply and "Authorized Users:</b> 1" in reply


async def test_command_find() -> None:
    # No search configured.
    assert "not enabled" in (await _command_service().handle_command("find", ["q"], 42) or "")
    # Missing query.
    service = _command_service(search_index_service=FakeSearch())
    assert "Usage:" in (await service.handle_command("find", [], 42) or "")
    # With results.
    service = _command_service(
        search_index_service=FakeSearch(
            [SearchResult(id="p1", score=0.9, payload={"name": "u/repo", "language": "Python"})]
        )
    )
    reply = await service.handle_command("find", ["oauth", "lib"], 42)
    assert reply is not None and "u/repo" in reply and "(0.90)" in reply


async def test_command_reindex_without_state(tmp_path: Path) -> None:
    service = _command_service(search_index_service=FakeSearch(), search_destination=tmp_path)
    reply = await service.handle_command("reindex", [], 42)
    assert reply is not None and "Run a sync first" in reply
