"""Minimal, dependency-free cron scheduling (replaces Kotlin's cardiologist).

Supports the standard 5-field expression ``minute hour day-of-month month day-of-week``
with ``*``, lists (``a,b``), ranges (``a-b``), and steps (``*/n``, ``a-b/n``). Day-of-week
is 0-6 with Sunday=0 (7 also accepted for Sunday). When both day-of-month and day-of-week
are restricted, a timestamp matches if EITHER does (standard cron semantics).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, timedelta

# Search bound for next_after: ~4 years of minutes (guards against impossible expressions).
_MAX_LOOKAHEAD_MINUTES = 4 * 366 * 24 * 60


def _parse_field(spec: str, lo: int, hi: int) -> set[int]:
    values: set[int] = set()
    for part in spec.split(","):
        step = 1
        rng = part
        if "/" in part:
            rng, step_str = part.split("/", 1)
            step = int(step_str)
        if rng == "*":
            start, end = lo, hi
        elif "-" in rng:
            a, b = rng.split("-", 1)
            start, end = int(a), int(b)
        else:
            start = end = int(rng)
        for value in range(start, end + 1, step):
            if lo <= value <= hi:
                values.add(value)
    if not values:
        raise ValueError(f"Cron field '{spec}' matched no values")
    return values


@dataclass(frozen=True)
class CronExpression:
    minutes: frozenset[int]
    hours: frozenset[int]
    days_of_month: frozenset[int]
    months: frozenset[int]
    days_of_week: frozenset[int]
    dom_restricted: bool
    dow_restricted: bool

    @staticmethod
    def parse(expression: str) -> CronExpression:
        fields = expression.split()
        if len(fields) != 5:
            raise ValueError(
                f"Cron expression must have 5 fields, got {len(fields)}: {expression!r}"
            )
        minute, hour, dom, month, dow = fields
        dow_values = _parse_field(dow, 0, 7)
        if 7 in dow_values:  # 7 and 0 both mean Sunday
            dow_values = (dow_values - {7}) | {0}
        return CronExpression(
            minutes=frozenset(_parse_field(minute, 0, 59)),
            hours=frozenset(_parse_field(hour, 0, 23)),
            days_of_month=frozenset(_parse_field(dom, 1, 31)),
            months=frozenset(_parse_field(month, 1, 12)),
            days_of_week=frozenset(dow_values),
            dom_restricted=dom != "*",
            dow_restricted=dow != "*",
        )

    def matches(self, when: datetime) -> bool:
        if when.minute not in self.minutes:
            return False
        if when.hour not in self.hours:
            return False
        if when.month not in self.months:
            return False
        dom_match = when.day in self.days_of_month
        dow_match = (when.isoweekday() % 7) in self.days_of_week  # Sunday: isoweekday 7 -> 0
        if self.dom_restricted and self.dow_restricted:
            return dom_match or dow_match
        if self.dom_restricted:
            return dom_match
        if self.dow_restricted:
            return dow_match
        return True

    def next_after(self, after: datetime) -> datetime:
        """The first minute strictly after ``after`` that matches this expression."""
        candidate = after.replace(second=0, microsecond=0) + timedelta(minutes=1)
        for _ in range(_MAX_LOOKAHEAD_MINUTES):
            if self.matches(candidate):
                return candidate
            candidate += timedelta(minutes=1)
        raise ValueError("Cron expression has no matching time within the search window")


async def run_cron(
    expression: str,
    action: Callable[[], Awaitable[None]],
    *,
    sleep: Callable[[float], Awaitable[None]],
    now: Callable[[], datetime],
    iterations: int | None = None,
) -> None:
    """Run ``action`` on each scheduled tick. Loops forever unless ``iterations`` is set.

    ``sleep`` and ``now`` are injected so tests can drive the schedule deterministically.
    """
    cron = CronExpression.parse(expression)
    count = 0
    while iterations is None or count < iterations:
        current = now()
        upcoming = cron.next_after(current)
        await sleep((upcoming - current).total_seconds())
        await action()
        count += 1
