#!/usr/bin/env bash
# Prune existing mirror repos to single-branch (main/master) to reclaim disk space.
# Works even when disk is 100% full by rewriting packed-refs in-place via Python.
# Safe to re-run: repos already pruned are a no-op except for the gc pass.
#
# Usage:
#   # Prune all repos:
#   bash /home/po4yka/gitout/scripts/prune-to-single-branch.sh
#
#   # Single-repo dry-run / targeted run:
#   bash /home/po4yka/gitout/scripts/prune-to-single-branch.sh \
#       /mnt/backup/git-backups/github/clone/owner/name

set -euo pipefail

BASE="${1:-/mnt/backup/git-backups/github/clone}"

if [[ ! -d "$BASE" ]]; then
    echo "ERROR: Base directory not found: $BASE" >&2
    exit 1
fi

# Python snippet: rewrite packed-refs in-place keeping only main/master.
# Reads entire file into memory, truncates, then writes filtered content.
# No temp file needed — works on a full disk.
REWRITE_PY='
import sys, re

path = sys.argv[1]
keep_pattern = re.compile(r"^refs/heads/(main|master)$")

with open(path, "r") as f:
    raw_lines = f.readlines()

kept = []
skip_peeled = False
for line in raw_lines:
    stripped = line.rstrip("\n")
    if stripped.startswith("#"):
        kept.append(line)
        skip_peeled = False
        continue
    if stripped.startswith("^"):
        # Peeled line belongs to the preceding ref
        if not skip_peeled:
            kept.append(line)
        continue
    # Normal ref line: "sha1 refname"
    parts = stripped.split(" ", 1)
    if len(parts) == 2 and keep_pattern.match(parts[1]):
        kept.append(line)
        skip_peeled = False
    else:
        skip_peeled = True

with open(path, "r+") as f:
    f.seek(0)
    f.write("".join(kept))
    f.truncate()
'

total=0
pruned=0
skipped=0

# Find all bare repos by looking for HEAD files two levels deep
while IFS= read -r repo_dir; do
    total=$((total + 1))

    # Count refs before
    ref_before=$(git -C "$repo_dir" for-each-ref --format='%(refname)' 2>/dev/null | wc -l)

    # 1. Rewrite packed-refs in-place (no disk space needed)
    if [[ -f "$repo_dir/packed-refs" ]]; then
        python3 -c "$REWRITE_PY" "$repo_dir/packed-refs"
    fi

    # 2. Delete loose refs that are not refs/heads/main or refs/heads/master
    if [[ -d "$repo_dir/refs" ]]; then
        find "$repo_dir/refs" -type f \
            | grep -vE '/refs/heads/(main|master)$' \
            | while IFS= read -r loose_ref; do
                rm -f "$loose_ref"
              done || true  # grep exits 1 when no lines match; suppress with pipefail
        # Clean up empty ref directories
        find "$repo_dir/refs" -mindepth 1 -type d -empty -delete 2>/dev/null || true
    fi

    # 3. Update remote fetch refspecs (for future fetches)
    git -C "$repo_dir" config --unset-all remote.origin.fetch 2>/dev/null || true
    git -C "$repo_dir" config remote.origin.fetch \
        "+refs/heads/main:refs/heads/main" 2>/dev/null || true
    git -C "$repo_dir" config --add remote.origin.fetch \
        "+refs/heads/master:refs/heads/master" 2>/dev/null || true

    ref_after=$(git -C "$repo_dir" for-each-ref --format='%(refname)' 2>/dev/null | wc -l)

    if [[ "$ref_after" -lt "$ref_before" ]]; then
        pruned=$((pruned + 1))
        echo "[$total] $repo_dir: refs $ref_before -> $ref_after"
    else
        skipped=$((skipped + 1))
        echo "[$total] $repo_dir: refs $ref_before (already pruned)"
    fi

    # 4. Run gc to reclaim disk space from now-unreachable objects.
    #    Skip if disk is still too full to allow gc's temp files (~1% free heuristic).
    avail_kb=$(df -k "$repo_dir" 2>/dev/null | awk 'NR==2{print $4}')
    if [[ "${avail_kb:-0}" -gt 102400 ]]; then   # >100MB free
        git -C "$repo_dir" gc --prune=now --quiet 2>/dev/null || true
    fi

done < <(find "$BASE" -mindepth 3 -maxdepth 3 -name "HEAD" -printf '%h\n' 2>/dev/null | sort)

echo ""
echo "=== Done: total=$total pruned=$pruned skipped=$skipped ==="
df -h "$BASE" 2>/dev/null || true
