"""Characterization tests for FailureTracker (behavior ported from FailureTracker.kt).

No Kotlin unit test exists for this class; these encode the observable behavior.
A controllable clock makes cooldown/cleanup deterministic.
"""

from __future__ import annotations

from pathlib import Path

from gitout.config import FailureTrackingConfig, LargeRepoConfig
from gitout.errors import ErrorCategory
from gitout.failure_tracker import FailureTracker

HOUR_MS = 60 * 60 * 1000


class Clock:
    def __init__(self, start: int = 0) -> None:
        self.t = start

    def __call__(self) -> int:
        return self.t


def _tracker(tmp_path: Path, clock: Clock, **overrides: object) -> FailureTracker:
    config = FailureTrackingConfig(enabled=True, **overrides)
    return FailureTracker(tmp_path / "failures.json", config, now_ms=clock)


def test_disabled_is_noop(tmp_path: Path) -> None:
    tracker = FailureTracker(
        tmp_path / "f.json", FailureTrackingConfig(enabled=False), now_ms=Clock()
    )
    tracker.record_failure("a/b", "boom", ErrorCategory.NETWORK_ERROR)
    assert tracker.get_failure_record("a/b") is None
    assert tracker.should_skip("a/b") is False


def test_record_failure_creates_and_increments(tmp_path: Path) -> None:
    tracker = _tracker(tmp_path, Clock(1000))
    tracker.record_failure("a/b", "net", ErrorCategory.NETWORK_ERROR)
    tracker.record_failure("a/b", "net2", ErrorCategory.HTTP2_ERROR)
    record = tracker.get_failure_record("a/b")
    assert record is not None
    assert record.consecutive_failures == 2
    assert record.total_failures == 2
    assert record.last_error_category == "HTTP2_ERROR"
    assert record.error_history == ["NETWORK_ERROR", "HTTP2_ERROR"]
    assert record.last_failure_timestamp == 1000


def test_error_history_capped_at_ten(tmp_path: Path) -> None:
    tracker = _tracker(tmp_path, Clock())
    for _ in range(15):
        tracker.record_failure("a/b", "x", ErrorCategory.NETWORK_ERROR)
    record = tracker.get_failure_record("a/b")
    assert record is not None
    assert len(record.error_history) == 10
    assert record.total_failures == 15


def test_record_success_resets_consecutive(tmp_path: Path) -> None:
    clock = Clock(5000)
    tracker = _tracker(tmp_path, clock)
    tracker.record_failure("a/b", "x", ErrorCategory.NETWORK_ERROR)
    tracker.record_failure("a/b", "x", ErrorCategory.NETWORK_ERROR)
    tracker.record_success("a/b")
    record = tracker.get_failure_record("a/b")
    assert record is not None
    assert record.consecutive_failures == 0
    assert record.total_failures == 2  # total is not reset
    assert record.last_success_timestamp == 5000


def test_should_skip_requires_auto_skip_and_threshold_and_cooldown(tmp_path: Path) -> None:
    clock = Clock(0)
    tracker = _tracker(
        tmp_path,
        clock,
        auto_skip_failing=True,
        max_consecutive_failures=3,
        failure_cooldown_hours=24,
    )
    for _ in range(3):
        tracker.record_failure("a/b", "x", ErrorCategory.NETWORK_ERROR)
    # Within cooldown -> skip.
    clock.t = 10 * HOUR_MS
    assert tracker.should_skip("a/b") is True
    # Past cooldown -> no longer skipped.
    clock.t = 25 * HOUR_MS
    assert tracker.should_skip("a/b") is False


def test_should_skip_false_when_auto_skip_disabled(tmp_path: Path) -> None:
    tracker = _tracker(tmp_path, Clock(0), auto_skip_failing=False, max_consecutive_failures=1)
    tracker.record_failure("a/b", "x", ErrorCategory.NETWORK_ERROR)
    assert tracker.should_skip("a/b") is False


def test_persistence_round_trip(tmp_path: Path) -> None:
    state = tmp_path / "failures.json"
    config = FailureTrackingConfig(enabled=True)
    first = FailureTracker(state, config, now_ms=Clock(1000))
    first.record_failure("a/b", "boom", ErrorCategory.HTTP2_ERROR)
    first.save_state()

    second = FailureTracker(state, config, now_ms=Clock(2000))
    record = second.get_failure_record("a/b")
    assert record is not None
    assert record.consecutive_failures == 1
    assert record.last_error_category == "HTTP2_ERROR"


def test_recommended_strategy(tmp_path: Path) -> None:
    tracker = _tracker(tmp_path, Clock())
    large = LargeRepoConfig(
        size_threshold_kb=1000,
        shallow_clone_threshold_kb=5000,
        shallow_clone_after_failures=2,
        timeout_multiplier=3.0,
    )
    # http2 in history -> use_http1; many failures + very large -> shallow.
    tracker.record_failure("a/b", "x", ErrorCategory.HTTP2_ERROR)
    tracker.record_failure("a/b", "x", ErrorCategory.HTTP2_ERROR)
    strategy = tracker.get_recommended_strategy("a/b", repo_size_kb=6000, large_repo_config=large)
    assert strategy.is_large_repo is True
    assert strategy.use_http1 is True
    assert strategy.use_shallow_clone is True
    assert strategy.timeout_multiplier == 3.0

    small = tracker.get_recommended_strategy("a/b", repo_size_kb=100, large_repo_config=large)
    assert small.is_large_repo is False
    assert small.use_shallow_clone is False
    assert small.timeout_multiplier == 1.0


def test_cleanup_removes_stale_inactive(tmp_path: Path) -> None:
    clock = Clock(0)
    tracker = _tracker(tmp_path, clock)
    tracker.record_failure("stale/repo", "x", ErrorCategory.NETWORK_ERROR)
    tracker.record_failure("active/repo", "x", ErrorCategory.NETWORK_ERROR)
    clock.t = 40 * 24 * HOUR_MS  # 40 days later
    tracker.cleanup(active_repos={"active/repo"})
    assert tracker.get_failure_record("stale/repo") is None
    assert tracker.get_failure_record("active/repo") is not None
