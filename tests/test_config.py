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
    ValidationError,
)
from tests.helpers import load_json

_PARSE_CASES = load_json("config", "parse_cases.json")["cases"]


@pytest.mark.characterization
@pytest.mark.parametrize("case", _PARSE_CASES, ids=[c["name"] for c in _PARSE_CASES])
def test_parse_normalizes_to_expected_dict(case: dict) -> None:
    toml_text = "\n".join(case["toml_lines"])
    parsed = cfg.parse(toml_text)
    assert cfg.to_normalized_dict(parsed) == case["expected"]


def test_parse_coerces_allowed_users_int_or_string() -> None:
    toml_text = (
        'version = 0\n[telegram]\nchat_id = "1"\nallowed_users = ["42", 99]\n'
    )
    parsed = cfg.parse(toml_text)
    assert parsed.telegram is not None
    assert parsed.telegram.allowed_users == [42, 99]


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


# --- ValidationError.message: human-readable output ---

_MESSAGE_CASES: list[tuple[str, ValidationError, str]] = [
    (
        "InvalidTopK_over",
        ValidationError(code="InvalidTopK", detail={"count": 200}),
        "search.top_k must be between 1 and 100, got 200",
    ),
    (
        "TooManyWorkers",
        ValidationError(code="TooManyWorkers", detail={"count": 64}),
        "parallelism.workers must be at most 32, got 64",
    ),
    (
        "InvalidWorkerCount",
        ValidationError(code="InvalidWorkerCount", detail={"count": 0}),
        "parallelism.workers must be at least 1, got 0",
    ),
    (
        "InvalidGitUrl",
        ValidationError(code="InvalidGitUrl", detail={"name": "myrepo", "url": "not-a-url"}),
        'git.repos "myrepo" has an invalid URL: not-a-url',
    ),
    (
        "CertFileNotFound",
        ValidationError(code="CertFileNotFound", detail={"path": "/etc/ssl/missing.pem"}),
        "ssl.cert_file not found: /etc/ssl/missing.pem",
    ),
    (
        "EmptyGitHubUser",
        ValidationError(code="EmptyGitHubUser"),
        "github.user must not be empty",
    ),
    (
        "NoGitHubCloneOptionsEnabled",
        ValidationError(code="NoGitHubCloneOptionsEnabled"),
        "github.clone has no clone options enabled (set starred, watched, gists, or repos)",
    ),
    (
        "InvalidHttpVersion",
        ValidationError(code="InvalidHttpVersion", detail={"version": "HTTP/3"}),
        'http.version must be "HTTP/1.1" or "HTTP/2", got "HTTP/3"',
    ),
    (
        "InvalidMaintenanceStrategy",
        ValidationError(code="InvalidMaintenanceStrategy", detail={"strategy": "turbo"}),
        'maintenance.strategy must be one of "gc-auto", "geometric", "none", got "turbo"',
    ),
    (
        "EmptyQdrantUrl",
        ValidationError(code="EmptyQdrantUrl"),
        "search.qdrant_url must not be blank",
    ),
    (
        "unknown_code_fallback",
        ValidationError(code="SomeUnknownCode", detail={"x": 1}),
        "SomeUnknownCode {'x': 1}",
    ),
    (
        "unknown_code_no_detail_fallback",
        ValidationError(code="BareUnknownCode"),
        "BareUnknownCode",
    ),
]


@pytest.mark.parametrize(
    "error, expected_message",
    [(e, m) for (_id, e, m) in _MESSAGE_CASES],
    ids=[c[0] for c in _MESSAGE_CASES],
)
def test_validation_error_message(error: ValidationError, expected_message: str) -> None:
    assert error.message == expected_message


def test_all_known_codes_produce_non_fallback_messages() -> None:
    """Every code validate() can emit must produce a message distinct from the raw fallback."""
    from gitout.config import (
        FailureTrackingConfig,
        GitConfig,
        GitHubClone,
        GitHubConfig,
        LargeRepoConfig,
        Lfs,
        Maintenance,
        Parallelism,
        PriorityPattern,
        Search,
        Telegram,
    )

    bad = Config(
        version=-1,
        github=GitHubConfig(user="", clone=GitHubClone(gists=False)),
        git=GitConfig(repos={"../evil": "not-a-url", "ok": ""}),
        parallelism=Parallelism(
            workers=0,
            progress_interval_ms=50,
            repository_timeout_seconds=0,
            priorities=[
                PriorityPattern(pattern="", priority=1),
                PriorityPattern(pattern="p", priority=1, timeout=0),
            ],
        ),
        metrics=Metrics(format="xml", export_path="   "),
        telegram=Telegram(chat_id="", notify_progress_step_percent=0),
        http=Http(
            version="HTTP/3", post_buffer_size=0, low_speed_limit=-1, low_speed_time=0
        ),
        large_repos=LargeRepoConfig(
            size_threshold_kb=100,
            timeout_multiplier=0.5,
            max_parallel=0,
            shallow_clone_after_failures=0,
        ),
        failure_tracking=FailureTrackingConfig(
            max_consecutive_failures=0, failure_cooldown_hours=-1
        ),
        maintenance=Maintenance(
            strategy="turbo",
            full_repack_interval="daily",
            repack_window=0,
            repack_depth=0,
        ),
        search=Search(enabled=True, top_k=0, qdrant_url="   ", collection_name=""),
        lfs=Lfs(),
        exit_on_failure=True,
    )

    errors = cfg.validate(bad)
    assert errors, "Expected validation errors for the crafted bad config"
    for error in errors:
        fallback = f"{error.code} {error.detail}" if error.detail else error.code
        assert error.message != fallback, (
            f"Code {error.code!r} produced the raw fallback message; add it to _MESSAGE_MAP"
        )
