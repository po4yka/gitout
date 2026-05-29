"""Benchmarking utilities (port of Benchmark.kt).

``Benchmark`` records named operation durations (via a ``measure`` context manager or
``record``) and renders a summary. ``PerformanceStats`` computes parallel speedup and
efficiency. Durations are in milliseconds; the clock is injectable for tests.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Measurement:
    operation: str
    duration_ms: float
    timestamp_ms: float


class Benchmark:
    def __init__(self, name: str, *, clock: Callable[[], float] = time.monotonic) -> None:
        self._name = name
        self._clock = clock
        self._start = clock()
        self._measurements: list[Measurement] = []

    @contextmanager
    def measure(self, operation: str) -> Iterator[None]:
        start = self._clock()
        try:
            yield
        finally:
            self.record(operation, (self._clock() - start) * 1000)

    def record(self, operation: str, duration_ms: float) -> None:
        self._measurements.append(
            Measurement(operation, duration_ms, (self._clock() - self._start) * 1000)
        )

    def total_elapsed_ms(self) -> float:
        return (self._clock() - self._start) * 1000

    @property
    def measurements(self) -> list[Measurement]:
        return list(self._measurements)

    def summary(self) -> str:
        if not self._measurements:
            return f"BENCHMARK [{self._name}]: No measurements recorded"
        total = self.total_elapsed_ms()
        durations = [m.duration_ms for m in self._measurements]
        lines = [
            f"BENCHMARK [{self._name}] Summary:",
            f"  Total time: {total:.0f}ms",
            f"  Operations: {len(self._measurements)}",
        ]
        for index, m in enumerate(self._measurements, start=1):
            pct = int(m.duration_ms / total * 100) if total else 0
            lines.append(f"  {index}. {m.operation}: {m.duration_ms:.0f}ms ({pct}%)")
        avg = sum(durations) / len(durations)
        lines += [
            "  Statistics:",
            f"    Average: {avg:.0f}ms",
            f"    Min: {min(durations):.0f}ms",
            f"    Max: {max(durations):.0f}ms",
        ]
        return "\n".join(lines)


@dataclass(frozen=True)
class PerformanceStats:
    total_repositories: int
    successful_syncs: int
    failed_syncs: int
    total_duration_ms: float
    average_sync_time_ms: float
    fastest_sync_ms: float
    slowest_sync_ms: float
    parallel_workers: int = field(default=1)

    def calculate_speedup(self) -> float:
        """Sequential time / parallel time. Returns 1.0 when no duration recorded."""
        if self.total_duration_ms == 0:
            return 1.0
        sequential = self.average_sync_time_ms * self.total_repositories
        return sequential / self.total_duration_ms

    def calculate_efficiency(self) -> float:
        """Speedup per worker."""
        if self.parallel_workers == 0:
            return 0.0
        return self.calculate_speedup() / self.parallel_workers
