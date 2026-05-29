"""Shared test fixtures and helpers for the characterization suite."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

FIXTURES = Path(__file__).parent / "fixtures"


def load_json(*parts: str) -> Any:
    """Load a JSON fixture relative to ``tests/fixtures``."""
    return json.loads((FIXTURES.joinpath(*parts)).read_text())


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES
