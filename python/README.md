# gitout (Python port)

Incremental, parity-gated rewrite of the Kotlin `gitout` (in `../src/main/kotlin`) to Python with `asyncio` + Typer + httpx. The Kotlin implementation stays the runnable reference until the Python port reaches parity, subsystem by subsystem.

## Layout

```
python/
├── pyproject.toml         # project + pytest/ruff/mypy config
├── gitout/                # the port (one module per Kotlin subsystem)
│   ├── errors.py          # ErrorCategory.kt
│   ├── config.py          # Config.kt (dataclasses + parse/validate)
│   ├── retry.py           # RetryPolicy.kt
│   ├── github.py          # GitHub.kt (GraphQL discovery)
│   └── git_commands.py    # Engine.kt git argv builder
├── tests/                 # characterization suite (behavior captured from Kotlin)
│   └── fixtures/          # language-neutral golden fixtures (JSON/TOML)
└── parity/                # side-by-side Kotlin-vs-Python parity harness
```

## TDD workflow

This project is built test-first. The `tests/` suite encodes the behavior of the
existing Kotlin tool as **characterization fixtures** — the executable spec the port
must satisfy. New subsystems are spec'd as failing tests first (during Phase 0 the
`gitout/` modules were stubs raising `NotImplementedError`, with `xfail` markers
keeping CI green), then implemented until the tests go green.

```bash
cd python
python -m pytest -q          # full suite (currently all green)
ruff check .
mypy gitout
```

Run the tool (dry-run prints the planned git commands without touching the network):

```bash
python -m gitout config.toml /backup/dest --dry-run
```

## Migration phases

- **Phase 0 (done):** safety net — characterization fixtures + tests + scaffolding.
- **Phase 1 (done):** core backup — config → GitHub discovery → parallel mirror clone
  → retry, plus the Typer CLI and `--dry-run`. All characterization tests green.
- **Phase 2 (done):** reliability core — storage circuit breaker, cross-session failure
  tracking (auto-skip + cooldown + clone-strategy hints), repository state tracking
  (change detection + exclusions), post-sync maintenance (gc/repack/commit-graph + full
  repack cadence), Git LFS fetch, and the storage pre-flight check, all wired into the
  engine's parallel sync.
- **Phase 3 (done):** semantic search (README extraction + Gemini embeddings + Qdrant
  index + indexing/search orchestration) with `search`/`index` CLI subcommands;
  state-tracker exclusion wiring; large-repo/shallow-clone heuristics + per-repo clone
  strategy in the live sync; lifecycle wiring (Healthchecks.io start/complete,
  auto-indexing after sync); dependency-free cron scheduling (`--cron`); and Telegram
  notifications (start/progress/completion via the Bot API, token resolution, whitelist
  auth) including the interactive command handlers (`/ping /start /help /status /stats
  /fails /info /find /reindex` with whitelist auth). Also ported: Benchmark /
  PerformanceStats and the `allowed_users` int|string coercion.

Only the Telegram long-poll *transport* loop (getUpdates) is left unported; the command
logic it would drive is fully ported and tested. The remaining step toward a full
cutover is promoting `python/` to the repo root and removing `src/main/kotlin`.

## CLI

```
gitout sync CONFIG DESTINATION [--dry-run]   # back up repositories
gitout search QUERY CONFIG DESTINATION       # semantic search
gitout index CONFIG DESTINATION              # (re)index for semantic search
```

(The Kotlin tool uses `gitout CONFIG DEST` with no subcommand; sync is an explicit
subcommand here because Typer/Click cannot mix positional root args with subcommands.)
