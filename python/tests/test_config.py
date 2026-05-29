"""Characterization tests for config parsing & validation (port of Config.kt / ConfigTest.kt).

Red until ``gitout.config`` is implemented in Phase 1.
"""

from __future__ import annotations

import pytest

from gitout import config as cfg
from gitout.config import (
    Config,
    GitConfig,
    GitHubClone,
    GitHubConfig,
    Http,
    Maintenance,
    Metrics,
    Parallelism,
    Search,
    Telegram,
)
from tests.helpers import load_json

pytestmark = pytest.mark.xfail(
    reason="gitout.config not implemented (Phase 1)", strict=False, raises=NotImplementedError
)

_PARSE_CASES = load_json("config", "parse_cases.json")["cases"]


@pytest.mark.characterization
@pytest.mark.parametrize("case", _PARSE_CASES, ids=[c["name"] for c in _PARSE_CASES])
def test_parse_normalizes_to_expected_dict(case: dict) -> None:
    toml_text = "\n".join(case["toml_lines"])
    parsed = cfg.parse(toml_text)
    assert cfg.to_normalized_dict(parsed) == case["expected"]


# --- validate(): (id, config, codes that MUST appear, codes that MUST NOT appear) ---
_VALIDATE_CASES: list[tuple[str, Config, set[str], set[str]]] = [
    ("valid_minimal", Config(version=1), set(), {"InvalidVersion"}),
    (
        "telegram_without_token_uses_env",
        Config(version=1, telegram=Telegram(chat_id="123456", token=None, enabled=True)),
        set(),
        {"EmptyTelegramChatId"},
    ),
    ("version_negative", Config(version=-1), {"InvalidVersion"}, set()),
    (
        "github_empty_user",
        Config(version=1, github=GitHubConfig(user="")),
        {"EmptyGitHubUser"},
        set(),
    ),
    (
        "github_no_clone_options",
        Config(
            version=1,
            github=GitHubConfig(user="x", clone=GitHubClone(gists=False)),
        ),
        {"NoGitHubCloneOptionsEnabled"},
        set(),
    ),
    (
        "git_repo_path_traversal_name",
        Config(version=1, git=GitConfig(repos={"../evil": "https://example.com/x.git"})),
        {"InvalidRepositoryName"},
        set(),
    ),
    (
        "git_repo_invalid_url",
        Config(version=1, git=GitConfig(repos={"ok": "not-a-url"})),
        {"InvalidGitUrl"},
        set(),
    ),
    (
        "workers_zero",
        Config(version=1, parallelism=Parallelism(workers=0)),
        {"InvalidWorkerCount"},
        set(),
    ),
    (
        "workers_too_many",
        Config(version=1, parallelism=Parallelism(workers=33)),
        {"TooManyWorkers"},
        set(),
    ),
    (
        "metrics_bad_format",
        Config(version=1, metrics=Metrics(format="xml")),
        {"InvalidMetricsFormat"},
        set(),
    ),
    (
        "telegram_empty_chat_id",
        Config(version=1, telegram=Telegram(chat_id="")),
        {"EmptyTelegramChatId"},
        set(),
    ),
    (
        "telegram_progress_step_out_of_range",
        Config(version=1, telegram=Telegram(chat_id="1", notify_progress_step_percent=0)),
        {"InvalidTelegramProgressStep"},
        set(),
    ),
    (
        "http_bad_version",
        Config(version=1, http=Http(version="HTTP/3")),
        {"InvalidHttpVersion"},
        set(),
    ),
    ("search_topk_zero", Config(version=1, search=Search(top_k=0)), {"InvalidTopK"}, set()),
    ("search_topk_too_high", Config(version=1, search=Search(top_k=101)), {"InvalidTopK"}, set()),
    ("search_topk_min_ok", Config(version=1, search=Search(top_k=1)), set(), {"InvalidTopK"}),
    ("search_topk_max_ok", Config(version=1, search=Search(top_k=100)), set(), {"InvalidTopK"}),
    (
        "search_enabled_blank_qdrant",
        Config(version=1, search=Search(enabled=True, qdrant_url="   ")),
        {"EmptyQdrantUrl"},
        set(),
    ),
    (
        "search_enabled_blank_collection",
        Config(version=1, search=Search(enabled=True, collection_name="")),
        {"EmptyCollectionName"},
        set(),
    ),
    (
        "search_disabled_blank_not_validated",
        Config(version=1, search=Search(enabled=False, qdrant_url="", collection_name="")),
        set(),
        {"EmptyQdrantUrl", "EmptyCollectionName"},
    ),
    (
        "maintenance_bad_strategy",
        Config(version=1, maintenance=Maintenance(strategy="turbo")),
        {"InvalidMaintenanceStrategy"},
        set(),
    ),
]


@pytest.mark.characterization
@pytest.mark.parametrize(
    "config, must_contain, must_not_contain",
    [(c, must, absent) for (_id, c, must, absent) in _VALIDATE_CASES],
    ids=[c[0] for c in _VALIDATE_CASES],
)
def test_validate(config: Config, must_contain: set[str], must_not_contain: set[str]) -> None:
    codes = {e.code for e in cfg.validate(config)}
    assert must_contain <= codes
    assert must_not_contain.isdisjoint(codes)
