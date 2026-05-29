"""Typer CLI entry point (port of main.kt's sync command).

``gitout CONFIG DESTINATION [--dry-run]``. Subcommands (search/index) and cron
scheduling from the Kotlin CLI arrive in later phases.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

import typer

from gitout import config as config_module
from gitout.engine import Engine
from gitout.github_client import load_repositories

app = typer.Typer(
    add_completion=False,
    help="Back up Git repositories from GitHub or any git host.",
)


@app.command()
def main(
    config: Path = typer.Argument(..., exists=True, dir_okay=False, help="Configuration TOML"),
    destination: Path = typer.Argument(..., help="Backup directory"),
    dry_run: bool = typer.Option(
        False, "--dry-run", "-n", envvar="GITOUT_DRY_RUN", help="Print actions, do not run them"
    ),
    workers: int | None = typer.Option(
        None, "--workers", envvar="GITOUT_WORKERS", help="Parallel worker count"
    ),
    timeout: float = typer.Option(
        600.0, "--timeout", help="Per-repository git timeout in seconds"
    ),
) -> None:
    cfg = config_module.parse(config.read_text())
    errors = config_module.validate(cfg)
    if errors:
        typer.echo("Configuration validation failed:", err=True)
        for error in errors:
            typer.echo(f"  - {error.code} {error.detail}", err=True)
        raise typer.Exit(code=1)

    engine = Engine(
        config=cfg,
        destination=destination,
        repo_loader=load_repositories,
        environ=os.environ,
        workers=workers,
        timeout_seconds=timeout,
    )
    outcomes = asyncio.run(engine.perform_sync(dry_run=dry_run))

    if dry_run:
        return

    failures = [o for o in outcomes if not o.ok]
    for failure in failures:
        typer.echo(f"FAILED {failure.task.url}: {failure.error}", err=True)
    typer.echo(f"Synced {len(outcomes) - len(failures)}/{len(outcomes)} repositories.")
    if failures and cfg.exit_on_failure:
        raise typer.Exit(code=1)


if __name__ == "__main__":
    app()
