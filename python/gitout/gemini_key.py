"""Gemini API key resolution (port of GeminiApiKeyResolver.kt).

Priority: GEMINI_API_KEY (value) > GEMINI_API_KEY_FILE (path to a file). Returns None
when neither yields a non-blank key.
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path


def resolve_gemini_api_key(environ: Mapping[str, str]) -> str | None:
    env_key = environ.get("GEMINI_API_KEY")
    if env_key and env_key.strip():
        return env_key

    key_file = environ.get("GEMINI_API_KEY_FILE")
    if key_file and key_file.strip():
        path = Path(key_file)
        if path.exists():
            key = path.read_text().strip()
            if key:
                return key
    return None
