"""Configuration model and TOML parsing.

Port of ``Config.kt``. The dataclasses and their defaults are the spec (they
mirror the Kotlin ``@Serializable`` classes, with field names matching the TOML
/ ``@SerialName`` keys). ``parse`` loads and coerces a TOML file into the
dataclass tree, ``validate`` enforces cross-field invariants, and
``to_normalized_dict`` serialises the config for logging and diagnostics.
"""

from __future__ import annotations

import re
import tomllib
from collections.abc import Callable
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any

DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT = 10


@dataclass
class GitHubArchive:
    owned: bool = False


@dataclass
class GitHubClone:
    starred: bool = False
    watched: bool = False
    gists: bool = True
    repos: list[str] = field(default_factory=list)
    ignore: list[str] = field(default_factory=list)
    single_branch_only: bool = False


@dataclass
class GitHubConfig:
    user: str
    token: str | None = None
    archive: GitHubArchive = field(default_factory=GitHubArchive)
    clone: GitHubClone = field(default_factory=GitHubClone)


@dataclass
class GitConfig:
    repos: dict[str, str] = field(default_factory=dict)


@dataclass
class Ssl:
    cert_file: str | None = None
    verify_certificates: bool = True


@dataclass
class Http:
    version: str = "HTTP/1.1"
    post_buffer_size: int = 524_288_000  # 500MB
    adaptive_fallback: bool = True
    low_speed_limit: int = 1000  # bytes/second
    low_speed_time: int = 60  # seconds


@dataclass
class PriorityPattern:
    pattern: str
    priority: int
    timeout: int | None = None


@dataclass
class Parallelism:
    workers: int = 4
    progress_interval_ms: int = 1000
    repository_timeout_seconds: int | None = None
    priorities: list[PriorityPattern] = field(default_factory=list)


@dataclass
class Metrics:
    enabled: bool = True
    format: str = "console"
    export_path: str | None = None


@dataclass(kw_only=True)
class Telegram:
    chat_id: str
    token: str | None = None
    enabled: bool = True
    notify_start: bool = True
    notify_progress: bool = True
    notify_progress_step_percent: int = DEFAULT_TELEGRAM_PROGRESS_STEP_PERCENT
    notify_completion: bool = True
    notify_errors: bool = True
    notify_new_repos: bool = True
    notify_updates: bool = False
    allowed_users: list[int] = field(default_factory=list)
    enable_commands: bool = False
    notify_only_repos: list[str] = field(default_factory=list)
    notify_ignore_repos: list[str] = field(default_factory=list)


@dataclass
class LargeRepoConfig:
    size_threshold_kb: int = 500_000  # 500MB
    timeout_multiplier: float = 3.0
    max_parallel: int = 2
    shallow_clone_threshold_kb: int = 2_000_000  # 2GB
    shallow_clone_after_failures: int = 3
    progress_reporting: bool = True


@dataclass
class FailureTrackingConfig:
    enabled: bool = True
    state_file: str = ".gitout-failures.json"
    max_consecutive_failures: int = 5
    failure_cooldown_hours: int = 24
    auto_skip_failing: bool = False


@dataclass
class HealthCheckConfig:
    preflight_enabled: bool = True
    preflight_timeout_seconds: int = 5
    circuit_breaker_enabled: bool = True
    circuit_breaker_threshold: int = 10


@dataclass
class Maintenance:
    enabled: bool = False
    strategy: str = "gc-auto"
    full_repack_interval: str = "never"
    repack_window: int = 50
    repack_depth: int = 50
    write_commit_graph: bool = True


@dataclass
class Lfs:
    fetch_lfs: bool = False


@dataclass
class Search:
    enabled: bool = False
    qdrant_url: str = "http://localhost:6333"
    collection_name: str = "repositories"
    top_k: int = 10
    auto_index: bool = True


@dataclass
class Config:
    version: int
    github: GitHubConfig | None = None
    git: GitConfig = field(default_factory=GitConfig)
    ssl: Ssl = field(default_factory=Ssl)
    http: Http = field(default_factory=Http)
    parallelism: Parallelism = field(default_factory=Parallelism)
    metrics: Metrics = field(default_factory=Metrics)
    telegram: Telegram | None = None
    large_repos: LargeRepoConfig = field(default_factory=LargeRepoConfig)
    failure_tracking: FailureTrackingConfig = field(default_factory=FailureTrackingConfig)
    health_check: HealthCheckConfig = field(default_factory=HealthCheckConfig)
    maintenance: Maintenance = field(default_factory=Maintenance)
    lfs: Lfs = field(default_factory=Lfs)
    exit_on_failure: bool = True
    search: Search = field(default_factory=Search)


