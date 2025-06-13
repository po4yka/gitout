#!/command/with-contenv bash
# shellcheck shell=bash

# Enable strict mode and optional shell tracing when DEBUG=1
set -euo pipefail
[ "${DEBUG:-0}" = 0 ] || set -x

echo "$(date -Iseconds) Starting gitout sync"

if [ -n "$HEALTHCHECK_ID" ]; then
    curl -sS -X POST -o /dev/null "$HEALTHCHECK_HOST/$HEALTHCHECK_ID/start"
fi

# Verify the gitout binary exists and is executable
if [ ! -x /app/gitout ]; then
    echo "ERROR: /app/gitout is missing or not executable" >&2
    exit 1
fi

# shellcheck disable=SC2086
/app/gitout $GITOUT_ARGS /config/config.toml /data

if [ -n "$HEALTHCHECK_ID" ]; then
        curl -sS -X POST -o /dev/null --fail "$HEALTHCHECK_HOST/$HEALTHCHECK_ID"
fi

echo "$(date -Iseconds) Gitout sync completed"
