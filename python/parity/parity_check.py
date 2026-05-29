#!/usr/bin/env python3
"""Diff the Kotlin reference and the Python port in --dry-run mode.

Phase 0 scaffold: the comparison plumbing (locating each binary, capturing its
dry-run plan, unified-diffing them) is wired up, but the Python CLI and the
shared dry-run plan format arrive in Phase 1. Until both sides exist this prints
which side is missing instead of diffing.

Usage:
    python parity/parity_check.py CONFIG DEST
"""

from __future__ import annotations

import argparse
import difflib
import subprocess
import sys
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]  # the python/ dir holding the gitout package
# ../build/install/gitout/bin/gitout (produced by `./gradlew installDist`)
KOTLIN_BIN = Path(__file__).resolve().parents[2] / "build" / "install" / "gitout" / "bin" / "gitout"


def _run(cmd: list[str]) -> tuple[int, str]:
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)  # noqa: S603
    return proc.returncode, proc.stdout + proc.stderr


def kotlin_dry_run(config: str, dest: str) -> str | None:
    if not KOTLIN_BIN.exists():
        return None
    _, out = _run([str(KOTLIN_BIN), "--dry-run", config, dest])
    return out


def python_dry_run(config: str, dest: str) -> str | None:
    if not (PACKAGE_ROOT / "gitout" / "cli.py").exists():
        return None
    proc = subprocess.run(  # noqa: S603
        [sys.executable, "-m", "gitout", "--dry-run", config, dest],
        capture_output=True,
        text=True,
        check=False,
        cwd=str(PACKAGE_ROOT),
    )
    return proc.stdout + proc.stderr


def main() -> int:
    parser = argparse.ArgumentParser(description="Kotlin vs Python dry-run parity check.")
    parser.add_argument("config", help="Path to the gitout TOML config.")
    parser.add_argument("dest", help="Backup destination directory.")
    args = parser.parse_args()

    kotlin = kotlin_dry_run(args.config, args.dest)
    python = python_dry_run(args.config, args.dest)

    missing = []
    if kotlin is None:
        missing.append("Kotlin reference (run `./gradlew installDist` in the repo root)")
    if python is None:
        missing.append("Python CLI (gitout.cli arrives in Phase 1)")
    if missing:
        print("parity check pending — missing: " + "; ".join(missing))
        return 0

    diff = list(
        difflib.unified_diff(
            kotlin.splitlines(keepends=True),
            python.splitlines(keepends=True),
            fromfile="kotlin",
            tofile="python",
        )
    )
    if diff:
        sys.stdout.writelines(diff)
        print("\nPARITY MISMATCH")
        return 1

    print("PARITY OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
