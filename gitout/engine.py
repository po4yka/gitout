"""Sync engine: token resolution, task collection, and parallel mirror/update execution.

Port of ``Engine.kt``. Handles GitHub token resolution, credentials setup,
sync-task collection, the ``DRY RUN`` plan line, and parallel execution with an
``asyncio.Semaphore`` worker pool and per-repo retry. Lifecycle wiring includes
repository state tracking and exclusions, failure tracking, circuit breaker and
storage pre-flight, large-repo/shallow-clone heuristics, LFS, maintenance,
health checks, Telegram notifications, and search indexing.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import tempfile
import time
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

from gitout.circuit_breaker import StorageCircuitBreaker
from gitout.config import Config
from gitout.errors import classify, display_name
from gitout.failure_tracker import FailureTracker
from gitout.git_commands import build_git_command
from gitout.git_exec import resolve_git_executable
from gitout.github import UserRepositories
from gitout.health_check import HealthCheck
from gitout.lfs import LfsSupport
from gitout.maintenance import RepositoryMaintenance
from gitout.retry import RetryContext, RetryPolicy, SyncFailureException
from gitout.search.index_service import SearchIndexService
from gitout.state_tracker import ExcludedRepo, RepositoryStateTracker
from gitout.telegram import FailedRepoSummary, TelegramNotificationService

__all__ = [
    "Engine",
    "SyncTask",
    "SyncOutcome",
    "collect_sync_tasks",
    "dry_run_line",
    "resolve_github_token",
    "resolve_git_executable",
    "preflight_storage_check",
    "default_git_runner",
]

# (argv, cwd, timeout_seconds) -> (exit_code, combined_output)
GitRunner = Callable[[list[str], Path, float], Awaitable[tuple[int, str]]]
# (user, token) -> discovered repositories
RepoLoader = Callable[[str, str], Awaitable[UserRepositories]]


@dataclass(frozen=True)
class SyncTask:
    name: str
    url: str
    destination: Path
    credentials_path: str | None = None
    reasons: frozenset[str] | None = None
    single_branch_only: bool = False
    default_branch: str | None = None
    size_kb: int | None = None
    is_large_repo: bool = False


@dataclass(frozen=True)
class SyncOutcome:
    task: SyncTask
    ok: bool
    error: str | None = None
    skipped: bool = False


def resolve_github_token(config_token: str | None, environ: Mapping[str, str]) -> str:
    """Resolve a GitHub token: config (trimmed) > GITHUB_TOKEN_FILE > GITHUB_TOKEN."""
    if config_token is not None and config_token.strip():
        return config_token.strip()

    token_file_path = environ.get("GITHUB_TOKEN_FILE")
    if token_file_path:
        token_file = Path(token_file_path)
        if token_file.exists():
            token = token_file.read_text().strip()
            if token:
                return token

    token_env = environ.get("GITHUB_TOKEN")
    if token_env and token_env.strip():
        return token_env.strip()

    raise ValueError(
        "GitHub token not found. Provide it via: (1) config.toml [github] token, "
        "(2) GITHUB_TOKEN_FILE, or (3) GITHUB_TOKEN."
    )


def collect_sync_tasks(
    config: Config,
    destination: Path,
    user_repos: UserRepositories | None,
    credentials_path: str | None = None,
    excluded: set[str] | None = None,
) -> list[SyncTask]:
    """Build the ordered list of repositories to sync from config + discovered repos.

    ``excluded`` names (deleted/inaccessible repos from the state tracker) are dropped,
    just like the config's ``ignore`` list.
    """
    excluded = excluded or set()
    tasks: list[SyncTask] = []

    github = config.github
    if github is not None and user_repos is not None:
        github_destination = destination / "github"
        reasons: dict[str, set[str]] = {}

        for name in user_repos.owned:
            reasons.setdefault(name, set()).add("owned")
        for name in github.clone.repos:
            reasons.setdefault(name, set()).add("explicit")
        if github.clone.starred:
            for name in user_repos.starred:
                reasons.setdefault(name, set()).add("starred")
        if github.clone.watched:
            for name in user_repos.watching:
                reasons.setdefault(name, set()).add("watching")

        for ignore in github.clone.ignore:
            reasons.pop(ignore, None)
        for name in excluded:
            reasons.pop(name, None)

        clone_destination = github_destination / "clone"
        threshold = config.large_repos.size_threshold_kb
        for name_and_owner, why in reasons.items():
            metadata = user_repos.metadata.get(name_and_owner)
            size_kb = metadata.disk_usage_kb if metadata else None
            tasks.append(
                SyncTask(
                    name=name_and_owner,
                    url=f"https://github.com/{name_and_owner}.git",
                    destination=clone_destination / name_and_owner,
                    credentials_path=credentials_path,
                    reasons=frozenset(why),
                    single_branch_only=github.clone.single_branch_only,
                    default_branch=metadata.default_branch if metadata else None,
                    size_kb=size_kb,
                    is_large_repo=size_kb is not None and size_kb >= threshold,
                )
            )

        if github.clone.gists:
            gists_destination = github_destination / "gists"
            for gist in user_repos.gists:
                tasks.append(
                    SyncTask(
                        name=f"gist:{gist}",
                        url=f"https://gist.github.com/{gist}.git",
                        destination=gists_destination / gist,
                        credentials_path=credentials_path,
                    )
                )

    git_destination = destination / "git"
    for name, url in config.git.repos.items():
        tasks.append(SyncTask(name=name, url=url, destination=git_destination / name))

    return tasks


def _build_argv(
    task: SyncTask,
    config: Config,
    *,
    force_http1: bool = False,
    use_shallow_clone: bool = False,
    show_progress: bool = False,
) -> list[str]:
    repo_exists = task.destination.exists()
    is_clone = not repo_exists
    return build_git_command(
        repo_exists=repo_exists,
        url=task.url if is_clone else None,
        repo_name=task.destination.name if is_clone else None,
        git_executable=resolve_git_executable(),
        verify_certificates=config.ssl.verify_certificates,
        http_version=config.http.version,
        post_buffer_size=config.http.post_buffer_size,
        low_speed_limit=config.http.low_speed_limit,
        low_speed_time=config.http.low_speed_time,
        credentials_path=task.credentials_path,
        force_http1=force_http1,
        use_shallow_clone=use_shallow_clone and is_clone,
        show_progress=show_progress,
        single_branch_only=task.single_branch_only,
        default_branch=task.default_branch,
    )


def dry_run_line(task: SyncTask, config: Config) -> str:
    """Render the ``DRY RUN <directory> <git argv>`` line (matches Engine.kt)."""
    repo_exists = task.destination.exists()
    directory = task.destination if repo_exists else task.destination.parent
    argv = _build_argv(task, config)
    return f"DRY RUN {directory} {' '.join(argv)}"


def _preflight_sync(root: Path) -> None:
    if not root.exists():
        raise ValueError(f"Backup destination does not exist: {root}")
    if not root.is_dir():
        raise ValueError(f"Backup destination is not a directory: {root}")
    sentinel = root / f".gitout-preflight-{os.getpid()}-{time.perf_counter_ns()}"
    payload = f"gitout-preflight:{os.getpid()}:{time.perf_counter_ns()}"
    try:
        sentinel.write_text(payload)
        read_back = sentinel.read_text()
        if read_back != payload:
            raise ValueError(
                f"Preflight sentinel content mismatch: expected '{payload}', got '{read_back}'"
            )
    finally:
        with contextlib.suppress(FileNotFoundError):
            sentinel.unlink()


async def preflight_storage_check(root: Path, timeout_ms: int) -> str | None:
    """Write/read/delete a sentinel on the backup volume before any API calls.

    Returns ``None`` on success or an error message on failure (mirrors
    Engine.preflightStorageCheck's Result<Unit>). A 0ms timeout fails fast.
    """
    try:
        await asyncio.wait_for(asyncio.to_thread(_preflight_sync, root), timeout=timeout_ms / 1000)
    except TimeoutError:
        return f"Preflight storage check timed out after {timeout_ms}ms"
    except Exception as exc:  # noqa: BLE001 - reported as a failure message, not raised
        return str(exc)
    return None


def _write_credentials(user: str, token: str) -> Path:
    """Write a git credential-store file with the GitHub https URL (deleted after sync)."""
    fd, path = tempfile.mkstemp(prefix="gitout-credentials-")
    url = f"https://{quote(user, safe='')}:{quote(token, safe='')}@github.com"
    with open(fd, "w") as handle:
        handle.write(url)
    return Path(path)


async def default_git_runner(argv: list[str], cwd: Path, timeout_seconds: float) -> tuple[int, str]:
    """Run git via a subprocess, capturing combined output, honouring a timeout."""
    process = await asyncio.create_subprocess_exec(
        *argv,
        cwd=str(cwd),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
    )
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout=timeout_seconds)
    except TimeoutError:
        process.kill()
        await process.wait()
        raise RuntimeError(f"git operation timed out after {timeout_seconds}s") from None
    return process.returncode or 0, stdout.decode(errors="replace")


@dataclass
class Engine:
    config: Config
    destination: Path
    repo_loader: RepoLoader | None = None
    git_runner: GitRunner = default_git_runner
    environ: Mapping[str, str] = field(default_factory=dict)
    workers: int | None = None
    timeout_seconds: float = 600.0
    credentials_path: str | None = None
    retry_policy: RetryPolicy = field(default_factory=RetryPolicy)
    # Resilience collaborators. When left None they are built from config on real runs;
    # inject to override (e.g. in tests).
    failure_tracker: FailureTracker | None = None
    circuit_breaker: StorageCircuitBreaker | None = None
    maintenance: RepositoryMaintenance | None = None
    lfs: LfsSupport | None = None
    # Lifecycle collaborators (built by the CLI when configured).
    search_index_service: SearchIndexService | None = None
    health_check: HealthCheck | None = None
    telegram: TelegramNotificationService | None = None
    _token: str | None = field(default=None, init=False, repr=False)

    def _apply_state_tracking(self, user_repos: UserRepositories) -> set[str]:
        """Detect repo state changes, maintain the exclusion list, persist state.

        Returns the set of excluded (deleted/inaccessible) repo names to skip. A repo
        that reappears in the API is re-included.
        """
        github_dir = self.destination / "github"
        github_dir.mkdir(parents=True, exist_ok=True)
        tracker = RepositoryStateTracker(github_dir / ".gitout-state.json")
        metadata = user_repos.metadata
        changes = tracker.detect_changes(metadata)

        excluded = dict(tracker.get_excluded_repos())
        now = int(time.time() * 1000)
        for change in changes.deleted:
            excluded.setdefault(
                change.name,
                ExcludedRepo(
                    name=change.name,
                    excluded_at=now,
                    reason="deleted",
                    last_metadata=change.metadata,
                ),
            )
        for name in [n for n in excluded if n in metadata]:
            del excluded[name]  # reappeared in the API

        tracker.save_state(metadata, excluded)
        return set(excluded)

    async def _discover(self) -> UserRepositories | None:
        github = self.config.github
        if github is None:
            return None
        if self.repo_loader is None:
            raise RuntimeError("repo_loader is required when [github] is configured")
        self._token = resolve_github_token(github.token, self.environ)
        return await self.repo_loader(github.user, self._token)

    def _build_collaborators(
        self,
    ) -> tuple[
        StorageCircuitBreaker | None,
        FailureTracker | None,
        RepositoryMaintenance | None,
        LfsSupport | None,
    ]:
        hc = self.config.health_check
        breaker = self.circuit_breaker
        if breaker is None and hc.circuit_breaker_enabled:
            breaker = StorageCircuitBreaker(hc.circuit_breaker_threshold)

        tracker = self.failure_tracker
        if tracker is None and self.config.failure_tracking.enabled:
            tracker = FailureTracker(
                self.destination / self.config.failure_tracking.state_file,
                self.config.failure_tracking,
            )

        maint = self.maintenance
        if maint is None and self.config.maintenance.enabled:
            maint = RepositoryMaintenance(
                self.config.maintenance, timeout_seconds=self.timeout_seconds
            )

        lfs = self.lfs
        if lfs is None and self.config.lfs.fetch_lfs:
            candidate = LfsSupport(timeout_seconds=self.timeout_seconds)
            lfs = candidate if candidate.is_lfs_available() else None

        return breaker, tracker, maint, lfs

    async def perform_sync(self, dry_run: bool = False) -> list[SyncOutcome]:
        if self.config.version != 0:
            raise ValueError("Only version 0 of the config is supported at this time")
        if not dry_run and not self.destination.is_dir():
            raise ValueError("Destination must exist and must be a directory")

        started_check = None
        if not dry_run and self.health_check is not None:
            started_check = await self.health_check.start()

        # Storage pre-flight: abort the whole run with one error rather than failing
        # every repository when the backup volume is full / read-only / unmounted.
        hc = self.config.health_check
        if not dry_run and hc.preflight_enabled:
            message = await preflight_storage_check(
                self.destination, hc.preflight_timeout_seconds * 1000
            )
            if message is not None:
                raise RuntimeError(f"Storage pre-flight check failed: {message}")

        user_repos = await self._discover()

        excluded_names: set[str] = set()
        if not dry_run and self.config.github is not None and user_repos is not None:
            excluded_names = self._apply_state_tracking(user_repos)

        credentials_path = self.credentials_path
        temp_credentials: Path | None = None
        if (
            not dry_run
            and self.config.github is not None
            and self._token
            and credentials_path is None
        ):
            temp_credentials = _write_credentials(self.config.github.user, self._token)
            credentials_path = str(temp_credentials)

        try:
            tasks = collect_sync_tasks(
                self.config, self.destination, user_repos, credentials_path, excluded_names
            )

            if dry_run:
                for task in tasks:
                    print(dry_run_line(task, self.config))
                return [SyncOutcome(task=t, ok=True) for t in tasks]

            breaker, tracker, maint, lfs = self._build_collaborators()
            worker_count = self.workers or self.config.parallelism.workers
            semaphore = asyncio.Semaphore(worker_count)
            # Additional limit on concurrent large-repo clones.
            large_repo_semaphore = asyncio.Semaphore(self.config.large_repos.max_parallel)

            if self.telegram is not None:
                self.telegram.notify_sync_start(len(tasks), worker_count)
            start_time = time.monotonic()

            async def run(task: SyncTask) -> SyncOutcome:
                # Checked before acquiring a permit so queued tasks skip once tripped.
                if breaker is not None and breaker.is_open():
                    return SyncOutcome(
                        task=task, ok=False, skipped=True, error="storage circuit breaker open"
                    )
                if tracker is not None and tracker.should_skip(task.name):
                    return SyncOutcome(task=task, ok=True, skipped=True)
                async with semaphore:
                    return await self._sync_one(
                        task, breaker, tracker, maint, lfs, large_repo_semaphore
                    )

            results = list(await asyncio.gather(*(run(t) for t in tasks)))

            if self.telegram is not None:
                successful = sum(1 for outcome in results if outcome.ok)
                self.telegram.notify_sync_completion(
                    successful, len(results) - successful, int(time.monotonic() - start_time)
                )
                self.telegram.record_failures(
                    [
                        FailedRepoSummary(
                            name=outcome.task.name,
                            url=outcome.task.url,
                            error_message=outcome.error or "",
                            category=display_name(classify(outcome.error or "")),
                            retry_attempts=1,
                        )
                        for outcome in results
                        if not outcome.ok and not outcome.skipped
                    ]
                )

            if tracker is not None:
                tracker.save_state()
            if maint is not None and maint.should_run_full_repack():
                maint.run_full_repack(self.destination)

            # Auto-index for semantic search, then signal the healthcheck.
            if (
                self.search_index_service is not None
                and self.config.search.auto_index
                and user_repos is not None
            ):
                await self.search_index_service.index_repositories(
                    user_repos.metadata, self.destination / "github" / "clone"
                )
            if started_check is not None:
                await started_check.complete()
            return results
        finally:
            if temp_credentials is not None:
                with contextlib.suppress(OSError):
                    temp_credentials.unlink()

    async def _sync_one(
        self,
        task: SyncTask,
        breaker: StorageCircuitBreaker | None,
        tracker: FailureTracker | None,
        maint: RepositoryMaintenance | None,
        lfs: LfsSupport | None,
        large_repo_semaphore: asyncio.Semaphore,
    ) -> SyncOutcome:
        is_clone = not task.destination.exists()
        cwd = task.destination.parent if is_clone else task.destination
        if is_clone:
            cwd.mkdir(parents=True, exist_ok=True)

        # Clone strategy from failure history + repo size (shallow for very-large repos
        # with repeated failures, HTTP/1.1 if HTTP2 errors were seen, longer timeout for
        # large repos).
        if tracker is not None:
            strategy = tracker.get_recommended_strategy(
                task.name, task.size_kb, self.config.large_repos
            )
            base_http1 = strategy.use_http1
            use_shallow = strategy.use_shallow_clone
            timeout_multiplier = strategy.timeout_multiplier
        else:
            base_http1 = False
            use_shallow = False
            timeout_multiplier = (
                self.config.large_repos.timeout_multiplier if task.is_large_repo else 1.0
            )
        effective_timeout = self.timeout_seconds * timeout_multiplier

        async def operation(context: RetryContext) -> str:
            force_http1 = base_http1 or context.should_use_http1_fallback
            show_progress = task.is_large_repo or context.is_retry
            argv = _build_argv(
                task,
                self.config,
                force_http1=force_http1,
                use_shallow_clone=use_shallow,
                show_progress=show_progress,
            )
            code, output = await self.git_runner(argv, cwd, effective_timeout)
            if code != 0:
                raise RuntimeError(output or f"git exited with code {code}")
            return output

        async def run_with_retry() -> None:
            await self.retry_policy.execute(operation, operation_description=task.url)

        use_large_limit = task.is_large_repo and is_clone
        try:
            if use_large_limit:
                async with large_repo_semaphore:
                    await run_with_retry()
            else:
                await run_with_retry()
        except SyncFailureException as exc:
            category = exc.error_categories[-1] if exc.error_categories else classify(str(exc))
            cause_message = str(exc.__cause__) if exc.__cause__ else str(exc)
            if tracker is not None:
                tracker.record_failure(task.name, cause_message, category)
            if breaker is not None:
                breaker.record_failure(category)
            return SyncOutcome(task=task, ok=False, error=str(exc))
        except Exception as exc:  # noqa: BLE001 - surfaced as an outcome, not raised
            category = classify(str(exc))
            if tracker is not None:
                tracker.record_failure(task.name, str(exc), category)
            if breaker is not None:
                breaker.record_failure(category)
            return SyncOutcome(task=task, ok=False, error=str(exc))

        # Success: reset trackers, then run post-sync maintenance and LFS fetch.
        if tracker is not None:
            tracker.record_success(task.name)
        if breaker is not None:
            breaker.record_success()
        if maint is not None:
            maint.run_post_sync_maintenance(task.destination)
        if lfs is not None:
            lfs.sync_lfs_if_needed(task.destination)
        return SyncOutcome(task=task, ok=True)
