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
- **Phase 2+ (todo):** repository state tracking, failure tracking + circuit breaker +
  storage pre-flight, large-repo/shallow-clone heuristics, LFS, maintenance, health
  checks, cron scheduling, Telegram bot, and semantic search (Qdrant + Gemini).
