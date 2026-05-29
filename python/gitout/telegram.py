"""Telegram notifications (port of TelegramNotificationService.kt, notification core).

Sends sync lifecycle notifications via the Telegram Bot API. Token resolution is
config > TELEGRAM_BOT_TOKEN_FILE > TELEGRAM_BOT_TOKEN. The message sender is injectable
(default posts to the Bot API with httpx); when the service is disabled every notify
call is a no-op. The interactive long-polling command bot is intentionally not ported.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path

import httpx

from gitout.config import DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT, Telegram

log = logging.getLogger(__name__)

# A message sender: (html_text) -> None.
MessageSender = Callable[[str], None]


@dataclass(frozen=True)
class SyncStats:
    last_sync_status: str = "No sync has been performed yet"
    is_syncing: bool = False
    last_sync_time: str | None = None
    total_repositories: int = 0
    successful_repositories: int = 0
    failed_repositories: int = 0
    last_progress_percentage: int = 0


@dataclass(frozen=True)
class FailedRepoSummary:
    name: str
    url: str
    error_message: str
    category: str
    retry_attempts: int


def resolve_telegram_token(config_token: str | None, environ: Mapping[str, str]) -> str | None:
    """Resolve a Telegram bot token: config > TELEGRAM_BOT_TOKEN_FILE > TELEGRAM_BOT_TOKEN."""
    if config_token is not None and config_token.strip():
        return config_token.strip()

    token_file_path = environ.get("TELEGRAM_BOT_TOKEN_FILE")
    if token_file_path:
        token_file = Path(token_file_path)
        if token_file.exists():
            token = token_file.read_text().strip()
            if token:
                return token

    token_env = environ.get("TELEGRAM_BOT_TOKEN")
    if token_env:
        return token_env
    return None


def format_duration(seconds: int) -> str:
    """Human-readable duration (e.g. '1h 2m 3s')."""
    hours, rem = divmod(max(seconds, 0), 3600)
    minutes, secs = divmod(rem, 60)
    parts = []
    if hours:
        parts.append(f"{hours}h")
    if minutes:
        parts.append(f"{minutes}m")
    parts.append(f"{secs}s")
    return " ".join(parts)


class TelegramNotificationService:
    def __init__(
        self,
        config: Telegram | None,
        *,
        environ: Mapping[str, str] | None = None,
        sender: MessageSender | None = None,
    ) -> None:
        environ = os.environ if environ is None else environ
        self._config = config
        self._token = resolve_telegram_token(config.token, environ) if config else None
        chat_id = config.chat_id.strip() if config and config.chat_id else ""
        self._chat_id: str | None = chat_id or None
        step = (
            config.notify_progress_step_percent
            if config
            else DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT
        )
        self._progress_step = max(step, 1)
        self._sender = sender
        self._stats = SyncStats()

    @property
    def stats(self) -> SyncStats:
        return self._stats

    def is_enabled(self) -> bool:
        return (
            self._config is not None
            and self._config.enabled
            and self._chat_id is not None
            and self._token is not None
        )

    def is_authorized(self, user_id: int) -> bool:
        return self._config is not None and user_id in self._config.allowed_users

    def _send(self, message: str) -> None:
        sender = self._sender
        if sender is None:
            sender = self._default_sender()
        try:
            sender(message)
        except Exception as exc:  # noqa: BLE001 - a failed notification must not fail the sync
            log.warning("Telegram notification failed: %s", exc)

    def _default_sender(self) -> MessageSender:
        token = self._token
        chat_id = self._chat_id

        def send(message: str) -> None:
            httpx.post(
                f"https://api.telegram.org/bot{token}/sendMessage",
                json={"chat_id": chat_id, "text": message, "parse_mode": "HTML"},
                timeout=30.0,
            )

        return send

    def notify_sync_start(self, repository_count: int, workers: int) -> None:
        self._stats = replace(
            self._stats,
            is_syncing=True,
            total_repositories=repository_count,
            successful_repositories=0,
            failed_repositories=0,
            last_progress_percentage=0,
            last_sync_status=f"Syncing {repository_count} repositories with {workers} workers",
        )
        if not self.is_enabled() or self._config is None or not self._config.notify_start:
            return
        self._send(
            "<b>GitOut Sync Started</b>\n\n"
            f"Repositories: {repository_count}\n"
            f"Workers: {workers}\n"
            f"Started: {_now()}"
        )

    def notify_progress(self, completed: int, total: int, current_repo: str | None = None) -> None:
        if not self.is_enabled() or self._config is None or not self._config.notify_progress:
            return
        if total <= 0:
            return
        percentage = max(0, min(100, int(completed / total * 100)))
        advanced = percentage - self._stats.last_progress_percentage
        if percentage < 100 and advanced < self._progress_step:
            return
        self._stats = replace(self._stats, last_progress_percentage=percentage)
        repo_line = f"\nCurrent: {current_repo}" if current_repo else ""
        self._send(f"<b>Sync Progress</b>\n\n{completed}/{total} ({percentage}%){repo_line}")

    def notify_sync_completion(self, successful: int, failed: int, duration_seconds: int) -> None:
        self._stats = replace(
            self._stats,
            is_syncing=False,
            successful_repositories=successful,
            failed_repositories=failed,
            last_sync_time=_now(),
            last_sync_status="Completed",
        )
        if not self.is_enabled() or self._config is None or not self._config.notify_completion:
            return
        self._send(
            "<b>GitOut Sync Completed</b>\n\n"
            f"Succeeded: {successful}\n"
            f"Failed: {failed}\n"
            f"Duration: {format_duration(duration_seconds)}"
        )


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")  # noqa: DTZ005 - local display time
