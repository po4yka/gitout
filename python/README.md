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
must satisfy. During Phase 0 the `gitout/` modules are stubs that raise
`NotImplementedError`, so the suite is **red**; each test module carries an `xfail`
marker so CI stays green until the corresponding subsystem is implemented in Phase 1.

To watch the spec fail for the right reason (before implementing):

```bash
cd python
python -m pytest --runxfail -q        # shows the real NotImplementedError failures
```

Normal run (xfail-aware, CI mode):

```bash
cd python
python -m pytest -q
ruff check .
mypy gitout
```

As each subsystem is implemented, remove its module's `pytestmark = pytest.mark.xfail(...)`
line and the tests must go green.

## Migration phases

- **Phase 0 (here):** safety net — characterization fixtures + tests + scaffolding.
- **Phase 1:** core backup — config → GitHub discovery → parallel mirror clone → retry.
- **Phase 2+:** resilience tracking, health checks, Telegram bot, semantic search, LFS.
