"""Post-sync repository maintenance (port of RepositoryMaintenance.kt).

Strategies: ``gc-auto`` (``git gc --auto``), ``geometric`` (``git repack
--geometric=2 -d``), or ``none``. Optionally writes a commit-graph after every sync
and runs a periodic full repack (``git repack -a -d``) on a weekly/monthly cadence
(~1 sync/day → 7 / 30 syncs). Maintenance commands use the literal ``git`` (matching
Kotlin). The command runner is injectable so tests assert argv without spawning git.
"""

from __future__ import annotations

import contextlib
import os
import subprocess
from collections.abc import Callable
from pathlib import Path

from gitout.config import Maintenance

# (argv, cwd) -> None
GitCommandRunner = Callable[[list[str], Path], None]


def _default_runner(timeout_seconds: float) -> GitCommandRunner:
    def run(argv: list[str], cwd: Path) -> None:
        with contextlib.suppress(Exception):
            subprocess.run(  # noqa: S603
                argv, cwd=str(cwd), capture_output=True, timeout=timeout_seconds, check=False
            )

    return run


class RepositoryMaintenance:
    def __init__(
        self,
        config: Maintenance,
        *,
        timeout_seconds: float = 600.0,
        run_git: GitCommandRunner | None = None,
    ) -> None:
        self._config = config
        self._run_git = run_git or _default_runner(timeout_seconds)
        self._sync_count = 0

    def run_post_sync_maintenance(self, repo_path: Path) -> None:
        if not self._config.enabled:
            return
        if not repo_path.is_dir():
            return

        abs_path = str(repo_path)
        if self._config.strategy == "gc-auto":
            self._run_git(["git", "-C", abs_path, "gc", "--auto"], repo_path)
        elif self._config.strategy == "geometric":
            self._run_git(["git", "-C", abs_path, "repack", "--geometric=2", "-d"], repo_path)
        # "none" and unknown strategies run no repack command (unknown is a no-op here).

        # The commit-graph is written after every strategy, including none/unknown.
        if self._config.write_commit_graph:
            self._run_git(
                ["git", "-C", abs_path, "commit-graph", "write", "--reachable"], repo_path
            )

    def should_run_full_repack(self) -> bool:
        if not self._config.enabled:
            return False
        self._sync_count += 1
        interval = self._config.full_repack_interval
        if interval == "weekly":
            return self._sync_count % 7 == 0
        if interval == "monthly":
            return self._sync_count % 30 == 0
        return False  # "never" or unknown

    def run_full_repack(self, destination_path: Path) -> None:
        if not self._config.enabled:
            return
        if not destination_path.is_dir():
            return
        for repo in self.find_git_repos(destination_path):
            self._run_git(
                [
                    "git",
                    "-C",
                    str(repo),
                    "repack",
                    "-a",
                    "-d",
                    f"--window={self._config.repack_window}",
                    f"--depth={self._config.repack_depth}",
                ],
                repo,
            )

    @staticmethod
    def find_git_repos(root: Path) -> list[Path]:
        """Bare repos (dirs containing a HEAD file) under ``root``, to a depth of 4."""
        if not root.exists():
            return []
        repos: list[Path] = []
        for dirpath, dirnames, _ in os.walk(root):
            current = Path(dirpath)
            depth = len(current.relative_to(root).parts)
            if depth > 4:
                dirnames[:] = []
                continue
            if (current / "HEAD").exists():
                repos.append(current)
        return repos
