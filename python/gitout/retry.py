"""Retry policy with adaptive, category-aware backoff.

Port of ``RetryPolicy.kt``. Enums, the context/result containers and the
``SyncFailureException`` shape are the spec; backoff math and ``execute`` are Phase 1.

The Kotlin code uses ``kotlinx.coroutines.delay``; here ``execute`` takes an injectable
``sleep`` coroutine so tests can assert the delay sequence without real waiting.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from enum import Enum
from typing import Generic, TypeVar

from gitout.errors import ErrorCategory

T = TypeVar("T")


class BackoffStrategy(Enum):
    """Strategy for the delay between retry attempts."""

    LINEAR = "LINEAR"  # baseDelayMs * attempt
    EXPONENTIAL = "EXPONENTIAL"  # baseDelayMs * 2^(attempt-1)
    CONSTANT = "CONSTANT"  # baseDelayMs


class SyncFailureException(Exception):
    """Raised when an operation fails after all retry attempts are exhausted."""

    def __init__(
        self,
        message: str,
        *,
        error_categories: list[ErrorCategory],
        attempt_count: int,
        cause: BaseException | None = None,
    ) -> None:
        super().__init__(message)
        self.error_categories = error_categories
        self.attempt_count = attempt_count
        self.__cause__ = cause


@dataclass(frozen=True)
class RetryContext:
    attempt: int
    max_attempts: int
    should_use_http1_fallback: bool
    last_error_category: ErrorCategory | None
    is_retry: bool


@dataclass(frozen=True)
class RetryResult(Generic[T]):
    value: T
    attempts: int
    used_http1_fallback: bool
    error_categories: list[ErrorCategory] = field(default_factory=list)


# A sleeper coroutine: awaits for the given number of milliseconds.
Sleeper = Callable[[int], Awaitable[None]]


class RetryPolicy:
    def __init__(
        self,
        *,
        max_attempts: int = 6,
        base_delay_ms: int = 5000,
        backoff_strategy: BackoffStrategy = BackoffStrategy.LINEAR,
        adaptive_retry: bool = True,
        sleep: Sleeper | None = None,
    ) -> None:
        # Phase 1: raise ValueError when max_attempts < 1 or base_delay_ms < 0.
        self.max_attempts = max_attempts
        self.base_delay_ms = base_delay_ms
        self.backoff_strategy = backoff_strategy
        self.adaptive_retry = adaptive_retry
        self._sleep = sleep

    def calculate_delay(self, attempt: int) -> int:
        """Delay in ms before ``attempt`` (1-indexed), per the backoff strategy."""
        raise NotImplementedError("Phase 1: port RetryPolicy.calculateDelay")

    async def execute_with_result(
        self,
        operation: Callable[[RetryContext], Awaitable[T]],
        *,
        operation_description: str | None = None,
    ) -> RetryResult[T]:
        """Run ``operation`` with retries; raise SyncFailureException when exhausted."""
        raise NotImplementedError("Phase 1: port RetryPolicy.executeWithResult")

    async def execute(
        self,
        operation: Callable[[RetryContext], Awaitable[T]],
        *,
        operation_description: str | None = None,
    ) -> T:
        result = await self.execute_with_result(
            operation, operation_description=operation_description
        )
        return result.value

    def __repr__(self) -> str:
        return (
            f"RetryPolicy(max_attempts={self.max_attempts}, base_delay_ms={self.base_delay_ms}, "
            f"backoff_strategy={self.backoff_strategy.value}, adaptive_retry={self.adaptive_retry})"
        )
