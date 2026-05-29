"""Error categorization for git sync operations.

Port of ``ErrorCategory.kt``. The enum members and their semantics are the spec;
``classify`` and the per-category property helpers are implemented in Phase 1.
"""

from __future__ import annotations

from enum import Enum


class ErrorCategory(Enum):
    """Categories of errors that can occur during git sync operations."""

    HTTP2_ERROR = "HTTP2_ERROR"
    NETWORK_ERROR = "NETWORK_ERROR"
    TIMEOUT = "TIMEOUT"
    AUTH_ERROR = "AUTH_ERROR"
    REPOSITORY_ERROR = "REPOSITORY_ERROR"
    STORAGE_ERROR = "STORAGE_ERROR"
    SSL_ERROR = "SSL_ERROR"
    RATE_LIMIT = "RATE_LIMIT"
    UNKNOWN = "UNKNOWN"


def classify(error_message: str | None) -> ErrorCategory:
    """Classify an error from its message. Returns UNKNOWN for ``None``."""
    raise NotImplementedError("Phase 1: port ErrorCategory.classify")


def should_use_http1_fallback(category: ErrorCategory) -> bool:
    """Whether this category should trigger an HTTP/1.1 fallback on retry."""
    raise NotImplementedError("Phase 1: port ErrorCategory.shouldUseHttp1Fallback")


def is_retryable(category: ErrorCategory) -> bool:
    """Whether this category should be retried at all."""
    raise NotImplementedError("Phase 1: port ErrorCategory.isRetryable")


def delay_multiplier(category: ErrorCategory) -> float:
    """Delay multiplier applied to the backoff for this category."""
    raise NotImplementedError("Phase 1: port ErrorCategory.delayMultiplier")


def display_name(category: ErrorCategory) -> str:
    """Human-readable name for notifications."""
    raise NotImplementedError("Phase 1: port ErrorCategory.displayName")


def suggestion(category: ErrorCategory) -> str:
    """Actionable recovery suggestion for this category."""
    raise NotImplementedError("Phase 1: port ErrorCategory.suggestion")
