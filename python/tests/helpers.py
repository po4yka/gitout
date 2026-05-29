"""Importable helpers for the characterization suite (usable at collection time)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

FIXTURES = Path(__file__).parent / "fixtures"


def load_json(*parts: str) -> Any:
    """Load a JSON fixture relative to ``tests/fixtures``."""
    return json.loads(FIXTURES.joinpath(*parts).read_text())
