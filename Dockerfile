FROM alpine:3.23.2 AS build
ENV GRADLE_OPTS="-Dkotlin.incremental=false -Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.logging.stacktrace=full"

RUN apk add --no-cache \
      openjdk21 \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk

WORKDIR /app

# Get the Gradle wrapper and cache the Gradle distribution first.
COPY gradlew settings.gradle ./
COPY gradle/wrapper ./gradle/wrapper
RUN ./gradlew --version

COPY gradle/libs.versions.toml ./gradle/libs.versions.toml
COPY build.gradle ./
COPY src/main ./src/main
RUN ./gradlew installDist

FROM alpine:3.23.2

VOLUME /config
VOLUME /data

RUN apk add --no-cache \
      git \
      openjdk17-jre-headless \
      tini \
      ca-certificates \
      bash \
      su-exec \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk \
 && update-ca-certificates

# Set SSL certificate file location
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt

# Environment variables for configuration
ENV GITOUT_CRON="" \
    GITOUT_TIMEOUT="10m" \
    GITOUT_DRY_RUN="" \
    GITOUT_HC_ID="" \
    GITOUT_HC_HOST="https://hc-ping.com" \
    PUID="" \
    PGID=""

WORKDIR /app
COPY --from=build /app/build/install/gitout ./

# Add entrypoint script for user management
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["/config/config.toml", "/data"]
