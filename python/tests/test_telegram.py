"""Characterization tests for Telegram notifications (port of TelegramNotificationServiceTest.kt
plus send/auth/progress coverage)."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from gitout.config import Telegram
from gitout.telegram import SyncStats, TelegramNotificationService, resolve_telegram_token


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
