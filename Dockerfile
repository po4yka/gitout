FROM python:3.11-alpine

VOLUME /config
VOLUME /data

RUN apk add --no-cache \
      git \
      git-lfs \
      tini \
      ca-certificates \
      bash \
      su-exec \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk \
 && update-ca-certificates

# Configuration via environment (see gitout sync --help).
ENV GITOUT_HC_HOST="https://hc-ping.com"

WORKDIR /app
COPY pyproject.toml README.md ./
COPY gitout ./gitout
RUN pip install --no-cache-dir .

# Entrypoint script handles PUID/PGID user mapping.
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["sync", "/config/config.toml", "/data"]
