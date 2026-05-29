"""Tests for the dependency-free cron scheduler."""

from __future__ import annotations

from datetime import datetime

import pytest

from gitout.cron import CronExpression, run_cron


def test_parse_requires_five_fields() -> None:
    with pytest.raises(ValueError, match="5 fields"):
        CronExpression.parse("* * * *")


def test_every_minute_matches_anything() -> None:
    cron = CronExpression.parse("* * * * *")
    assert cron.matches(datetime(2024, 6, 15, 13, 37))


def test_specific_minute_hour() -> None:
    cron = CronExpression.parse("30 2 * * *")  # 02:30 daily
    assert cron.matches(datetime(2024, 6, 15, 2, 30))
    assert not cron.matches(datetime(2024, 6, 15, 2, 31))
    assert not cron.matches(datetime(2024, 6, 15, 3, 30))


def test_step_and_range_and_list() -> None:
    cron = CronExpression.parse("*/15 9-17 * * 1,3")  # every 15 min, 9-17h, Mon & Wed
    assert cron.matches(datetime(2024, 6, 17, 9, 0))  # Monday 09:00
    assert cron.matches(datetime(2024, 6, 19, 17, 45))  # Wednesday 17:45
    assert not cron.matches(datetime(2024, 6, 18, 9, 0))  # Tuesday
    assert not cron.matches(datetime(2024, 6, 17, 9, 7))  # not a 15-min boundary


def test_sunday_accepts_zero_and_seven() -> None:
    by_zero = CronExpression.parse("0 0 * * 0")
    by_seven = CronExpression.parse("0 0 * * 7")
    sunday = datetime(2024, 6, 16, 0, 0)  # a Sunday
    assert by_zero.matches(sunday)
    assert by_seven.matches(sunday)


def test_dom_or_dow_when_both_restricted() -> None:
    # "1st of month OR any Monday" — standard cron OR semantics.
    cron = CronExpression.parse("0 0 1 * 1")
    assert cron.matches(datetime(2024, 6, 1, 0, 0))  # the 1st (a Saturday)
    assert cron.matches(datetime(2024, 6, 17, 0, 0))  # a Monday
    assert not cron.matches(datetime(2024, 6, 18, 0, 0))  # Tuesday, not the 1st


def test_next_after() -> None:
    cron = CronExpression.parse("30 2 * * *")
    nxt = cron.next_after(datetime(2024, 6, 15, 2, 0))
    assert nxt == datetime(2024, 6, 15, 2, 30)
    # After today's run, the next is tomorrow.
    after = cron.next_after(datetime(2024, 6, 15, 2, 30))
    assert after == datetime(2024, 6, 16, 2, 30)


async def test_run_cron_drives_action_for_n_iterations() -> None:
    slept: list[float] = []
    fired: list[int] = []
    clock = {"t": datetime(2024, 6, 15, 2, 0)}

    async def sleep(seconds: float) -> None:
        slept.append(seconds)
        clock["t"] = clock["t"].replace(minute=30)  # advance to the scheduled minute

    async def action() -> None:
        fired.append(1)

    await run_cron("30 2 * * *", action, sleep=sleep, now=lambda: clock["t"], iterations=1)
    assert fired == [1]
    assert slept == [1800.0]  # 30 minutes