def _build_message_map() -> dict[str, Callable[[dict[str, Any]], str]]:
    """Return a mapping from error code to a callable(detail) -> str."""

    def _fmt(template: str) -> Callable[[dict[str, Any]], str]:
        """Return a callable that formats ``template`` with detail keys."""

        def _inner(d: dict[str, Any]) -> str:
            return template.format_map(d)

        return _inner

    return {
        "InvalidVersion": _fmt("version must be >= 0, got {version}"),
        "EmptyGitHubUser": lambda d: "github.user must not be empty",
        "NoGitHubCloneOptionsEnabled": lambda d: (
            "github.clone has no clone options enabled "
            "(set starred, watched, gists, or repos)"
        ),
        "EmptyGitRepoName": _fmt("git.repos has an entry with a blank name (url: {url})"),
        "InvalidRepositoryName": _fmt(
            'git.repos "{name}" is not a valid repository name '
            "(must match [a-zA-Z0-9._/-]+ without .., leading/trailing or double slashes)"
        ),
        "EmptyGitRepoUrl": _fmt('git.repos "{name}" has a blank URL'),
        "InvalidGitUrl": _fmt('git.repos "{name}" has an invalid URL: {url}'),
        "CertFileNotFound": _fmt("ssl.cert_file not found: {path}"),
        "InvalidWorkerCount": _fmt(
            "parallelism.workers must be at least 1, got {count}"
        ),
        "TooManyWorkers": _fmt("parallelism.workers must be at most 32, got {count}"),
        "InvalidProgressInterval": _fmt(
            "parallelism.progress_interval_ms must be at least 100 ms, got {interval}"
        ),
        "InvalidRepositoryTimeout": _fmt(
            "parallelism.repository_timeout_seconds must be at least 1, got {timeout}"
        ),
        "EmptyPriorityPattern": lambda d: (
            "parallelism.priorities has an entry with a blank pattern"
        ),
        "InvalidPriorityTimeout": _fmt(
            'parallelism.priorities pattern "{pattern}" timeout must be at least 1, got {timeout}'
        ),
        "InvalidMetricsFormat": _fmt(
            'metrics.format must be one of "console", "json", "csv", got "{format}"'
        ),
        "EmptyMetricsExportPath": lambda d: "metrics.export_path must not be blank",
        "EmptyTelegramChatId": lambda d: "telegram.chat_id must not be empty",
        "InvalidTelegramProgressStep": _fmt(
            "telegram.notify_progress_step_percent must be between 1 and 100, got {step}"
        ),
        "InvalidHttpVersion": _fmt(
            'http.version must be "HTTP/1.1" or "HTTP/2", got "{version}"'
        ),
        "InvalidPostBufferSize": _fmt(
            "http.post_buffer_size must be at least 1024 bytes, got {size}"
        ),
        "InvalidLowSpeedLimit": _fmt(
            "http.low_speed_limit must be >= 0 bytes/second, got {limit}"
        ),
        "InvalidLowSpeedTime": _fmt(
            "http.low_speed_time must be at least 1 second, got {time}"
        ),
        "InvalidLargeRepoThreshold": _fmt(
            "large_repos.size_threshold_kb must be at least 1024 KB, got {threshold}"
        ),
        "InvalidTimeoutMultiplier": _fmt(
            "large_repos.timeout_multiplier must be at least 1.0, got {multiplier}"
        ),
        "InvalidMaxParallelLargeRepos": _fmt(
            "large_repos.max_parallel must be at least 1, got {count}"
        ),
        "InvalidShallowCloneFailures": _fmt(
            "large_repos.shallow_clone_after_failures must be at least 1, got {count}"
        ),
        "InvalidMaxConsecutiveFailures": _fmt(
            "failure_tracking.max_consecutive_failures must be at least 1, got {count}"
        ),
        "InvalidFailureCooldown": _fmt(
            "failure_tracking.failure_cooldown_hours must be >= 0, got {hours}"
        ),
        "InvalidMaintenanceStrategy": _fmt(
            'maintenance.strategy must be one of "gc-auto", "geometric", "none", got "{strategy}"'
        ),
        "InvalidFullRepackInterval": _fmt(
            'maintenance.full_repack_interval must be one of "never", "weekly", "monthly", '
            'got "{interval}"'
        ),
        "InvalidRepackWindow": _fmt(
            "maintenance.repack_window must be at least 1, got {window}"
        ),
        "InvalidRepackDepth": _fmt(
            "maintenance.repack_depth must be at least 1, got {depth}"
        ),
        "InvalidTopK": _fmt(
            "search.top_k must be between 1 and 100, got {count}"
        ),
        "EmptyQdrantUrl": lambda d: "search.qdrant_url must not be blank",
        "EmptyCollectionName": lambda d: "search.collection_name must not be blank",
    }


