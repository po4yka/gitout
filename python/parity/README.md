# Parity harness

Side-by-side validation that the Python port produces the same observable output as the Kotlin reference. This is the gate that closes each migration phase: a subsystem is "done" only when its characterization tests are green **and** a real run matches Kotlin.

## How it works

`parity_check.py` runs both implementations in `--dry-run` mode against the same config + destination and diffs their planned actions (the git argv they would execute, the repositories they would discover, etc.). `--dry-run` must print the plan without performing network or filesystem mutations.

```bash
# Build the Kotlin reference once:
(cd .. && ./gradlew installDist)        # -> ../build/install/gitout/bin/gitout

# Compare against the Python port:
python parity/parity_check.py path/to/config.toml /tmp/backup-dest
```

## Status

Phase 0 scaffold. The Python CLI (`gitout.cli`) and the `--dry-run` plan format land in Phase 1; until then `parity_check.py` reports which side is missing rather than diffing. The git-argv contract is already pinned offline by `tests/test_git_commands.py`, so this harness is a second, end-to-end line of defense rather than the only one.
