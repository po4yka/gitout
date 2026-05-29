"""End-to-end CLI tests (dry-run; no network, no subprocess)."""

from __future__ import annotations

from pathlib import Path

import pytest
from typer.testing import CliRunner

from gitout import cli
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
    result = runner.invoke(cli.app, [str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 0, result.output
    assert "DRY RUN" in result.output
    assert "clone --mirror -- https://example.com/x.git mirror" in result.output


def test_invalid_config_exits_nonzero(tmp_path: Path) -> None:
    config = _write_config(tmp_path, "version = 1\n[search]\nenabled = true\ntop_k = 0\n")
    result = runner.invoke(cli.app, [str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 1
    assert "InvalidTopK" in result.output


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
    result = runner.invoke(cli.app, [str(config), str(tmp_path / "dest"), "--dry-run"])
    assert result.exit_code == 0, result.output
    assert "https://github.com/me/repo.git" in result.output
    assert "https://gist.github.com/g1.git" in result.output