_MESSAGE_MAP: dict[str, Callable[[dict[str, Any]], str]] = _build_message_map()


@dataclass(frozen=True)
class ValidationError:
    """A configuration validation failure.

    ``code`` matches the Kotlin ``ValidationError`` subtype name (e.g. ``InvalidTopK``);
    ``detail`` carries the offending values for that subtype.
    """

    code: str
    detail: dict[str, Any] = field(default_factory=dict)

    @property
    def message(self) -> str:
        """Return a human-readable description of this validation error."""
        formatter = _MESSAGE_MAP.get(self.code)
        if formatter is not None:
            return formatter(self.detail)
        # Generic fallback for any unrecognised code (should not occur in practice).
        return f"{self.code} {self.detail}" if self.detail else self.code


def _known_kwargs(cls: type, data: dict[str, Any]) -> dict[str, Any]:
    """Keep only keys that are fields of ``cls`` (lenient: drop unknown keys)."""
    names = {f.name for f in fields(cls)}
    return {k: v for k, v in data.items() if k in names}


def parse(toml_text: str) -> Config:
    """Parse TOML into a :class:`Config`, ignoring unknown keys (lenient, like ktoml)."""
    raw = tomllib.loads(toml_text)

    github: GitHubConfig | None = None
    gh = raw.get("github")
    if gh is not None:
        github = GitHubConfig(
            user=gh.get("user", ""),
            token=gh.get("token"),
            archive=GitHubArchive(**_known_kwargs(GitHubArchive, gh.get("archive", {}))),
            clone=GitHubClone(**_known_kwargs(GitHubClone, gh.get("clone", {}))),
        )

    parallelism_raw = raw.get("parallelism", {})
    priorities = [
        PriorityPattern(**_known_kwargs(PriorityPattern, p))
        for p in parallelism_raw.get("priorities", [])
    ]
    parallelism_kwargs = _known_kwargs(Parallelism, parallelism_raw)
    parallelism_kwargs.pop("priorities", None)
    parallelism = Parallelism(priorities=priorities, **parallelism_kwargs)

    telegram: Telegram | None = None
    tg = raw.get("telegram")
    if tg is not None:
        telegram_kwargs = _known_kwargs(Telegram, tg)
        if "allowed_users" in telegram_kwargs:
            # Telegram user ids may be given as ints or quoted strings in TOML.
            telegram_kwargs["allowed_users"] = [
                int(user) for user in telegram_kwargs["allowed_users"]
            ]
        telegram = Telegram(**telegram_kwargs)

    return Config(
        version=raw.get("version", 0),
        github=github,
        git=GitConfig(repos=dict(raw.get("git", {}).get("repos", {}))),
        ssl=Ssl(**_known_kwargs(Ssl, raw.get("ssl", {}))),
        http=Http(**_known_kwargs(Http, raw.get("http", {}))),
        parallelism=parallelism,
        metrics=Metrics(**_known_kwargs(Metrics, raw.get("metrics", {}))),
        telegram=telegram,
        large_repos=LargeRepoConfig(**_known_kwargs(LargeRepoConfig, raw.get("large_repos", {}))),
        failure_tracking=FailureTrackingConfig(
            **_known_kwargs(FailureTrackingConfig, raw.get("failure_tracking", {}))
        ),
        health_check=HealthCheckConfig(
            **_known_kwargs(HealthCheckConfig, raw.get("health_check", {}))
        ),
        maintenance=Maintenance(**_known_kwargs(Maintenance, raw.get("maintenance", {}))),
        lfs=Lfs(**_known_kwargs(Lfs, raw.get("lfs", {}))),
        exit_on_failure=raw.get("exit_on_failure", True),
        search=Search(**_known_kwargs(Search, raw.get("search", {}))),
    )


def to_normalized_dict(config: Config) -> dict[str, Any]:
    """Serialize a :class:`Config` to a TOML/SerialName-keyed dict for parity comparison."""
    return asdict(config)


_GIT_URL_RE = re.compile(r"^(https?://|git@|git://|ssh://|file://).*")
_SCP_URL_RE = re.compile(r"^[\w.-]+@[\w.-]+:.*")
_REPO_NAME_RE = re.compile(r"^[a-zA-Z0-9._/-]+$")


def _blank(value: str) -> bool:
    return not value.strip()


def _is_valid_git_url(url: str) -> bool:
    return bool(_GIT_URL_RE.match(url) or _SCP_URL_RE.match(url))


