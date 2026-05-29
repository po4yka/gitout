# Git Out

[![Build & Test](https://github.com/po4yka/gitout/actions/workflows/build.yaml/badge.svg)](https://github.com/po4yka/gitout/actions/workflows/build.yaml)

A CLI that automatically backs up Git repositories from GitHub (owned, starred, watched, and gists) or any git host, using `git clone --mirror`. It syncs repositories in parallel with adaptive retry, tracks failures across runs, and optionally indexes repositories for semantic search and sends Telegram notifications.

This is a Python implementation (ported from the original Kotlin tool; the Kotlin sources remain in git history).

## Install

```bash
pip install .
# or, for development:
pip install -e ".[dev]"
```

Requires Python 3.11+ and `git` (and `git-lfs` if LFS fetching is enabled) on `PATH`.

## Usage

```bash
gitout sync CONFIG DESTINATION [--dry-run]   # back up repositories
gitout search QUERY CONFIG DESTINATION       # semantic search over backed-up repos
gitout index CONFIG DESTINATION              # (re)index repositories for search
gitout --version
```

`--dry-run` prints the planned `git` commands without touching the network or filesystem.

### Useful options / env vars

- `--workers N` (`GITOUT_WORKERS`) — parallel worker count.
- `--timeout SECONDS` — per-repository git timeout.
- `--cron "<expr>"` (`GITOUT_CRON`) — run forever, syncing on a 5-field cron schedule.
- `--hc-id` / `--hc-host` (`GITOUT_HC_ID` / `GITOUT_HC_HOST`) — Healthchecks.io ping.
- `--dry-run` (`GITOUT_DRY_RUN`).

### Configuration

A TOML file (`version = 0`) describes what to back up. Sections: `github`, `git`, `ssl`,
`http`, `parallelism`, `metrics`, `telegram`, `large_repos`, `failure_tracking`,
`health_check`, `maintenance`, `lfs`, `search`. Tokens resolve from config, then
`GITHUB_TOKEN_FILE`, then `GITHUB_TOKEN`.

```toml
version = 0

[github]
user = "octocat"
# token = "..."   # or set GITHUB_TOKEN / GITHUB_TOKEN_FILE

[github.clone]
starred = true
watched = false
gists = true

[git.repos]
example = "https://example.com/example.git"
```

## Docker

```bash
docker build -t gitout .
docker run --rm -v "$PWD/config:/config" -v "$PWD/data:/data" gitout
```

The entrypoint maps `PUID`/`PGID` for file ownership and runs `gitout sync /config/config.toml /data` by default. Published to Docker Hub (`po4yka/gitout`) and GHCR (`ghcr.io/po4yka/gitout`).

## Development

```bash
pip install -e ".[dev]"
ruff check gitout tests
mypy gitout
python -m pytest -q
```

## Architecture

```
gitout/
├── cli.py            # Typer CLI (sync / search / index)
├── config.py         # TOML config model, parse, validate
├── engine.py         # task collection + parallel sync orchestration
├── github.py         # GraphQL response folding
├── github_client.py  # async httpx GraphQL paging client
├── git_commands.py   # git argv construction
├── retry.py          # adaptive retry policy
├── errors.py         # error categorization
├── circuit_breaker.py / failure_tracker.py / state_tracker.py
├── maintenance.py    # gc / repack / commit-graph
├── lfs.py            # Git LFS fetch
├── health_check.py   # Healthchecks.io ping
├── cron.py           # dependency-free cron scheduler
├── telegram.py       # notifications + command handlers
└── search/           # README extraction, Gemini embeddings, Qdrant index
```
