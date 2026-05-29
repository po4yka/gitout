"""Cross-session repository failure tracking (port of FailureTracker.kt).

Persists per-repository failure history to a JSON state file (camelCase keys, so the
file is interchangeable with the Kotlin implementation), drives auto-skip with a
cooldown, and recommends a clone strategy from failure history + repo size.

The wall clock is injectable (``now_ms``) so tests are deterministic.
"""

from __future__ import annotations

import contextlib
import json
import time
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from gitout.config import FailureTrackingConfig, LargeRepoConfig
from gitout.errors import ErrorCategory

_THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000


def _now_ms() -> int:
    return int(time.time() * 1000)


@dataclass(frozen=True)
class RepositoryFailureRecord:
    name: str
    consecutive_failures: int
    total_failures: int
    last_failure_timestamp: int | None
    last_success_timestamp: int | None
    last_error_message: str | None
    last_error_category: str | None
    error_history: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "consecutiveFailures": self.consecutive_failures,
            "totalFailures": self.total_failures,
            "lastFailureTimestamp": self.last_failure_timestamp,
            "lastSuccessTimestamp": self.last_success_timestamp,
            "lastErrorMessage": self.last_error_message,
            "lastErrorCategory": self.last_error_category,
            "errorHistory": list(self.error_history),
        }

    @staticmethod
    def from_dict(data: dict[str, Any]) -> RepositoryFailureRecord:
        return RepositoryFailureRecord(
            name=data["name"],
            consecutive_failures=data.get("consecutiveFailures", 0),
            total_failures=data.get("totalFailures", 0),
            last_failure_timestamp=data.get("lastFailureTimestamp"),
            last_success_timestamp=data.get("lastSuccessTimestamp"),
            last_error_message=data.get("lastErrorMessage"),
            last_error_category=data.get("lastErrorCategory"),
            error_history=list(data.get("errorHistory", [])),
        )


@dataclass(frozen=True)
class CloneStrategy:
    use_http1: bool
    use_shallow_clone: bool
    is_large_repo: bool
    timeout_multiplier: float
    consecutive_failures: int


class FailureTracker:
    def __init__(
        self,
        state_file: Path,
        config: FailureTrackingConfig,
        *,
        now_ms: Callable[[], int] = _now_ms,
    ) -> None:
        self._state_file = state_file
        self._config = config
        self._now_ms = now_ms
        self._repositories: dict[str, RepositoryFailureRecord] = self._load_state()

    def _load_state(self) -> dict[str, RepositoryFailureRecord]:
        if not self._config.enabled or not self._state_file.exists():
            return {}
        try:
            data = json.loads(self._state_file.read_text())
            return {
                name: RepositoryFailureRecord.from_dict(record)
                for name, record in data.get("repositories", {}).items()
            }
        except (OSError, ValueError, KeyError):
            return {}

    def save_state(self) -> None:
        if not self._config.enabled:
            return
        payload = {
            "version": 1,
            "repositories": {n: r.to_dict() for n, r in self._repositories.items()},
        }
        with contextlib.suppress(OSError):
            self._state_file.write_text(json.dumps(payload, indent=2))

    def record_success(self, repo_name: str) -> None:
        if not self._config.enabled:
            return
        existing = self._repositories.get(repo_name)
        if existing is not None:
            self._repositories[repo_name] = replace(
                existing,
                consecutive_failures=0,
                last_success_timestamp=self._now_ms(),
            )

    def record_failure(
        self, repo_name: str, error_message: str, error_category: ErrorCategory
    ) -> None:
        if not self._config.enabled:
            return
        now = self._now_ms()
        existing = self._repositories.get(repo_name)
        if existing is not None:
            self._repositories[repo_name] = replace(
                existing,
                consecutive_failures=existing.consecutive_failures + 1,
                total_failures=existing.total_failures + 1,
                last_failure_timestamp=now,
                last_error_message=error_message,
                last_error_category=error_category.name,
                error_history=(existing.error_history + [error_category.name])[-10:],
            )
        else:
            self._repositories[repo_name] = RepositoryFailureRecord(
                name=repo_name,
                consecutive_failures=1,
                total_failures=1,
                last_failure_timestamp=now,
                last_success_timestamp=None,
                last_error_message=error_message,
                last_error_category=error_category.name,
                error_history=[error_category.name],
            )

    def should_skip(self, repo_name: str) -> bool:
        if not self._config.enabled or not self._config.auto_skip_failing:
            return False
        record = self._repositories.get(repo_name)
        if record is None:
            return False
        if record.consecutive_failures < self._config.max_consecutive_failures:
            return False
        if record.last_failure_timestamp is None:
            return False
        cooldown_ms = self._config.failure_cooldown_hours * 60 * 60 * 1000
        return (self._now_ms() - record.last_failure_timestamp) < cooldown_ms

    def get_failure_record(self, repo_name: str) -> RepositoryFailureRecord | None:
        return self._repositories.get(repo_name)

    def get_failing_repositories(self) -> list[RepositoryFailureRecord]:
        return [r for r in self._repositories.values() if r.consecutive_failures > 0]

    def get_recommended_strategy(
        self, repo_name: str, repo_size_kb: int | None, large_repo_config: LargeRepoConfig
    ) -> CloneStrategy:
        record = self._repositories.get(repo_name)
        is_large = repo_size_kb is not None and repo_size_kb >= large_repo_config.size_threshold_kb
        is_very_large = (
            repo_size_kb is not None
            and repo_size_kb >= large_repo_config.shallow_clone_threshold_kb
        )
        use_shallow = (
            record is not None
            and record.consecutive_failures >= large_repo_config.shallow_clone_after_failures
            and is_very_large
        )
        use_http1 = record is not None and ErrorCategory.HTTP2_ERROR.name in record.error_history
        return CloneStrategy(
            use_http1=use_http1,
            use_shallow_clone=use_shallow,
            is_large_repo=is_large,
            timeout_multiplier=large_repo_config.timeout_multiplier if is_large else 1.0,
            consecutive_failures=record.consecutive_failures if record else 0,
        )

    def cleanup(self, active_repos: set[str], max_age_ms: int = _THIRTY_DAYS_MS) -> None:
        if not self._config.enabled:
            return
        now = self._now_ms()

        def keep(name: str, record: RepositoryFailureRecord) -> bool:
            if name in active_repos:
                return True
            last_activity = max(
                record.last_failure_timestamp or 0, record.last_success_timestamp or 0
            )
            return now - last_activity < max_age_ms

        self._repositories = {n: r for n, r in self._repositories.items() if keep(n, r)}
