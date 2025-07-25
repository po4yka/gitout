#!/command/with-contenv bash
# shellcheck shell=bash

# Enable strict mode and optional shell tracing when DEBUG=1
set -euo pipefail
[ "${DEBUG:-0}" = 0 ] || set -x

if [ -z "${CRON:-}" ]; then  # Changed from $CRON to ${CRON:-}
    echo "Not running in cron mode" >&2
    exit 0
fi

if [ -z "${HEALTHCHECK_ID:-}" ]; then  # Also fix this one
    echo "NOTE: Define HEALTHCHECK_ID with https://healthchecks.io to monitor sync job" >&2
fi

echo "Initializing cron"
echo "Schedule: $CRON"
echo "Running as $(id -u):$(id -g)"
echo "Healthcheck: ${HEALTHCHECK_ID:-<none>}"

# Helpful diagnostics
command -v crond >/dev/null 2>&1 || command -v cron
ls -l /etc/services.d/cron/run

if [ ! -x /etc/services.d/cron/run ]; then
    echo "ERROR: cron run script is not executable" >&2
    exit 1
fi

echo "$CRON /usr/bin/flock -n /app/sync.lock /app/sync.sh" | crontab -u abc -
crontab -u abc -l

