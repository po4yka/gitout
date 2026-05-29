"""Search subsystem error type (port of SearchException.kt)."""

from __future__ import annotations


class SearchException(Exception):
    """Raised for embedding/index/search failures (HTTP errors, malformed responses)."""

    def __init__(self, message: str, cause: BaseException | None = None) -> None:
        super().__init__(message)
        if cause is not None:
            self.__cause__ = cause
