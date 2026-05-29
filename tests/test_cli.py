"""End-to-end CLI tests (dry-run; no network, no subprocess)."""

from __future__ import annotations

import logging
from pathlib import Path

import pytest
from typer.testing import CliRunner

from gitout import cli
from gitout.cli import _configure_logging
from gitout.github import RepositoryMetadata, UserRepositories

runner = CliRunner()


def _write_config(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "config.toml"
    path.write_text(text)
    return path


def test_dry_run_git_only(tmp_path: Path) -> None:
    config = _write_config(
        tmp_path,
        'version = 0\n[git.repos]\nmirror = "https://example.com/x.git"\n',
    )
    result = runner.invoke(cli.app, ["sync", str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 0, result.output
    assert "DRY RUN" in result.output
    assert "clone --mirror -- https://example.com/x.git mirror" in result.output


def test_invalid_config_exits_nonzero(tmp_path: Path) -> None:
    config = _write_config(tmp_path, "version = 1\n[search]\nenabled = true\ntop_k = 0\n")
    result = runner.invoke(cli.app, ["sync", str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 1
    assert "search.top_k must be between 1 and 100, got 0" in result.output


def test_dry_run_github(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_loader(user: str, token: str) -> UserRepositories:
        return UserRepositories(
            owned={"me/repo"},
            starred=set(),
            watching=set(),
            gists={"g1"},
            metadata={
                "me/repo": RepositoryMetadata(
                    name="me/repo",
                    is_archived=False,
                    is_private=False,
                    is_fork=False,
                    visibility="PUBLIC",
                    description=None,
                    updated_at="2024-01-01T00:00:00Z",
                    repo_type="owned",
                ),
            },
        )

    monkeypatch.setattr(cli, "load_repositories", fake_loader)
    config = _write_config(
        tmp_path,
        'version = 0\n[github]\nuser = "me"\ntoken = "tok"\n[github.clone]\ngists = true\n',
    )
    result = runner.invoke(cli.app, ["sync", str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 0, result.output
    assert "https://github.com/me/repo.git" in result.output
    assert "https://gist.github.com/g1.git" in result.output


def test_search_not_enabled(tmp_path: Path) -> None:
    config = _write_config(tmp_path, "version = 0\n")
    result = runner.invoke(cli.app, ["search", "kotlin", str(config), str(tmp_path)])
    assert result.exit_code == 0
    assert "Search is not enabled" in result.output


def test_index_without_state(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    config = _write_config(
        tmp_path,
        'version = 0\n[search]\nenabled = true\nqdrant_url = "http://localhost:6333"\n',
    )
    result = runner.invoke(cli.app, ["index", str(config), str(tmp_path)])
    assert result.exit_code == 0
    assert "No repository state found" in result.output


def test_configure_logging_levels() -> None:
    """_configure_logging sets the expected root logger level for each mode."""
    root = logging.getLogger()
    original_level = root.level
    original_handlers = root.handlers[:]
    try:
        _configure_logging(verbose=0, quiet=True)
        assert root.level == logging.WARNING

        _configure_logging(verbose=0, quiet=False)
        assert root.level == logging.INFO

        _configure_logging(verbose=1, quiet=False)
        assert root.level == logging.DEBUG

        # quiet wins over verbose
        _configure_logging(verbose=1, quiet=True)
        assert root.level == logging.WARNING
    finally:
        root.level = original_level
        root.handlers = original_handlers
