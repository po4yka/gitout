#!/command/with-contenv bash
# shellcheck shell=bash

# Enable strict mode and optional shell tracing when DEBUG=1
set -euo pipefail
[ "${DEBUG:-0}" = 0 ] || set -x
#
# Copyright (c) 2017 Joshua Avalon
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

PUID=${PUID:-9001}
PGID=${PGID:-9001}

if ! getent group abc >/dev/null; then
    groupadd -g "$PGID" abc
else
    groupmod -g "$PGID" abc
fi

if ! id abc >/dev/null 2>&1; then
    useradd -u "$PUID" -g abc -s /usr/sbin/nologin -M abc
else
    usermod -u "$PUID" abc
fi

echo "
Initializing container

User uid: $(id -u abc)
User gid: $(id -g abc)
PUID: $PUID
PGID: $PGID
"

chown abc:abc /app
echo "gitout binary permissions:" && ls -l /app/gitout
echo "cron run permissions:" && ls -l /etc/services.d/cron/run

if [ ! -x /app/gitout ]; then
    echo "ERROR: /app/gitout is missing or not executable" >&2
fi

if [ ! -x /etc/services.d/cron/run ]; then
    echo "ERROR: cron run script is missing or not executable" >&2
fi
