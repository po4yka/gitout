#!/bin/bash
set -e

# Handle PUID and PGID for file permissions
if [ -n "$PUID" ] && [ -n "$PGID" ]; then
    echo "Setting up user with PUID=$PUID and PGID=$PGID"

    # Create group if it doesn't exist
    if ! getent group gitout > /dev/null 2>&1; then
        addgroup -g "$PGID" gitout
    fi

    # Create user if it doesn't exist
    if ! getent passwd gitout > /dev/null 2>&1; then
        adduser -D -u "$PUID" -G gitout gitout
    fi

    # Change ownership of data directory
    chown -R gitout:gitout /data /app

    # Run as gitout user
    exec su-exec gitout /app/bin/gitout "$@"
else
    # Run as root (default)
    exec /app/bin/gitout "$@"
fi
