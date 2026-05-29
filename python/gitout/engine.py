"""Sync engine: task collection and parallel mirror/update execution.

Port of the core of ``Engine.kt`` (the backup path): GitHub token resolution,
sync-task collection, the ``DRY RUN`` plan line, and parallel execution with an
``asyncio.Semaphore`` worker pool and per-repo retry.

Deferred to later phases (present in Kotlin, not yet ported): repository state
tracking / exclusions, failure tracking, circuit breaker + storage pre-flight,
large-repo/shallow-clone heuristics, LFS, maintenance, health checks, cron,
Telegram, and search indexing.
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

from gitout.config import Config
from gitout.git_commands import build_git_command
from gitout.git_exec import resolve_git_executable
from gitout.github import UserRepositories
from gitout.retry import RetryContext, RetryPolicy

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


@dataclass(frozen=True)
class SyncOutcome:
    task: SyncTask
    ok: bool
    error: str | None = None


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
) -> list[SyncTask]:
    """Build the ordered list of repositories to sync from config + discovered repos."""
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

        clone_destination = github_destination / "clone"
        for name_and_owner, why in reasons.items():
            metadata = user_repos.metadata.get(name_and_owner)
            tasks.append(
                SyncTask(
                    name=name_and_owner,
                    url=f"https://github.com/{name_and_owner}.git",
                    destination=clone_destination / name_and_owner,
                    credentials_path=credentials_path,
                    reasons=frozenset(why),
                    single_branch_only=github.clone.single_branch_only,
                    default_branch=metadata.default_branch if metadata else None,
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


def _build_argv(task: SyncTask, config: Config, *, force_http1: bool = False) -> list[str]:
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
    _token: str | None = field(default=None, init=False, repr=False)

    async def _discover(self) -> UserRepositories | None:
        github = self.config.github
        if github is None:
            return None
        if self.repo_loader is None:
            raise RuntimeError("repo_loader is required when [github] is configured")
        self._token = resolve_github_token(github.token, self.environ)
        return await self.repo_loader(github.user, self._token)

    async def perform_sync(self, dry_run: bool = False) -> list[SyncOutcome]:
        if self.config.version != 0:
            raise ValueError("Only version 0 of the config is supported at this time")
        if not dry_run and not self.destination.is_dir():
            raise ValueError("Destination must exist and must be a directory")

        user_repos = await self._discover()

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
                self.config, self.destination, user_repos, credentials_path
            )

            if dry_run:
                for task in tasks:
                    print(dry_run_line(task, self.config))
                return [SyncOutcome(task=t, ok=True) for t in tasks]

            worker_count = self.workers or self.config.parallelism.workers
            semaphore = asyncio.Semaphore(worker_count)

            async def run(task: SyncTask) -> SyncOutcome:
                async with semaphore:
                    return await self._sync_one(task)

            return list(await asyncio.gather(*(run(t) for t in tasks)))
        finally:
            if temp_credentials is not None:
                with contextlib.suppress(OSError):
                    temp_credentials.unlink()

    async def _sync_one(self, task: SyncTask) -> SyncOutcome:
        is_clone = not task.destination.exists()
        cwd = task.destination.parent if is_clone else task.destination
        if is_clone:
            cwd.mkdir(parents=True, exist_ok=True)

        async def operation(context: RetryContext) -> str:
            argv = _build_argv(task, self.config, force_http1=context.should_use_http1_fallback)
            code, output = await self.git_runner(argv, cwd, self.timeout_seconds)
            if code != 0:
                raise RuntimeError(output or f"git exited with code {code}")
            return output

        try:
            await self.retry_policy.execute(operation, operation_description=task.url)
            return SyncOutcome(task=task, ok=True)
        except Exception as exc:  # noqa: BLE001 - surfaced as an outcome, not raised
            return SyncOutcome(task=task, ok=False, error=str(exc))
