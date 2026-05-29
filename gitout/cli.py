"""Typer CLI entry point (port of main.kt + SearchCommand + IndexCommand).

Subcommands:
  gitout sync CONFIG DESTINATION [--dry-run]   back up repositories
  gitout search QUERY CONFIG DESTINATION       semantic search
  gitout index CONFIG DESTINATION              (re)index for semantic search

Note: unlike the Kotlin CLI (``gitout CONFIG DEST``), sync is an explicit
subcommand here — Typer/Click cannot mix positional root args with subcommands.
"""

from __future__ import annotations

import asyncio
import os
from datetime import datetime
from pathlib import Path

import typer

from gitout import __version__
from gitout import config as config_module
from gitout.cron import run_cron
from gitout.engine import Engine
from gitout.gemini_key import resolve_gemini_api_key
from gitout.github_client import load_repositories
from gitout.health_check import DEFAULT_HEALTHCHECK_HOST, HealthCheckService
from gitout.search.gemini import GeminiEmbeddingClient
from gitout.search.index_service import SearchIndexService
from gitout.search.qdrant import QdrantClient
from gitout.search.readme_extractor import ReadmeExtractor
from gitout.state_tracker import RepositoryStateTracker
from gitout.telegram import TelegramNotificationService

app = typer.Typer(
    add_completion=False,
    help="Back up Git repositories from GitHub or any git host.",
)


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(__version__)
        raise typer.Exit()


@app.callback()
def _root(
    version: bool = typer.Option(
        False, "--version", callback=_version_callback, is_eager=True, help="Show version and exit"
    ),
) -> None:
    """gitout — back up Git repositories from GitHub or any git host."""


@app.command()
def sync(
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
    hc_id: str | None = typer.Option(
        None, "--hc-id", envvar="GITOUT_HC_ID", help="Healthchecks.io check id to ping"
    ),
    hc_host: str = typer.Option(
        DEFAULT_HEALTHCHECK_HOST, "--hc-host", envvar="GITOUT_HC_HOST", help="Healthchecks.io host"
    ),
    cron: str | None = typer.Option(
        None, "--cron", envvar="GITOUT_CRON", help="Run forever, syncing on this cron schedule"
    ),
) -> None:
    """Back up repositories described by the config into the destination."""
    cfg = config_module.parse(config.read_text())
    errors = config_module.validate(cfg)
    if errors:
        typer.echo("Configuration validation failed:", err=True)
        for error in errors:
            typer.echo(f"  - {error.code} {error.detail}", err=True)
        raise typer.Exit(code=1)

    search_service: SearchIndexService | None = None
    if cfg.search.enabled and not dry_run:
        api_key = resolve_gemini_api_key(os.environ)
        if api_key is not None:
            search_service = SearchIndexService(
                GeminiEmbeddingClient(api_key),
                QdrantClient(cfg.search.qdrant_url),
                ReadmeExtractor(),
                cfg.search,
            )
        else:
            typer.echo(
                "Search enabled but GEMINI_API_KEY/GEMINI_API_KEY_FILE not set; "
                "skipping auto-indexing.",
                err=True,
            )

    health_check = HealthCheckService(hc_host).new_check(hc_id) if hc_id else None

    telegram = (
        TelegramNotificationService(
            cfg.telegram,
            environ=os.environ,
            search_index_service=search_service,
            search_destination=destination,
        )
        if cfg.telegram is not None and not dry_run
        else None
    )

    engine = Engine(
        config=cfg,
        destination=destination,
        repo_loader=load_repositories,
        environ=os.environ,
        workers=workers,
        timeout_seconds=timeout,
        search_index_service=search_service,
        health_check=health_check,
        telegram=telegram,
    )

    if cron:

        async def scheduled() -> None:
            try:
                await engine.perform_sync(dry_run=dry_run)
            except Exception as exc:  # noqa: BLE001 - keep the schedule alive across failures
                typer.echo(f"Scheduled sync failed: {exc}", err=True)

        typer.echo(f"Running on schedule: {cron}")
        asyncio.run(run_cron(cron, scheduled, sleep=asyncio.sleep, now=datetime.now))
        return

    outcomes = asyncio.run(engine.perform_sync(dry_run=dry_run))

    if dry_run:
        return

    failures = [o for o in outcomes if not o.ok]
    for failure in failures:
        typer.echo(f"FAILED {failure.task.url}: {failure.error}", err=True)
    typer.echo(f"Synced {len(outcomes) - len(failures)}/{len(outcomes)} repositories.")
    if failures and cfg.exit_on_failure:
        raise typer.Exit(code=1)


def _build_search_service(cfg: config_module.Config) -> SearchIndexService | None:
    """Build a SearchIndexService, or echo why it can't be built and return None."""
    if not cfg.search.enabled:
        typer.echo("Search is not enabled in config. Set search.enabled = true")
        return None
    api_key = resolve_gemini_api_key(os.environ)
    if api_key is None:
        typer.echo("GEMINI_API_KEY or GEMINI_API_KEY_FILE is not set", err=True)
        raise typer.Exit(code=1)
    return SearchIndexService(
        GeminiEmbeddingClient(api_key),
        QdrantClient(cfg.search.qdrant_url),
        ReadmeExtractor(),
        cfg.search,
    )


@app.command()
def search(
    query: str = typer.Argument(..., help="Natural language search query"),
    config: Path = typer.Argument(..., exists=True, dir_okay=False, help="Configuration TOML"),
    destination: Path = typer.Argument(..., help="Backup directory"),
) -> None:
    """Search backed-up repositories by natural-language query."""
    cfg = config_module.parse(config.read_text())
    service = _build_search_service(cfg)
    if service is None:
        return

    results = asyncio.run(service.search(query))
    if not results:
        typer.echo(f'No repositories found matching "{query}"')
        return

    typer.echo(f'Results for "{query}":')
    typer.echo("")
    for index, result in enumerate(results):
        name = result.payload.get("name") or result.id
        description = (result.payload.get("description") or "").strip() or "(no description)"
        language = result.payload.get("language") or "unknown"
        topics = ", ".join(result.payload.get("topics") or [])
        typer.echo(f"{index + 1}. {name} ({result.score:.2f})")
        typer.echo(f"   {description}")
        if topics:
            typer.echo(f"   Language: {language} | Topics: {topics}")
        else:
            typer.echo(f"   Language: {language}")
        if index < len(results) - 1:
            typer.echo("")


@app.command()
def index(
    config: Path = typer.Argument(..., exists=True, dir_okay=False, help="Configuration TOML"),
    destination: Path = typer.Argument(..., help="Backup directory"),
) -> None:
    """Index all backed-up repositories for semantic search."""
    cfg = config_module.parse(config.read_text())
    service = _build_search_service(cfg)
    if service is None:
        return

    state_file = destination / "github" / ".gitout-state.json"
    if not state_file.exists():
        typer.echo(f"No repository state found at {state_file}. Run gitout sync first.")
        return

    repos = RepositoryStateTracker(state_file).load_repositories()
    typer.echo(f"Starting indexing of {len(repos)} repositories...")
    clone_dir = destination / "github" / "clone"
    asyncio.run(service.index_repositories(repos, clone_dir))
    typer.echo("Indexing complete.")


if __name__ == "__main__":
    app()
