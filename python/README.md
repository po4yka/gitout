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
- **Phase 3 (in progress):** semantic search (README extraction + Gemini embeddings +
  Qdrant index + indexing/search orchestration), the `search`/`index` CLI subcommands,
  and state-tracker exclusion wiring (deleted repos are skipped, re-included if they
  reappear). Remaining: Telegram bot, Healthchecks.io ping, cron scheduling, and
  large-repo/shallow-clone heuristics in the live sync path.

## CLI

```
gitout sync CONFIG DESTINATION [--dry-run]   # back up repositories
gitout search QUERY CONFIG DESTINATION       # semantic search
gitout index CONFIG DESTINATION              # (re)index for semantic search
```

(The Kotlin tool uses `gitout CONFIG DEST` with no subcommand; sync is an explicit
subcommand here because Typer/Click cannot mix positional root args with subcommands.)
