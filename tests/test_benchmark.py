"""Tests for Benchmark + PerformanceStats (port of Benchmark.kt)."""

from __future__ import annotations

import pytest

from gitout.benchmark import Benchmark, PerformanceStats


class FakeClock:
    """Monotonic clock returning preset seconds on each call."""

    def __init__(self, values: list[float]) -> None:
        self._values = values
        self._i = 0

    def __call__(self) -> float:
        value = self._values[min(self._i, len(self._values) - 1)]
        self._i += 1
        return value


def test_record_and_measurements() -> None:
    bench = Benchmark("sync", clock=FakeClock([0.0, 1.0, 2.0, 3.0]))
    bench.record("clone", 1500.0)
    bench.record("update", 500.0)
    assert [m.operation for m in bench.measurements] == ["clone", "update"]
    assert bench.measurements[0].duration_ms == 1500.0


def test_measure_context_manager_times_block() -> None:
    # clock() called: start(0), measure-start(1), measure-end(4), record-timestamp(4)
    bench = Benchmark("x", clock=FakeClock([0.0, 1.0, 4.0, 4.0]))
    with bench.measure("op"):
        pass
    assert bench.measurements[0].duration_ms == 3000.0  # (4 - 1) * 1000


def test_summary_empty() -> None:
    bench = Benchmark("empty", clock=FakeClock([0.0]))
    assert "No measurements recorded" in bench.summary()


def test_summary_lists_operations() -> None:
    bench = Benchmark("sync", clock=FakeClock([0.0, 1.0, 1.0, 1.0, 10.0]))
    bench.record("a", 1000.0)
    bench.record("b", 2000.0)
    summary = bench.summary()
    assert "Operations: 2" in summary
    assert "1. a:" in summary
    assert "2. b:" in summary


def test_performance_stats_speedup_and_efficiency() -> None:
    stats = PerformanceStats(
        total_repositories=8,
        successful_syncs=8,
        failed_syncs=0,
        total_duration_ms=2000.0,
        average_sync_time_ms=1000.0,
        fastest_sync_ms=500.0,
        slowest_sync_ms=1500.0,
        parallel_workers=4,
    )
    # sequential = 1000 * 8 = 8000; speedup = 8000/2000 = 4.0; efficiency = 4/4 = 1.0
    assert stats.calculate_speedup() == pytest.approx(4.0)
    assert stats.calculate_efficiency() == pytest.approx(1.0)


def test_performance_stats_speedup_defaults_to_one_with_no_duration() -> None:
    stats = PerformanceStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, parallel_workers=4)
    assert stats.calculate_speedup() == 1.0
