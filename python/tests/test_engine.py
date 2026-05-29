"""Behavioral spec for the sync engine (port of Engine.kt task collection + dry-run).

Covers the pure, network-free seams: GitHub token resolution, sync-task collection
(candidate reasons, ignore filtering, destinations, URLs), and the dry-run plan line.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from gitout.config import Config, GitConfig, GitHubClone, GitHubConfig
from gitout.engine import (
    Engine,
    SyncTask,
    collect_sync_tasks,
    dry_run_line,
    resolve_github_token,
)
from gitout.github import RepositoryMetadata, UserRepositories
from gitout.retry import RetryPolicy


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
    assert line.startswith(f"DRY RUN {dest.parent} git -c safe.directory=*")
    assert line.endswith("clone --mirror -- https://github.com/me/owned-1.git owned-1")


def test_dry_run_line_update_existing_repo(tmp_path: Path) -> None:
    dest = tmp_path / "git" / "mirror"
    dest.mkdir(parents=True)
    task = SyncTask(name="mirror", url="https://example.com/x.git", destination=dest)
    line = dry_run_line(task, Config(version=1))
    # repo dir exists -> update in place.
    assert line.startswith(f"DRY RUN {dest} git -c safe.directory=*")
    assert line.endswith("remote update --prune")


# --- async execution ---


async def test_perform_sync_runs_git_and_creates_parent(tmp_path: Path) -> None:
    cfg = Config(version=1, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))
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
    cfg = Config(version=1, git=GitConfig(repos={"mirror": "https://example.com/x.git"}))
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
