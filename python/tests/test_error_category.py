"""Characterization tests for error classification (port of ErrorCategoryTest.kt).

Red until ``gitout.errors`` is implemented in Phase 1 — remove the ``pytestmark``
xfail line below once it is, and the cases must go green.
"""

from __future__ import annotations

import pytest

from gitout import errors
from gitout.errors import ErrorCategory
from tests.helpers import load_json

pytestmark = pytest.mark.xfail(
    reason="gitout.errors not implemented (Phase 1)", strict=False, raises=NotImplementedError
)

_CLASSIFY = load_json("error_category", "classify_cases.json")["cases"]
_PROPERTIES = load_json("error_category", "properties.json")["categories"]


@pytest.mark.characterization
@pytest.mark.parametrize(
    "case",
    _CLASSIFY,
    ids=[c.get("note") or repr(c["message"])[:40] for c in _CLASSIFY],
)
def test_classify(case: dict) -> None:
    assert errors.classify(case["message"]) is ErrorCategory[case["expected"]]


@pytest.mark.characterization
@pytest.mark.parametrize("name", list(_PROPERTIES.keys()))
def test_category_properties(name: str) -> None:
    category = ErrorCategory[name]
    expected = _PROPERTIES[name]
    assert errors.is_retryable(category) is expected["is_retryable"]
    assert errors.should_use_http1_fallback(category) is expected["should_use_http1_fallback"]
    assert errors.delay_multiplier(category) == expected["delay_multiplier"]
    assert errors.display_name(category) == expected["display_name"]
    assert errors.suggestion(category) == expected["suggestion"]
