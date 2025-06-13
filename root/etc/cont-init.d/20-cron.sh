#!/command/with-contenv sh

if [ -z "$CRON" ]; then
	echo "Not running in cron mode" >&2
	exit 0
fi

if [ -z "$HEALTHCHECK_ID" ]; then
	echo "NOTE: Define HEALTHCHECK_ID with https://healthchecks.io to monitor sync job" >&2
fi

# Set up the cron schedule.
echo "Initializing cron"
echo "$CRON /usr/bin/flock -n /app/sync.lock /app/sync.sh" | crontab -u abc -
