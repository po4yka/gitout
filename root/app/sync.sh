#!/command/with-contenv sh

echo "$(date -Iseconds) Starting gitout sync"

if [ -n "$HEALTHCHECK_ID" ]; then
	curl -sS -X POST -o /dev/null "$HEALTHCHECK_HOST/$HEALTHCHECK_ID/start"
fi

# If gitout fails we want to avoid triggering the health check.
set -euo pipefail

# shellcheck disable=SC2086
/app/gitout $GITOUT_ARGS /config/config.toml /data

if [ -n "$HEALTHCHECK_ID" ]; then
        curl -sS -X POST -o /dev/null --fail "$HEALTHCHECK_HOST/$HEALTHCHECK_ID"
fi

echo "$(date -Iseconds) Gitout sync completed"
