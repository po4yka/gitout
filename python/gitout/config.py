"""Configuration model and TOML parsing.

Port of ``Config.kt``. The dataclasses and their defaults are the spec (they mirror
the Kotlin ``@Serializable`` classes, with field names matching the TOML / ``@SerialName``
keys). ``parse``, ``validate`` and ``to_normalized_dict`` are implemented in Phase 1.
"""

from __future__ import annotations

from dataclasses import dataclass, field
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


@dataclass(frozen=True)
class ValidationError:
    """A configuration validation failure.

    ``code`` matches the Kotlin ``ValidationError`` subtype name (e.g. ``InvalidTopK``);
    ``detail`` carries the offending values for that subtype.
    """

    code: str
    detail: dict[str, Any] = field(default_factory=dict)


def parse(toml_text: str) -> Config:
    """Parse TOML into a :class:`Config`, ignoring unknown keys (lenient, like ktoml)."""
    raise NotImplementedError("Phase 1: port Config.parse")


def validate(config: Config) -> list[ValidationError]:
    """Return validation errors for ``config`` (empty list when valid)."""
    raise NotImplementedError("Phase 1: port Config.validate")


def to_normalized_dict(config: Config) -> dict[str, Any]:
    """Serialize a :class:`Config` to a TOML/SerialName-keyed dict for parity comparison."""
    raise NotImplementedError("Phase 1: port Config serialization")
