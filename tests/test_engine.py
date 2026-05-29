"""Behavioral spec for the sync engine (port of Engine.kt task collection + dry-run).

Covers the pure, network-free seams: GitHub token resolution, sync-task collection
(candidate reasons, ignore filtering, destinations, URLs), and the dry-run plan line.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from gitout import engine as engine_module
from gitout.circuit_breaker import StorageCircuitBreaker
from gitout.config import (
    Config,
    FailureTrackingConfig,
    GitConfig,
    GitHubClone,
    GitHubConfig,
    LargeRepoConfig,
    Telegram,
)
from gitout.engine import (
    Engine,
    SyncTask,
    collect_sync_tasks,
    dry_run_line,
    resolve_git_executable,
    resolve_github_token,
)
from gitout.errors import ErrorCategory
from gitout.failure_tracker import FailureTracker
from gitout.github import RepositoryMetadata, UserRepositories
from gitout.retry import RetryPolicy
from gitout.telegram import TelegramNotificationService


class FakeRunner:
    def __init__(self, code: int = 0, output: str = "") -> None:
        self.code = code
        self.output = output
        self.calls: list[tuple[list[str], Path, float]] = []

    async def __call__(self, argv: list[str], cwd: Path, timeout: float) -> tuple[int, str]:
        self.calls.append((argv, cwd, timeout))
        return self.code, self.output


async def _noop_sleep(ms: int) -> None:
    return None

# --- token resolution: config > GITHUB_TOKEN_FILE > GITHUB_TOKEN ---


def test_token_from_config_wins() -> None:
    assert resolve_github_token("cfg-token", {"GITHUB_TOKEN": "env-token"}) == "cfg-token"


def test_token_from_file(tmp_path: Path) -> None:
    token_file = tmp_path / "token"
    token_file.write_text("  file-token\n")
    env = {"GITHUB_TOKEN_FILE": str(token_file), "GITHUB_TOKEN": "env-token"}
    assert resolve_github_token(None, env) == "file-token"


def test_token_from_env() -> None:
    assert resolve_github_token(None, {"GITHUB_TOKEN": "  env-token \n"}) == "env-token"


def test_token_missing_raises() -> None:
    with pytest.raises(ValueError, match="token"):
        resolve_github_token(None, {})


def test_blank_config_token_falls_through_to_env() -> None:
    assert resolve_github_token("   ", {"GITHUB_TOKEN": "env-token"}) == "env-token"


# --- sync task collection ---


def _user_repos() -> UserRepositories:
    def meta(name: str, repo_type: str, branch: str | None = None) -> RepositoryMetadata:
        return RepositoryMetadata(
            name=name,
            is_archived=False,
            is_private=False,
            is_fork=False,
            visibility="PUBLIC",
            description=None,
            updated_at="2024-01-01T00:00:00Z",
            repo_type=repo_type,
            default_branch=branch,
        )

    return UserRepositories(
        owned={"me/owned-1"},
        starred={"other/star-1"},
        watching={"other/watch-1"},
        gists={"abc123"},
        metadata={
            "me/owned-1": meta("me/owned-1", "owned", branch="main"),
            "other/star-1": meta("other/star-1", "starred"),
            "other/watch-1": meta("other/watch-1", "watching"),
        },
    )


def _config(**clone: object) -> Config:
    return Config(version=1, github=GitHubConfig(user="me", clone=GitHubClone(**clone)))


def _by_name(tasks: list[SyncTask]) -> dict[str, SyncTask]:
    return {t.name: t for t in tasks}


def test_owned_always_included_others_gated_off(tmp_path: Path) -> None:
    cfg = _config(starred=False, watched=False, gists=False)
    tasks = _by_name(collect_sync_tasks(cfg, tmp_path, _user_repos()))
    assert set(tasks) == {"me/owned-1"}
    t = tasks["me/owned-1"]
    assert t.url == "https://github.com/me/owned-1.git"
    assert t.destination == tmp_path / "github" / "clone" / "me/owned-1"
    assert t.default_branch == "main"


def test_starred_watched_explicit_and_gists_included(tmp_path: Path) -> None:
    cfg = _config(
        starred=True,
        watched=True,
        gists=True,
        repos=["explicit/repo"],
        single_branch_only=True,
    )
    tasks = _by_name(collect_sync_tasks(cfg, tmp_path, _user_repos()))
    assert set(tasks) == {
        "me/owned-1",
        "other/star-1",
        "other/watch-1",
        "explicit/repo",
        "gist:abc123",
    }
    assert tasks["other/star-1"].reasons == frozenset({"starred"})
    assert tasks["explicit/repo"].reasons == frozenset({"explicit"})
    # single_branch_only propagates to repo tasks but not gists.
    assert tasks["me/owned-1"].single_branch_only is True
    gist = tasks["gist:abc123"]
    assert gist.url == "https://gist.github.com/abc123.git"
    assert gist.destination == tmp_path / "github" / "gists" / "abc123"
    assert gist.single_branch_only is False


def test_ignore_removes_candidate(tmp_path: Path) -> None:
    cfg = _config(starred=True, ignore=["other/star-1"])
    tasks = _by_name(collect_sync_tasks(cfg, tmp_path, _user_repos()))
    assert "other/star-1" not in tasks


def test_excluded_repos_dropped(tmp_path: Path) -> None:
    cfg = _config(starred=True)
    tasks = _by_name(
        collect_sync_tasks(cfg, tmp_path, _user_repos(), excluded={"other/star-1"})
    )
    assert "other/star-1" not in tasks
    assert "me/owned-1" in tasks


def test_custom_git_repos_included_without_github(tmp_path: Path) -> None:
    cfg = Config(version=1, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))
    tasks = _by_name(collect_sync_tasks(cfg, tmp_path, None))
    assert set(tasks) == {"mirror"}
    t = tasks["mirror"]
    assert t.url == "https://example.com/x.git"
    assert t.destination == tmp_path / "git" / "mirror"


# --- dry-run plan line ---


def test_dry_run_line_clone_new_repo(tmp_path: Path) -> None:
    dest = tmp_path / "github" / "clone" / "me/owned-1"
    task = SyncTask(name="me/owned-1", url="https://github.com/me/owned-1.git", destination=dest)
    line = dry_run_line(task, Config(version=1))
    # repo dir does not exist -> clone, cwd is the parent, leaf name is the positional arg.
    git = resolve_git_executable()
    assert line.startswith(f"DRY RUN {dest.parent} {git} -c safe.directory=*")
    assert line.endswith("clone --mirror -- https://github.com/me/owned-1.git owned-1")


def test_dry_run_line_update_existing_repo(tmp_path: Path) -> None:
    dest = tmp_path / "git" / "mirror"
    dest.mkdir(parents=True)
    task = SyncTask(name="mirror", url="https://example.com/x.git", destination=dest)
    line = dry_run_line(task, Config(version=1))
    # repo dir exists -> update in place.
    assert line.startswith(f"DRY RUN {dest} {resolve_git_executable()} -c safe.directory=*")
    assert line.endswith("remote update --prune")


# --- async execution ---


async def test_perform_sync_runs_git_and_creates_parent(tmp_path: Path) -> None:
    cfg = Config(version=0, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))
    runner = FakeRunner()
    engine = Engine(config=cfg, destination=tmp_path, git_runner=runner)

    outcomes = await engine.perform_sync(dry_run=False)

    assert [o.ok for o in outcomes] == [True]
    assert len(runner.calls) == 1
    argv, cwd, _ = runner.calls[0]
    assert "clone" in argv and "--mirror" in argv
    assert cwd == tmp_path / "git"
    assert cwd.exists()


async def test_perform_sync_retries_then_reports_failure(tmp_path: Path) -> None:
    cfg = Config(version=0, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))
    runner = FakeRunner(code=128, output="fatal: the remote end hung up unexpectedly")
    engine = Engine(
        config=cfg,
        destination=tmp_path,
        git_runner=runner,
        retry_policy=RetryPolicy(max_attempts=2, base_delay_ms=0, sleep=_noop_sleep),
    )

    outcomes = await engine.perform_sync(dry_run=False)

    assert outcomes[0].ok is False
    # The retry wraps the git output in a SyncFailureException summary.
    assert "after 2 attempts" in (outcomes[0].error or "")
    assert "NETWORK_ERROR" in (outcomes[0].error or "")
    assert len(runner.calls) == 2  # NETWORK_ERROR is retryable -> one retry


async def test_perform_sync_rejects_nonzero_version(tmp_path: Path) -> None:
    engine = Engine(config=Config(version=1), destination=tmp_path, git_runner=FakeRunner())
    with pytest.raises(ValueError, match="version 0"):
        await engine.perform_sync(dry_run=True)


async def test_perform_sync_requires_existing_destination(tmp_path: Path) -> None:
    engine = Engine(
        config=Config(version=0), destination=tmp_path / "missing", git_runner=FakeRunner()
    )
    with pytest.raises(ValueError, match="Destination"):
        await engine.perform_sync(dry_run=False)


# --- resilience integration ---


class SpyMaintenance:
    def __init__(self) -> None:
        self.synced: list[Path] = []

    def run_post_sync_maintenance(self, path: Path) -> None:
        self.synced.append(path)

    def should_run_full_repack(self) -> bool:
        return False

    def run_full_repack(self, path: Path) -> None:  # pragma: no cover - not triggered here
        pass


class SpyLfs:
    def __init__(self) -> None:
        self.synced: list[Path] = []

    def sync_lfs_if_needed(self, path: Path) -> bool:
        self.synced.append(path)
        return True


def _git_only(tmp_path: Path) -> Config:
    return Config(version=0, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))


async def test_open_circuit_breaker_skips_all_tasks(tmp_path: Path) -> None:
    breaker = StorageCircuitBreaker(threshold=1)
    breaker.record_failure(ErrorCategory.STORAGE_ERROR)  # already open
    runner = FakeRunner()
    engine = Engine(
        config=Config(
            version=0,
            git=GitConfig(repos={"a": "https://x/a.git", "b": "https://x/b.git"}),
        ),
        destination=tmp_path,
        git_runner=runner,
        circuit_breaker=breaker,
    )
    outcomes = await engine.perform_sync(dry_run=False)
    assert all(o.skipped and not o.ok for o in outcomes)
    assert runner.calls == []  # nothing synced once the breaker is open


async def test_failure_recorded_in_tracker(tmp_path: Path) -> None:
    tracker = FailureTracker(
        tmp_path / "f.json", FailureTrackingConfig(enabled=True), now_ms=lambda: 0
    )
    breaker = StorageCircuitBreaker(threshold=3)
    runner = FakeRunner(code=128, output="fatal: the remote end hung up unexpectedly")
    engine = Engine(
        config=_git_only(tmp_path),
        destination=tmp_path,
        git_runner=runner,
        failure_tracker=tracker,
        circuit_breaker=breaker,
        retry_policy=RetryPolicy(max_attempts=1, base_delay_ms=0, sleep=_noop_sleep),
    )
    outcomes = await engine.perform_sync(dry_run=False)
    assert outcomes[0].ok is False
    record = tracker.get_failure_record("mirror")
    assert record is not None
    assert record.consecutive_failures == 1
    assert record.last_error_category == "NETWORK_ERROR"
    assert "remote end hung up" in (record.last_error_message or "")
    assert breaker.is_open() is False  # network error never trips the storage breaker


async def test_maintenance_and_lfs_run_after_success(tmp_path: Path) -> None:
    maint = SpyMaintenance()
    lfs = SpyLfs()
    engine = Engine(
        config=_git_only(tmp_path),
        destination=tmp_path,
        git_runner=FakeRunner(),
        maintenance=maint,  # type: ignore[arg-type]
        lfs=lfs,  # type: ignore[arg-type]
    )
    await engine.perform_sync(dry_run=False)
    dest = tmp_path / "git" / "mirror"
    assert maint.synced == [dest]
    assert lfs.synced == [dest]


async def test_preflight_failure_aborts_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def failing_preflight(root: Path, timeout_ms: int) -> str:
        return "no space left on device"

    monkeypatch.setattr(engine_module, "preflight_storage_check", failing_preflight)
    engine = Engine(config=_git_only(tmp_path), destination=tmp_path, git_runner=FakeRunner())
    with pytest.raises(RuntimeError, match="pre-flight"):
        await engine.perform_sync(dry_run=False)


# --- large-repo heuristics + lifecycle ---


def _big_repo_meta(name: str, size_kb: int, branch: str = "main") -> RepositoryMetadata:
    return RepositoryMetadata(
        name=name,
        is_archived=False,
        is_private=False,
        is_fork=False,
        visibility="PUBLIC",
        description=None,
        updated_at="2024-01-01T00:00:00Z",
        repo_type="owned",
        disk_usage_kb=size_kb,
        default_branch=branch,
    )


def _github_config() -> Config:
    return Config(
        version=0,
        github=GitHubConfig(user="me", token="t", clone=GitHubClone(gists=False)),
        large_repos=LargeRepoConfig(
            size_threshold_kb=1000,
            shallow_clone_threshold_kb=5000,
            shallow_clone_after_failures=1,
            timeout_multiplier=3.0,
        ),
    )


def test_collection_flags_large_repo(tmp_path: Path) -> None:
    repos = UserRepositories(
        owned={"u/big", "u/small"},
        starred=set(),
        watching=set(),
        gists=set(),
        metadata={
            "u/big": _big_repo_meta("u/big", 6000),
            "u/small": _big_repo_meta("u/small", 10),
        },
    )
    cfg = Config(version=0, github=GitHubConfig(user="me", clone=GitHubClone(gists=False)),
                 large_repos=LargeRepoConfig(size_threshold_kb=1000))
    by_name = _by_name(collect_sync_tasks(cfg, tmp_path, repos))
    assert by_name["u/big"].is_large_repo is True
    assert by_name["u/big"].size_kb == 6000
    assert by_name["u/small"].is_large_repo is False


class SpySearch:
    def __init__(self) -> None:
        self.calls: list[tuple[dict, Path]] = []

    async def index_repositories(self, metadata: dict, backup_dir: Path) -> None:
        self.calls.append((metadata, backup_dir))


class SpyStarted:
    def __init__(self) -> None:
        self.completed = False

    async def complete(self) -> None:
        self.completed = True


class SpyHealthCheck:
    def __init__(self) -> None:
        self.started = SpyStarted()

    async def start(self) -> SpyStarted:
        return self.started


async def test_healthcheck_and_autoindex_run_on_github_sync(tmp_path: Path) -> None:
    async def loader(user: str, token: str) -> UserRepositories:
        return UserRepositories(
            owned={"u/repo"},
            starred=set(),
            watching=set(),
            gists=set(),
            metadata={"u/repo": _big_repo_meta("u/repo", 10)},
        )

    search = SpySearch()
    health = SpyHealthCheck()
    engine = Engine(
        config=_github_config(),
        destination=tmp_path,
        repo_loader=loader,
        git_runner=FakeRunner(),
        search_index_service=search,  # type: ignore[arg-type]
        health_check=health,  # type: ignore[arg-type]
    )
    await engine.perform_sync(dry_run=False)

    assert health.started.completed is True
    assert len(search.calls) == 1
    metadata, backup_dir = search.calls[0]
    assert "u/repo" in metadata
    assert backup_dir == tmp_path / "github" / "clone"


async def test_large_repo_uses_shallow_and_http1_from_failure_history(tmp_path: Path) -> None:
    async def loader(user: str, token: str) -> UserRepositories:
        return UserRepositories(
            owned={"u/big"},
            starred=set(),
            watching=set(),
            gists=set(),
            metadata={"u/big": _big_repo_meta("u/big", 6000)},
        )

    tracker = FailureTracker(
        tmp_path / "f.json", FailureTrackingConfig(enabled=True), now_ms=lambda: 0
    )
    # Seed history: a prior HTTP2 failure makes the strategy pick shallow + HTTP/1.1.
    tracker.record_failure("u/big", "stream cancel", ErrorCategory.HTTP2_ERROR)

    runner = FakeRunner()
    engine = Engine(
        config=_github_config(),
        destination=tmp_path,
        repo_loader=loader,
        git_runner=runner,
        failure_tracker=tracker,
    )
    await engine.perform_sync(dry_run=False)

    assert len(runner.calls) == 1
    argv = runner.calls[0][0]
    assert "--depth=1" in argv  # shallow clone
    assert "http.version=HTTP/1.1" in argv  # forced HTTP/1.1
    assert "--progress" in argv  # large repos show progress


async def test_telegram_start_and_completion_notifications(tmp_path: Path) -> None:
    sent: list[str] = []
    telegram = TelegramNotificationService(
        Telegram(chat_id="1", token="t", enabled=True), environ={}, sender=sent.append
    )
    engine = Engine(
        config=_git_only(tmp_path),
        destination=tmp_path,
        git_runner=FakeRunner(),
        telegram=telegram,
    )
    await engine.perform_sync(dry_run=False)
    assert any("Sync Started" in m for m in sent)
    assert any("Sync Completed" in m for m in sent)
