"""Shared pytest fixtures for the characterization suite.

Collection-time helpers (e.g. ``load_json`` used inside ``parametrize``) live in
``tests.helpers`` so they can be imported directly; this module exposes them as
fixtures for in-test use.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tests.helpers import FIXTURES, load_json

__all__ = ["load_json"]


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES
