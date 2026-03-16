#!/usr/bin/env bash
# Repair repos whose fetch refspecs were hardcoded to main/master by prune-to-single-branch.sh.
# Detects each repo's actual default branch via git ls-remote and rewrites the refspec.
#
# Usage:
#   DRY_RUN=true  bash scripts/repair-single-branch.sh              # preview (default)
#   DRY_RUN=false bash scripts/repair-single-branch.sh              # apply fixes
#   DRY_RUN=false bash scripts/repair-single-branch.sh /path/to/clone  # custom base

set -euo pipefail

BASE="${1:-/mnt/backup/git-backups/github/clone}"
DRY_RUN="${DRY_RUN:-true}"
CREDENTIAL_FILE="${CREDENTIAL_FILE:-}"

if [[ ! -d "$BASE" ]]; then
    echo "ERROR: Base directory not found: $BASE" >&2
    exit 1
fi

repaired=0
skipped=0
failed=0
total=0

cred_args=()
if [[ -n "$CREDENTIAL_FILE" ]]; then
    cred_args=(-c "credential.helper=store --file=$CREDENTIAL_FILE")
fi

while IFS= read -r repo_dir; do
    total=$((total + 1))
    name=$(echo "$repo_dir" | sed "s|^$BASE/||")

    url=$(git -C "$repo_dir" config remote.origin.url 2>/dev/null || echo "")
    if [[ -z "$url" ]]; then
        echo "[$total] SKIP $name: no remote.origin.url"
        skipped=$((skipped + 1))
        continue
    fi

    # Check current refspec -- skip if already a mirror (refs/*)
    current_refspec=$(git -C "$repo_dir" config --get remote.origin.fetch 2>/dev/null || echo "")
    if [[ "$current_refspec" == "+refs/*:refs/*" ]]; then
        skipped=$((skipped + 1))
        continue
    fi

    # Detect default branch via ls-remote
    default_branch=$(git "${cred_args[@]}" ls-remote --symref "$url" HEAD 2>/dev/null \
        | grep '^ref:' | sed 's|ref: refs/heads/||;s|\t.*||')

    if [[ -z "$default_branch" ]]; then
        echo "[$total] SKIP $name: could not detect default branch"
        skipped=$((skipped + 1))
        continue
    fi

    expected="+refs/heads/$default_branch:refs/heads/$default_branch"

    # Already correct?
    if [[ "$current_refspec" == "$expected" ]]; then
        skipped=$((skipped + 1))
        continue
    fi

    echo "[$total] REPAIR $name: '$current_refspec' -> '$expected' (HEAD=$default_branch)"

    if [[ "$DRY_RUN" == "false" ]]; then
        git -C "$repo_dir" config --unset-all remote.origin.fetch 2>/dev/null || true
        git -C "$repo_dir" config remote.origin.fetch "$expected"
        echo "ref: refs/heads/$default_branch" > "$repo_dir/HEAD"

        if git "${cred_args[@]}" -C "$repo_dir" fetch --prune origin 2>/dev/null; then
            repaired=$((repaired + 1))
        else
            failed=$((failed + 1))
            echo "  FETCH FAILED for $name"
        fi
    else
        repaired=$((repaired + 1))
    fi
done < <(find "$BASE" -mindepth 3 -maxdepth 3 -name "HEAD" -printf '%h\n' 2>/dev/null | sort)

echo ""
echo "=== Done: total=$total repaired=$repaired skipped=$skipped failed=$failed ==="
[[ "$DRY_RUN" == "true" ]] && echo "(DRY_RUN mode -- no changes applied. Set DRY_RUN=false to apply.)"
df -h "$BASE" 2>/dev/null || true
