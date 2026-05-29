# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

**gitout** is a Python CLI that automatically backs up Git repositories from GitHub or any git host. It syncs repositories in parallel with `asyncio`, uses an async httpx GraphQL client for the GitHub API, and includes resilience (retry, circuit breaker, failure tracking, state tracking), maintenance, Git LFS, cron scheduling, Healthchecks.io pings, Telegram notifications, and semantic search (Gemini + Qdrant).

It was ported from a Kotlin implementation; the Kotlin sources remain in git history and the behavior is parity-tested (see characterization tests).

## Build & Development

```bash
pip install -e ".[dev]"          # install with dev tooling
ruff check gitout tests          # lint
mypy gitout                      # type-check (strict)
python -m pytest -q              # run all tests
python -m pytest -q tests/test_config.py   # one module
python -m gitout sync config.toml /backup/path --dry-run
docker build -t gitout .         # Docker image
```

## Architecture

### Core flow

`cli.sync` → `Engine.perform_sync(dry_run)`:
version guard → healthcheck start → storage pre-flight → GitHub discovery
(`github_client.load_repositories`) → state tracking + exclusions →
`collect_sync_tasks` → parallel `asyncio.Semaphore` worker pool → per-repo
`_sync_one` (clone strategy → retry → record success/failure → maintenance →
LFS) → save failure state → full repack → auto-index → healthcheck complete.

### Module map

- **cli.py** — Typer CLI: `sync` / `search` / `index`, `--version`, `--cron`.
- **config.py** — TOML config model (dataclasses), `parse`, `validate`, `to_normalized_dict`. Sections: github, git, ssl, http, parallelism, metrics, telegram, large_repos, failure_tracking, health_check, maintenance, lfs, search.
- **engine.py** — task collection, parallel sync, `preflight_storage_check`, token resolution, credentials, lifecycle wiring; `StorageCircuitBreaker` in `circuit_breaker.py`.
- **github.py / github_client.py** — GraphQL response fold + async httpx paging.
- **git_commands.py** — `build_git_command` argv builder.
- **retry.py** — `RetryPolicy` (LINEAR/EXPONENTIAL/CONSTANT, adaptive multiplier, HTTP/1.1 fallback; default 6 attempts, 5s base).
- **errors.py** — `ErrorCategory` + `classify` and helpers.
- **failure_tracker.py / state_tracker.py / maintenance.py / lfs.py / health_check.py / cron.py** — resilience, repo state, gc/repack, LFS, pings, scheduling.
- **telegram.py** — notifications + command handlers (`/ping /start /help /status /stats /fails /info /find /reindex`).
- **search/** — `readme_extractor`, `gemini`, `qdrant`, `index_service`, `exceptions`.

### Key patterns

- **Parallel sync:** `asyncio.Semaphore(workers)` + `asyncio.gather`. Worker priority: CLI `--workers` → `GITOUT_WORKERS` → config → 4.
- **Git operations:** clone `git clone --mirror`; update `git remote update --prune` (or `fetch --prune origin` for single-branch). The git executable is resolved via PATH (`git_exec.resolve_git_executable`).
- **Token resolution:** config → `GITHUB_TOKEN_FILE` → `GITHUB_TOKEN` (Telegram: `TELEGRAM_BOT_TOKEN_FILE`/`TELEGRAM_BOT_TOKEN`; Gemini: `GEMINI_API_KEY`/`GEMINI_API_KEY_FILE`).

## Testing

Tests in `tests/` (and `tests/search/`) use pytest + `pytest-asyncio` (auto mode), httpx `MockTransport` for HTTP, real git for the README extractor, and injectable clocks/sleepers/runners for determinism. Many tests are characterization tests capturing the original Kotlin behavior.

## CI/CD

- **build.yaml**: PRs + master. Ruff, mypy, pytest, Docker build + smoke test.
- **publish.yaml**: master + version tags. Builds sdist/wheel, multi-arch Docker (amd64/arm64) to Docker Hub + GHCR, GitHub release.
- **codeql.yaml**: weekly + on-demand Python security scanning.
- Secrets: `DOCKER_USERNAME`/`DOCKER_PASSWORD`, `GITHUB_TOKEN` (auto).

## Development Patterns

### Adding a config option
1. Add the field to the relevant dataclass in `config.py` (snake_case, with a default).
2. Add a validation rule in `validate()` if needed.
3. Add a parse fixture case and/or a `validate` case in `tests/test_config.py`.

### Retry pattern
```python
await retry_policy.execute(operation, operation_description="...")
```

### Parallel pattern
```python
semaphore = asyncio.Semaphore(workers)
async def run(item):
    async with semaphore:
        return await process(item)
results = await asyncio.gather(*(run(i) for i in items))
```

## Implementation Notes

- Tokens are written to a temp credential-store file and deleted after sync; never log token values.
- All git operations have timeouts; failures are classified and retried adaptively.
- Network/IO collaborators (httpx clients, git runners, clocks, senders) are injectable so tests stay hermetic.
- Keep ruff + mypy `--strict` clean.

## Git Commit Guidelines

- Never mention Claude, AI, or assistant tools in commit messages
- Imperative mood ("Add feature" not "Added feature")
- Focus on what and why, not how

## Docker

- Base `python:3.11-alpine`; installs the package via pip; entrypoint handles PUID/PGID user mapping and runs `gitout sync /config/config.toml /data` by default.
- Multi-arch: linux/amd64, linux/arm64. Published to Docker Hub (`po4yka/gitout`) and GHCR (`ghcr.io/po4yka/gitout`).

## Telegram (telegram.py)

- Token resolution: config → `TELEGRAM_BOT_TOKEN_FILE` → `TELEGRAM_BOT_TOKEN`.
- Notifications: start/progress/completion via the Bot API (injectable sender).
- Command handlers: `/ping /start /help /status /stats /fails /info /find /reindex` with whitelist auth (`allowed_users`). The long-poll transport loop is not implemented.