def _is_valid_repository_name(name: str) -> bool:
    if ".." in name:
        return False
    if name.startswith("/") or name.endswith("/"):
        return False
    if "//" in name:
        return False
    return bool(_REPO_NAME_RE.match(name))


def validate(config: Config) -> list[ValidationError]:
    """Return validation errors for ``config`` (empty list when valid)."""
    errors: list[ValidationError] = []

    def err(code: str, **detail: Any) -> None:
        errors.append(ValidationError(code=code, detail=detail))

    if config.version < 0:
        err("InvalidVersion", version=config.version)

    gh = config.github
    if gh is not None:
        if _blank(gh.user):
            err("EmptyGitHubUser")
        c = gh.clone
        if not c.starred and not c.watched and not c.gists and not c.repos:
            err("NoGitHubCloneOptionsEnabled")

    for name, url in config.git.repos.items():
        if _blank(name):
            err("EmptyGitRepoName", url=url)
        elif not _is_valid_repository_name(name):
            err("InvalidRepositoryName", name=name)
        if _blank(url):
            err("EmptyGitRepoUrl", name=name)
        if not _is_valid_git_url(url):
            err("InvalidGitUrl", name=name, url=url)

    cert = config.ssl.cert_file
    if cert is not None and not _blank(cert) and not Path(cert).exists():
        err("CertFileNotFound", path=cert)

    p = config.parallelism
    if p.workers < 1:
        err("InvalidWorkerCount", count=p.workers)
    if p.workers > 32:
        err("TooManyWorkers", count=p.workers)
    if p.progress_interval_ms < 100:
        err("InvalidProgressInterval", interval=p.progress_interval_ms)
    if p.repository_timeout_seconds is not None and p.repository_timeout_seconds < 1:
        err("InvalidRepositoryTimeout", timeout=p.repository_timeout_seconds)
    for pattern in p.priorities:
        if _blank(pattern.pattern):
            err("EmptyPriorityPattern")
        if pattern.timeout is not None and pattern.timeout < 1:
            err("InvalidPriorityTimeout", pattern=pattern.pattern, timeout=pattern.timeout)

    if config.metrics.format not in ("console", "json", "csv"):
        err("InvalidMetricsFormat", format=config.metrics.format)
    if config.metrics.export_path is not None and _blank(config.metrics.export_path):
        err("EmptyMetricsExportPath")

    tg = config.telegram
    if tg is not None:
        if _blank(tg.chat_id):
            err("EmptyTelegramChatId")
        if not 1 <= tg.notify_progress_step_percent <= 100:
            err("InvalidTelegramProgressStep", step=tg.notify_progress_step_percent)

    h = config.http
    if h.version not in ("HTTP/1.1", "HTTP/2"):
        err("InvalidHttpVersion", version=h.version)
    if h.post_buffer_size < 1024:
        err("InvalidPostBufferSize", size=h.post_buffer_size)
    if h.low_speed_limit < 0:
        err("InvalidLowSpeedLimit", limit=h.low_speed_limit)
    if h.low_speed_time < 1:
        err("InvalidLowSpeedTime", time=h.low_speed_time)

    lr = config.large_repos
    if lr.size_threshold_kb < 1024:
        err("InvalidLargeRepoThreshold", threshold=lr.size_threshold_kb)
    if lr.timeout_multiplier < 1.0:
        err("InvalidTimeoutMultiplier", multiplier=lr.timeout_multiplier)
    if lr.max_parallel < 1:
        err("InvalidMaxParallelLargeRepos", count=lr.max_parallel)
    if lr.shallow_clone_after_failures < 1:
        err("InvalidShallowCloneFailures", count=lr.shallow_clone_after_failures)

    ft = config.failure_tracking
    if ft.max_consecutive_failures < 1:
        err("InvalidMaxConsecutiveFailures", count=ft.max_consecutive_failures)
    if ft.failure_cooldown_hours < 0:
        err("InvalidFailureCooldown", hours=ft.failure_cooldown_hours)

    m = config.maintenance
    if m.strategy not in ("gc-auto", "geometric", "none"):
        err("InvalidMaintenanceStrategy", strategy=m.strategy)
    if m.full_repack_interval not in ("never", "weekly", "monthly"):
        err("InvalidFullRepackInterval", interval=m.full_repack_interval)
    if m.repack_window < 1:
        err("InvalidRepackWindow", window=m.repack_window)
    if m.repack_depth < 1:
        err("InvalidRepackDepth", depth=m.repack_depth)

    s = config.search
    if s.top_k < 1 or s.top_k > 100:
        err("InvalidTopK", count=s.top_k)
    if s.enabled:
        if _blank(s.qdrant_url):
            err("EmptyQdrantUrl")
        if _blank(s.collection_name):
            err("EmptyCollectionName")

    return errors
