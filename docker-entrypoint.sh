#!/bin/bash
set -e

# Handle PUID and PGID for file permissions
if [ -n "$PUID" ] && [ -n "$PGID" ]; then
    # Validate PUID and PGID are numeric
    if ! [[ "$PUID" =~ ^[0-9]+$ ]]; then
        echo "Error: PUID must be a numeric value, got: $PUID"
        exit 1
    fi
    if ! [[ "$PGID" =~ ^[0-9]+$ ]]; then
        echo "Error: PGID must be a numeric value, got: $PGID"
        exit 1
    fi

    echo "Setting up user with PUID=$PUID and PGID=$PGID"

    # Create group if it doesn't exist
    if ! getent group gitout > /dev/null 2>&1; then
        addgroup -g "$PGID" gitout || { echo "Failed to create group with GID $PGID"; exit 1; }
    fi

    # Create user if it doesn't exist
    if ! getent passwd gitout > /dev/null 2>&1; then
        adduser -D -u "$PUID" -G gitout gitout || { echo "Failed to create user with UID $PUID"; exit 1; }
    fi

    # Change ownership of data directory only (not /app which is read-only anyway)
    chown -R gitout:gitout /data || { echo "Failed to set ownership on /data"; exit 1; }

    # Run as gitout user
    exec su-exec gitout /app/bin/gitout "$@"
else
    # Run as root (default)
    exec /app/bin/gitout "$@"
fi
