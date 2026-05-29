"""Telegram notifications (port of TelegramNotificationService.kt, notification core).

Sends sync lifecycle notifications via the Telegram Bot API. Token resolution is
config > TELEGRAM_BOT_TOKEN_FILE > TELEGRAM_BOT_TOKEN. The message sender is injectable
(default posts to the Bot API with httpx); when the service is disabled every notify
call is a no-op. The interactive long-polling command bot is intentionally not ported.
"""

from __future__ import annotations

import html
import logging
import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path

import httpx

from gitout import __version__
from gitout.config import DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT, Telegram
from gitout.search.index_service import SearchIndexService
from gitout.state_tracker import RepositoryStateTracker

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
        search_index_service: SearchIndexService | None = None,
        search_destination: Path | None = None,
        started_at: datetime | None = None,
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
        self._search = search_index_service
        self._search_destination = search_destination
        self._started_at = started_at or datetime.now()  # noqa: DTZ005 - uptime display only
        self._failed: list[FailedRepoSummary] = []

    def record_failures(self, failures: list[FailedRepoSummary]) -> None:
        """Store the latest failed-repo summaries for the /fails command."""
        self._failed = list(failures)

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

    # --- interactive command handlers ---

    async def handle_command(
        self, command: str, args: list[str], user_id: int | None
    ) -> str | None:
        """Dispatch a bot command to its handler. Returns the HTML reply, or None.

        None means "no reply" (unknown command, or a request from an unknown user).
        Unauthorized users receive an explicit denial message.
        """
        if self._config is None or user_id is None:
            return None
        if user_id not in self._config.allowed_users:
            return "<b>Unauthorized</b>\n\nYou are not authorized to use this bot."

        if command == "ping":
            return self._cmd_ping()
        if command == "start":
            return self._cmd_start()
        if command == "help":
            return self._cmd_help()
        if command == "status":
            return self._cmd_status()
        if command == "stats":
            return self._cmd_stats()
        if command == "fails":
            return self._cmd_fails()
        if command == "info":
            return self._cmd_info()
        if command == "find":
            return await self._cmd_find(args)
        if command == "reindex":
            return await self._cmd_reindex()
        return None

    def _cmd_ping(self) -> str:
        uptime = int((datetime.now() - self._started_at).total_seconds())  # noqa: DTZ005
        status = "Active" if self.is_enabled() else "Inactive"
        return f"<b>Pong!</b>\n\n<b>Status:</b> {status}\n<b>Uptime:</b> {format_duration(uptime)}"

    @staticmethod
    def _cmd_start() -> str:
        return (
            "Welcome to GitOut Bot!\n\nAvailable commands:\n"
            "/status - Get current sync status\n"
            "/stats - Get repository statistics\n"
            "/info - Get bot and repository information\n"
            "/find <query> - Search repositories by natural language\n"
            "/reindex - Re-index all repositories for search\n"
            "/help - Show this help message"
        )

    @staticmethod
    def _cmd_help() -> str:
        return (
            "<b>GitOut Bot Help</b>\n\n"
            "<b>Available Commands:</b>\n"
            "/status - Get current synchronization status\n"
            "/stats - Get repository statistics and last sync time\n"
            "/info - Get bot and repository information\n"
            "/find <query> - Search repositories by natural language\n"
            "/reindex - Re-index all repositories for search\n"
            "/help - Show this help message\n\n"
            "<i>This bot monitors GitOut repository synchronization and sends notifications.</i>"
        )

    def _cmd_status(self) -> str:
        title = "Sync in Progress" if self._stats.is_syncing else "Last Sync Status"
        return f"<b>{title}</b>\n\n{self._stats.last_sync_status}"

    def _cmd_stats(self) -> str:
        stats = self._stats
        if stats.last_sync_time is None:
            return "<b>Repository Statistics</b>\n\nNo synchronization has been performed yet."
        total = stats.total_repositories
        rate = int(stats.successful_repositories / total * 100) if total > 0 else 0
        lines = [
            "<b>Repository Statistics</b>\n",
            f"<b>Last Sync:</b> {stats.last_sync_time}",
            f"<b>Total Repositories:</b> {total}",
            f"<b>Successful:</b> {stats.successful_repositories}",
        ]
        if stats.failed_repositories > 0:
            lines.append(f"<b>Failed:</b> {stats.failed_repositories}")
        lines.append(f"<b>Success Rate:</b> {rate}%")
        return "\n".join(lines)

    def _cmd_fails(self) -> str:
        if not self._failed:
            return "<b>No recent repository failures.</b>"
        lines = ["<b>Recent Repository Failures</b>", ""]
        for summary in self._failed[:10]:
            attempts = f" ({summary.retry_attempts} attempts)" if summary.retry_attempts > 1 else ""
            lines.append(f"- <code>{summary.name}</code> [{summary.category}]{attempts}")
            lines.append(f"  {summary.error_message.strip()}")
            lines.append("")
        if len(self._failed) > 10:
            lines.append(f"...and {len(self._failed) - 10} more")
        return "\n".join(lines).rstrip()

    def _cmd_info(self) -> str:
        config = self._config
        assert config is not None  # handle_command guarantees this
        return (
            "<b>GitOut Bot Information</b>\n\n"
            f"<b>Version:</b> {__version__}\n"
            f"<b>Status:</b> {'Active' if self.is_enabled() else 'Inactive'}\n"
            "<b>Notifications:</b>\n"
            f"  - Start: {'Enabled' if config.notify_start else 'Disabled'}\n"
            f"  - Progress: {'Enabled' if config.notify_progress else 'Disabled'}\n"
            f"  - Completion: {'Enabled' if config.notify_completion else 'Disabled'}\n"
            f"  - Errors: {'Enabled' if config.notify_errors else 'Disabled'}\n"
            f"<b>Commands:</b> {'Enabled' if config.enable_commands else 'Disabled'}\n"
            f"<b>Authorized Users:</b> {len(config.allowed_users)}"
        )

    async def _cmd_find(self, args: list[str]) -> str:
        if self._search is None:
            return "Search is not enabled. Add [search] configuration to enable it."
        query = " ".join(args).strip()
        if not query:
            return "Usage: /find <query>\n\nExample: /find OAuth authentication library"
        results = await self._search.search(query)
        if not results:
            return f'No repositories found matching "{html.escape(query)}"'
        lines = [f'<b>Results for "{html.escape(query)}":</b>', ""]
        for index, result in enumerate(results[:10], start=1):
            name = result.payload.get("name") or result.id
            description = result.payload.get("description") or ""
            language = result.payload.get("language") or "unknown"
            topics = ", ".join(result.payload.get("topics") or []) or "none"
            desc = description or "(no description)"
            lines.append(f"{index}. <code>{html.escape(str(name))}</code> ({result.score:.2f})")
            lines.append(f"   {html.escape(desc)}")
            lines.append(f"   Language: {language} | Topics: {topics}")
            lines.append("")
        return "\n".join(lines).rstrip()

    async def _cmd_reindex(self) -> str:
        if self._search is None or self._search_destination is None:
            return "Search is not enabled. Add [search] configuration to enable it."
        state_file = self._search_destination / "github" / ".gitout-state.json"
        if not state_file.exists():
            return "No repository state found. Run a sync first."
        try:
            repos = RepositoryStateTracker(state_file).load_repositories()
            clone_dir = self._search_destination / "github" / "clone"
            await self._search.index_repositories(repos, clone_dir)
            return f"Re-index complete. Indexed {len(repos)} repositories."
        except Exception as exc:  # noqa: BLE001 - reported back to the user
            return f"Re-index failed: {html.escape(str(exc))}"


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")  # noqa: DTZ005 - local display time
